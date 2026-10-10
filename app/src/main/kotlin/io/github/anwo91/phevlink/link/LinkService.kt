package io.github.anwo91.phevlink.link

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.planner.ClimatePlanner
import io.github.anwo91.phevlink.graph
import io.github.anwo91.phevlink.notify.Notifier
import io.github.anwo91.phevlink.schedule.DepartureReceiver
import io.github.anwo91.phevlink.schedule.DepartureScheduler
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import io.github.anwo91.phevlink.weather.MetNo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Foreground service for everything that happens while the app is not on
 * screen: widget/tile/shortcut actions, scheduled departures, and periodic
 * checks while the car charges or preconditions. Android only lets a
 * foreground app or service request the car's Wi-Fi, which is why this exists.
 *
 * The service only runs while there is work; between background checks it is
 * stopped and an exact alarm brings it back. A partial wake lock keeps the CPU
 * awake while it is talking to the car (screen off, phone in a pocket).
 */
class LinkService : LifecycleService() {

    private val repo by lazy { graph.repo }
    private var running = 0
    private var stopJob: Job? = null
    private var lastStartId = 0
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** A string in the app language, resolved now. */
    private fun str(@StringRes id: Int, vararg args: Any): String {
        val res = AppLocale.localized(this)
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }

    override fun onCreate() {
        super.onCreate()
        goForeground(str(R.string.svc_contacting), "")
        lifecycleScope.launch {
            combine(repo.link, repo.car) { l, c ->
                val parts = listOfNotNull(
                    c.batteryPercent?.let { "$it%" },
                    if (c.charging == true) str(R.string.svc_charging) else null,
                    if (c.climate.state == PreAcState.RUNNING) str(R.string.svc_climate_on) else null,
                )
                l.text to parts.joinToString(" · ").ifEmpty { l.detail ?: "" }
            }.distinctUntilChanged().collect { (title, text) ->
                if (foreground) graph.notifier.updateLink(buildNotification(title, text))
            }
        }
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        goForeground(str(R.string.svc_contacting), "")
        stopJob?.cancel()
        WakeHolder.releaseFromReceiver()
        when (intent?.action) {
            ACTION_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND)?.let { json ->
                    runCatching { Codecs.commandFromMap(Json.parseObject(json)) }.getOrNull()
                }
                if (cmd != null) work { runCommand(cmd) } else maybeStop(0)
            }
            ACTION_QUICK_CLIMATE -> work { runCommand(repo.quickClimate()) }
            ACTION_TOGGLE_CLIMATE -> work {
                val on = repo.climateEndsAt()?.let { System.currentTimeMillis() < it } == true
                runCommand(if (on) CarCommand.ClimateOff else repo.quickClimate())
            }
            ACTION_REFRESH -> work { refresh() }
            ACTION_PLAN -> work { planDeparture(intent) }
            ACTION_START_DEPARTURE -> work { startDeparture(intent) }
            ACTION_WATCH -> work { watchTick(intent.getIntExtra(EXTRA_FAILURES, 0)) }
            ACTION_STOP -> {
                WatchScheduler.cancel(this)
                maybeStop(0)
            }
            else -> maybeStop(0)
        }
        return START_NOT_STICKY
    }

    private fun work(block: suspend () -> Unit) {
        running++
        acquireWake()
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                graph.log.error("Background task failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running--
                if (running == 0) releaseWake()
                maybeStop(2_000)
            }
        }
    }

    private suspend fun runCommand(cmd: CarCommand) {
        repo.hold(TAG)
        try {
            when (val r = repo.execute(cmd)) {
                is CommandResult.Success -> graph.notifier.result(Labels.command(this, cmd), successText(cmd), ok = true)
                is CommandResult.Failure -> graph.notifier.result(str(R.string.notif_cmd_failed, Labels.command(this, cmd)), r.reason, ok = false)
            }
        } finally {
            repo.release(TAG, 15_000)
        }
        armWatchIfBusy()
    }

    private fun successText(cmd: CarCommand): String = when (cmd) {
        is CarCommand.ClimateOn -> str(
            R.string.notif_climate_running, cmd.duration.minutes,
            Format.clock(System.currentTimeMillis() + cmd.duration.minutes * 60_000L),
        )
        CarCommand.ClimateOff -> str(R.string.notif_climate_off)
        is CarCommand.Locate -> str(R.string.notif_flashed)
        else -> str(R.string.notif_done)
    }

    /** Connect, ask for fresh data, give it a moment. True if the car answered. */
    private suspend fun refresh(): Boolean {
        repo.hold("svc-refresh")
        try {
            withTimeoutOrNull(60_000) { repo.link.first { it.connected } } ?: run {
                graph.log.warn("Background check: car not reachable (${repo.link.value.text})")
                return false
            }
            val r = repo.execute(CarCommand.RequestUpdate, 15_000)
            delay(4_000)
            return r is CommandResult.Success
        } finally {
            repo.release("svc-refresh", 5_000)
        }
    }

    // ------------------------------------------------------------ departures

    private suspend fun planDeparture(intent: Intent) {
        val s = graph.settings.current
        val id = intent.getStringExtra(EXTRA_DEPARTURE) ?: return
        val atMs = intent.getLongExtra(EXTRA_AT, 0L)
        val dep = s.departures.firstOrNull { it.id == id && it.enabled }
        if (dep == null || atMs < System.currentTimeMillis()) {
            DepartureScheduler.reschedule(this); return
        }
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault())
        val temp = if (dep.smart) MetNo.temperatureNear(this, s, at, graph.log) else null
        val plan = ClimatePlanner.decide(dep, temp)
        graph.log.link("Departure ${Format.clock(atMs)}: " + (plan?.reason ?: "mild (${temp?.toInt()}°C) — skipping"))
        if (plan == null) {
            graph.notifier.result(
                str(R.string.notif_no_preheat_title),
                str(R.string.notif_no_preheat_text, temp?.toInt()?.toString() ?: "?", Format.clock(atMs)), ok = true,
            )
            DepartureScheduler.reschedule(this, after = atMs); return
        }
        val startAt = ClimatePlanner.startTime(at, plan).toInstant().toEpochMilli() - 60_000 // a minute of margin
        if (startAt - System.currentTimeMillis() > 90_000) {
            DepartureScheduler.scheduleStart(this, dep.id, atMs, plan.mode, plan.duration, startAt)
        } else {
            startDeparture(intent.apply {
                putExtra(EXTRA_MODE, plan.mode.name)
                putExtra(EXTRA_MINUTES, plan.duration.minutes)
            })
        }
    }

    private suspend fun startDeparture(intent: Intent) {
        val atMs = intent.getLongExtra(EXTRA_AT, 0L)
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { ClimateMode.valueOf(it) }.getOrNull() } ?: ClimateMode.HEAT
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 20)
        val cmd = CarCommand.ClimateOn(mode, ClimateDuration.fromMinutes(minutes))
        repo.hold("svc-departure")
        try {
            // A scheduled start deserves extra patience: the car's Wi-Fi may need to wake up.
            when (val r = repo.execute(cmd, timeoutMs = 120_000)) {
                is CommandResult.Success -> graph.notifier.result(
                    str(R.string.notif_departure_started, str(Labels.mode(mode))),
                    str(R.string.notif_departure_ready, Format.clock(atMs)), ok = true,
                )
                is CommandResult.Failure -> graph.notifier.result(
                    str(R.string.notif_departure_failed, str(Labels.modeInline(mode))),
                    str(R.string.notif_departure_retry, r.reason), ok = false,
                )
            }
        } finally {
            repo.release("svc-departure", 10_000)
            DepartureScheduler.reschedule(this, after = atMs)
        }
        armWatchIfBusy()
    }

    // ----------------------------------------------------------------- watch

    private suspend fun watchTick(failuresBefore: Int) {
        if (!graph.settings.current.watchWhileBusy) return
        if (!repo.busy()) {
            graph.log.link("Background watch: nothing to watch")
            return
        }
        val ok = refresh()
        val failures = if (ok) 0 else failuresBefore + 1
        when {
            failures >= MAX_WATCH_FAILURES -> graph.log.warn("Background watch stopped: car unreachable $failures times in a row")
            !repo.busy() -> graph.log.link("Background watch finished — nothing more to wait for")
            else -> WatchScheduler.schedule(this, nextWatchDelayMs(), failures)
        }
    }

    private fun armWatchIfBusy() {
        if (graph.settings.current.watchWhileBusy && repo.busy()) WatchScheduler.schedule(this, nextWatchDelayMs(), 0)
    }

    private fun nextWatchDelayMs(): Long {
        val car = repo.car.value
        val now = System.currentTimeMillis()
        val minutes = repo.climateEndsAt(car)?.takeIf { it > now }?.let { end ->
            ((end - now) / 60_000 + 1).coerceIn(1, 30)
        } ?: run {
            val left = (car.chargeMinutesRemaining ?: 60).toLong()
            minOf(graph.settings.current.watchIntervalMinutes.toLong(), left + 2).coerceAtLeast(2)
        }
        return minutes * 60_000
    }

    // ----------------------------------------------------------------- misc

    private fun maybeStop(delayMs: Long) {
        stopJob?.cancel()
        stopJob = lifecycleScope.launch {
            delay(delayMs)
            if (running == 0) {
                foreground = false
                ServiceCompat.stopForeground(this@LinkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // Only stops if no newer start request arrived meanwhile.
                stopSelfResult(lastStartId)
            }
        }
    }

    private fun goForeground(title: String, text: String) {
        ServiceCompat.startForeground(
            this, Notifier.ID_LINK, buildNotification(title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        foreground = true
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phevlink:service").apply {
            setReferenceCounted(false)
            acquire(5 * 60_000L) // safety timeout
        }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun buildNotification(title: String, text: String) = graph.notifier.linkNotification(
        title, text,
        listOf(
            NotificationCompat.Action(
                R.drawable.ic_stat_car, str(R.string.common_stop),
                PendingIntent.getService(
                    this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        ),
    )

    companion object {
        private const val TAG = "svc"
        private const val MAX_WATCH_FAILURES = 3
        const val ACTION_COMMAND = "io.github.anwo91.phevlink.COMMAND"
        const val ACTION_QUICK_CLIMATE = "io.github.anwo91.phevlink.QUICK_CLIMATE"
        const val ACTION_TOGGLE_CLIMATE = "io.github.anwo91.phevlink.TOGGLE_CLIMATE"
        const val ACTION_REFRESH = "io.github.anwo91.phevlink.REFRESH"
        const val ACTION_PLAN = "io.github.anwo91.phevlink.PLAN_DEPARTURE"
        const val ACTION_START_DEPARTURE = "io.github.anwo91.phevlink.START_DEPARTURE"
        const val ACTION_WATCH = "io.github.anwo91.phevlink.WATCH"
        const val ACTION_STOP = "io.github.anwo91.phevlink.STOP"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_DEPARTURE = "departure"
        const val EXTRA_AT = "at"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_FAILURES = "failures"

        fun intent(context: Context, action: String) = Intent(context, LinkService::class.java).setAction(action)

        fun commandIntent(context: Context, cmd: CarCommand) =
            intent(context, ACTION_COMMAND).putExtra(EXTRA_COMMAND, Json.write(Codecs.commandToMap(cmd)))

        /** Only call from a context allowed to start foreground services (UI, alarm, widget/tile click). */
        fun start(context: Context, intent: Intent) {
            runCatching { context.startForegroundService(intent) }
                .onFailure { context.graph.log.error("Could not start background service: ${it.message}") }
        }
    }
}

/** Exact alarms for the periodic background checks (they work in Doze, unlike delay()). */
object WatchScheduler {
    private const val REQ_WATCH = 12

    private fun pending(context: Context, failures: Int) = PendingIntent.getBroadcast(
        context, REQ_WATCH,
        Intent(context, DepartureReceiver::class.java).setAction(LinkService.ACTION_WATCH).putExtra(LinkService.EXTRA_FAILURES, failures),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context, delayMs: Long, failures: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + delayMs
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, failures))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, failures))
        context.graph.log.link("Next background check at ${Format.clock(at)}")
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, 0))
    }
}

/** Bridges the gap between an alarm broadcast returning and the service taking over. */
object WakeHolder {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquireForReceiver(context: Context) {
        if (lock?.isHeld == true) return
        lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phevlink:alarm").apply {
                setReferenceCounted(false)
                acquire(60_000L)
            }
    }

    @Synchronized
    fun releaseFromReceiver() {
        lock?.let { if (it.isHeld) runCatching { it.release() } }
        lock = null
    }
}
