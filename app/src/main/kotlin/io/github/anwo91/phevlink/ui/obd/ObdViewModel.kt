package io.github.anwo91.phevlink.ui.obd

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.obd.ClearDtcsResult
import io.github.anwo91.phevlink.core.obd.DtcScanResult
import io.github.anwo91.phevlink.core.obd.livetest.LiveTest
import io.github.anwo91.phevlink.core.obd.livetest.Verdict
import io.github.anwo91.phevlink.graph
import io.github.anwo91.phevlink.obd.ObdDeviceSpec
import io.github.anwo91.phevlink.util.AppLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The OBD tab: connection, live data, fault codes, live tests and tools. */
class ObdViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    val obd = g.obd
    val state = obd.state
    val device = obd.device
    val recentDevices = obd.recentDevices
    val recording = obd.recording
    val logTraffic = obd.logTraffic
    val sessionActive = obd.sessionActive
    val tests = g.liveTests
    val testStore = g.testStore
    val log = g.log

    /** VIN from the car's Wi-Fi link, if known (for the battery reference capacity). */
    private val wifiVin: String? get() = g.repo.car.value.vin

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _busy = MutableStateFlow<Int?>(null)
    /** String resource of the operation in progress (scan, clear…), or null. */
    val busy: StateFlow<Int?> = _busy.asStateFlow()

    private val _lastScan = MutableStateFlow<DtcScanResult?>(null)
    val lastScan: StateFlow<DtcScanResult?> = _lastScan.asStateFlow()

    private val _lastClear = MutableStateFlow<ClearDtcsResult?>(null)
    val lastClear: StateFlow<ClearDtcsResult?> = _lastClear.asStateFlow()

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()

    init {
        // Give the client the VIN known from Wi-Fi so the battery health uses the right model year.
        viewModelScope.launch {
            state.collect { obd.client?.let { c -> if (c.vinHint == null) c.vinHint = wifiVin } }
        }
    }

    /** Language for texts that are not Android resources (fault codes, live tests, tips). */
    fun lang(): String = AppLocale.effectiveLanguage(getApplication())

    fun connect(spec: ObdDeviceSpec) = obd.connect(spec)
    fun disconnect() = obd.disconnect()
    fun reconnect() = obd.reconnect()
    fun forget(spec: ObdDeviceSpec) = obd.forget(spec)

    fun scan(extended: Boolean) = op(R.string.obd_busy_scanning) {
        _lastScan.value = obd.scanDtcs(extended)
    }

    fun clear() = op(R.string.obd_busy_clearing) {
        val r = obd.clearDtcs()
        _lastClear.value = r
        _messages.emit(r.message)
    }

    fun readBattery() = op(R.string.obd_busy_battery) {
        if (obd.readBattery() == null) {
            _messages.emit(state.value.batteryMessage ?: getApplication<Application>().getString(R.string.obd_battery_unavailable))
        }
    }

    fun readCells() = op(R.string.obd_busy_cells) {
        val c = obd.readCells()
        val app = getApplication<Application>()
        when {
            c == null -> _messages.emit(state.value.batteryMessage ?: app.getString(R.string.obd_battery_unavailable))
            !c.verified -> _messages.emit(app.getString(R.string.obd_cells_unverified_toast))
        }
    }

    fun sendTerminal(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        viewModelScope.launch {
            _terminal.value = (_terminal.value + "> $c").takeLast(400)
            val answer = obd.terminal(c)
            _terminal.value = (_terminal.value + answer.lines().filter { it.isNotBlank() }).takeLast(400)
        }
    }

    fun clearTerminal() {
        _terminal.value = emptyList()
    }

    fun setLogTraffic(on: Boolean) = obd.setLogTraffic(on)

    fun toggleRecording() {
        if (recording.value != null) {
            val f = obd.stopRecording()
            if (f != null) toast(getApplication<Application>().getString(R.string.obd_rec_saved, f.absolutePath))
        } else if (!obd.startRecording()) {
            toast(getApplication<Application>().getString(R.string.obd_rec_failed))
        }
    }

    // ------------------------------------------------------------------ live tests

    fun startTest(test: LiveTest) {
        val client = obd.client
        if (client == null) {
            toast(getApplication<Application>().getString(R.string.obd_not_connected))
            return
        }
        // current: a reconnect (automatic, or the user's) creates a new client; the test follows it instead of
        // talking to the closed one for the rest of a 15–25 minute test.
        tests.start(test, client, testStore, client.state.value.vin ?: wifiVin, current = { obd.client })
    }

    /** Answers the prompt of [step]; a late second tap meant for an earlier prompt is ignored. */
    fun answerTest(positive: Boolean, step: Int? = null) = tests.answer(positive, step)
    fun cancelTest() = tests.cancel()
    fun dismissTest() = tests.dismiss()
    fun lastRun(id: String): Pair<Verdict, Long>? = tests.lastRun(testStore, id)

    fun toast(text: String) {
        viewModelScope.launch { _messages.emit(text) }
    }

    private fun op(label: Int, block: suspend () -> Unit) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = label
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The OBD client is meant to report failures in its results, but anything that still escapes
                // would crash the app from viewModelScope: log it and show it instead.
                log.error("OBD: operation failed unexpectedly: ${e.javaClass.simpleName}: ${e.message}")
                _messages.emit(e.message ?: e.javaClass.simpleName)
            } finally {
                _busy.value = null
            }
        }
    }

    companion object {
        private val READ_SERVICES = setOf("01", "02", "03", "05", "06", "07", "09", "0A", "18", "19", "1A", "21", "22", "3E")

        /**
         * True for commands that only read: adapter (AT/ST) commands, which never reach the car, and OBD/KWP/UDS read
         * services. Everything else (04 clear, 10 session, 11 reset, 14 clear, 27 security, 2E/2F/31/3B/3D writes and
         * routines…) needs a confirmation in the terminal.
         */
        fun isReadOnlyCommand(cmd: String): Boolean {
            val c = cmd.trim().uppercase().replace(" ", "")
            if (c.isEmpty() || c.startsWith("AT") || c.startsWith("ST")) return true
            return c.length >= 2 && c.take(2) in READ_SERVICES
        }
    }
}
