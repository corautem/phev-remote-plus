package io.github.anwo91.phevlink.core.obd

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.diag.LogKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * Timeouts and retry limits for [Elm327]. The defaults suit real Bluetooth/Wi-Fi adapters;
 * [fast] is for the in-process emulator in tests.
 */
data class ElmConfig(
    /** Wait for the `>` prompt after an AT command. */
    val commandTimeoutMs: Long = 2_000,
    /** Wait for the banner/prompt after ATZ / ATWS (real adapters need about a second). */
    val resetTimeoutMs: Long = 2_500,
    /** After a reset, output that keeps arriving within this quiet period is swallowed. */
    val resetSettleMs: Long = 300,
    /** Default overall timeout of one OBD request (the prompt must arrive within it). */
    val requestTimeoutMs: Long = 5_000,
    /** Timeout of the first request after a protocol change (the adapter may print SEARCHING... for seconds). */
    val searchTimeoutMs: Long = 15_000,
    /** Timeout of terminal commands ([Elm327.raw]). */
    val rawTimeoutMs: Long = 6_000,
    /** Quiet period that ends a drain of stale input. */
    val drainQuietMs: Long = 100,
    /** Upper bound of one drain. */
    val drainMaxMs: Long = 1_500,
    /** Upper bound of one `ObdLink.send`. */
    val sendTimeoutMs: Long = 3_000,
    /** How often a request is re-sent while the ECU only answers "response pending" (7F xx 78) or "busy" (21). */
    val pendingRetries: Int = 5,
    /** Pause before such a re-send. */
    val pendingDelayMs: Long = 150,
) {
    companion object {
        /** Short timeouts for the in-process [io.github.anwo91.phevlink.core.obd.emulator.FakeElm]. */
        fun fast() = ElmConfig(
            commandTimeoutMs = 300, resetTimeoutMs = 300, resetSettleMs = 10, requestTimeoutMs = 400,
            searchTimeoutMs = 500, rawTimeoutMs = 300, drainQuietMs = 10, drainMaxMs = 150, sendTimeoutMs = 300,
            pendingRetries = 3, pendingDelayMs = 2,
        )
    }
}

/** What [Elm327.initialize] found out about the adapter and the car. */
data class ElmInfo(
    /** ATI answer, e.g. "ELM327 v1.5". */
    val version: String,
    /** AT@1 device description; null when not supported. */
    val description: String?,
    /** Human-readable protocol, e.g. "ISO 15765-4 CAN (11 bit, 500 kbaud)". */
    val protocol: String,
    /** Raw ATDPN answer ("6"; "A6" = found by automatic search). */
    val protocolNumber: String?,
    /** 12 V battery voltage measured by the adapter at the OBD port (ATRV). */
    val voltage: Double?,
    /** Heuristic: a clone with a reduced command set. Reasons in [cloneNotes]. */
    val isClone: Boolean,
    val cloneNotes: List<String>,
    /** STN chip id (OBDLink and other STN-based adapters) when the adapter answered `STI`. */
    val stnChip: String?,
    /** Custom CAN flow control (ATFCSH/ATFCSD/ATFCSM1) works: needed for drive-battery data. */
    val supportsFlowControlHeader: Boolean,
    /** ATCRA (CAN receive filter) works. */
    val supportsReceiveFilter: Boolean,
    /** ATAT1 (adaptive timing) was accepted. */
    val supportsAdaptiveTiming: Boolean,
    /** The car answered `0100`. */
    val carReachable: Boolean,
    /** User-facing advice (ignition off, adapter limits); null when everything is fine. */
    val message: String?,
    /** Non-null when drive-battery cell data cannot be read with this adapter; explains why. */
    val batteryDataMessage: String?,
)

/**
 * Everything the adapter printed for one OBD request, already reassembled and filtered to replies that
 * belong to the request (right service id and echo, right sender for physical requests).
 * "Response pending" replies are removed.
 */
class ElmResponse(
    /** The request as sent, e.g. "2101". */
    val command: String,
    val target: EcuTarget,
    /** Complete replies, positive and negative (`7F`). */
    val replies: List<EcuReply>,
    /** Multi-frame replies that stopped early (see [ObdException.Incomplete]). */
    val incomplete: List<ElmResponseParser.Partial>,
    /** Adapter status lines (`NO DATA`, `CAN ERROR`, ...). */
    val status: List<String>,
    /** Cleaned lines as printed by the adapter (for diagnostics). */
    val lines: List<String>,
    /** The ECU never got past "response pending". */
    val stillPending: Boolean,
) {
    val positive: List<EcuReply> get() = replies.filter { !it.isNegative }
    val negative: List<EcuReply> get() = replies.filter { it.isNegative }

    /** Positive replies, or the most specific [ObdException] explaining why there are none. */
    fun positiveOrThrow(): List<EcuReply> {
        val pos = positive
        if (pos.isNotEmpty()) return pos
        negative.firstOrNull()?.let { throw ObdException.NegativeResponse(it.rxId, it.negativeServiceId ?: 0, it.nrc ?: 0) }
        if (stillPending) throw ObdException.Timeout(command, 0, "ECU kept answering 'response pending'")
        incomplete.firstOrNull()?.let { throw ObdException.Incomplete(it.rxId, it.received, it.expected, command) }
        status.firstOrNull { s -> SILENT_BUS.any { s.startsWith(it) } }?.let { throw ObdException.UnableToConnect(command, it) }
        val errors = status.filter { s -> s != "NO DATA" && s != "OK" }
        if (status.none { it == "NO DATA" } && errors.isNotEmpty()) throw ObdException.AdapterError(errors.joinToString(", "), command)
        throw ObdException.NoData(command)
    }

    override fun toString(): String = "ElmResponse($command → ${replies.joinToString()}${if (status.isEmpty()) "" else " $status"})"

    internal companion object {
        val SILENT_BUS = listOf("UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS INIT ERROR")
    }
}

/**
 * Driver for ELM327-compatible OBD adapters (genuine ELM, STN/OBDLink and the many clones) over an [ObdLink].
 *
 * - Command/response: `CMD\r` is sent, text is read until the `>` prompt. Every wait has a timeout; a timed-out
 *   command triggers a resynchronisation before the next one (a single space interrupts a busy adapter, then
 *   stale output is drained), so a late answer can't be mistaken for the next command's answer.
 * - Requests are physically or functionally addressed ([EcuTarget]); `ATSH`/`ATCRA`/`ATFCSH`/`ATFCSD`/`ATFCSM`
 *   are only sent when they differ from what the adapter is known to have.
 * - Replies are parsed with CAN ids (ATH1) and reassembled per sender ([ElmResponseParser]).
 * - Thread-safe: all public functions are serialised with a mutex.
 *
 * Errors are [ObdException]s; [ObdException.LinkLost] means the link is dead.
 */
class Elm327(
    private val link: ObdLink,
    private val log: DiagLog,
    val config: ElmConfig = ElmConfig(),
) {
    private val lock = Mutex()

    /** Result of the last [initialize]; null before. */
    @Volatile
    var info: ElmInfo? = null
        private set

    /** Log every command and answer to the [DiagLog] (TX/RX). Turn off to keep the log small while polling. */
    @Volatile
    var logTraffic: Boolean = true

    /** Custom CAN flow control works. Can turn false at runtime when an adapter accepts ATFCSH but rejects ATFCSM1. */
    @Volatile
    var supportsFlowControlHeader: Boolean = true
        private set

    /** ATCRA receive filter works. */
    @Volatile
    var supportsReceiveFilter: Boolean = true
        private set

    // Adapter settings as far as we know them (null = unknown). Guarded by [lock].
    private var curHeader: Int? = null
    private var craKnown = false
    private var curCra: Int? = null
    private var curFcHeader: Int? = null
    private var curFcData: String? = null
    private var curFcMode: Int? = null
    private var headersOn = true
    private var formatDirty = true
    private var resyncNeeded = false
    private var lastTimeoutCmd: String? = null
    private var searchPending = false

    private class CmdResult(val lines: List<String>, val display: List<String>, val prompt: Boolean, val raw: String)
    private class Read(val text: String, val prompt: Boolean)

    // ------------------------------------------------------------------ public API

    /**
     * Resets and configures the adapter, probes its capabilities and checks that the car answers.
     *
     * Sequence: drain stale input → ATZ (fallback ATWS, then ATD; gives up early when the adapter sends nothing at
     * all) → ATE0 ATL0 ATS1 ATH1 ATCAF1 → ATAT1 (optional) → ATSP6 → ATI (also a sync check: answers still in flight
     * from a slow reset are skipped and the settings re-applied) → AT@1, STI, ATRV → flow-control probe
     * (ATFCSH761/ATFCSD300000/ATFCSM1, then ATFCSM0) → receive-filter probe (ATCRA) → `0100` (on failure: ATSP0
     * auto search, then back to ATSP6) → ATDPN.
     *
     * Never throws for "car not answering": see [ElmInfo.carReachable] and [ElmInfo.message].
     * @throws ObdException.Timeout the adapter doesn't answer at all
     * @throws ObdException.LinkLost the link failed
     */
    suspend fun initialize(): ElmInfo = lock.withLock {
        val i = doInitialize()
        info = i
        i
    }

    /**
     * Sends [payload] (e.g. `21 01`, `01 0C`, `03`) to [target] and returns the positive replies (several for a
     * broadcast). Response-pending (7F xx 78) is waited out by re-asking.
     *
     * @throws ObdException.NegativeResponse every reply was negative
     * @throws ObdException.NoData nobody answered
     * @throws ObdException.Incomplete only part of a multi-frame reply arrived (flow control problem)
     * @throws ObdException.UnableToConnect the bus is silent (ignition off)
     * @throws ObdException.AdapterError the adapter printed an error
     * @throws ObdException.Timeout no prompt in time
     * @throws ObdException.LinkLost link failed
     */
    suspend fun request(target: EcuTarget, payload: ByteArray, timeoutMs: Long = config.requestTimeoutMs): List<EcuReply> =
        lock.withLock { doExchange(target, payload, timeoutMs).positiveOrThrow() }

    /**
     * Like [request] but returns everything (positive and negative replies, status lines) instead of throwing
     * for protocol-level outcomes. Still throws [ObdException.Timeout] / [ObdException.LinkLost].
     * Used for broadcasts where some ECUs may refuse (mode 04) and for quiet probing.
     */
    suspend fun exchange(target: EcuTarget, payload: ByteArray, timeoutMs: Long = config.requestTimeoutMs): ElmResponse =
        lock.withLock { doExchange(target, payload, timeoutMs) }

    /**
     * Terminal: sends exactly what the user typed (plus `\r`) and returns the cleaned answer, one line per row,
     * in the adapter's own case. Nothing is refused. Notes for the UI:
     * - an empty line makes an ELM327 **repeat the previous command**;
     * - AT/ST commands may change adapter settings; the driver re-applies its format settings
     *   (ATE0 ATL0 ATS1 ATH1 ATCAF1) and header/filter/flow-control settings before its next request,
     *   but not the protocol (ATSP);
     * - monitor commands (ATMA, STM...) run until interrupted: they end at the timeout and the adapter is then
     *   interrupted and resynchronised.
     */
    suspend fun raw(command: String, timeoutMs: Long = config.rawTimeoutMs): String = lock.withLock {
        val cmd = command.trimEnd('\r', '\n')
        val norm = ElmText.normalize(cmd)
        try {
            val r = command(cmd, timeoutMs, throwOnTimeout = false)
            val text = r.display.joinToString("\n")
            if (r.prompt) text else (if (text.isEmpty()) "" else "$text\n") + "(no prompt within $timeoutMs ms — command interrupted)"
        } finally {
            if (norm.startsWith("AT") || norm.startsWith("ST")) {
                invalidateCache()
                formatDirty = true
                if (norm.startsWith("ATSP") || norm.startsWith("ATTP") || norm == "ATZ" || norm == "ATWS" || norm == "ATD") searchPending = true
            }
        }
    }

    /** 12 V battery voltage measured by the adapter (ATRV), or null. */
    suspend fun voltage(): Double? = lock.withLock {
        try {
            parseVoltage(command("ATRV", config.commandTimeoutMs).lines.joinToString(" "))
        } catch (e: ObdException.Timeout) {
            null
        }
    }

    /** Closes the link. */
    fun close() {
        runCatching { link.close() }
    }

    // ------------------------------------------------------------------ initialisation

    private suspend fun doInitialize(): ElmInfo {
        log.link("ELM: initialising adapter on ${link.description}")
        invalidateCache()
        resyncNeeded = false
        supportsFlowControlHeader = true
        supportsReceiveFilter = true
        val junk = drain(config.drainQuietMs, config.drainMaxMs)
        if (junk.isNotBlank()) log.warn("ELM: discarded ${junk.length} stale bytes before reset: '${printable(junk)}'")

        var res = reset("ATZ")
        if (res.lines == null) res = reset("ATWS")
        if (res.lines == null && res.gotBytes) res = reset("ATD")
        if (res.lines == null && !res.gotBytes) {
            log.error("ELM: the adapter sends nothing at all (ATZ, ATWS)")
            throw ObdException.Timeout("ATZ", config.resetTimeoutMs, "")
        }
        res.lines?.firstOrNull { looksLikeBanner(it) }?.let { log.link("ELM: adapter says '$it'") }

        applyFormat()
        val adaptive = atOkSafe("ATAT1")
        if (!adaptive) log.link("ELM: ATAT1 not supported — using the adapter's fixed timing")
        if (!atOkSafe("ATSP6")) log.warn("ELM: ATSP6 rejected — adapter may not support ISO 15765-4 CAN 11/500")
        searchPending = true

        // ATI doubles as a sync check: if a slow reset left answers in flight, they arrive before the version.
        val ati = commandUntil("ATI", config.commandTimeoutMs, accept = ::isVersionAnswer) ?: run {
            log.error("ELM: no version answer to ATI")
            throw ObdException.Timeout("ATI", config.commandTimeoutMs, "")
        }
        if (ati.skipped > 0) {
            log.warn("ELM: answers were out of step after the reset — re-applying settings")
            applyFormat()
            atOkSafe("ATAT1")
            atOkSafe("ATSP6")
        }
        val version = ati.result.display.filter { it != "?" && !it.equals("OK", true) }.joinToString(" ")
            .ifBlank { res.lines?.firstOrNull { looksLikeBanner(it) } ?: "unknown" }
        val description = optionalText("AT@1")
        val stn = optionalText("STI")?.takeIf { it.uppercase(Locale.ROOT).contains("STN") }
        val voltage = parseVoltage(optionalText("ATRV"))

        val fcOk = probeFlowControl()
        val craOk = probeReceiveFilter()
        val reachable = verifyCar()
        val dpn = optionalText("ATDPN")?.uppercase(Locale.ROOT)

        val notes = ArrayList<String>()
        val upper = version.uppercase(Locale.ROOT)
        val vnum = Regex("V(\\d+)\\.(\\d)").find(upper)?.let { it.groupValues[1].toInt() * 10 + it.groupValues[2].toInt() }
        if (stn == null) {
            if (upper.contains("V1.5")) notes += "Reports \"v1.5\": ELM Electronics never made a v1.5 chip, so this is a clone."
            if (!fcOk && vnum != null && vnum >= 14) notes += "Claims $version but rejects ATFCSH (custom CAN flow control), which genuine ELM327 chips of that version support."
            if (!craOk && vnum != null && vnum >= 14) notes += "Claims $version but rejects ATCRA (CAN receive filter)."
        }
        val batteryMsg = if (fcOk) null else MSG_NO_FLOW_CONTROL
        val message = when {
            !reachable -> MSG_IGNITION
            !fcOk -> MSG_NO_FLOW_CONTROL
            else -> null
        }
        val result = ElmInfo(
            version = version,
            description = description,
            protocol = describeProtocol(dpn),
            protocolNumber = dpn,
            voltage = voltage,
            isClone = notes.isNotEmpty(),
            cloneNotes = notes,
            stnChip = stn,
            supportsFlowControlHeader = fcOk,
            supportsReceiveFilter = craOk,
            supportsAdaptiveTiming = adaptive,
            carReachable = reachable,
            message = message,
            batteryDataMessage = batteryMsg,
        )
        log.link(
            "ELM: ready — $version, ${result.protocol}, ${voltage?.let { "$it V" } ?: "voltage n/a"}, " +
                "flow control ${if (fcOk) "yes" else "NO"}, filter ${if (craOk) "yes" else "no"}, " +
                "car ${if (reachable) "answers" else "NOT answering"}" + if (notes.isEmpty()) "" else ", clone: ${notes.joinToString(" ")}",
        )
        return result
    }

    /** [lines] null = no usable answer; [gotBytes] = the adapter sent anything at all. */
    private class ResetResult(val lines: List<String>?, val gotBytes: Boolean)

    /**
     * ATZ/ATWS/ATD. A reset is its own sync point: prompt-terminated blocks are read until the banner (or OK for
     * ATD) shows up, so stray prompts from garbage or late answers are skipped.
     */
    private suspend fun reset(cmd: String): ResetResult {
        invalidateCache()
        formatDirty = true
        if (resyncNeeded) {
            resyncNeeded = false
            sendText(" ") // interrupt whatever might still run; the banner check below does the rest
            drain(config.drainQuietMs * 2, config.drainMaxMs)
        }
        traceTx(cmd)
        sendText("$cmd\r")
        val deadline = monotonicMs() + config.resetTimeoutMs
        val all = ArrayList<String>()
        var prompt = false
        var gotBytes = false
        var done = false
        try {
            while (true) {
                val remaining = deadline - monotonicMs()
                if (remaining <= 0) break
                val r = readUntilPrompt(remaining)
                if (r.text.isNotEmpty() || r.prompt) gotBytes = true
                all += ElmText.lines(r.text, cmd)
                if (!r.prompt) break
                prompt = true
                if (all.any { looksLikeBanner(it) } || (cmd == "ATD" && all.any { isOk(it) })) break
                // A prompt without a banner: a stray '>' from garbage printed before the reset. Keep reading.
            }
            traceRx(all, prompt)
            if (all.none { looksLikeBanner(it) } && !(cmd == "ATD" && all.any { isOk(it) })) {
                log.warn("ELM: no usable answer to $cmd")
                return ResetResult(null, gotBytes)
            }
            val tail = drain(config.resetSettleMs, config.drainMaxMs)
            if (tail.isNotBlank()) log.add(LogKind.RX, "ELM (after $cmd) '${printable(tail)}'")
            done = true
            return ResetResult(all, true)
        } finally {
            if (!done) {
                resyncNeeded = true
                lastTimeoutCmd = cmd
            }
        }
    }

    private suspend fun applyFormat() {
        if (!atOkSafe("ATE0")) log.warn("ELM: ATE0 not confirmed (echo is tolerated)")
        if (!atOkSafe("ATL0")) log.warn("ELM: ATL0 not confirmed (line feeds are tolerated)")
        if (!atOkSafe("ATS1")) log.warn("ELM: ATS1 not confirmed (compact hex is tolerated)")
        headersOn = atOkSafe("ATH1")
        if (!headersOn) log.warn("ELM: ATH1 rejected — replies come without CAN ids; answers of several ECUs can't be told apart")
        if (!atOkSafe("ATCAF1")) log.warn("ELM: ATCAF1 not confirmed")
        formatDirty = false
    }

    private suspend fun probeFlowControl(): Boolean {
        val ok = atOkSafe("ATFCSH761") && atOkSafe("ATFCSD300000") && atOkSafe("ATFCSM1")
        if (ok) {
            curFcHeader = 0x761
            curFcData = "300000"
        }
        // Back to automatic flow control for standard requests; on a clone without FC support it is 0 anyway.
        curFcMode = if (atOkSafe("ATFCSM0") || !ok) 0 else null
        supportsFlowControlHeader = ok
        if (ok) {
            log.link("ELM: custom CAN flow control supported (ATFCSH/ATFCSD/ATFCSM1)")
        } else {
            log.warn("ELM: adapter rejects ATFCSH/ATFCSD/ATFCSM — multi-frame data from the battery ECU (761/762) is unavailable")
        }
        return ok
    }

    private suspend fun probeReceiveFilter(): Boolean {
        if (!atOkSafe("ATCRA")) {
            craKnown = false
            supportsReceiveFilter = false
            log.link("ELM: ATCRA (receive filter) not supported — filtering replies in software")
            return false
        }
        craKnown = true
        curCra = null
        val setOk = atOkSafe("ATCRA7E8")
        if (setOk && !atOkSafe("ATCRA")) {
            craKnown = false
            log.warn("ELM: could not reset ATCRA after the probe")
        }
        supportsReceiveFilter = setOk
        return setOk
    }

    /** `0100` on ATSP6; on failure try ATSP0 (auto search), then go back to ATSP6. */
    private suspend fun verifyCar(): Boolean {
        val first = probeCar()
        if (first == null) return true
        log.warn("ELM: car not answering 0100 on ISO 15765-4 CAN 11/500 ($first); trying automatic protocol search (ATSP0)")
        if (atOkSafe("ATSP0")) {
            searchPending = true
            val second = probeCar()
            if (second == null) {
                log.link("ELM: car answered after automatic protocol search")
                return true
            }
            log.warn("ELM: still no answer ($second) — the ignition is probably off; back to ATSP6")
            atOkSafe("ATSP6")
            searchPending = true
        }
        return false
    }

    /** null = car answered; otherwise the reason it didn't. */
    private suspend fun probeCar(): String? = try {
        val resp = doExchange(EcuTarget.OBD_BROADCAST, bytesOf(0x01, 0x00), config.requestTimeoutMs)
        if (resp.positive.isNotEmpty()) null else resp.status.joinToString(", ").ifBlank { "no answer" }
    } catch (e: ObdException.Timeout) {
        "timeout"
    } catch (e: ObdException.AdapterError) {
        e.text
    }

    // ------------------------------------------------------------------ requests

    private suspend fun doExchange(target: EcuTarget, payload: ByteArray, timeoutMs: Long): ElmResponse {
        require(payload.isNotEmpty()) { "empty request" }
        if (formatDirty) applyFormat()
        prepare(target)
        val cmd = ObdHex.format(payload, "")
        val sid = payload.u8(0)
        var pendingRounds = 0
        var retriedStop = false
        var retriedReboot = false
        while (true) {
            val t = if (searchPending) maxOf(timeoutMs, config.searchTimeoutMs) else timeoutMs
            val r = command(cmd, t)
            searchPending = false
            var lines = r.lines
            var parsed = ElmResponseParser.parse(lines, headersOn, target.rxId ?: EcuReply.UNKNOWN_RX)
            var relevant = parsed.messages.filter { matches(it, payload, sid, target) }
            // Replies from the asked module(s) that answer something else = a late answer to an earlier command.
            // Frames other modules send on their own (no receive filter on many clones) don't count.
            val staleOnly = relevant.isEmpty() &&
                ((parsed.messages.any { fromTarget(it.rxId, target) && it.sid >= 0x40 } && ownStatus(parsed, target).isEmpty()) ||
                    (lines.isNotEmpty() && lines.all { isOk(it) }))
            if (staleOnly) {
                // Only replies to something else (data for another request, or a bare "OK" of an AT command):
                // a late answer to an earlier, interrupted command. Ours follows.
                log.warn("ELM: discarding stale reply ${parsed.messages.joinToString()} — waiting for the answer to $cmd")
                val again = readUntilPrompt(t)
                if (!again.prompt) {
                    resyncNeeded = true
                    throw ObdException.Timeout(cmd, t, again.text)
                }
                lines = ElmText.lines(again.text, cmd)
                traceRx(lines, true)
                parsed = ElmResponseParser.parse(lines, headersOn, target.rxId ?: EcuReply.UNKNOWN_RX)
                relevant = parsed.messages.filter { matches(it, payload, sid, target) }
            }
            if (parsed.status.any { looksLikeBanner(it) || it.startsWith("LV RESET") }) {
                log.warn("ELM: the adapter restarted itself (low voltage?) — re-applying settings")
                invalidateCache()
                formatDirty = true
                if (!retriedReboot) {
                    retriedReboot = true
                    applyFormat()
                    prepare(target)
                    continue
                }
            }
            if (relevant.isEmpty() && parsed.status.any { it.startsWith("STOPPED") } && !retriedStop) {
                retriedStop = true
                continue
            }
            val pending = relevant.filter { it.isNegative && (it.nrc == Nrc.RESPONSE_PENDING || it.nrc == Nrc.BUSY_REPEAT_REQUEST) }
            val finals = relevant.filter { r2 -> pending.none { it === r2 } }
            if (finals.isEmpty() && pending.isNotEmpty() && pendingRounds < config.pendingRetries) {
                pendingRounds++
                log.add(LogKind.LINK, "ELM: ${pending.first().ecuName} answered '${Nrc.name(pending.first().nrc ?: 0)}' to $cmd — asking again ($pendingRounds)")
                delay(config.pendingDelayMs)
                continue
            }
            val status = ownStatus(parsed, target)
            val positiveSid = (sid + 0x40) and 0xFF
            val incomplete = parsed.incomplete.filter { p ->
                fromTarget(p.rxId, target) && (p.sid < 0 || p.sid == positiveSid || p.sid == 0x7F)
            }
            noteChatter(parsed, relevant, incomplete, target)
            if (incomplete.isNotEmpty() && target.flowControl) {
                log.warn("ELM: only ${incomplete.first().received} of ${incomplete.first().expected} bytes from ${target.label} — the adapter did not send flow control")
            }
            return ElmResponse(cmd, target, finals, incomplete, status, lines, finals.isEmpty() && pending.isNotEmpty())
        }
    }

    /** Could a frame from [rxId] be an answer to a request sent to [target]? */
    private fun fromTarget(rxId: Int, target: EcuTarget): Boolean = when {
        rxId == EcuReply.UNKNOWN_RX -> true
        target.broadcast -> rxId in OBD_REPLY_IDS // ISO 15765-4: answers to 7DF come from 7E8..7EF
        target.rxId == null -> true
        else -> rxId == target.rxId
    }

    /** Status lines without "sequence errors" of other modules (a chattering module's counter produces them). */
    private fun ownStatus(parsed: ElmResponseParser.Parsed, target: EcuTarget): List<String> = parsed.status.filter { st ->
        !st.startsWith(SEQUENCE_ERROR) ||
            fromTarget(st.removePrefix(SEQUENCE_ERROR).trim().toIntOrNull(16) ?: EcuReply.UNKNOWN_RX, target)
    }

    /** CAN ids already reported as sending frames nobody asked for (logged once each). */
    private val chatterIds = HashSet<Int>()

    /**
     * Without a working receive filter the adapter prints every frame on the bus while it waits, e.g. the PHEV's
     * 7BC, whose counting first byte looks like the start of a long multi-frame message. Those frames are ignored;
     * this says once per module that it happened, so a log reader isn't misled.
     */
    private fun noteChatter(parsed: ElmResponseParser.Parsed, relevant: List<EcuReply>, own: List<ElmResponseParser.Partial>, target: EcuTarget) {
        val ids = LinkedHashSet<Int>()
        parsed.messages.forEach { m -> if (relevant.none { it === m } && !fromTarget(m.rxId, target)) ids += m.rxId }
        parsed.incomplete.forEach { p -> if (p.rxId != EcuReply.UNKNOWN_RX && own.none { it === p }) ids += p.rxId }
        for (id in ids) {
            if (target.rxId == id || !chatterIds.add(id)) continue
            log.add(
                LogKind.LINK,
                "ELM: ignoring frames from ${ObdHex.canId(id)} while asking ${target.label} — that module sends on its own and " +
                    if (supportsReceiveFilter) "the receive filter let them through" else "this adapter can't filter them out",
            )
        }
    }

    private fun matches(r: EcuReply, payload: ByteArray, sid: Int, target: EcuTarget): Boolean {
        if (!target.broadcast && target.rxId != null && r.rxId != EcuReply.UNKNOWN_RX && r.rxId != target.rxId) return false
        if (r.data.isEmpty()) return false
        if (r.isNegative) return r.data.u8(1) == sid
        if (r.sid != ((sid + 0x40) and 0xFF)) return false
        val echo = minOf(echoLength(sid), payload.size - 1)
        for (i in 1..echo) {
            if (r.data.size <= i || r.data[i] != payload[i]) return false
        }
        return true
    }

    /** How many request bytes after the SID the positive reply repeats. */
    private fun echoLength(sid: Int): Int = when (sid) {
        0x01, 0x09, 0x21, 0x1A, 0x19 -> 1
        0x02, 0x22 -> 2
        else -> 0
    }

    /** Sends ATSH/ATCRA/ATFC* only when the adapter's current setting differs. */
    private suspend fun prepare(target: EcuTarget) {
        if (curHeader != target.txId) {
            val cmd = "ATSH" + ObdHex.canId(target.txId)
            if (!atOk(cmd)) {
                curHeader = null
                throw ObdException.AdapterError("$cmd rejected", cmd)
            }
            curHeader = target.txId
        }
        if (supportsReceiveFilter) {
            val want: Int? = if (target.broadcast) null else target.rxId
            if (!craKnown || curCra != want) {
                val cmd = if (want == null) "ATCRA" else "ATCRA" + ObdHex.canId(want)
                if (atOk(cmd)) {
                    craKnown = true
                    curCra = want
                } else {
                    craKnown = false
                    supportsReceiveFilter = false
                    log.warn("ELM: $cmd rejected — filtering replies in software from now on")
                }
            }
        }
        if (target.flowControl && !target.broadcast) {
            if (supportsFlowControlHeader) {
                var ok = true
                if (curFcHeader != target.txId) {
                    ok = atOk("ATFCSH" + ObdHex.canId(target.txId))
                    curFcHeader = if (ok) target.txId else null
                }
                if (ok && curFcData != FC_DATA) {
                    ok = atOk("ATFCSD$FC_DATA")
                    curFcData = if (ok) FC_DATA else null
                }
                if (ok && curFcMode != 1) {
                    ok = atOk("ATFCSM1")
                    curFcMode = if (ok) 1 else null
                }
                if (!ok) {
                    supportsFlowControlHeader = false
                    curFcMode = null
                    log.warn("ELM: custom flow control rejected — multi-frame replies from ${target.label} will stop after the first frame")
                }
            }
        } else if (curFcMode != 0) {
            // Standard targets (7E0..7E7 / 7DF) use the adapter's automatic flow control.
            curFcMode = if (atOk("ATFCSM0") || !supportsFlowControlHeader) 0 else null
        }
    }

    // ------------------------------------------------------------------ text I/O

    private suspend fun command(cmd: String, timeoutMs: Long, throwOnTimeout: Boolean = true): CmdResult {
        if (resyncNeeded) resync()
        traceTx(cmd)
        var finished = false
        try {
            sendText("$cmd\r")
            val r = readUntilPrompt(timeoutMs)
            val res = CmdResult(ElmText.lines(r.text, cmd), ElmText.lines(r.text, cmd, upper = false), r.prompt, r.text)
            traceRx(res.lines, r.prompt)
            finished = r.prompt
            if (!r.prompt && throwOnTimeout) throw ObdException.Timeout(cmd, timeoutMs, r.text)
            return res
        } finally {
            if (!finished) {
                resyncNeeded = true
                lastTimeoutCmd = cmd
            }
        }
    }

    /**
     * AT command expecting OK. If the answer is clearly a late reply to an OBD request (CAN frames, NO DATA, ...),
     * one more prompt is read: the real answer follows.
     */
    private suspend fun atOk(cmd: String): Boolean {
        val r = command(cmd, config.commandTimeoutMs)
        if (r.lines.any { isOk(it) }) return true
        if (r.lines.any { it == "?" } || r.lines.isEmpty()) return false
        val parsed = ElmResponseParser.parse(r.lines, headersOn)
        val looksLikeObdReply = parsed.messages.isNotEmpty() || parsed.incomplete.isNotEmpty() ||
            parsed.status.any { s -> OBD_STATUS.any { s.startsWith(it) } }
        if (looksLikeObdReply) {
            log.warn("ELM: stale OBD answer to $cmd — reading the real answer")
            val again = readUntilPrompt(config.commandTimeoutMs)
            val lines = ElmText.lines(again.text, cmd)
            traceRx(lines, again.prompt)
            if (!again.prompt) {
                if (again.text.isNotEmpty()) resyncNeeded = true
                return false
            }
            return lines.any { isOk(it) }
        }
        return false
    }

    /** [atOk] that treats a timeout as "not supported" (optional probes). */
    private suspend fun atOkSafe(cmd: String): Boolean = try {
        atOk(cmd)
    } catch (e: ObdException.Timeout) {
        false
    }

    /** Text answer of an optional command, null when unsupported ("?") or silent. Keeps the adapter's case. */
    private suspend fun optionalText(cmd: String): String? = try {
        val r = command(cmd, config.commandTimeoutMs)
        val text = r.display.filter { it != "?" && !it.equals("OK", ignoreCase = true) }.joinToString(" ").trim()
        if (r.lines.any { it == "?" } || text.isEmpty()) null else text
    } catch (e: ObdException.Timeout) {
        null
    }

    /**
     * After a timeout the adapter may still owe us an answer (a late "OK" or data). Draining alone can't know how
     * long to wait, so a sync point is used: a single space interrupts a busy ELM327 (it prints STOPPED; an idle one
     * ignores spaces), then a command with a recognisable answer is sent — `ATI` (version), or `ATRV` (voltage) when
     * the command that timed out could itself have produced a version/banner. The adapter answers strictly in order,
     * so every block before that answer is stale and dropped.
     */
    private suspend fun resync() {
        resyncNeeded = false
        sendText(" ")
        val junk = drain(config.drainQuietMs * 2, config.drainMaxMs)
        log.warn("ELM: resynchronising after an interrupted command" + if (junk.isBlank()) "" else ", discarded '${printable(junk)}'")
        val last = lastTimeoutCmd?.let { ElmText.normalize(it) }.orEmpty()
        val versionLike = last.startsWith("ATI") || last.startsWith("ATZ") || last.startsWith("ATWS") || last.startsWith("AT@") || last.startsWith("ATD")
        val synced = if (versionLike) {
            commandUntil("ATRV", config.commandTimeoutMs, allowResync = false, accept = ::isVoltageAnswer)
        } else {
            commandUntil("ATI", config.commandTimeoutMs, allowResync = false, accept = ::isVersionAnswer)
        }
        if (synced == null) throw ObdException.Timeout("resync", config.commandTimeoutMs, "")
        lastTimeoutCmd = null
    }

    private fun isVersionAnswer(lines: List<String>): Boolean {
        val known = info?.version?.uppercase(Locale.ROOT)
        return lines.any { looksLikeBanner(it) || it == known || VERSION_RE.containsMatchIn(it) }
    }

    private fun isVoltageAnswer(lines: List<String>): Boolean =
        lines.any { VOLT_RE.matches(it) } || lines.singleOrNull() == "?"

    private class Synced(val result: CmdResult, val skipped: Int)

    /**
     * Sends [cmd] and reads prompt-terminated blocks until [accept] matches one (earlier blocks are late answers
     * to something else and are dropped). Null when nothing matching arrives within [timeoutMs].
     */
    private suspend fun commandUntil(cmd: String, timeoutMs: Long, allowResync: Boolean = true, accept: (List<String>) -> Boolean): Synced? {
        if (allowResync && resyncNeeded) resync()
        traceTx(cmd)
        var finished = false
        try {
            sendText("$cmd\r")
            val deadline = monotonicMs() + timeoutMs
            var skipped = 0
            while (true) {
                val left = deadline - monotonicMs()
                if (left <= 0) return null
                val r = readUntilPrompt(left)
                if (!r.prompt) return null
                val res = CmdResult(ElmText.lines(r.text, cmd), ElmText.lines(r.text, cmd, upper = false), true, r.text)
                traceRx(res.lines, true)
                if (accept(res.lines)) {
                    finished = true
                    return Synced(res, skipped)
                }
                skipped++
                log.warn("ELM: discarded stale answer '${res.lines.joinToString(" | ")}' while waiting for $cmd")
            }
        } finally {
            if (!finished) {
                resyncNeeded = true
                lastTimeoutCmd = cmd
            }
        }
    }

    private suspend fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val done = try {
            withTimeoutOrNull(config.sendTimeoutMs) {
                link.send(bytes)
                true
            }
        } catch (e: IOException) {
            throw ObdException.LinkLost(e)
        }
        if (done == null) {
            resyncNeeded = true
            throw ObdException.Timeout(text.trim().ifEmpty { "<send>" }, config.sendTimeoutMs, "")
        }
    }

    private suspend fun receiveChunk(timeoutMs: Long): ByteArray? {
        if (timeoutMs <= 0) return null
        val start = monotonicMs()
        val chunk = try {
            // Safety net in case a link implementation ignores its timeout.
            withTimeoutOrNull(timeoutMs + RECEIVE_GRACE_MS) { link.receive(timeoutMs) }
        } catch (e: IOException) {
            throw ObdException.LinkLost(e)
        }
        if (chunk == null || chunk.isEmpty()) {
            // A link that returns immediately instead of waiting must not make us spin.
            if (monotonicMs() - start < 2) delay(minOf(10L, timeoutMs))
            return null
        }
        return chunk
    }

    private suspend fun readUntilPrompt(timeoutMs: Long): Read {
        val sb = StringBuilder()
        val deadline = monotonicMs() + timeoutMs
        while (true) {
            val remaining = deadline - monotonicMs()
            if (remaining <= 0) return Read(sb.toString(), false)
            val chunk = receiveChunk(remaining) ?: continue
            val s = String(chunk, Charsets.ISO_8859_1)
            val idx = s.indexOf('>')
            if (idx >= 0) {
                sb.append(s, 0, idx)
                val rest = s.substring(idx + 1)
                if (rest.any { it.code > 0x20 }) log.warn("ELM: ignoring data after the prompt: '${printable(rest)}'")
                return Read(sb.toString(), true)
            }
            sb.append(s)
            if (sb.length > MAX_RESPONSE_CHARS) {
                log.warn("ELM: answer longer than $MAX_RESPONSE_CHARS characters — interrupting")
                resyncNeeded = true
                return Read(sb.toString(), false)
            }
        }
    }

    private suspend fun drain(quietMs: Long, maxMs: Long): String {
        val sb = StringBuilder()
        val end = monotonicMs() + maxMs
        while (true) {
            val left = end - monotonicMs()
            if (left <= 0) break
            val c = receiveChunk(minOf(quietMs, left)) ?: break
            sb.append(String(c, Charsets.ISO_8859_1))
        }
        return sb.toString()
    }

    private fun invalidateCache() {
        curHeader = null
        craKnown = false
        curCra = null
        curFcHeader = null
        curFcData = null
        curFcMode = null
    }

    private fun traceTx(cmd: String) {
        if (logTraffic) log.add(LogKind.TX, "ELM " + cmd.ifEmpty { "<CR>" })
    }

    private fun traceRx(lines: List<String>, prompt: Boolean) {
        if (logTraffic) {
            log.add(LogKind.RX, "ELM " + (if (lines.isEmpty()) "(empty)" else lines.joinToString(" | ")) + if (prompt) "" else " (no prompt)")
        }
    }

    companion object {
        /** Flow-control frame the adapter sends in mode 1: ContinueToSend, block size 0, STmin 0. */
        const val FC_DATA = "300000"

        const val MSG_IGNITION =
            "Adapter answers but the car doesn't — turn the ignition on (POWER switch to ON, or READY with the brake pressed) and try again."
        const val MSG_NO_FLOW_CONTROL =
            "Adapter rejected ATFCSH — battery data needs an adapter that supports custom CAN flow control " +
                "(e.g. OBDLink LX/MX+, Vgate iCar Pro or a genuine-ELM-based adapter); standard data still works."
        const val MSG_NO_ADAPTER =
            "No answer from the OBD adapter. Check that it is plugged into the OBD port and powered (LED on), " +
                "and that the right Bluetooth device is selected."

        private const val RECEIVE_GRACE_MS = 500L
        private const val SEQUENCE_ERROR = "SEQUENCE ERROR"
        private val OBD_REPLY_IDS = 0x7E8..0x7EF
        private const val MAX_RESPONSE_CHARS = 64 * 1024
        private val VERSION_RE = Regex("V\\d\\.\\d")
        private val VOLT_RE = Regex("^\\d{1,2}([.,]\\d+)?\\s*V?$")
        private val OBD_STATUS = listOf("NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUFFER FULL", "STOPPED", "DATA ERROR", "RX ERROR")

        internal fun isOk(line: String) = line == "OK" || line.endsWith("OK")

        internal fun looksLikeBanner(line: String): Boolean {
            val u = line.uppercase(Locale.ROOT)
            return u.contains("ELM") || u.startsWith("STN") || u.contains("OBDII") || u.contains("OBDLINK")
        }

        /** Parses "12.6V" / "12,6 V" → 12.6. */
        fun parseVoltage(text: String?): Double? {
            if (text == null) return null
            val m = Regex("(\\d{1,2}(?:[.,]\\d+)?)\\s*V?", RegexOption.IGNORE_CASE).find(text) ?: return null
            return m.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..40.0 }
        }

        /** ATDPN answer → protocol name. */
        fun describeProtocol(dpn: String?): String {
            val d = dpn?.trim()?.uppercase(Locale.ROOT).orEmpty()
            if (d.isEmpty()) return "unknown"
            val auto = d.length == 2 && d[0] == 'A'
            val code = if (auto) d.substring(1) else d
            val name = when (code) {
                "0" -> "Automatic"
                "1" -> "SAE J1850 PWM (41.6 kbaud)"
                "2" -> "SAE J1850 VPW (10.4 kbaud)"
                "3" -> "ISO 9141-2"
                "4" -> "ISO 14230-4 KWP (5 baud init)"
                "5" -> "ISO 14230-4 KWP (fast init)"
                "6" -> "ISO 15765-4 CAN (11 bit, 500 kbaud)"
                "7" -> "ISO 15765-4 CAN (29 bit, 500 kbaud)"
                "8" -> "ISO 15765-4 CAN (11 bit, 250 kbaud)"
                "9" -> "ISO 15765-4 CAN (29 bit, 250 kbaud)"
                "A" -> "SAE J1939 CAN"
                "B" -> "USER1 CAN"
                "C" -> "USER2 CAN"
                else -> "protocol $code"
            }
            return if (auto) "$name (auto-detected)" else name
        }

        private fun printable(s: String): String =
            s.map { if (it.code in 0x20..0x7E) it else '.' }.joinToString("").take(120)
    }
}
