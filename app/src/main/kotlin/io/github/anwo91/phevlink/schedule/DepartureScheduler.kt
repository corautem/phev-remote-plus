package io.github.anwo91.phevlink.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.planner.ClimatePlanner
import io.github.anwo91.phevlink.graph
import io.github.anwo91.phevlink.link.LinkService
import io.github.anwo91.phevlink.util.Format
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Two-stage alarms per departure:
 *  1. PLAN, 35 min before leaving: check the weather, decide mode and duration;
 *  2. START, at departure minus duration: switch the climate on.
 * Exact alarms let the service start in the background at the right time.
 */
object DepartureScheduler {
    private const val REQ_PLAN = 10
    private const val REQ_START = 11

    data class Next(val departureId: String, val atMs: Long, val planAtMs: Long)

    fun next(context: Context, after: Long? = null): Next? {
        val s = context.graph.settings.current
        val from = ZonedDateTime.now().let { now ->
            if (after != null && after >= now.toInstant().toEpochMilli()) {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(after + 60_000), ZoneId.systemDefault())
            } else now
        }
        val (dep, at) = ClimatePlanner.nextDeparture(s.departures, from) ?: return null
        val atMs = at.toInstant().toEpochMilli()
        val planAt = (atMs - ClimatePlanner.LEAD_MINUTES * 60_000).coerceAtLeast(System.currentTimeMillis() + 5_000)
        return Next(dep.id, atMs, planAt)
    }

    fun reschedule(context: Context, after: Long? = null) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(planIntent(context, null, 0))
        val n = next(context, after)
        if (n == null) {
            context.graph.log.link("No departures scheduled")
            return
        }
        setExact(context, am, n.planAtMs, planIntent(context, n.departureId, n.atMs))
        context.graph.log.link(
            "Next departure ${Format.clockOrDay(n.atMs, locale = Locale.ENGLISH)} — " +
                "weather check at ${Format.clockOrDay(n.planAtMs, locale = Locale.ENGLISH)}"
        )
    }

    fun scheduleStart(context: Context, depId: String, atMs: Long, mode: ClimateMode, duration: ClimateDuration, startAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, REQ_START,
            Intent(context, DepartureReceiver::class.java).setAction(LinkService.ACTION_START_DEPARTURE)
                .putExtra(LinkService.EXTRA_DEPARTURE, depId)
                .putExtra(LinkService.EXTRA_AT, atMs)
                .putExtra(LinkService.EXTRA_MODE, mode.name)
                .putExtra(LinkService.EXTRA_MINUTES, duration.minutes),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setExact(context, am, startAtMs, pi)
        context.graph.log.link("${mode.label} ${duration.minutes} min will start at ${Format.clock(startAtMs)}")
    }

    private fun planIntent(context: Context, depId: String?, atMs: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_PLAN,
        Intent(context, DepartureReceiver::class.java).setAction(LinkService.ACTION_PLAN)
            .putExtra(LinkService.EXTRA_DEPARTURE, depId)
            .putExtra(LinkService.EXTRA_AT, atMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setExact(context: Context, am: AlarmManager, atMs: Long, pi: PendingIntent) {
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactOk) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            context.graph.log.warn("Exact alarms not allowed — departures may run a few minutes late")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

/** Alarm target: hands the work to the foreground service. */
class DepartureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Keep the CPU awake until the service has started and taken its own wake lock.
        io.github.anwo91.phevlink.link.WakeHolder.acquireForReceiver(context)
        val forward = Intent(context, LinkService::class.java).setAction(intent.action).putExtras(intent)
        LinkService.start(context, forward)
    }
}

/** Alarms do not survive reboots, time changes or app updates; set them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DepartureScheduler.reschedule(context)
    }
}
