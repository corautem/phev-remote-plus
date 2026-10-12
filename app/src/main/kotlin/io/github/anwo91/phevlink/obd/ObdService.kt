package io.github.anwo91.phevlink.obd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ObdPhase
import io.github.anwo91.phevlink.core.obd.ObdState
import io.github.anwo91.phevlink.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service (type `connectedDevice`) that keeps the process alive — and the CPU awake — while an OBD
 * session runs or a CSV recording is active, so live data keeps flowing with the screen off. Shows an ongoing
 * notification ("OBD connected — <device>") with a Disconnect action, and stops itself as soon as
 * [ObdRepository.sessionActive] and [ObdRepository.recording] are both off.
 *
 * Started by [ObdRepository.connect] through [start], which only calls `startForegroundService` when
 * `startForeground` is allowed to succeed (Android 14 requires a Bluetooth/network permission for this type;
 * on Android 12+ BLUETOOTH_CONNECT must be granted for Bluetooth adapters).
 */
class ObdService : LifecycleService() {

    private val repo by lazy { graph.obd }
    private var lastStartId = 0
    private var foreground = false
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeAcquiredAt = 0L

    private data class Ui(val title: String, val text: String, val keep: Boolean)

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        if (intent?.action == ACTION_DISCONNECT) {
            graph.log.link("OBD: Disconnect tapped in the notification")
            repo.disconnect()
        } else if (!goForeground(currentUi())) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        watch()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        foreground = false
        super.onDestroy()
    }

    private fun watch() {
        if (watchJob?.isActive == true) return
        watchJob = lifecycleScope.launch {
            combine(repo.state, repo.device, repo.recording, repo.sessionActive) { s, d, r, active -> ui(s, d, r, active) }
                .onEach { if (it.keep) refreshWake() }
                .distinctUntilChanged()
                .collect { ui ->
                    if (!ui.keep) {
                        stopNow()
                    } else if (foreground) {
                        updateNotification(build(ui))
                    }
                }
        }
    }

    private fun currentUi(): Ui = ui(repo.state.value, repo.device.value, repo.recording.value, repo.sessionActive.value)

    private fun ui(s: ObdState, d: ObdDeviceSpec?, r: RecordingInfo?, active: Boolean): Ui {
        val name = d?.name ?: getString(R.string.obd_default_device)
        val title = when (s.phase) {
            ObdPhase.READY, ObdPhase.POLLING -> getString(R.string.obd_notif_connected, name)
            ObdPhase.CONNECTING, ObdPhase.INITIALISING -> getString(R.string.obd_notif_connecting, name)
            ObdPhase.ERROR -> getString(R.string.obd_notif_problem, name)
            ObdPhase.DISCONNECTED -> getString(R.string.obd_notif_idle)
        }
        val parts = listOfNotNull(
            s.message?.takeIf { it.isNotBlank() },
            r?.let { resources.getQuantityString(R.plurals.obd_notif_recording, it.rows, it.rows) },
        )
        return Ui(title, parts.joinToString(" · "), active || r != null)
    }

    private fun goForeground(ui: Ui): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(ui), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        foreground = true
        true
    } catch (e: Exception) {
        graph.log.error("OBD: could not start the foreground service: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun stopNow() {
        releaseWake()
        if (foreground) {
            foreground = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        // Only stops when no newer start request arrived meanwhile.
        stopSelfResult(lastStartId)
    }

    private fun updateNotification(n: Notification) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }
    }

    private fun build(ui: Ui): Notification {
        val disconnect = PendingIntent.getService(
            this, REQ_DISCONNECT, Intent(this, ObdService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setColor(getColor(R.color.brand))
            .setContentTitle(ui.title)
            .setContentText(ui.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ui.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(graph.notifier.openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_sc_stop, getString(R.string.obd_action_disconnect), disconnect)
            .build()
    }

    /** Partial wake lock while needed; acquired with a timeout and refreshed while state keeps changing. */
    private fun refreshWake() {
        val now = SystemClock.elapsedRealtime()
        val held = wakeLock?.isHeld == true
        if (held && now - wakeAcquiredAt < WAKE_REFRESH_MS) return
        try {
            val wl = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phevlink:obd")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            wl.acquire(WAKE_TIMEOUT_MS)
            wakeAcquiredAt = now
        } catch (e: Exception) {
            graph.log.warn("OBD: no wake lock: ${e.message}")
        }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "obd"
        const val NOTIFICATION_ID = 50
        const val ACTION_START = "io.github.anwo91.phevlink.OBD_START"
        const val ACTION_DISCONNECT = "io.github.anwo91.phevlink.OBD_DISCONNECT"
        private const val REQ_DISCONNECT = 51
        private const val WAKE_TIMEOUT_MS = 30 * 60_000L
        private const val WAKE_REFRESH_MS = 10 * 60_000L

        /** Creates (or updates the texts of) the "obd" notification channel. */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.obd_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.obd_channel_description)
                        setShowBadge(false)
                    },
                )
            }
        }

        /**
         * Whether `startForeground(…, connectedDevice)` will be allowed: Android 12+ needs BLUETOOTH_CONNECT for
         * Bluetooth adapters; Wi-Fi adapters are covered by the (install-time) network permissions on Android 14+.
         */
        fun canRunInForeground(context: Context, kind: ObdDeviceSpec.Kind): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return when (kind) {
                ObdDeviceSpec.Kind.WIFI -> ObdDevices.hasConnectPermission(context) ||
                    granted(context, Manifest.permission.CHANGE_WIFI_STATE) ||
                    granted(context, Manifest.permission.CHANGE_NETWORK_STATE)
                else -> ObdDevices.hasConnectPermission(context)
            }
        }

        private fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /**
         * Starts the service for a session with an adapter of [kind]. Only call while the app is visible (Android
         * forbids starting foreground services from the background). Never throws.
         * @return true when the start request was accepted
         */
        fun start(context: Context, kind: ObdDeviceSpec.Kind, log: DiagLog): Boolean {
            if (kind == ObdDeviceSpec.Kind.DEMO) {
                log.link("OBD: no background service for the simulated adapter")
                return false
            }
            if (!canRunInForeground(context, kind)) {
                log.warn("OBD: not starting the background service (Bluetooth permission missing)")
                return false
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java).setAction(ACTION_START))
                true
            } catch (e: Exception) {
                log.warn("OBD: could not start the background service (${e.javaClass.simpleName}: ${e.message}) — the connection only lasts while the app is open")
                false
            }
        }
    }
}
