package io.github.anwo91.phevlink.core.obd.emulator

import io.github.anwo91.phevlink.core.obd.ObdHex
import io.github.anwo91.phevlink.core.obd.ObdLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Collections
import java.util.Locale
import kotlin.math.roundToInt

/**
 * In-process emulator of an ELM327 adapter plugged into a 2014–2017 Outlander PHEV (no sockets), for tests and demos.
 *
 * Adapter: AT command set as used by the driver (ATZ/ATWS/ATD banner, echo on until ATE0, line feeds, spaces,
 * headers on/off, ATSH, ATCRA, ATFCSH/ATFCSD/ATFCSM, ATSP/ATTP, ATDP/ATDPN, ATRV, ATI, AT@1, `?` for anything
 * unknown, an empty line repeats the last command), `>` prompt, replies formatted like a real ELM327 with headers on
 * (ISO-TP PCI bytes, first/consecutive frames, frames padded to 8 bytes with 00) or off (`037` + `0: ...` lines),
 * `SEARCHING...` before the first OBD request after ATZ/ATSP, `NO DATA` when nobody answers.
 *
 * Flow control: a multi-frame reply only continues past the first frame when the adapter's flow-control frame reaches
 * the ECU: automatic mode (ATFCSM0) works only for ECUs whose reply id is request id + 8 (7E0/7E8); otherwise
 * ATFCSH<request id> + ATFCSD30.. + ATFCSM1 are needed. Without it, the emulator prints **only the first frame** and
 * the prompt — what a real ELM does when the ECU never gets a flow-control frame (it waits, times out and stops).
 *
 * ECUs (data):
 * - 7E0/7E8 engine: mode 01 PIDs 01 04 05 0C 0D 0F 11 1F 21 2F 31 33 42 46 with consistent bitmaps (0100/0120/0140),
 *   mode 03 → P0420, 07 → P0456, 0A → none, mode 04 clears 03/07, mode 09 00/02 (VIN, multi-frame)/0A (ECU name);
 *   service 21: 08 0E 0F 12 1A 1D 1E answer with the OBDb Outlander MY2011 test vectors (CC BY-SA 4.0), other local
 *   ids → 7F 21 31; 18/19 → 7F xx 11.
 * - 7E1/7E9 second OBD ECU (only with [secondObdEcu]): PIDs 01 05, mode 03 → U0100.
 * - 7E2/7EA hybrid/EV control unit (only with [hybridEcu]): PID 01 (MIL + count), modes 03/0A/18 → [hybridDtcs] /
 *   [hybridPermanentDtcs]; mode 04 clears the stored ones only (permanent codes stay, like a real car).
 * - 761/762 BMU: 21 01 → the forum example reply (55 bytes) or the [pack] model; 18 02 FF 00 → [bmuDtcs] (default
 *   P0A7F, status 60); 19 → 7F 19 11.
 * - 765/766 OBC: 21 01 → 42 charges at 100 V, 500 at 200 V (made-up values); UDS 19 02 (after 10 03) → [obcDtcs].
 *   753/754, 755/756 motors: 21 02 → front 1000 rpm (0 when not [driving]), rear 0 rpm (made-up values).
 *   18/19 → 7F xx 11 (19 02 → no codes with [motorsAnswerUds]).
 * - Anything else: no answer (NO DATA).
 *
 * All knobs can be changed at any time; delays are zero by default so tests stay fast. [demo] builds the scenario the
 * app's demo mode shows (a parked, charging car with a worn drive battery).
 */
class FakeElm(
    /** ATI answer and reset banner. */
    @Volatile var version: String = "ELM327 v1.5",
) : ObdLink {
    override val description: String = "FakeElm (in-process ELM327 + Outlander PHEV emulator)"

    // ------------------------------------------------------------------ fault knobs

    /** ATFCSH/ATFCSD/ATFCSM answer "?" (cheap clone): BMU replies stop after the first frame. */
    @Volatile var cloneRejectsFlowControl = false

    /** ATCRA answers "?". */
    @Volatile var rejectReceiveFilter = false

    /**
     * CAN id of a module that sends frames on its own, like the PHEV's 7BC; null = a quiet bus. With headers on and
     * no receive filter for another id, every OBD answer gets one of its frames before and one after the real
     * replies: first byte counting 01..3F (so they look like single, first, consecutive and flow-control frames in
     * turn), second byte 91, then zeros — what a clone without ATCRA prints.
     */
    @Volatile var chatterId: Int? = null
    private var chatterCount = 0x16

    /** ATAT1 answers "?". */
    @Volatile var rejectAdaptiveTiming = false

    /** Deliver output in 20-byte chunks like BLE UART adapters. */
    @Volatile var bleChunks = false

    /** Line endings are `\r` only, even with ATL1. */
    @Volatile var crOnly = false

    /** All output in lower case. */
    @Volatile var lowercase = false

    /** Delay before each answer becomes readable, in ms. */
    @Volatile var slowMs = 0L

    /** ATZ produces no output at all (driver must fall back to ATWS). */
    @Volatile var silentReset = false

    /** Extra delay of the ATZ/ATWS answers only (a slow-booting adapter), in ms. Output stays in order (FIFO). */
    @Volatile var resetDelayMs = 0L

    /** Car switched off: OBD requests → `NO DATA` (fixed protocol) or `SEARCHING...` + `UNABLE TO CONNECT` (auto). */
    @Volatile var ignitionOff = false

    /** Junk (NUL, 0xFF, a stray `>`) before the first prompt and in front of the first reset answer. */
    @Volatile var garbageBeforePrompt = false

    /** The next BMU `21 01` answers start with this many `7F 21 78` (response pending). */
    @Volatile var pendingResponses = 0

    /**
     * With [pendingResponses]: each "pending" ends with the prompt (adapter stops listening), so the driver must ask
     * again; otherwise pending and the real answer come in one block (genuine ELM behaviour).
     */
    @Volatile var pendingSeparatePrompt = false

    /**
     * false = the engine ECU (7E0/7E8) has gone to sleep while the rest of the car still answers — what happens for a
     * moment when the real car is switched off.
     */
    @Volatile var engineAwake = true

    /** Adds a second OBD ECU at 7E1/7E9. */
    @Volatile var secondObdEcu = false

    /** Answer `STI` like an STN chip (OBDLink). */
    @Volatile var stnChip = false

    /** ATRV value. */
    @Volatile var adapterVoltage = 12.6

    /** AT@1 answer. */
    @Volatile var deviceDescription = "OBDII to RS232 Interpreter"

    /** When set, the next [receive] (and everything after) throws [IOException], as if Bluetooth dropped. */
    var dropLink: Boolean
        get() = dropRequested
        set(value) {
            dropRequested = value
            signal.trySend(Unit)
        }

    @Volatile private var dropRequested = false

    // ------------------------------------------------------------------ car state

    val storedDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf(0x0420))
    val pendingDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf(0x0456))
    val permanentDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val secondEcuDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf(0xC100))

    /** Adds the hybrid/EV control unit at 7E2/7EA (a generic OBD ECU, so its codes show up in a quick scan). */
    @Volatile var hybridEcu = false

    /** Stored codes of the hybrid/EV control unit (modes 03 and KWP 18). */
    val hybridDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf(0x0A80))

    /** Permanent codes of the hybrid/EV control unit (mode 0A); mode 04 doesn't clear them. */
    val hybridPermanentDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf(0x0A80))

    /** BMU codes for KWP `18 02 FF 00` (reported with status 60). */
    val bmuDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf(0x0A7F))

    /** OBC codes for UDS `19 02 FF` (failure type 24, status 08 = confirmed). Empty: a clean but answering OBC. */
    val obcDtcs: MutableList<Int> = Collections.synchronizedList(mutableListOf(0x0A80))

    /** The engine also answers KWP `18 02 FF 00` with its stored codes (otherwise it refuses with 7F 18 11). */
    @Volatile var engineAnswersKwp = false

    /**
     * The diagnostic session the OBC needs before it reports codes: 03 (UDS extended) by default; 92 = the KWP2000
     * extended session, for a module that answers `10 03` with 7F 10 12 (as some real ones do).
     */
    @Volatile var obcSessionId = 0x03

    /** The motor controllers answer UDS `19 02 FF` (no codes) instead of refusing every fault-code read. */
    @Volatile var motorsAnswerUds = false

    /** Car moving (engine 1726 rpm, 50 km/h, front motor 1000 rpm); false = parked with the ignition on. */
    @Volatile var driving = true

    /** Drive-battery model behind BMU `21 01`/`21 02`; null = the fixed forum sample ([BMU_2101], [BMU_2102]). */
    @Volatile var pack: Pack? = null

    /**
     * Whether the OBC is charging right now (AC/DC currents in `21 02`); null = the fixed sample (charging at
     * 230 V / 14 A). Read on every request, so it can follow a simulated car.
     */
    @Volatile var charging: (() -> Boolean?)? = null

    @Volatile var vin = "JMAXDGG2WFZ012345"

    /**
     * A simple drive battery for demos. Cell voltages follow the state of charge ([soc], in %, read on every request:
     * roughly 3.6 V empty to 4.1 V full — an estimate, not Mitsubishi data) with a tiny fixed scatter; [weakCell]
     * (1-based) sits [weakCellDropMv] below the rest. [capacityAh] is the BMU's full-charge capacity estimate.
     */
    class Pack(
        val capacityAh: Double,
        val soc: () -> Double,
        val weakCell: Int? = null,
        val weakCellDropMv: Int = 0,
    ) {
        private fun socNow(): Double = soc().takeIf { !it.isNaN() }?.coerceIn(0.0, 100.0) ?: 50.0

        /** Millivolts of cells 1..80. */
        fun cellsMv(): IntArray {
            val base = Math.round(3600 + socNow() * 5.0).toInt()
            return IntArray(CELL_COUNT) { i ->
                val n = i + 1
                base + ((n * 7) % 3 - 1) - if (n == weakCell) weakCellDropMv else 0
            }
        }

        /** `61 01 …` with the documented fields (SoC, highest/lowest cell, pack voltage, capacity, charge) replaced. */
        fun reply2101(): ByteArray {
            val out = BMU_2101.copyOf()
            val cells = cellsMv()
            var hi = 0
            var lo = 0
            for (i in cells.indices) {
                if (cells[i] > cells[hi]) hi = i
                if (cells[i] < cells[lo]) lo = i
            }
            val soc = socNow()
            val socRaw = Math.round((soc + 5.0) * 2.0).toInt().coerceIn(0, 255)
            fun put16(at: Int, v: Int) {
                out[at] = ((v shr 8) and 0xFF).toByte()
                out[at + 1] = (v and 0xFF).toByte()
            }
            // Offsets in the full reply (payload byte + 2 for the 61 01 echo); see PhevSignals "bmu.*".
            out[2] = socRaw.toByte()
            out[3] = socRaw.toByte()
            put16(4, cells[hi]); out[6] = (hi + 1).toByte()
            put16(7, cells[lo]); out[9] = (lo + 1).toByte()
            put16(10, (cells.sum() / 100.0).roundToInt())
            put16(29, (capacityAh * 10).roundToInt())
            put16(31, (capacityAh * soc / 100.0 * 10).roundToInt())
            return out
        }

        /** `61 02` + the 80 cell voltages, big-endian millivolts. */
        fun reply2102(): ByteArray {
            val cells = cellsMv()
            val out = ByteArray(2 + 2 * cells.size)
            out[0] = 0x61; out[1] = 0x02
            for ((i, mv) in cells.withIndex()) {
                out[2 + 2 * i] = ((mv shr 8) and 0xFF).toByte()
                out[3 + 2 * i] = (mv and 0xFF).toByte()
            }
            return out
        }

        companion object {
            const val CELL_COUNT = 80
        }
    }

    // ------------------------------------------------------------------ introspection

    private val commandLog = Collections.synchronizedList(ArrayList<String>())

    /** Every command received (upper case, spaces removed), in order. */
    val commands: List<String> get() = synchronized(commandLog) { commandLog.toList() }

    /** How often [cmd] (e.g. "ATSH761", "2101") was received. */
    fun count(cmd: String): Int = commands.count { it == cmd }

    fun clearCommandLog() = commandLog.clear()

    // ------------------------------------------------------------------ adapter state (guarded by lock)

    private val lock = Any()
    private var echo = true
    private var linefeeds = true
    private var spaces = true
    private var headers = false
    private var caf = true
    private var header = 0x7DF
    private var cra: Int? = null
    /** OBC in an extended diagnostic session? Like the real car, it only reports DTCs (UDS 19 02) once one is open. */
    private var obcSession = false
    private var fcHeader: Int? = null
    private var fcData: String? = null
    private var fcMode = 0
    private var protocol = '0'
    private var detected: Char? = null
    private var searchNext = true
    private var lastCommand: String? = null
    private var garbageDone = false
    private val input = StringBuilder()

    private class Out(val readyAtNs: Long, val bytes: ByteArray)

    private val queue = ArrayDeque<Out>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var closed = false

    private class Resp(val lines: List<String>, val prefix: String = "", val silent: Boolean = false)

    private inner class Ecu(val txId: Int, val rxId: Int, val obd: Boolean, val active: () -> Boolean, val handle: (ByteArray) -> List<ByteArray>?)

    private val ecus = listOf(
        Ecu(0x7E0, 0x7E8, true, { engineAwake }, ::engine),
        Ecu(0x7E1, 0x7E9, true, { secondObdEcu }, ::secondEcu),
        Ecu(0x7E2, 0x7EA, true, { hybridEcu }, ::hybrid),
        Ecu(0x761, 0x762, false, { true }, ::bmu),
        Ecu(0x765, 0x766, false, { true }, ::obc),
        Ecu(0x753, 0x754, false, { true }, { motor(it, front = true) }),
        Ecu(0x755, 0x756, false, { true }, { motor(it, front = false) }),
    )

    // ------------------------------------------------------------------ ObdLink

    override suspend fun send(bytes: ByteArray) {
        if (closed || dropRequested) throw IOException("FakeElm: link closed")
        synchronized(lock) {
            startOnce()
            for (b in bytes) {
                val c = (b.toInt() and 0xFF).toChar()
                when (c) {
                    '\r' -> {
                        val line = input.toString()
                        input.setLength(0)
                        handleLine(line)
                    }
                    '\n' -> Unit
                    else -> input.append(c)
                }
            }
        }
        signal.trySend(Unit)
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? {
        synchronized(lock) { startOnce() }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            if (dropRequested) {
                closed = true
                throw IOException("FakeElm: simulated link loss")
            }
            if (closed) throw IOException("FakeElm: link closed")
            val now = System.nanoTime()
            val ready: ByteArray?
            val nextAt: Long?
            synchronized(lock) {
                val head = queue.firstOrNull()
                if (head != null && head.readyAtNs <= now) {
                    queue.removeFirst()
                    ready = head.bytes
                    nextAt = null
                } else {
                    ready = null
                    nextAt = head?.readyAtNs
                }
            }
            if (ready != null) return ready
            if (now >= deadline) return null
            val wake = if (nextAt != null) minOf(nextAt, deadline) else deadline
            val waitMs = ((wake - now) / 1_000_000L).coerceAtLeast(1L)
            withTimeoutOrNull(waitMs) { signal.receive() }
        }
    }

    override fun close() {
        closed = true
        signal.trySend(Unit)
    }

    // ------------------------------------------------------------------ command handling

    private fun startOnce() {
        if (garbageDone) return
        garbageDone = true
        if (garbageBeforePrompt) enqueue("\u0000ÿþgarbage\u0007?\r>\u0000".toByteArray(Charsets.ISO_8859_1), 0)
    }

    private fun handleLine(rawLine: String) {
        val norm = rawLine.filterNot { it == ' ' || it.code < 0x20 }.uppercase(Locale.ROOT)
        val cmd = norm.ifEmpty { lastCommand ?: "" }
        if (cmd.isEmpty()) {
            emit("", Resp(emptyList()))
            return
        }
        commandLog += cmd
        val echoText = if (echo) rawLine + eol() else ""
        val resp = when {
            cmd.startsWith("AT") -> at(cmd.substring(2))
            cmd.startsWith("ST") -> if (cmd == "STI" && stnChip) Resp(listOf("STN1110 v4.2.1")) else Resp(listOf("?"))
            else -> obd(cmd)
        }
        lastCommand = cmd
        if (resp.silent) return
        emit(echoText, resp, if (cmd == "ATZ" || cmd == "ATWS") resetDelayMs else 0L)
    }

    private fun resetDefaults() {
        echo = true; linefeeds = true; spaces = true; headers = false; caf = true
        header = 0x7DF; cra = null; fcHeader = null; fcData = null; fcMode = 0
        protocol = '0'; detected = null; searchNext = true; obcSession = false
    }

    private val ok = Resp(listOf("OK"))
    private val unknown = Resp(listOf("?"))

    private fun at(a: String): Resp {
        when (a) {
            "Z", "WS" -> {
                if (a == "Z" && silentReset) return Resp(emptyList(), silent = true)
                val prefix = if (garbageBeforePrompt && a == "Z" && !resetGarbageDone) {
                    resetGarbageDone = true
                    "\u0000ÿ>"
                } else ""
                resetDefaults()
                return Resp(listOf("", version), prefix)
            }
            "D" -> { resetDefaults(); return ok }
            "I" -> return Resp(listOf(version))
            "@1" -> return Resp(listOf(deviceDescription))
            "RV" -> return Resp(listOf(String.format(Locale.ROOT, "%.1fV", adapterVoltage)))
            "DPN" -> return Resp(listOf(if (protocol == '0') (detected?.let { "A$it" } ?: "0") else protocol.toString()))
            "DP" -> return Resp(listOf(if (protocol == '0') "AUTO" + (detected?.let { ", ISO 15765-4 (CAN 11/500)" } ?: "") else "ISO 15765-4 (CAN 11/500)"))
            "PC", "AL", "NL", "AR", "M0", "M1", "R0", "R1" -> return ok
            "CRA" -> {
                if (rejectReceiveFilter) return unknown
                cra = null
                return ok
            }
        }
        fun bit(prefix: String, set: (Boolean) -> Unit): Resp? {
            if (a.length == prefix.length + 1 && a.startsWith(prefix) && (a.last() == '0' || a.last() == '1')) {
                set(a.last() == '1')
                return ok
            }
            return null
        }
        bit("E") { echo = it }?.let { return it }
        bit("L") { linefeeds = it }?.let { return it }
        bit("S") { spaces = it }?.let { return it }
        bit("H") { headers = it }?.let { return it }
        bit("CAF") { caf = it }?.let { return it }
        Regex("AT([012])").matchEntire(a)?.let { return if (rejectAdaptiveTiming) unknown else ok }
        Regex("[ST]PA?([0-9A-C])").matchEntire(a)?.let { m ->
            protocol = m.groupValues[1][0]
            detected = null
            searchNext = true
            return ok
        }
        Regex("ST[0-9A-F]{1,2}").matchEntire(a)?.let { return ok }
        Regex("SH([0-9A-F]{3})").matchEntire(a)?.let { m ->
            header = m.groupValues[1].toInt(16)
            return ok
        }
        Regex("SH([0-9A-F]{6}|[0-9A-F]{8})").matchEntire(a)?.let { return ok }
        Regex("CRA([0-9A-F]{3})").matchEntire(a)?.let { m ->
            if (rejectReceiveFilter) return unknown
            cra = m.groupValues[1].toInt(16)
            return ok
        }
        Regex("FCSH([0-9A-F]{3})").matchEntire(a)?.let { m ->
            if (cloneRejectsFlowControl) return unknown
            fcHeader = m.groupValues[1].toInt(16)
            return ok
        }
        Regex("FCSD([0-9A-F]{2,10})").matchEntire(a)?.let { m ->
            if (cloneRejectsFlowControl) return unknown
            fcData = m.groupValues[1]
            return ok
        }
        Regex("FCSM([012])").matchEntire(a)?.let { m ->
            if (cloneRejectsFlowControl) return unknown
            val mode = m.groupValues[1].toInt()
            if (mode == 1 && (fcHeader == null || fcData == null)) return unknown
            fcMode = mode
            return ok
        }
        return unknown
    }

    private var resetGarbageDone = false

    private fun obd(cmd: String): Resp {
        if (cmd.length % 2 != 0 || !cmd.all { Character.digit(it, 16) >= 0 }) return unknown
        val req = ObdHex.parse(cmd)
        val lines = ArrayList<String>()
        if (ignitionOff) {
            if (protocol == '0') return Resp(listOf("SEARCHING...", "UNABLE TO CONNECT"))
            return Resp(listOf("NO DATA"))
        }
        if (searchNext) {
            lines += "SEARCHING..."
            searchNext = false
        }
        if (protocol == '0') detected = '6'
        var frames = 0
        val chatter = chatterId?.takeIf { headers && (cra == null || cra == it) }
        fun chatterFrame() {
            if (chatter == null) return
            chatterCount = chatterCount % 0x3F + 1
            val sep = if (spaces) " " else ""
            lines += ObdHex.canId(chatter) + sep + ObdHex.format(byteArrayOf(chatterCount.toByte(), 0x91.toByte(), 0, 0, 0, 0, 0, 0), sep)
            frames++
        }
        chatterFrame()
        for (ecu in ecus) {
            if (!ecu.active()) continue
            val addressed = if (header == 0x7DF) ecu.obd else ecu.txId == header
            if (!addressed) continue
            val replies = synchronized(storedDtcs) { ecu.handle(req) } ?: continue
            if (cra != null && cra != ecu.rxId) continue
            for (r in replies) {
                val printed = format(ecu, r)
                frames += printed.size
                lines += printed
            }
        }
        chatterFrame()
        if (frames == 0) lines += "NO DATA"
        return Resp(lines)
    }

    private fun flowControlReaches(ecu: Ecu): Boolean = when (fcMode) {
        0 -> ecu.rxId - 8 == ecu.txId
        1 -> fcHeader == ecu.txId && fcData?.startsWith("30") == true
        else -> fcHeader == ecu.txId
    }

    /** ISO-TP frames of [payload] (8 bytes each, padded with 00), printed per the current settings. */
    private fun format(ecu: Ecu, payload: ByteArray): List<String> {
        val frames = ArrayList<ByteArray>()
        if (payload.size <= 7) {
            frames += pad(byteArrayOf(payload.size.toByte()) + payload)
        } else {
            val n = payload.size
            frames += byteArrayOf((0x10 or (n shr 8)).toByte(), (n and 0xFF).toByte()) + payload.copyOfRange(0, 6)
            var i = 6
            var seq = 1
            while (i < n) {
                val end = minOf(n, i + 7)
                frames += pad(byteArrayOf((0x20 or (seq and 0x0F)).toByte()) + payload.copyOfRange(i, end))
                i = end
                seq++
            }
        }
        val shown = if (frames.size > 1 && !flowControlReaches(ecu)) frames.take(1) else frames
        val sep = if (spaces) " " else ""
        fun hex(b: ByteArray) = ObdHex.format(b, sep)
        return when {
            headers -> shown.map { ObdHex.canId(ecu.rxId) + sep + hex(it) }
            !caf -> shown.map { hex(it) }
            frames.size == 1 -> listOf(hex(payload))
            else -> listOf(String.format(Locale.ROOT, "%03X", payload.size)) + shown.mapIndexed { idx, f ->
                "${Integer.toHexString(idx and 0x0F).uppercase(Locale.ROOT)}:$sep" + hex(f.copyOfRange(if (idx == 0) 2 else 1, f.size))
            }
        }
    }

    private fun pad(f: ByteArray): ByteArray = if (f.size >= 8) f else f + ByteArray(8 - f.size)

    private fun eol() = if (linefeeds && !crOnly) "\r\n" else "\r"

    private fun emit(echoText: String, resp: Resp, extraDelayMs: Long = 0L) {
        val e = eol()
        val sb = StringBuilder()
        sb.append(resp.prefix).append(echoText)
        for (l in resp.lines) sb.append(l).append(e)
        sb.append(e).append('>')
        var text = sb.toString()
        if (lowercase) text = text.lowercase(Locale.ROOT)
        enqueue(text.toByteArray(Charsets.ISO_8859_1), slowMs + extraDelayMs)
    }

    private fun enqueue(bytes: ByteArray, delayMs: Long) {
        // A serial stream can't overtake itself: never ready before what is already queued.
        val ready = maxOf(System.nanoTime() + delayMs * 1_000_000L, queue.lastOrNull()?.readyAtNs ?: 0L)
        val size = if (bleChunks) 20 else maxOf(1, bytes.size)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + size)
            queue.addLast(Out(ready, bytes.copyOfRange(i, end)))
            i = end
        }
    }

    // ------------------------------------------------------------------ ECUs

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun neg(sid: Int, nrc: Int) = listOf(b(0x7F, sid, nrc))
    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    private val enginePids: Map<Int, () -> ByteArray> = mapOf(
        0x01 to { b(if (storedDtcs.isEmpty()) 0x00 else 0x80 or storedDtcs.size, 0x07, 0xE5, 0x00) },
        0x04 to { b(if (driving) 0x4D else 0x00) },
        0x05 to { b(0x7B) },
        0x0C to { if (driving) b(0x1A, 0xF8) else b(0x00, 0x00) },
        0x0D to { b(if (driving) 0x32 else 0x00) },
        0x0F to { b(0x46) },
        0x11 to { b(if (driving) 0x26 else 0x00) },
        0x1F to { b(0x01, 0x2C) },
        0x21 to { b(0x00, 0x0A) },
        0x2F to { b(0x80) },
        0x31 to { b(0x05, 0xDC) },
        0x33 to { b(0x63) },
        0x42 to { b(0x3A, 0x98) },
        0x46 to { b(0x37) },
    )

    /** OBDb Outlander MY2011 test vectors (tests/test_cases/2011/commands), payload incl. 61 xx. */
    private val engine21: Map<Int, ByteArray> = mapOf(
        0x08 to ObdHex.parse("61 08 22 00 E4 04 04"),
        0x0E to ObdHex.parse("61 0E 00 00 00 00 00"),
        0x0F to ObdHex.parse("61 0F 00 00 00 00 00"),
        0x12 to ObdHex.parse("61 12 02 17 0B 00 00"),
        0x1A to ObdHex.parse("61 1A 3F 34 00 00 00"),
        0x1D to ObdHex.parse("61 1D 82 01 40 00 00"),
        0x1E to ObdHex.parse("61 1E 26 00 00 00 00"),
    )

    private fun supportedSet(dataPids: Set<Int>): Set<Int> {
        val max = dataPids.maxOrNull() ?: 0
        val bitmaps = (0x20..0xE0 step 0x20).filter { it < max }
        return dataPids + bitmaps
    }

    private fun mode01(pid: Int, data: Map<Int, () -> ByteArray>): List<ByteArray>? {
        val all = supportedSet(data.keys)
        if (pid and 0x1F == 0) {
            if (pid != 0 && pid !in all) return null
            val bm = ByteArray(4)
            for (i in 1..32) if ((pid + i) in all) bm[(i - 1) / 8] = (bm[(i - 1) / 8].toInt() or (0x80 shr ((i - 1) % 8))).toByte()
            return listOf(b(0x41, pid) + bm)
        }
        val v = data[pid] ?: return null
        return listOf(b(0x41, pid) + v())
    }

    private fun dtcReply(sid: Int, list: List<Int>): List<ByteArray> {
        val codes = synchronized(list) { list.toList() }
        val out = ArrayList<Int>()
        out += sid
        out += codes.size
        for (c in codes) {
            out += (c shr 8) and 0xFF
            out += c and 0xFF
        }
        return listOf(b(*out.toIntArray()))
    }

    private fun engine(req: ByteArray): List<ByteArray>? {
        val sid = req.u(0)
        return when (sid) {
            0x01 -> if (req.size >= 2) mode01(req.u(1), enginePids) else null
            0x03 -> dtcReply(0x43, storedDtcs)
            0x07 -> dtcReply(0x47, pendingDtcs)
            0x0A -> dtcReply(0x4A, permanentDtcs)
            0x04 -> {
                storedDtcs.clear()
                pendingDtcs.clear()
                listOf(b(0x44))
            }
            0x09 -> when (if (req.size >= 2) req.u(1) else -1) {
                0x00 -> listOf(b(0x49, 0x00, 0x40, 0x40, 0x00, 0x00))
                0x02 -> listOf(b(0x49, 0x02, 0x01) + vin.toByteArray(Charsets.US_ASCII))
                0x0A -> listOf(b(0x49, 0x0A, 0x01) + "ECM\u0000-EngineControl\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
                else -> null
            }
            0x21 -> if (req.size >= 2) engine21[req.u(1)]?.let { listOf(it) } ?: neg(0x21, 0x31) else neg(0x21, 0x13)
            0x18 -> if (engineAnswersKwp) kwpDtcReply(storedDtcs) else neg(sid, 0x11)
            else -> neg(sid, 0x11)
        }
    }

    /** KWP2000 ReadDTCByStatus reply: `58 <n> (hi lo status)*`, status 60 for every code. */
    private fun kwpDtcReply(list: List<Int>): List<ByteArray> {
        val codes = synchronized(list) { list.toList() }
        val out = ArrayList<Int>()
        out += 0x58
        out += codes.size
        for (c in codes) {
            out += (c shr 8) and 0xFF
            out += c and 0xFF
            out += 0x60
        }
        return listOf(b(*out.toIntArray()))
    }

    private fun hybrid(req: ByteArray): List<ByteArray>? = when (req.u(0)) {
        0x01 -> if (req.size >= 2) {
            // PID 01: MIL on while a code is stored, no readiness monitors of its own.
            mode01(req.u(1), mapOf(0x01 to { b(if (hybridDtcs.isEmpty()) 0x00 else 0x80 or hybridDtcs.size, 0x00, 0x00, 0x00) }))
        } else null
        0x03 -> dtcReply(0x43, hybridDtcs)
        0x07 -> listOf(b(0x47, 0x00))
        0x0A -> dtcReply(0x4A, hybridPermanentDtcs)
        0x04 -> {
            hybridDtcs.clear()
            listOf(b(0x44))
        }
        0x18 -> kwpDtcReply(hybridDtcs)
        else -> neg(req.u(0), 0x11)
    }

    private fun secondEcu(req: ByteArray): List<ByteArray>? = when (req.u(0)) {
        0x01 -> if (req.size >= 2) mode01(req.u(1), mapOf(0x01 to { b(0x00, 0x00, 0x00, 0x00) }, 0x05 to { b(0x50) })) else null
        0x03 -> dtcReply(0x43, secondEcuDtcs)
        0x07 -> listOf(b(0x47, 0x00))
        0x0A -> listOf(b(0x4A, 0x00))
        0x04 -> {
            secondEcuDtcs.clear()
            listOf(b(0x44))
        }
        else -> null
    }

    private fun bmu(req: ByteArray): List<ByteArray> {
        val sid = req.u(0)
        if (sid == 0x21 && req.size == 2 && req.u(1) == 0x01) {
            val n = pendingResponses
            if (n > 0) {
                if (pendingSeparatePrompt) {
                    pendingResponses = n - 1
                    return neg(0x21, 0x78)
                }
                pendingResponses = 0
                return List(n) { b(0x7F, 0x21, 0x78) } + bmu2101()
            }
            return listOf(bmu2101())
        }
        if (sid == 0x21 && req.size == 2 && req.u(1) == 0x02) return listOf(pack?.reply2102() ?: BMU_2102)
        if (sid == 0x21 && req.size == 2 && req.u(1) == 0x03) return listOf(BMU_2103)
        return when (sid) {
            0x18 -> kwpDtcReply(bmuDtcs)
            0x21 -> neg(0x21, 0x31)
            else -> neg(sid, 0x11)
        }
    }

    private fun bmu2101(): ByteArray = pack?.reply2101() ?: BMU_2101

    private fun obc(req: ByteArray): List<ByteArray> = when {
        req.u(0) == 0x21 && req.size == 2 && req.u(1) == 0x01 -> listOf(b(0x61, 0x01, 0x00, 0x2A, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x00))
        // 21 02: AC 230.0 V (0x08FC), 14.0 A (0x8C), DC 360.0 V (0x0E10), 9.0 A (0x5A), CP duty 53 % at byte 13.
        req.u(0) == 0x21 && req.size == 2 && req.u(1) == 0x02 ->
            if (charging?.invoke() == false) {
                // Not charging: no AC input, no DC into the pack, no pilot duty.
                listOf(b(0x61, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            } else {
                listOf(b(0x61, 0x02, 0x00, 0x00, 0x08, 0xFC, 0x8C, 0x0E, 0x10, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x35))
            }
        // 21 03: DC/DC 12 V output current 8.2 A (0x52) at byte 7.
        req.u(0) == 0x21 && req.size == 2 && req.u(1) == 0x03 ->
            listOf(b(0x61, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x52))
        req.u(0) == 0x21 -> neg(0x21, 0x31)
        // Like the real car: KWP ReadDTCByStatus isn't supported here…
        req.u(0) == 0x18 -> neg(0x18, 0x12)
        // …DiagnosticSessionControl to the extended session opens the door…
        req.u(0) == 0x10 && req.size >= 2 && req.u(1) == obcSessionId -> { obcSession = true; listOf(b(0x50, obcSessionId, 0x00, 0x32, 0x01, 0xF4)) }
        req.u(0) == 0x10 && req.size >= 2 && (req.u(1) == 0x01 || req.u(1) == 0x81) -> { obcSession = false; listOf(b(0x50, req.u(1))) }
        req.u(0) == 0x10 -> neg(0x10, 0x12)
        // …and UDS ReadDTCInformation only answers once a session is open (otherwise NRC 0x80).
        req.u(0) == 0x19 && req.size >= 2 && req.u(1) == 0x02 ->
            if (obcSession) listOf(udsDtcReply(obcDtcs)) else neg(0x19, 0x80)
        else -> neg(req.u(0), 0x11)
    }

    /** UDS reportDTCByStatusMask reply: `59 02 FF (hi lo 24 08)*`. */
    private fun udsDtcReply(list: List<Int>): ByteArray {
        val codes = synchronized(list) { list.toList() }
        val out = arrayListOf(0x59, 0x02, 0xFF)
        for (c in codes) {
            out += (c shr 8) and 0xFF
            out += c and 0xFF
            out += 0x24
            out += 0x08
        }
        return b(*out.toIntArray())
    }

    private fun motor(req: ByteArray, front: Boolean): List<ByteArray> = when {
        req.u(0) == 0x21 && req.size == 2 && req.u(1) == 0x02 ->
            listOf(if (front && driving) b(0x61, 0x02, 0x00, 0x00, 0x52, 0x08, 0x00, 0x00) else b(0x61, 0x02, 0x00, 0x00, 0x4E, 0x20, 0x00, 0x00))
        req.u(0) == 0x21 -> neg(0x21, 0x31)
        req.u(0) == 0x19 && req.size >= 2 && req.u(1) == 0x02 && motorsAnswerUds -> listOf(b(0x59, 0x02, 0xFF))
        else -> neg(req.u(0), 0x11)
    }

    companion object {
        /** Demo pack: full-charge capacity estimate (≈ 72 % of the 38 Ah reference of a 2014–2016 car). */
        const val DEMO_CAPACITY_AH = 27.4

        /** Demo pack: the one weak cell, and how far it sits below the others. */
        const val DEMO_WEAK_CELL = 23
        const val DEMO_WEAK_CELL_DROP_MV = 65

        /** Demo state of charge when the caller has none yet (the demo car starts at 64 %). */
        const val DEMO_DEFAULT_SOC = 64.0

        /**
         * The app's demo scenario: an MY2015 car parked with the ignition on, plugged in and charging, whose drive
         * battery is worn —
         * - quick scan: P0A80 "replace hybrid battery pack" stored (and permanent, so clearing can't hide it) by the
         *   hybrid/EV control unit; P0456 (EVAP very small leak) pending in the engine ECU;
         * - full scan adds the BMU's own P0A7F "hybrid battery pack deterioration"; the OBC answers but is clean;
         * - battery: [DEMO_CAPACITY_AH] (≈ 72 % health) and cell [DEMO_WEAK_CELL] [DEMO_WEAK_CELL_DROP_MV] mV low.
         *
         * @param socPercent the car's state of charge (read on every BMU request); null → [DEMO_DEFAULT_SOC]
         * @param charging whether the car is charging right now; null → assume it is (the demo starts charging)
         * @param latencyMs answer delay, so scans take a moment like with a real adapter
         */
        fun demo(
            socPercent: () -> Double? = { null },
            charging: () -> Boolean? = { null },
            latencyMs: Long = 25L,
        ): FakeElm = FakeElm(version = "ELM327 v1.5").apply {
            slowMs = latencyMs
            driving = false
            storedDtcs.clear()
            pendingDtcs.clear()
            pendingDtcs += 0x0456
            permanentDtcs.clear()
            engineAnswersKwp = true
            motorsAnswerUds = true
            hybridEcu = true
            hybridDtcs.clear(); hybridDtcs += 0x0A80
            hybridPermanentDtcs.clear(); hybridPermanentDtcs += 0x0A80
            bmuDtcs.clear(); bmuDtcs += 0x0A7F
            obcDtcs.clear()
            pack = Pack(
                capacityAh = DEMO_CAPACITY_AH,
                soc = { socPercent() ?: DEMO_DEFAULT_SOC },
                weakCell = DEMO_WEAK_CELL,
                weakCellDropMv = DEMO_WEAK_CELL_DROP_MV,
            )
            this.charging = charging
        }

        /**
         * BMU reply to 21 01 from the myoutlanderphev.com forum thread (frames 762 10 37 ... 762 27 ...),
         * reassembled: 55 bytes starting with 61 01.
         */
        val BMU_2101: ByteArray = ObdHex.parse(
            "61 01 82 83 0F 8B " +
                "24 0F 88 03 0C 6E 52 " +
                "03 4D 1C 01 99 00 00 " +
                "00 00 00 01 00 01 30 " +
                "0F 0F 01 54 00 CC 9C " +
                "FE 00 03 0F 88 86 8B " +
                "64 00 00 00 00 00 00 " +
                "00 00 0F 8A 00 02 00",
        )

        /**
         * `21 02` reply: 80 cell voltages as big-endian millivolts, consistent with [BMU_2101]'s summary
         * (highest 3979 mV at cell 36, lowest 3976 mV at cell 3, the rest 3978 mV).
         */
        val BMU_2102: ByteArray = run {
            val out = ArrayList<Int>()
            out += 0x61; out += 0x02
            for (i in 1..80) {
                val mv = when (i) { 3 -> 3976; 36 -> 3979; else -> 3978 }
                out += (mv shr 8) and 0xff; out += mv and 0xff
            }
            ByteArray(out.size) { out[it].toByte() }
        }

        /** `21 03` reply: 12 module temperatures, all 25 °C (raw = °C + 50). */
        val BMU_2103: ByteArray = (listOf(0x61, 0x03) + List(12) { 75 }).let { l -> ByteArray(l.size) { l[it].toByte() } }
    }
}
