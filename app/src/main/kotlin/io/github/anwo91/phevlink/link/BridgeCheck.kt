package io.github.anwo91.phevlink.link

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.data.Route
import io.github.anwo91.phevlink.graph
import io.github.anwo91.phevlink.util.Format
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The phone's look at the bridge while the app is closed: reads the bridge's saved status (`GET /api/status` — about a
 * second on home Wi-Fi, never wakes the car; the bridge keeps watching it around the clock) and
 * - keeps the car data, widget and tile fresh,
 * - shows the bridge's new alerts (charge complete, left unlocked, the evening plug-in reminder …) when this phone
 *   takes its alerts from the bridge,
 * - while the car is charging or preconditioning, looks again in about 10 minutes (else the hourly job does).
 * Runs from the hourly job, the background watch, the reminder alarm and any wake that happens anyway.
 */
object BridgeCheck {
    @Volatile private var lastRunMs = 0L
    @Volatile private var lastFailLogMs = 0L

    /** Fresh status from the bridge, or null (no bridge, not reachable). [why] goes into the log. */
    suspend fun run(context: Context, why: String): BridgeClient.Snapshot? {
        val g = context.graph
        val s = g.settings.current
        if (!s.hasBridge || s.bridgeToken.isBlank() || DemoMode.active.value) return null
        lastRunMs = System.currentTimeMillis()
        val snap = withTimeoutOrNull(8_000) { BridgeClient(s.bridgeUrl, s.bridgeToken, g.log, context).fetchStatus(4_000) }
        if (snap == null) {
            val now = System.currentTimeMillis()
            if (now - lastFailLogMs > 3 * 3600_000L) {
                lastFailLogMs = now
                g.log.link("Bridge check ($why): the bridge didn't answer (${BridgeSetupApi.lastFailure ?: "no answer"}) — fine away from home without VPN")
            }
            return null
        }
        g.repo.takeBridgeSnapshot(snap)
        val shown = BridgeAlerts.deliver(context, snap)
        val busy = s.watchWhileBusy && g.repo.busy(snap.car)
        if (busy) WatchScheduler.schedule(context, nextDelayMs(context, snap.car), 0)
        g.log.link(
            "Bridge check ($why): " + listOfNotNull(
                snap.car.batteryPercent?.let { "$it %" },
                "charging".takeIf { snap.car.charging == true },
                "climate on".takeIf { g.repo.climateEndsAt(snap.car)?.let { it > System.currentTimeMillis() } == true },
                snap.reach?.state,
                "$shown new alert(s) shown".takeIf { shown > 0 },
            ).joinToString(", ") + if (busy) " — next look in a few minutes" else "",
        )
        return snap
    }

    /** Runs a check when none ran in the last [maxAgeMs] — for wakes that happen anyway (a departure alarm, the app). */
    fun runIfStale(context: Context, why: String, maxAgeMs: Long = 10 * 60_000L) {
        if (System.currentTimeMillis() - lastRunMs < maxAgeMs) return
        context.graph.scope.launch { run(context, why) }
    }

    /** Like the background watch: until the climate run ends, or the charge's estimate, at most the watch interval. */
    fun nextDelayMs(context: Context, car: CarState): Long {
        val g = context.graph
        val now = System.currentTimeMillis()
        val minutes = g.repo.climateEndsAt(car)?.takeIf { it > now }?.let { end -> ((end - now) / 60_000 + 1).coerceIn(1, 30) }
            ?: minOf(g.settings.current.watchIntervalMinutes.toLong(), (car.chargeMinutesRemaining ?: 60).toLong() + 2).coerceAtLeast(2)
        return minutes * 60_000
    }

    /** Whether this phone shows the bridge's alerts (and keeps its own checks off on the bridge route). */
    fun alertsFromBridge(context: Context): Boolean {
        val s = context.graph.settings.current
        return s.hasBridge && s.alertsFromBridge && s.route != Route.DIRECT
    }
}

/**
 * Shows the bridge's alerts on this phone, each once: remembers the newest alert time it has seen (bridge clock, per
 * bridge). The first look at a bridge doesn't replay its history — only what's from the last 15 minutes.
 */
object BridgeAlerts {
    private const val PREFS = "bridge_alerts"
    private const val KEY_URL = "url"
    private const val KEY_SEEN = "seen_ms"
    private const val FIRST_LOOK_MS = 15 * 60_000L

    /** How many notifications were shown. */
    @Synchronized
    fun deliver(context: Context, snap: BridgeClient.Snapshot): Int {
        val g = context.graph
        val s = g.settings.current
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = if (prefs.getString(KEY_URL, null) == s.bridgeUrl) prefs.getLong(KEY_SEEN, -1L) else -1L
        val now = snap.bridgeNowMs ?: System.currentTimeMillis()
        val fresh = snap.alerts.filter { if (seen < 0) now - it.timeMs < FIRST_LOOK_MS else it.timeMs > seen }.sortedBy { it.timeMs }
        val newest = maxOf(seen, snap.alerts.maxOfOrNull { it.timeMs } ?: seen, if (seen < 0) now - FIRST_LOOK_MS else seen)
        prefs.edit().putString(KEY_URL, s.bridgeUrl).putLong(KEY_SEEN, newest).apply()
        if (fresh.isEmpty()) return 0
        if (!s.phoneAlerts || !BridgeCheck.alertsFromBridge(context)) {
            g.log.link("Bridge alerts: ${fresh.size} new (${fresh.joinToString { it.type }}) — not shown: this phone " +
                if (!s.phoneAlerts) "has alerts off" else "uses its own alerts")
            return 0
        }
        var shown = 0
        for (a in fresh) {
            val type = runCatching { AlertType.valueOf(a.type) }.getOrNull()
            if (type == null) {
                g.log.link("Bridge alerts: unknown kind ${a.type} (${a.message}) — not shown (update the app)")
                continue
            }
            if (!s.alerts.enabled(type)) {
                g.log.link("Bridge alerts: ${a.type} at ${Format.clock(a.timeMs)} — off on this phone")
                continue
            }
            g.notifier.alert(Alert(type, a.message, a.timeMs), snap.car, s.alerts)
            g.log.link("Bridge alerts: showed ${a.type} from ${Format.clock(a.timeMs)} (${a.message})")
            shown++
        }
        return shown
    }
}

/**
 * An inexact alarm a couple of minutes after the bridge's evening reminder time, so the reminder shows soon after it's
 * raised rather than at the next hourly check. Battery-friendly: allowed while idle, once a day.
 */
object ReminderAlarm {
    private const val REQ = 14

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQ, Intent(context, BridgeCheckReceiver::class.java).setAction(BridgeCheckReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** [shared] = the bridge's shared settings (plugReminder, plugReminderAt). */
    fun schedule(context: Context, shared: Map<String, Any?>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val on = shared["plugReminder"] as? Boolean ?: true
        if (!on || !context.graph.settings.current.hasBridge) {
            am.cancel(pending(context))
            return
        }
        val at = (shared["plugReminderAt"] as? String)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(21, 0)
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(at.plusMinutes(2)).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending(context))
    }
}

/** Short background look at the bridge (the evening reminder): finishes within a few seconds, no service needed. */
class BridgeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        val g = context.applicationContext.graph
        g.scope.launch {
            try {
                withTimeoutOrNull(9_000) { BridgeCheck.run(context.applicationContext, "evening reminder") }
                // Tomorrow's alarm (and the reminder settings may have changed on another phone).
                g.sharedSync.state.value.shared?.let { ReminderAlarm.schedule(context.applicationContext, it) }
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.github.anwo91.phevlink.BRIDGE_CHECK"
    }
}
