package io.github.anwo91.phevlink.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.ui.MainActivity

class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_LINK, "Car connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the app is talking to the car in the background"
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERTS, "Car alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Charging finished, car left unlocked, lights on…"
                },
                NotificationChannel(CH_RESULTS, "Command results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Outcome of preheating, scheduled departures and quick actions"
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

    fun alert(a: Alert) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(a.title)
            .setContentText(a.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.message))
            .setCategory(if (a.type == AlertType.CHARGE_COMPLETE || a.type == AlertType.CLIMATE_FINISHED) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(ID_ALERT_BASE + a.type.ordinal, n)
    }

    fun result(title: String, text: String, ok: Boolean) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_RESULTS)
            .setSmallIcon(if (ok) R.drawable.ic_stat_car else R.drawable.ic_stat_warn)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 30 * 60_000L else 6 * 3600_000L)
            .build()
        nm.notify(ID_RESULT, n)
    }

    fun linkNotification(title: String, text: String, actions: List<NotificationCompat.Action> = emptyList()): Notification =
        NotificationCompat.Builder(context, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { actions.forEach { addAction(it) } }
            .build()

    fun updateLink(n: Notification) {
        if (canPost()) nm.notify(ID_LINK, n)
    }

    companion object {
        const val CH_LINK = "link"
        const val CH_ALERTS = "alerts"
        const val CH_RESULTS = "results"
        const val ID_LINK = 1
        const val ID_RESULT = 2
        const val ID_ALERT_BASE = 100
    }
}
