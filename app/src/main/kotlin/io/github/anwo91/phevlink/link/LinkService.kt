package io.github.anwo91.phevlink.link

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import io.github.anwo91.phevlink.schedule.DepartureScheduler
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.weather.MetNo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Foreground service for everything that happens while the app is not on
 * screen: widget/tile/shortcut actions, scheduled departures, and watching
 * the car while it charges or preconditions. Android only lets a foreground
 * app or service request the car's Wi-Fi, which is why this exists.
 */
class LinkService : LifecycleService() {

    private val repo by lazy { graph.repo }
    private var running = 0
    private var watchJob: Job? = null
    private var stopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground("Contacting the car…", "")
        lifecycleScope.launch {
            combine(repo.link, repo.car) { l, c -> l to c }.collect { (l, c) ->
                val parts = listOfNotNull(
                    c.batteryPercent?.let { "$it%" },
                    if (c.charging == true) "charging" else null,
                    if (c.climate.state == PreAcState.RUNNING) "climate on" else null,
                )
                graph.notifier.updateLink(buildNotification(l.text, parts.joinToString(" · ").ifEmpty { l.detail ?: "" }))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground("Contacting the car…", "")
        stopJob?.cancel()
        when (intent?.action) {
            ACTION_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND)?.let { json ->
                    runCatching { Codecs.commandFromMap(Json.parseObject(json)) }.getOrNull()
                }
                if (cmd != null) work { runCommand(cmd) } else maybeStop(0)
            }
            ACTION_QUICK_CLIMATE -> work { runCommand(repo.quickClimate()) }
            ACTION_TOGGLE_CLIMATE -> work {
                val running = repo.car.value.climate.state == PreAcState.RUNNING
                runCommand(if (running) CarCommand.ClimateOff else repo.quickClimate())
            }
            ACTION_REFRESH -> work { refresh() }
            ACTION_PLAN -> work { planDeparture(intent) }
            ACTION_START_DEPARTURE -> work { startDeparture(intent) }
            ACTION_WATCH -> startWatch()
            ACTION_STOP -> {
                watchJob?.cancel(); watchJob = null
                maybeStop(0)
            }
            else -> maybeStop(0)
        }
        return START_NOT_STICKY
    }

    private fun work(block: suspend () -> Unit) {
        running++
        lifecycleScope.launch {
            try {
                block()
            } finally {
                running--
                // A command often leaves the car busy (charging, climate running): keep an eye on it.
                if (graph.settings.current.watchWhileBusy && repo.busy()) startWatch()
                maybeStop(3_000)
            }
        }
    }

    private suspend fun runCommand(cmd: CarCommand) {
        val tag = "svc"
        repo.hold(tag)
        try {
            val r = repo.execute(cmd)
            when (r) {
                is CommandResult.Success -> graph.notifier.result(cmd.label, successText(cmd), ok = true)
                is CommandResult.Failure -> graph.notifier.result("${cmd.label} failed", r.reason, ok = false)
            }
        } finally {
            repo.release(tag, 15_000)
        }
    }

    private fun successText(cmd: CarCommand): String = when (cmd) {
        is CarCommand.ClimateOn -> "Running for ${cmd.duration.minutes} min — ready around ${
            Format.clock(System.currentTimeMillis() + cmd.duration.minutes * 60_000L)
        }."
        CarCommand.ClimateOff -> "Climate switched off."
        is CarCommand.Locate -> "Headlights flashed."
        else -> "Done."
    }

    private suspend fun refresh() {
        repo.hold("svc-refresh")
        try {
            withTimeoutOrNull(60_000) { repo.link.first { it.connected } } ?: return
            repo.execute(CarCommand.RequestUpdate, 15_000)
            delay(4_000)
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
            graph.notifier.result("No preheating needed", "It's about ${temp?.toInt()}°C at ${Format.clock(atMs)} — leaving the climate off.", ok = true)
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
            // A scheduled start deserves a little extra patience: Wi-Fi may need to wake up.
            val r = repo.execute(cmd, timeoutMs = 120_000)
            when (r) {
                is CommandResult.Success -> graph.notifier.result(
                    "${mode.label} started", "Your car will be ready for ${Format.clock(atMs)}.", ok = true,
                )
                is CommandResult.Failure -> graph.notifier.result(
                    "Scheduled ${mode.label.lowercase()} failed", "${r.reason}\nTap to open the app and try again.", ok = false,
                )
            }
        } finally {
            repo.release("svc-departure", 10_000)
            DepartureScheduler.reschedule(this, after = atMs)
        }
    }

    // ----------------------------------------------------------------- watch

    private fun startWatch() {
        if (watchJob?.isActive == true) return
        graph.log.link("Watching the car in the background")
        watchJob = lifecycleScope.launch {
            try {
                while (graph.settings.current.watchWhileBusy) {
                    val car = repo.car.value
                    val waitMin = when {
                        car.climate.state == PreAcState.RUNNING -> {
                            // Check right after the climate should end.
                            val started = car.climate.startedAtMs ?: System.currentTimeMillis()
                            val end = started + (car.climate.duration?.minutes ?: 20) * 60_000L
                            ((end - System.currentTimeMillis()) / 60_000 + 1).coerceIn(1, 30)
                        }
                        car.charging == true -> {
                            val left = car.chargeMinutesRemaining ?: 60
                            minOf(graph.settings.current.watchIntervalMinutes.toLong(), left.toLong() + 2).coerceAtLeast(2)
                        }
                        else -> break
                    }
                    delay(waitMin * 60_000)
                    refresh()
                    if (!repo.busy()) break
                }
            } finally {
                graph.log.link("Background watch ended")
                maybeStop(2_000)
            }
        }
    }

    // ----------------------------------------------------------------- misc

    private fun maybeStop(delayMs: Long) {
        stopJob?.cancel()
        stopJob = lifecycleScope.launch {
            delay(delayMs)
            if (running == 0 && watchJob?.isActive != true) {
                ServiceCompat.stopForeground(this@LinkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startInForeground(title: String, text: String) {
        ServiceCompat.startForeground(
            this, Notifier.ID_LINK, buildNotification(title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun buildNotification(title: String, text: String) = graph.notifier.linkNotification(
        title, text,
        listOf(
            NotificationCompat.Action(
                R.drawable.ic_stat_car, "Stop",
                PendingIntent.getService(
                    this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        ),
    )

    companion object {
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
