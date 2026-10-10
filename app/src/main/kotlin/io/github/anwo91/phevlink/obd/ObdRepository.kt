package io.github.anwo91.phevlink.obd

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.FileProvider
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.AdapterGuess
import io.github.anwo91.phevlink.core.obd.BatteryHealth
import io.github.anwo91.phevlink.core.obd.CellData
import io.github.anwo91.phevlink.core.obd.ClearDtcsResult
import io.github.anwo91.phevlink.core.obd.DtcScanResult
import io.github.anwo91.phevlink.core.obd.Elm327
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.ObdCsvLogger
import io.github.anwo91.phevlink.core.obd.ObdLink
import io.github.anwo91.phevlink.core.obd.ObdPhase
import io.github.anwo91.phevlink.core.obd.ObdState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** An active CSV recording: [rows] written so far to [file], started at [startedAtMs] (wall clock). */
data class RecordingInfo(val file: File, val rows: Int, val startedAtMs: Long)

/**
 * The single owner of the OBD connection (one [ObdClient] at a time). Screens and the [ObdService] go through here.
 *
 * A connection is a *session* ([connect] … [disconnect]):
 * 1. starts [ObdService] (foreground, keeps the process alive with the screen off);
 * 2. opens the link ([BluetoothClassicLink], [BluetoothLeLink] or [WifiTcpLink]) with up to
 *    [INITIAL_ATTEMPTS] tries and clear messages (Bluetooth off, permission missing, out of range…);
 * 3. [ObdClient.connect] (adapter init + discovery), then [ObdClient.startLive] every [LIVE_INTERVAL_MS];
 * 4. if the link drops while live, reconnects up to 3 times (after 2 s, 5 s, 10 s), then gives up and keeps the
 *    error message in [state]. A connection that keeps dropping right after connecting stops the session too.
 *
 * Nothing here throws to the caller: every failure ends up in [state] ([ObdState.message]) and in the [DiagLog]
 * (LINK lines prefixed "OBD:").
 *
 * @param scope long-lived application scope (sessions and recordings run in it)
 */
class ObdRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val log: DiagLog,
) {
    /** Last outside temperature the car reported over OBD (°C, wall-clock ms), or null. */
    @Volatile
    var lastAmbient: Pair<Double, Long>? = null
        private set

    private val app: Context = context.applicationContext
    private val store = ObdDeviceStore(app)

    private val _state = MutableStateFlow(ObdState())

    /** State of the current client; a DISCONNECTED/CONNECTING/ERROR state with a message when there is no client. */
    val state: StateFlow<ObdState> = _state.asStateFlow()

    private val _device = MutableStateFlow(store.last())

    /** The adapter of the current (or last) session. */
    val device: StateFlow<ObdDeviceSpec?> = _device.asStateFlow()

    private val _recent = MutableStateFlow(store.recent())

    /** Recently used adapters, most recent first. */
    val recentDevices: StateFlow<List<ObdDeviceSpec>> = _recent.asStateFlow()

    private val _recording = MutableStateFlow<RecordingInfo?>(null)

    /** The running CSV recording, or null. */
    val recording: StateFlow<RecordingInfo?> = _recording.asStateFlow()

    private val _logTraffic = MutableStateFlow(false)

    /** User choice: log every ELM command/answer (TX/RX) even during live polling. See [setLogTraffic]. */
    val logTraffic: StateFlow<Boolean> = _logTraffic.asStateFlow()

    private val _sessionActive = MutableStateFlow(false)

    /** True from [connect] until the session ends (disconnect or give-up). [ObdService] runs while this or [recording] is set. */
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    @Volatile private var current: ObdClient? = null

    /** The current client (connected or connecting), for live tests; null between sessions. */
    val client: ObdClient? get() = current

    private val lock = Any()
    private var sessionJob: Job? = null
    private val trafficHolds = AtomicInteger(0)
    @Volatile private var lastCycle = 0L

    private val recLock = Any()
    private var recJob: Job? = null
    private var recFile: File? = null

    /** A connected client and the scope its live polling and state forwarding run in. */
    private class Active(val client: ObdClient, val scope: CoroutineScope)

    // ------------------------------------------------------------------ sessions

    /**
     * Connects to [spec] (remembered as last/recent device): disconnects any previous client first, starts the
     * foreground service, opens the link, initialises the adapter and starts live polling. Returns immediately;
     * follow [state]. Call from the UI (Android only lets a visible app start the foreground service).
     */
    fun connect(spec: ObdDeviceSpec) {
        _recent.value = store.remember(spec)
        _device.value = spec
        val job = synchronized(lock) {
            val previous = sessionJob
            val newJob = scope.launch(start = CoroutineStart.LAZY) {
                if (previous != null) {
                    log.link("OBD: closing the previous connection first")
                    previous.cancelAndJoin()
                }
                runSession(spec)
            }
            sessionJob = newJob
            _sessionActive.value = true
            newJob
        }
        _state.value = ObdState(phase = ObdPhase.CONNECTING, message = connectingText(spec), pollCycle = lastCycle)
        ObdService.start(app, spec.kind, log)
        job.start()
    }

    /** Stops the session (live polling, link, recording). The service stops itself. */
    fun disconnect() {
        val job = synchronized(lock) { sessionJob }
        stopRecording()
        if (job != null && !job.isCompleted) {
            log.link("OBD: disconnect requested")
            job.cancel()
        } else {
            _state.value = ObdState(phase = ObdPhase.DISCONNECTED, message = app.getString(R.string.obd_disconnected), pollCycle = lastCycle)
        }
    }

    /** Connects again to the current/last device (new link, new client). No-op without a device. */
    fun reconnect() {
        val spec = _device.value
        if (spec == null) {
            log.warn("OBD: reconnect requested but no adapter was used yet")
            return
        }
        connect(spec)
    }

    /** Removes [spec] from the recent devices. */
    fun forget(spec: ObdDeviceSpec) {
        _recent.value = store.forget(spec)
        if (_device.value?.sameDevice(spec) == true && !_sessionActive.value) _device.value = store.last()
    }

    private suspend fun runSession(spec: ObdDeviceSpec) {
        val self = currentCoroutineContext()[Job]
        var failed = false
        try {
            _state.value = ObdState(phase = ObdPhase.CONNECTING, message = connectingText(spec), pollCycle = lastCycle)
            var active: Active = establish(spec, INITIAL_ATTEMPTS) ?: run {
                failed = true
                return
            }
            var shortDrops = 0
            while (true) {
                val upSince = SystemClock.elapsedRealtime()
                val end = active.client.state.first { it.phase == ObdPhase.ERROR || it.phase == ObdPhase.DISCONNECTED }
                val upMs = SystemClock.elapsedRealtime() - upSince
                closeActive(active)
                if (end.phase == ObdPhase.DISCONNECTED) {
                    log.link("OBD: the client was closed — ending the session")
                    return
                }
                val reason = end.message ?: ObdClient.MSG_LINK_LOST
                log.warn("OBD: connection to ${spec.name} dropped after ${upMs / 1000} s: $reason")
                shortDrops = if (upMs < STABLE_MS) shortDrops + 1 else 0
                if (shortDrops >= MAX_SHORT_DROPS) {
                    fail(app.getString(R.string.obd_unstable))
                    failed = true
                    return
                }
                var next: Active? = null
                for ((i, backoff) in RECONNECT_BACKOFF_MS.withIndex()) {
                    val blocked = blocker(spec, afterDrop = true)
                    if (blocked != null) {
                        fail(blocked)
                        failed = true
                        return
                    }
                    val msg = app.getString(R.string.obd_reconnecting, (backoff / 1000).toInt(), i + 1, RECONNECT_BACKOFF_MS.size)
                    log.link("OBD: reconnecting in ${backoff / 1000} s (attempt ${i + 1} of ${RECONNECT_BACKOFF_MS.size})")
                    _state.value = _state.value.copy(phase = ObdPhase.CONNECTING, carReachable = false, message = msg)
                    delay(backoff)
                    next = establish(spec, 1)
                    if (next != null) break
                }
                active = next ?: run {
                    fail(app.getString(R.string.obd_gave_up, reason, RECONNECT_BACKOFF_MS.size))
                    failed = true
                    return
                }
            }
        } finally {
            withContext(NonCancellable) {
                current?.let { c -> closeClient(c) }
                val stillCurrent = synchronized(lock) {
                    if (sessionJob === self) {
                        sessionJob = null
                        _sessionActive.value = false
                        true
                    } else {
                        false
                    }
                }
                if (stillCurrent && !failed) {
                    _state.value = ObdState(phase = ObdPhase.DISCONNECTED, message = app.getString(R.string.obd_disconnected), pollCycle = lastCycle)
                }
                // A recording without a connection would keep the service alive while writing nothing.
                if (stillCurrent) stopRecording()?.let { lastRecording = it }
                log.link("OBD: session with ${spec.name} ended" + if (failed) " (failed)" else "")
            }
        }
    }

    /**
     * Opens the link and initialises the adapter, up to [attempts] times. Returns the live client, or null with
     * an ERROR [state] explaining why.
     */
    private suspend fun establish(spec: ObdDeviceSpec, attempts: Int): Active? {
        var lastMessage: String? = null
        for (attempt in 1..attempts) {
            val blocked = blocker(spec, afterDrop = false)
            if (blocked != null) {
                fail(blocked)
                return null
            }
            if (attempt > 1) {
                _state.value = ObdState(
                    phase = ObdPhase.CONNECTING,
                    message = app.getString(R.string.obd_retrying, lastMessage.orEmpty(), attempt, attempts),
                    pollCycle = lastCycle,
                )
                delay(RETRY_DELAY_MS)
            }
            log.link("OBD: connecting to ${spec.name} (${spec.address}) via ${kindForLog(spec.kind)} — attempt $attempt of $attempts")
            val t0 = SystemClock.elapsedRealtime()
            val link: ObdLink = try {
                withTimeoutOrNull(OPEN_TIMEOUT_MS) { openLink(spec) }
                    ?: throw ObdConnectException(ObdConnectException.Reason.TIMEOUT, "No link within ${OPEN_TIMEOUT_MS / 1000} s")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastMessage = explain(spec, e)
                log.warn(
                    "OBD: link to ${spec.name} failed after ${SystemClock.elapsedRealtime() - t0} ms: " +
                        "${e.javaClass.simpleName}: ${e.message}" + (e.cause?.let { c -> " (cause: ${c.javaClass.simpleName}: ${c.message})" } ?: ""),
                )
                val fatal = (e is ObdConnectException && !e.retryable) || e is SecurityException
                if (fatal) break
                continue
            }
            log.link("OBD: link ready (${link.description}) after ${SystemClock.elapsedRealtime() - t0} ms — initialising the adapter")

            val clientScope = CoroutineScope(SupervisorJob(currentCoroutineContext()[Job]) + Dispatchers.Default)
            val client = ObdClient(link, log, clientScope)
            val active = Active(client, clientScope)
            current = client
            val base = lastCycle + 1
            clientScope.launch {
                client.state.collect { s ->
                    // The client starts DISCONNECTED and ends DISCONNECTED; the repository reports those phases itself.
                    // Outside temperature measured by the car, for climate decisions (better than a forecast).
                    AMBIENT_IDS.firstNotNullOfOrNull { id -> s.values[id]?.value?.let { v -> v to s.values.getValue(id).updatedAtMs } }
                        ?.let { if (it.first in -40.0..60.0) lastAmbient = it }
                    if (s.phase != ObdPhase.DISCONNECTED) {
                        val cycle = s.pollCycle + base
                        lastCycle = maxOf(lastCycle, cycle)
                        _state.value = s.copy(pollCycle = cycle)
                    }
                }
            }
            val ok = withTraffic { client.connect() }
            if (ok) {
                client.startLive(LIVE_INTERVAL_MS)
                applyTraffic()
                log.link("OBD: connected to ${spec.name} in ${SystemClock.elapsedRealtime() - t0} ms — live data every $LIVE_INTERVAL_MS ms")
                return active
            }
            val clientMessage = client.state.value.message
            lastMessage = clientMessage ?: app.getString(R.string.obd_err_unreachable, spec.name)
            log.warn("OBD: adapter initialisation failed (attempt $attempt of $attempts): $lastMessage")
            closeActive(active)
            if (clientMessage == Elm327.MSG_NO_ADAPTER) {
                // The Bluetooth/Wi-Fi link itself came up, so the device is powered and in range — it just doesn't
                // answer ELM327 commands. Field log: an Autel "Maxi-…" scan-tool VCI over BLE, silent to ATZ/ATWS.
                val brand = AdapterGuess.proprietaryBrand(spec.name)
                log.warn(
                    "OBD: ${spec.name} accepted the ${kindForLog(spec.kind)} connection (${link.description}) but never answered " +
                        "ELM327 commands — " + (brand?.let { "the name suggests a $it scan tool, which only works with its own app" }
                            ?: "not an ELM327 adapter, or one that is stuck"),
                )
                lastMessage = if (brand != null) app.getString(R.string.obd_err_not_elm_brand, spec.name, brand)
                else app.getString(R.string.obd_err_not_elm, spec.name)
                // A known proprietary tool won't start speaking ELM327 on the next try.
                if (brand != null) break
            }
        }
        fail(lastMessage ?: app.getString(R.string.obd_err_unreachable, spec.name))
        return null
    }

    private suspend fun openLink(spec: ObdDeviceSpec): ObdLink = when (spec.kind) {
        ObdDeviceSpec.Kind.CLASSIC -> BluetoothClassicLink.open(app, spec.address, spec.name, log)
        ObdDeviceSpec.Kind.BLE -> BluetoothLeLink.open(app, spec.address, spec.name, log)
        ObdDeviceSpec.Kind.WIFI -> WifiTcpLink.open(app, spec.address, spec.name, log)
    }

    /** Why connecting can't work right now (no Bluetooth, permission, Bluetooth off), or null. */
    private fun blocker(spec: ObdDeviceSpec, afterDrop: Boolean): String? {
        if (spec.kind == ObdDeviceSpec.Kind.WIFI) return null
        if (!ObdDevices.hasBluetooth(app)) return app.getString(R.string.obd_err_no_bluetooth)
        if (!ObdDevices.hasConnectPermission(app)) return app.getString(R.string.obd_err_permission)
        if (!ObdDevices.isBluetoothOn(app)) {
            return app.getString(if (afterDrop) R.string.obd_bt_turned_off else R.string.obd_err_bluetooth_off)
        }
        return null
    }

    private suspend fun closeActive(a: Active) {
        closeClient(a.client)
        a.scope.cancel()
    }

    /** Stops polling and closes the link; never interrupted halfway. */
    private suspend fun closeClient(c: ObdClient) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (current === c) current = null
            try {
                c.disconnect()
            } catch (e: Exception) {
                log.warn("OBD: error while closing the client: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { c.elm.close() }
            }
        }
    }

    private fun fail(message: String) {
        log.error("OBD: $message")
        _state.value = ObdState(phase = ObdPhase.ERROR, message = message, pollCycle = lastCycle)
    }

    private fun explain(spec: ObdDeviceSpec, e: Throwable): String {
        val name = spec.name
        return when {
            e is ObdConnectException -> when (e.reason) {
                ObdConnectException.Reason.NO_BLUETOOTH -> app.getString(R.string.obd_err_no_bluetooth)
                ObdConnectException.Reason.BLUETOOTH_OFF -> app.getString(R.string.obd_err_bluetooth_off)
                ObdConnectException.Reason.PERMISSION -> app.getString(R.string.obd_err_permission)
                ObdConnectException.Reason.BAD_ADDRESS -> app.getString(R.string.obd_err_bad_address, spec.address)
                ObdConnectException.Reason.UNREACHABLE -> app.getString(R.string.obd_err_unreachable, name)
                ObdConnectException.Reason.NO_UART_SERVICE -> app.getString(R.string.obd_err_no_uart, name)
                ObdConnectException.Reason.WIFI_UNREACHABLE -> app.getString(R.string.obd_err_wifi_unreachable, spec.address)
                ObdConnectException.Reason.TIMEOUT -> app.getString(R.string.obd_err_timeout, name)
                ObdConnectException.Reason.PAIRING_FAILED -> app.getString(R.string.obd_err_pairing, name)
            }
            e is SecurityException -> app.getString(R.string.obd_err_permission)
            e is IOException && looksUnreachable(e.message) -> app.getString(R.string.obd_err_unreachable, name)
            else -> app.getString(R.string.obd_err_other, name, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun looksUnreachable(message: String?): Boolean {
        val m = message?.lowercase(Locale.ROOT) ?: return false
        return UNREACHABLE_HINTS.any { m.contains(it) }
    }

    private fun connectingText(spec: ObdDeviceSpec): String =
        app.getString(R.string.obd_connecting, spec.name, app.getString(kindLabel(spec.kind)))

    private fun kindLabel(kind: ObdDeviceSpec.Kind): Int = when (kind) {
        ObdDeviceSpec.Kind.CLASSIC -> R.string.obd_kind_classic
        ObdDeviceSpec.Kind.BLE -> R.string.obd_kind_ble
        ObdDeviceSpec.Kind.WIFI -> R.string.obd_kind_wifi
    }

    private fun kindForLog(kind: ObdDeviceSpec.Kind): String = when (kind) {
        ObdDeviceSpec.Kind.CLASSIC -> "Bluetooth Classic"
        ObdDeviceSpec.Kind.BLE -> "Bluetooth LE"
        ObdDeviceSpec.Kind.WIFI -> "Wi-Fi"
    }

    // ------------------------------------------------------------------ operations

    /** Reads fault codes (see [ObdClient.scanDtcs]); a "not connected" result without a client. */
    suspend fun scanDtcs(extended: Boolean): DtcScanResult {
        val c = current
        if (c == null) {
            val msg = app.getString(R.string.obd_not_connected)
            return DtcScanResult(emptyList(), emptyList(), listOf(msg), extended, System.currentTimeMillis(), msg)
        }
        return withTraffic { c.scanDtcs(extended) }
    }

    /** Clears generic fault codes (see [ObdClient.clearDtcs]). */
    suspend fun clearDtcs(): ClearDtcsResult {
        val c = current ?: return ClearDtcsResult(false, emptyList(), emptyList(), emptyList(), app.getString(R.string.obd_not_connected))
        return withTraffic { c.clearDtcs() }
    }

    /** Terminal command (see [ObdClient.terminal]); "ERROR: …" without a client. */
    suspend fun terminal(cmd: String): String {
        val c = current ?: return "ERROR: " + app.getString(R.string.obd_not_connected)
        return withTraffic { c.terminal(cmd) }
    }

    /** Reads the drive battery now (see [ObdClient.readBattery]); null without a client or when unavailable. */
    suspend fun readBattery(): BatteryHealth? = current?.readBattery()

    /** Reads the per-cell voltages and module temperatures (see [ObdClient.readCells]). */
    suspend fun readCells(): CellData? = current?.readCells()

    /**
     * Log every ELM command and answer to the diagnostic log ([ObdClient.elm] `logTraffic`). Off by default so live
     * polling doesn't flood the log; connecting, DTC scans and terminal commands are always logged.
     */
    fun setLogTraffic(enabled: Boolean) {
        _logTraffic.value = enabled
        applyTraffic()
    }

    private fun applyTraffic() {
        val on = _logTraffic.value || trafficHolds.get() > 0
        current?.let { it.elm.logTraffic = on }
    }

    private suspend fun <T> withTraffic(block: suspend () -> T): T {
        trafficHolds.incrementAndGet()
        applyTraffic()
        try {
            return block()
        } finally {
            trafficHolds.decrementAndGet()
            applyTraffic()
        }
    }

    // ------------------------------------------------------------------ recording

    /**
     * Starts writing one CSV row per poll cycle to `obd-logs/obd-yyyyMMdd-HHmmss.csv` in the app's external files
     * directory (internal files directory if external storage is unavailable), flushed after every row. Keeps
     * running across automatic reconnects; [disconnect] stops it.
     * @return true when recording (also when it already was)
     */
    fun startRecording(): Boolean {
        synchronized(recLock) {
            if (recJob?.isActive == true) return true
            val file = try {
                val dir = app.getExternalFilesDir(LOG_DIR) ?: File(app.filesDir, LOG_DIR)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
                File(dir, "obd-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv")
            } catch (e: Exception) {
                log.error("OBD: cannot start recording: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
            val writer = try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
            } catch (e: Exception) {
                log.error("OBD: cannot create ${file.absolutePath}: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
            val c = current
            val columns = if (c != null && _state.value.supportedPids.isNotEmpty()) c.knownValueIds() else emptyList()
            // maxRows = 1: rows are streamed to the file; the logger would otherwise also keep up to 100 000 rows
            // (one map of ~100 cells per second) in memory — tens of MB per hour, an OOM on a long recording.
            val logger = ObdCsvLogger(columns, maxRows = 1)
            val startedAt = System.currentTimeMillis()
            recFile = file
            _recording.value = RecordingInfo(file, 0, startedAt)
            recJob = scope.launch(Dispatchers.IO) {
                var rows = 0
                try {
                    _state.collect { s ->
                        if (logger.append(writer, s)) {
                            rows++
                            _recording.value = RecordingInfo(file, rows, startedAt)
                        }
                    }
                } catch (e: IOException) {
                    log.error("OBD: recording to ${file.name} failed: ${e.message}")
                } finally {
                    runCatching { writer.close() }
                    log.link("OBD: recording stopped — $rows rows in ${file.absolutePath}")
                    synchronized(recLock) {
                        if (recFile == file) {
                            recFile = null
                            recJob = null
                            _recording.value = null
                        }
                    }
                }
            }
            log.link("OBD: recording to ${file.absolutePath}" + if (columns.isEmpty()) " (columns added as values appear)" else " (${columns.size} columns)")
            return true
        }
    }

    /** Stops the recording. Returns its file (complete: every row is flushed), or null when none was running. */
    /** The most recent finished recording (for "Share"), or null. */
    @Volatile var lastRecording: File? = null
        private set

    fun stopRecording(): File? {
        val (job, file) = synchronized(recLock) {
            val pair = recJob to recFile
            recJob = null
            recFile = null
            pair
        }
        job?.cancel()
        if (file != null) {
            _recording.value = null
            lastRecording = file
        }
        return file
    }

    /**
     * A share-sheet intent for a recording, or null when the app has no FileProvider configured for it
     * (none is declared at the moment; the UI should then show the file path instead).
     */
    fun shareIntent(file: File): Intent? {
        val authority = fileProviderAuthority() ?: return null
        val uri = try {
            FileProvider.getUriForFile(app, authority, file)
        } catch (e: Exception) {
            log.warn("OBD: cannot share ${file.name}: ${e.message}")
            return null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Suppress("DEPRECATION") // getPackageInfo(String, Int): the flags overload needs API 33.
    private fun fileProviderAuthority(): String? = try {
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
            .providers
            ?.firstOrNull { it.name == FILE_PROVIDER_CLASS || it.name.endsWith(".FileProvider") }
            ?.authority
            ?.split(';')
            ?.firstOrNull { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val LIVE_INTERVAL_MS = 1_000L
        const val LOG_DIR = "obd-logs"
        private const val INITIAL_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_500L
        private const val OPEN_TIMEOUT_MS = 60_000L
        /** A/C ECU sensor first (the one behind the dashboard reading), then the AWC unit and the standard PID. */
        private val AMBIENT_IDS = listOf("ac.ambient_temp", "awc.ambient_temp", "obd.46")
        private val RECONNECT_BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L)

        /** A connection that lasted less than this counts as a short drop. */
        private const val STABLE_MS = 30_000L
        private const val MAX_SHORT_DROPS = 3
        private const val FILE_PROVIDER_CLASS = "androidx.core.content.FileProvider"
        private val UNREACHABLE_HINTS = listOf(
            "socket might closed", "read failed", "timeout", "timed out", "host is down", "refused", "unreachable",
            "status 8", "status 133", "status 62",
        )
    }
}
