package io.github.anwo91.phevlink.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertPrefs
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.ui.MainActivity
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import io.github.anwo91.phevlink.util.Money

class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    /** Language the channel names were last written in. */
    @Volatile private var channelLanguage: String? = null

    init {
        ensureChannels()
    }

    /** Creates the channels, and renames them after a language change (Android allows that in place). */
    private fun ensureChannels() {
        val res = AppLocale.localized(context)
        val lang = res.resources.configuration.locales.toLanguageTags()
        if (lang == channelLanguage) return
        channelLanguage = lang
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_LINK, res.getString(R.string.ch_link_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = res.getString(R.string.ch_link_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERTS, res.getString(R.string.ch_alerts_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = res.getString(R.string.ch_alerts_desc)
                },
                NotificationChannel(CH_RESULTS, res.getString(R.string.ch_results_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = res.getString(R.string.ch_results_desc)
                },
            )
        )
    }

    // Works on every Android version (the runtime permission only exists from 13).
    private fun canPost() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Posts [a] in the app language. The core builds alert texts in English, so with the car
     * [state] (and the alert [prefs]) they are rebuilt here; without them the English text is used.
     */
    fun alert(a: Alert, state: CarState? = null, prefs: AlertPrefs? = null) {
        if (!canPost()) return
        ensureChannels()
        val res = AppLocale.localized(context)
        val title = res.getString(alertTitle(a.type))
        val message = if (a.type == AlertType.WEEKLY_SUMMARY) weeklyText(res, a.params) ?: a.message
            else state?.let { alertMessage(res, a.type, it, prefs ?: AlertPrefs()) } ?: a.message
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(alertIcon(a.type))
            .setColor(context.getColor(alertColor(a.type)))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(if (a.type == AlertType.CHARGE_COMPLETE || a.type == AlertType.CLIMATE_FINISHED) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(if (a.type == AlertType.WEEKLY_SUMMARY) openPageIntent(MainActivity.OPEN_HISTORY, REQ_HISTORY) else openAppIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(ID_ALERT_BASE + a.type.ordinal, n)
    }

    private fun openPageIntent(page: String, req: Int): PendingIntent = PendingIntent.getActivity(
        context, req,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, page).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The week in a few words: "3 charges, 24.0 kWh · 31 kr" and "5 trips, 120 % of the battery ≈ 50 km". */
    private fun weeklyText(res: Context, p: Map<String, Any?>): String? {
        val charges = (p["charges"] as? Number)?.toInt() ?: return null
        val trips = (p["trips"] as? Number)?.toInt() ?: 0
        val loc = AppLocale.uiLocale(res)
        val lines = ArrayList<String>()
        if (charges > 0) {
            val kwh = String.format(loc, "%.1f", (p["kwh"] as? Number)?.toDouble() ?: 0.0)
            // " +": some charges' costs aren't known yet.
            val cost = (p["cost"] as? Number)?.toDouble()?.let { Money.format(it, p["currency"] as? String, loc) + if (p["costComplete"] == false) " +" else "" }
            lines += res.resources.getQuantityString(R.plurals.week_charges, charges, charges, kwh) + (cost?.let { " · ≈ $it" } ?: "")
        }
        if (trips > 0) {
            val used = (p["usedPct"] as? Number)?.toInt() ?: 0
            val km = (p["electricKm"] as? Number)?.toInt()
            lines += res.resources.getQuantityString(R.plurals.week_trips, trips, trips, used) + (km?.let { " " + res.getString(R.string.week_km, it) } ?: "")
        }
        return lines.joinToString("\n").ifEmpty { null }
    }

    /** The car is home while the bridge still waits to be registered: tap opens the bridge setup at that step. */
    fun bridgeCarHome() {
        if (!canPost()) return
        ensureChannels()
        val res = AppLocale.localized(context)
        val text = res.getString(R.string.notif_bridge_home_text)
        val open = PendingIntent.getActivity(
            context, REQ_BRIDGE_SETUP,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BRIDGE_SETUP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setColor(context.getColor(R.color.brand))
            .setContentTitle(res.getString(R.string.notif_bridge_home_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_BRIDGE_HOME, n)
    }

    fun result(title: String, text: String, ok: Boolean) {
        if (!canPost()) return
        ensureChannels()
        val n = NotificationCompat.Builder(context, CH_RESULTS)
            .setSmallIcon(if (ok) R.drawable.ic_stat_car else R.drawable.ic_stat_warn)
            .setColor(context.getColor(if (ok) R.color.brand else R.color.notif_warn))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 30 * 60_000L else 6 * 3600_000L)
            .build()
        nm.notify(ID_RESULT, n)
    }

    /**
     * A result that isn't a failure anyone can fix — e.g. a departure skipped because the car isn't within reach:
     * shown without sound, in the neutral colour, no "try again".
     */
    fun quietResult(title: String, text: String) {
        if (!canPost()) return
        ensureChannels()
        val n = NotificationCompat.Builder(context, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(context.getColor(R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(6 * 3600_000L)
            .build()
        nm.notify(ID_RESULT, n)
    }

    /** [immediate] false: Android may wait ~10 s before showing it (a short background linger then shows nothing). */
    fun linkNotification(
        title: String, text: String, immediate: Boolean = true, actions: List<NotificationCompat.Action> = emptyList(),
    ): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(context.getColor(R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(
                if (immediate) NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE else NotificationCompat.FOREGROUND_SERVICE_DEFERRED,
            )
            .apply { actions.forEach { addAction(it) } }
            .build()
    }

    fun updateLink(n: Notification) {
        if (canPost()) nm.notify(ID_LINK, n)
    }

    /** Status-bar icon by subject, so the bar itself says what the message is about. */
    @DrawableRes
    private fun alertIcon(t: AlertType): Int = when (t) {
        AlertType.CHARGE_COMPLETE, AlertType.CHARGE_STOPPED, AlertType.CHARGE_STARTED -> R.drawable.ic_stat_charge
        AlertType.CLIMATE_STARTED, AlertType.CLIMATE_FINISHED, AlertType.CLIMATE_CANCELLED -> R.drawable.ic_stat_climate
        AlertType.LEFT_UNLOCKED, AlertType.DOOR_OPEN -> R.drawable.ic_stat_lock
        AlertType.LIGHTS_ON -> R.drawable.ic_stat_lights
        AlertType.THEFT_ALARM, AlertType.CHARGE_TIMER_FAILED -> R.drawable.ic_stat_warn
        AlertType.PLUG_IN_REMINDER, AlertType.WEEKLY_SUMMARY -> R.drawable.ic_stat_charge
    }

    /** Accent colour: teal for news, amber for things to look at, red for the alarm. */
    @ColorRes
    private fun alertColor(t: AlertType): Int = when (t) {
        AlertType.CHARGE_COMPLETE, AlertType.CHARGE_STARTED, AlertType.CLIMATE_STARTED, AlertType.CLIMATE_FINISHED, AlertType.WEEKLY_SUMMARY -> R.color.brand
        AlertType.CHARGE_STOPPED, AlertType.CLIMATE_CANCELLED, AlertType.LEFT_UNLOCKED, AlertType.DOOR_OPEN,
        AlertType.LIGHTS_ON, AlertType.CHARGE_TIMER_FAILED, AlertType.PLUG_IN_REMINDER -> R.color.notif_warn
        AlertType.THEFT_ALARM -> R.color.notif_alarm
    }

    @StringRes
    private fun alertTitle(t: AlertType): Int = when (t) {
        AlertType.CHARGE_COMPLETE -> R.string.alert_title_charge_complete
        AlertType.CHARGE_STOPPED -> R.string.alert_title_charge_stopped
        AlertType.CHARGE_STARTED -> R.string.alert_title_charge_started
        AlertType.CLIMATE_STARTED -> R.string.alert_title_climate_started
        AlertType.CLIMATE_FINISHED -> R.string.alert_title_climate_finished
        AlertType.CLIMATE_CANCELLED -> R.string.alert_title_climate_cancelled
        AlertType.LEFT_UNLOCKED -> R.string.alert_title_left_unlocked
        AlertType.DOOR_OPEN -> R.string.alert_title_door_open
        AlertType.LIGHTS_ON -> R.string.alert_title_lights_on
        AlertType.THEFT_ALARM -> R.string.alert_title_theft
        AlertType.CHARGE_TIMER_FAILED -> R.string.alert_title_charge_failed
        AlertType.PLUG_IN_REMINDER -> R.string.alert_title_plug_in
        AlertType.WEEKLY_SUMMARY -> R.string.alert_title_weekly
    }

    /** Same content as the core's English message (see AlertEngine), from the same car state. Null if it cannot be rebuilt. */
    private fun alertMessage(res: Context, t: AlertType, s: CarState, p: AlertPrefs): String? {
        val pct = s.batteryPercent
        return when (t) {
            AlertType.CHARGE_COMPLETE ->
                if (pct != null) res.getString(R.string.alert_battery_full_pct, pct) else res.getString(R.string.alert_battery_full)
            AlertType.CHARGE_STOPPED ->
                if (pct != null) res.getString(R.string.alert_charge_stopped_pct, pct) else res.getString(R.string.alert_charge_stopped)
            AlertType.CHARGE_STARTED -> {
                val charging = if (pct != null) res.getString(R.string.alert_charging_pct, pct) else res.getString(R.string.alert_charging)
                val eta = s.chargeMinutesRemaining?.let { res.getString(R.string.alert_charge_eta, Format.minutes(it, res)) }
                listOfNotNull(charging, eta).joinToString(" ")
            }
            AlertType.CLIMATE_STARTED -> {
                val what = listOfNotNull(
                    s.climate.mode?.let { res.getString(Labels.mode(it)) },
                    s.climate.duration?.let { res.getString(R.string.dur_min, it.minutes) },
                ).joinToString(", ")
                if (what.isEmpty()) res.getString(R.string.alert_climate_running) else "$what."
            }
            AlertType.CLIMATE_FINISHED -> res.getString(R.string.alert_climate_finished)
            AlertType.CLIMATE_CANCELLED -> res.getString(R.string.alert_climate_cancelled)
            AlertType.LEFT_UNLOCKED ->
                res.resources.getQuantityString(R.plurals.alert_left_unlocked, p.leftUnlockedMinutes, p.leftUnlockedMinutes)
            AlertType.DOOR_OPEN -> {
                val which = s.doors?.let { Labels.openDoors(res, it) }.orEmpty().joinToString()
                res.resources.getQuantityString(R.plurals.alert_door_open, p.doorOpenMinutes, p.doorOpenMinutes, which)
            }
            AlertType.LIGHTS_ON -> when {
                s.headlights == true && s.parkingLights == true -> res.getString(R.string.alert_lights_on_both)
                s.headlights == true -> res.getString(R.string.alert_lights_on_head)
                s.parkingLights == true -> res.getString(R.string.alert_lights_on_parking)
                else -> null
            }
            AlertType.THEFT_ALARM -> res.getString(R.string.alert_theft)
            AlertType.CHARGE_TIMER_FAILED -> res.getString(R.string.alert_charge_failed)
            AlertType.PLUG_IN_REMINDER -> if (pct != null) res.getString(R.string.alert_plug_in_pct, pct) else res.getString(R.string.alert_plug_in)
            AlertType.WEEKLY_SUMMARY -> null
        }
    }

    companion object {
        const val CH_LINK = "link"
        const val CH_ALERTS = "alerts"
        const val CH_RESULTS = "results"
        const val ID_LINK = 1
        const val ID_RESULT = 2
        const val ID_ALERT_BASE = 100
        const val ID_BRIDGE_HOME = 60
        private const val REQ_BRIDGE_SETUP = 7
        private const val REQ_HISTORY = 8
    }
}
