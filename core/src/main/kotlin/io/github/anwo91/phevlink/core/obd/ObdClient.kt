package io.github.anwo91.phevlink.core.obd

import io.github.anwo91.phevlink.core.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level OBD client for the Outlander PHEV: connection lifecycle, discovery, live polling (standard PIDs and
 * [PhevSignals]), drive-battery health, fault codes and a raw terminal, all published as [state].
 *
 * Every operation that touches the adapter is serialised with one mutex, so terminal commands, live polling and
 * scans never interleave on the link (polling takes the mutex per request, so a terminal command or a scan waits at
 * most one request). No public method throws (except [CancellationException]): failures end up in [state]
 * ([ObdState.message], [ObdPhase.ERROR] when the link is gone). One client per link: after [ObdPhase.ERROR] or
 * [disconnect], create a new client with a new link.
 *
 * Typical use:
 * ```
 * val client = ObdClient(link, diagLog, viewModelScope)
 * if (client.connect()) client.startLive(1_000)
 * client.state.collect { render(it) }
 * ```
 *
 * @param clock wall-clock time for timestamps and poll scheduling (tests can pass a fake)
 * @param signals manufacturer-specific signals to poll (default: all of [PhevSignals])
 */
class ObdClient(
    private val link: ObdLink,
    private val log: DiagLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    elmConfig: ElmConfig = ElmConfig(),
    signals: List<SignalDef> = PhevSignals.all,
) {
    /** The underlying adapter driver. Prefer the client's methods: they keep [state] consistent and serialise access. */
    val elm: Elm327 = Elm327(link, log, elmConfig)

    private val _state = MutableStateFlow(ObdState())

    /** Current state; collect it from the UI. */
    val state: StateFlow<ObdState> = _state.asStateFlow()

    private val linkMutex = Mutex()
    private val signalRequests: List<SignalRequest> = SignalRequest.group(signals)
    private val lastPolled = ConcurrentHashMap<String, Long>()
    private val health = PollHealth()

    @Volatile private var liveJob: Job? = null
    @Volatile private var liveIntervalMs = 1_000L
    @Volatile private var linkDead = false
    @Volatile private var userClosed = false
    @Volatile private var lastObcPayload: ByteArray? = null
    @Volatile private var lastVoltageMs = 0L
    @Volatile private var silentCycles = 0
    @Volatile private var probeFailures = 0
    @Volatile private var nextProbeMs = 0L
    @Volatile private var nextDiscoveryMs = 0L

    // ------------------------------------------------------------------ public API

    /**
     * Initialises the adapter ([Elm327.initialize]) and, if the car answers, discovers it: supported PIDs per ECU,
     * VIN, ECU names, and a quick probe of the known manufacturer ECUs (which also reads the battery).
     * Stops live polling first. Can be called again (e.g. after the ignition was turned on).
     *
     * @return true when the adapter is ready ([ObdPhase.READY]); check [ObdState.carReachable] for the car.
     */
    suspend fun connect(): Boolean {
        stopLive()
        if (linkDead) {
            _state.update { it.copy(phase = ObdPhase.ERROR, message = MSG_RECONNECT) }
            return false
        }
        return try {
            linkMutex.withLock {
                _state.update { it.copy(phase = ObdPhase.CONNECTING, message = "Connecting to the OBD adapter…") }
                log.link("OBD: connecting via ${link.description}")
                _state.update { it.copy(phase = ObdPhase.INITIALISING, message = "Initialising the adapter…") }
                val info = elm.initialize()
                health.reset()
                lastPolled.clear()
                silentCycles = 0
                probeFailures = 0
                nextProbeMs = 0L
                nextDiscoveryMs = clock() + DISCOVERY_RETRY_MS
                val now = clock()
                _state.update {
                    it.copy(
                        adapter = info,
                        adapterVoltage = info.voltage,
                        carReachable = info.carReachable,
                        batteryMessage = info.batteryDataMessage,
                        message = info.message,
                        unavailable = emptyMap(),
                        values = info.voltage?.let { v -> it.values + (ID_ADAPTER_VOLTAGE to voltageValue(v, now)) } ?: it.values,
                    )
                }
                lastVoltageMs = now
                if (info.carReachable) {
                    _state.update { it.copy(message = "Reading the car…") }
                    discoverLocked()
                }
                val s = _state.value
                val msg = when {
                    !s.carReachable -> info.message ?: Elm327.MSG_IGNITION
                    else -> "Connected: ${s.ecus.size} control unit${if (s.ecus.size == 1) "" else "s"} answered." +
                        (s.batteryMessage?.let { " $it" } ?: "")
                }
                _state.update { it.copy(phase = ObdPhase.READY, message = msg) }
                log.link("OBD: $msg")
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            false
        } catch (e: ObdException) {
            fail(describe(e))
            false
        } catch (e: Exception) {
            fail("Unexpected error while connecting: $e")
            false
        }
    }

    /**
     * Starts polling in [scope]: standard PIDs and [PhevSignals] by their poll periods (fastest = [intervalMs]),
     * the battery about every 5 s, adapter voltage every 10 s. Requires [ObdPhase.READY].
     * While the car doesn't answer, only a `0100` probe is sent, with a growing pause (5 s up to 30 s) so a dongle
     * left plugged in doesn't keep the car's network busy; when the car answers again, polling resumes by itself.
     */
    suspend fun startLive(intervalMs: Long = 1_000L) {
        val phase = _state.value.phase
        if (phase != ObdPhase.READY && phase != ObdPhase.POLLING) {
            _state.update { it.copy(message = it.message ?: "Connect to the adapter first.") }
            return
        }
        liveIntervalMs = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        if (liveJob?.isActive == true) return
        _state.update { it.copy(phase = ObdPhase.POLLING) }
        log.link("OBD: live polling every $liveIntervalMs ms")
        liveJob = scope.launch { runLive() }
    }

    /** Stops live polling and waits until the current request has finished. */
    suspend fun stopLive() {
        val job = liveJob ?: return
        liveJob = null
        if (currentCoroutineContext()[Job] === job) {
            job.cancel()
            return
        }
        job.cancelAndJoin()
        _state.update { if (it.phase == ObdPhase.POLLING) it.copy(phase = ObdPhase.READY) else it }
    }

    /**
     * One poll cycle: every item that is due (or everything with [force], which also skips the car-off probe pause);
     * also what the live loop runs. Requests or ECUs that fail twice in a row are skipped for the session
     * ([ObdState.unavailable], [resetUnavailable]) — but only when something else in the same cycle answered, so a
     * car that was switched off doesn't get its ECUs blamed.
     * @return false when the client can't poll (not connected, link lost)
     */
    suspend fun pollOnce(force: Boolean = false): Boolean {
        if (linkDead) return false
        val phase = _state.value.phase
        if (phase != ObdPhase.READY && phase != ObdPhase.POLLING) return false
        return try {
            if (!_state.value.carReachable) {
                // Car off: probe with a growing pause (5 → 30 s) so a forgotten dongle doesn't keep the bus busy.
                if (!force && clock() < nextProbeMs) return true
                val back = linkMutex.withLock { probeCarLocked() }
                if (!back) {
                    probeFailures++
                    nextProbeMs = clock() + minOf(PROBE_BACKOFF_MAX_MS, PROBE_BACKOFF_MS shl minOf(probeFailures - 1, 3))
                    return true
                }
                probeFailures = 0
            } else if (_state.value.supportedPids.isEmpty() && (force || clock() >= nextDiscoveryMs)) {
                // Discovery failed during connect (car answered later or a transient error): retry now and then.
                nextDiscoveryMs = clock() + DISCOVERY_RETRY_MS
                linkMutex.withLock { discoverLocked() }
            }
            val now = clock()
            val items = plan().filter { !health.isDisabled(it.key, it.ecuKey) && (force || isDue(it, now)) }
            val results = ArrayList<Pair<PollItem, Attempt>>()
            val silentThisCycle = HashSet<String>()
            for (item in items) {
                currentCoroutineContext().ensureActive()
                // An ECU that has never answered and just stayed silent: don't spend a timeout on each of its requests.
                if (item.ecuKey in silentThisCycle) continue
                val a = linkMutex.withLock { pollItemLocked(item) }
                lastPolled[item.key] = clock()
                results += item to a
                if (a.outcome == Outcome.NO_ANSWER && !health.everAnswered(item.ecuKey)) silentThisCycle += item.ecuKey
            }
            if (force || clock() - lastVoltageMs >= VOLTAGE_PERIOD_MS) {
                lastVoltageMs = clock()
                linkMutex.withLock { elm.voltage() }?.let { v ->
                    _state.update { it.copy(adapterVoltage = v, values = it.values + (ID_ADAPTER_VOLTAGE to voltageValue(v, clock()))) }
                }
            }
            commitCycle(results)
            if (results.isNotEmpty()) finishCycle()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            false
        } catch (e: ObdException) {
            log.warn("OBD: poll cycle failed: ${e.message}")
            true
        }
    }

    /**
     * Reads the drive battery now (BMU `21 01`, plus the charger counters), regardless of the poll schedule and of
     * earlier failures. Returns the new snapshot, or null (reason in [ObdState.batteryMessage]).
     */
    suspend fun readBattery(): BatteryHealth? {
        if (!canTalk()) return null
        return try {
            linkMutex.withLock {
                signalRequests.firstOrNull { it.key == OBC_KEY }?.let { obc ->
                    if (!health.isDisabled(obc.key, ecuKeyOf(obc))) health.record(obc.key, ecuKeyOf(obc), obc.ecuName, requestSignalsLocked(obc))
                }
                val bmu = signalRequests.firstOrNull { it.key == BMU_KEY } ?: return@withLock null
                val a = requestSignalsLocked(bmu)
                if (a.outcome == Outcome.OK) {
                    health.enable(bmu.key, ecuKeyOf(bmu))
                    _state.update { it.copy(unavailable = health.snapshot()) }
                    _state.value.batteryHealth
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            null
        }
    }

    /**
     * Reads the per-cell voltages (BMU `21 02`) and module temperatures (`21 03`) now, cross-checking against the
     * latest `21 01` summary. Returns the decoded cells, or null (the raw frames are logged either way so an
     * unverified layout can be pinned down from a capture).
     */
    suspend fun readCells(): CellData? {
        if (!canTalk()) return null
        return try {
            linkMutex.withLock {
                val summary = _state.value.batteryHealth ?: run { requestSignalsLocked(signalRequests.first { it.key == BMU_KEY }); _state.value.batteryHealth }
                val cellsPayload = requestBmuRaw(bytesOf(0x21, 0x02)) ?: run {
                    _state.update { it.copy(batteryMessage = MSG_BMU_SILENT) }
                    return@withLock null
                }
                val tempsPayload = requestBmuRaw(bytesOf(0x21, 0x03))
                log.link(
                    "OBD: BMU 21 02 = ${cellsPayload.size} bytes" + (tempsPayload?.let { ", 21 03 = ${it.size} bytes" } ?: "") +
                        " — raw 21 02: ${ObdHex.format(cellsPayload)}" + (tempsPayload?.let { " | 21 03: ${ObdHex.format(it)}" } ?: ""),
                )
                val cells = CellData.decode(cellsPayload, tempsPayload, summary, clock())
                if (cells != null) {
                    log.link("OBD: decoded ${cells.count} cells (${cells.layout}), ${cells.minMv}–${cells.maxMv} mV, spread ${cells.spreadMv} mV" +
                        if (cells.verified) ", matches the summary" else " — could not verify against the summary")
                    _state.update { it.copy(cells = cells) }
                }
                cells
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            null
        }
    }

    /** Sends a raw BMU manufacturer request and returns the payload after the `61 xx` echo, or null. */
    private suspend fun requestBmuRaw(request: ByteArray): ByteArray? {
        var payload: ByteArray? = null
        val a = attempt {
            val replies = elm.request(PhevSignals.BMU, request)
            payload = replies.firstNotNullOfOrNull { SignalDecoder.payloadOf(request, it) } ?: throw ObdException.NoData(ObdHex.format(request, ""))
        }
        return if (a.outcome == Outcome.OK) payload else null
    }

    /**
     * VIN known from elsewhere (e.g. the car's Wi-Fi link), used for the model-year dependent battery reference
     * capacity until the car reports its VIN over OBD (mode 09).
     */
    @Volatile var vinHint: String? = null

    private fun referenceAh(): Double = PhevSignals.referenceCapacityAh(_state.value.vin ?: vinHint)

    /** Reads the adapter's 12 V measurement (ATRV) now and publishes it. Works with the car off. */
    suspend fun readAdapterVoltage(): Double? {
        if (linkDead) return null
        return try {
            val v = linkMutex.withLock { quiet { elm.voltage() } } ?: return null
            val now = clock()
            lastVoltageMs = now
            _state.update { it.copy(adapterVoltage = v, values = it.values + (ID_ADAPTER_VOLTAGE to voltageValue(v, now))) }
            v
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            null
        }
    }

    /**
     * Checks right now whether the car answers (a `0100` broadcast) instead of waiting for the live loop: while the
     * car is off the loop only probes every 5–30 s, so a caller that just asked the user to switch the car on could
     * otherwise wait up to 30 s for the next probe. Cheap no-op when the car already answers.
     * @return [ObdState.carReachable] afterwards; false without a usable link
     */
    suspend fun checkCar(): Boolean {
        if (!canTalk()) return false
        if (_state.value.carReachable) return true
        return try {
            val ok = linkMutex.withLock { _state.value.carReachable || probeCarLocked() }
            if (ok) {
                // The live loop's back-off no longer applies.
                probeFailures = 0
                nextProbeMs = 0L
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            false
        } catch (e: ObdException) {
            false
        }
    }

    /**
     * Reads the emission readiness monitors from the engine ECU: PID 01 (since codes were cleared) and, if
     * [thisDriveCycle], PID 41. Returns null when the car doesn't answer.
     */
    suspend fun readReadiness(thisDriveCycle: Boolean = false): Readiness? {
        if (!canTalk()) return null
        val pid = if (thisDriveCycle) 0x41 else 0x01
        return try {
            linkMutex.withLock {
                val replies = quiet { elm.request(EcuTarget.obd(Ecus.ENGINE.rxId), bytesOf(0x01, pid)) } ?: return@withLock null
                val r = replies.firstOrNull { it.data.size >= 6 && (it.data[0].toInt() and 0xFF) == 0x41 && (it.data[1].toInt() and 0xFF) == pid }
                    ?: return@withLock null
                Readiness.decode(r.data.copyOfRange(2, r.data.size), thisDriveCycle)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            null
        }
    }

    /**
     * Reads the given mode 01 PIDs from the engine ECU now (regardless of the poll schedule) and publishes them.
     * Returns the values that could be read, by id.
     */
    suspend fun readPids(pids: List<Int>, rxId: Int = Ecus.ENGINE.rxId): Map<String, ObdValue> {
        if (!canTalk()) return emptyMap()
        val out = LinkedHashMap<String, ObdValue>()
        return try {
            for (pid in pids) {
                currentCoroutineContext().ensureActive()
                val a = linkMutex.withLock { pollItemLocked(PidItem(rxId, pid, 0L)) }
                lastPolled[ObdHex.canId(rxId - 8) + ":01" + ObdHex.byte(pid)] = clock()
                if (a.outcome == Outcome.OK) _state.value.values[Mode01.valueId(pid, rxId)]?.let { out[it.id] = it }
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            out
        }
    }

    /**
     * Reads fault codes: OBD-II modes 03 (stored), 07 (pending) and 0A (permanent) as broadcasts, and with
     * [includeExtended] also the experimental per-ECU read (KWP2000 `18 02 FF 00`, falling back to UDS `19 02 FF`)
     * from every known ECU. Read-only. Updates [ObdState.dtcs].
     */
    suspend fun scanDtcs(includeExtended: Boolean = false): DtcScanResult {
        if (!canTalk()) return DtcScanResult(emptyList(), emptyList(), listOf(MSG_NOT_CONNECTED), includeExtended, clock(), MSG_NOT_CONNECTED)
        return try {
            linkMutex.withLock {
                val r = scanLocked(includeExtended)
                _state.update { it.copy(dtcs = r.dtcs, lastDtcScanMs = r.scannedAtMs) }
                r
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            DtcScanResult(emptyList(), emptyList(), listOf(MSG_LINK_LOST), includeExtended, clock(), MSG_LINK_LOST)
        }
    }

    /**
     * Clears emission-related (generic) fault codes with OBD-II mode 04 on the functional address, then re-reads
     * modes 03/07/0A. This also erases freeze-frame data and resets the readiness monitors. Codes are deliberately
     * **not** cleared in individual ECUs (KWP/UDS) — safety. Permanent codes stay until the ECU clears them itself.
     */
    suspend fun clearDtcs(): ClearDtcsResult {
        if (!canTalk()) return ClearDtcsResult(false, emptyList(), emptyList(), emptyList(), MSG_NOT_CONNECTED)
        return try {
            linkMutex.withLock {
                log.link("OBD: clearing generic DTCs (mode 04)")
                val timeout = maxOf(elm.config.requestTimeoutMs, 5_000L)
                val resp = try {
                    elm.exchange(EcuTarget.OBD_BROADCAST, bytesOf(0x04), timeout)
                } catch (e: ObdException.Timeout) {
                    null
                }
                val confirmed = resp?.positive?.map { Ecus.nameForRx(it.rxId) }?.distinct().orEmpty()
                val refused = resp?.negative?.map { "${Ecus.nameForRx(it.rxId)}: ${Nrc.name(it.nrc ?: 0)}" }?.distinct().orEmpty()
                val after = scanLocked(includeExtended = false)
                _state.update { it.copy(dtcs = after.dtcs, lastDtcScanMs = after.scannedAtMs) }
                val message = when {
                    confirmed.isNotEmpty() -> "Codes cleared by ${confirmed.joinToString()}." +
                        (if (refused.isNotEmpty()) " Refused: ${refused.joinToString()}." else "") +
                        (if (after.dtcs.isNotEmpty()) " Still reported: ${after.dtcs.joinToString { it.code }}." else "")
                    refused.isNotEmpty() -> "The car refused to clear codes (${refused.joinToString()}). " +
                        "Usually the engine must not be running: switch to ignition ON (not READY) and try again."
                    resp == null -> "No answer to the clear request (timeout). Is the ignition on?"
                    else -> "No ECU confirmed the clear request. Is the ignition on?"
                }
                log.link("OBD: $message")
                ClearDtcsResult(confirmed.isNotEmpty(), confirmed, refused, after.dtcs, message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            ClearDtcsResult(false, emptyList(), emptyList(), emptyList(), MSG_LINK_LOST)
        } catch (e: ObdException) {
            // elm.exchange() can also throw AdapterError (e.g. ATSH rejected after the terminal switched the
            // protocol). Only Timeout was caught above, so this used to escape to the caller (an app crash).
            log.warn("OBD: clear request not sent: ${e.message}")
            ClearDtcsResult(false, emptyList(), emptyList(), emptyList(), describe(e))
        }
    }

    /**
     * Terminal: sends [cmd] exactly as typed and returns the adapter's answer (see [Elm327.raw] for caveats: an empty
     * line repeats the previous command). Works in any phase while the link is up. Errors come back as text
     * starting with "ERROR:".
     */
    suspend fun terminal(cmd: String): String {
        if (linkDead) return "ERROR: not connected"
        return try {
            linkMutex.withLock { elm.raw(cmd) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException.LinkLost) {
            onLinkLost(e)
            "ERROR: ${e.message}"
        } catch (e: ObdException) {
            "ERROR: ${e.message}"
        }
    }

    /** Stops polling and closes the link. The client can't be used afterwards. */
    suspend fun disconnect() {
        withContext(NonCancellable) {
            userClosed = true
            stopLive()
            linkDead = true
            elm.close()
            _state.update { it.copy(phase = ObdPhase.DISCONNECTED, carReachable = false, message = "Disconnected.") }
            log.link("OBD: disconnected")
        }
    }

    /** Re-enables requests/ECUs that were disabled after repeated failures (e.g. after turning the car on). */
    fun resetUnavailable() {
        health.reset()
        _state.update { it.copy(unavailable = emptyMap()) }
    }

    /**
     * Ids of all values this client can produce with the current discovery results (stable order):
     * adapter voltage, supported mode 01 PIDs, [PhevSignals] and derived battery values. Handy as fixed CSV columns.
     */
    fun knownValueIds(): List<String> {
        val ids = ArrayList<String>()
        ids += ID_ADAPTER_VOLTAGE
        for ((rx, pids) in _state.value.supportedPids.toSortedMap()) {
            for (pid in pids.sorted()) if (!Mode01.isBitmapPid(pid)) ids += Mode01.valueId(pid, rx)
        }
        for (r in signalRequests) for (s in r.signals) ids += s.id
        if (signalRequests.any { it.key == BMU_KEY }) ids += listOf(BatteryHealth.ID_SOH, BatteryHealth.ID_SPREAD, BatteryHealth.ID_ENERGY)
        return ids.distinct()
    }

    // ------------------------------------------------------------------ discovery

    private suspend fun discoverLocked() {
        // 1. Supported PIDs per ECU (0100, 0120, ... 01C0 as long as someone announces the next range).
        val supported = LinkedHashMap<Int, MutableSet<Int>>()
        var base = 0x00
        while (base <= 0xC0) {
            val resp = quiet { elm.exchange(EcuTarget.OBD_BROADCAST, bytesOf(0x01, base)) } ?: break
            var more = false
            for (r in resp.positive) {
                if (r.data.size < 6) continue
                val rx = if (r.rxId == EcuReply.UNKNOWN_RX) Ecus.ENGINE.rxId else r.rxId
                val set = Mode01.parseSupported(base, r.data.copyOfRange(2, 6))
                supported.getOrPut(rx) { LinkedHashSet() } += set
                if ((base + 0x20) in set) more = true
            }
            if (!more) break
            base += 0x20
        }
        _state.update { it.copy(supportedPids = supported.mapValues { e -> e.value.toSet() }) }
        log.link("OBD: supported PIDs " + supported.entries.joinToString { (rx, p) -> "${ObdHex.canId(rx)}: ${p.joinToString(" ") { ObdHex.byte(it) }}" })

        // 2. VIN and ECU names (mode 09).
        val vin = quiet { elm.exchange(EcuTarget.OBD_BROADCAST, bytesOf(0x09, 0x02)) }?.positive?.firstNotNullOfOrNull { Mode09.parseVin(it) }
        val names = quiet { elm.exchange(EcuTarget.OBD_BROADCAST, bytesOf(0x09, 0x0A)) }?.positive
            ?.mapNotNull { r -> Mode09.parseEcuName(r)?.let { r.rxId to it } }?.toMap().orEmpty()
        if (vin != null) log.link("OBD: VIN $vin")

        // 3. ECUs: OBD ECUs from the PID discovery, manufacturer ECUs by probing their first request.
        val ecus = ArrayList<EcuInfo>()
        for (rx in supported.keys) {
            val k = Ecus.byRx(rx)
            ecus += EcuInfo(rx - 8, rx, Ecus.nameForRx(rx), obd = true, probe = k?.probe ?: true, source = k?.source ?: "OBD-II", reportedName = names[rx])
        }
        _state.update { it.copy(vin = vin ?: it.vin, ecus = ecus.toList()) }
        val probeTargets = signalRequests.map { it.target }.distinctBy { it.txId }.filter { it.txId !in 0x7E0..0x7E7 }
        for (target in probeTargets) {
            val req = signalRequests.first { it.target.txId == target.txId }
            val a = requestSignalsLocked(req)
            health.record(req.key, ecuKeyOf(req), "${req.ecuName} ${ObdHex.format(req.request)}", a)?.let { log.warn("OBD: $it") }
            if (a.outcome != Outcome.NO_ANSWER) {
                val k = Ecus.byTx(target.txId)
                ecus += EcuInfo(target.txId, target.rxId ?: (target.txId + 8), Ecus.nameForTx(target.txId), obd = false,
                    probe = k?.probe ?: true, source = k?.source ?: "probe")
                _state.update { it.copy(ecus = ecus.toList()) }
            }
            lastPolled[req.key] = clock()
        }
        _state.update { it.copy(unavailable = health.snapshot()) }
        log.link("OBD: ECUs found: ${ecus.joinToString { "${it.name} (${ObdHex.canId(it.rxId)})" }}")
    }

    /** 0100 broadcast while the car is considered unreachable. */
    private suspend fun probeCarLocked(): Boolean {
        val ok = quiet { elm.exchange(EcuTarget.OBD_BROADCAST, bytesOf(0x01, 0x00)) }?.positive?.isNotEmpty() == true
        if (ok) {
            log.link("OBD: the car answers")
            silentCycles = 0
            _state.update { it.copy(carReachable = true, message = "The car answers.") }
            if (_state.value.supportedPids.isEmpty()) discoverLocked()
        } else {
            _state.update { it.copy(carReachable = false, message = it.message ?: Elm327.MSG_IGNITION) }
        }
        return ok
    }

    // ------------------------------------------------------------------ polling

    private enum class Outcome { OK, NEGATIVE, NO_ANSWER, INCOMPLETE }

    private class Attempt(val outcome: Outcome, val detail: String)

    private abstract class PollItem {
        abstract val key: String
        abstract val ecuKey: String
        abstract val periodMs: Long
        abstract val label: String
    }

    private class PidItem(val rxId: Int, val pid: Int, override val periodMs: Long) : PollItem() {
        override val key = ObdHex.canId(rxId - 8) + ":01" + ObdHex.byte(pid)
        override val ecuKey = ObdHex.canId(rxId - 8) + ":01"
        override val label = "${Ecus.nameForRx(rxId)} PID ${ObdHex.byte(pid)}"
    }

    private class SignalItem(val req: SignalRequest, override val periodMs: Long) : PollItem() {
        override val key = req.key
        override val ecuKey = ecuKeyOf(req)
        override val label = "${req.ecuName} ${ObdHex.format(req.request)}"
    }

    private fun plan(): List<PollItem> {
        val items = ArrayList<PollItem>()
        for ((rx, pids) in _state.value.supportedPids) {
            if (rx !in 0x7E8..0x7EF) continue
            for (pid in pids) {
                if (Mode01.isBitmapPid(pid)) continue
                items += PidItem(rx, pid, periodMs(Mode01.pids[pid]?.pollSeconds ?: 30.0))
            }
        }
        for (req in signalRequests) items += SignalItem(req, periodMs(req.pollSeconds))
        return items
    }

    /** Poll period: "1 s or faster" means every live tick; slower items keep their own period. */
    private fun periodMs(pollSeconds: Double): Long {
        val interval = liveIntervalMs
        return if (pollSeconds <= 1.0) interval else (pollSeconds * 1000).toLong().coerceAtLeast(interval)
    }

    private fun isDue(item: PollItem, now: Long): Boolean {
        val last = lastPolled[item.key] ?: return true
        return now - last >= item.periodMs - minOf(200L, liveIntervalMs / 4)
    }

    private suspend fun pollItemLocked(item: PollItem): Attempt = when (item) {
        is PidItem -> attempt {
            val reply = elm.request(EcuTarget.obd(item.rxId), bytesOf(0x01, item.pid)).first()
            val payload = reply.data.copyOfRange(2, reply.data.size)
            val dec = Mode01.decode(item.pid, payload)
            val now = clock()
            val v = ObdValue(
                Mode01.valueId(item.pid, item.rxId), Mode01.nameOf(item.pid), Ecus.nameForRx(item.rxId),
                dec.value, dec.text, Mode01.unitOf(item.pid), Confidence.VERIFIED, now, item.rxId,
                if (Mode01.pids.containsKey(item.pid)) "SAE J1979 mode 01 PID ${ObdHex.byte(item.pid)}" else "Raw data bytes (no decoder)",
            )
            _state.update { it.copy(values = it.values + (v.id to v)) }
        }
        is SignalItem -> requestSignalsLocked(item.req)
        else -> Attempt(Outcome.NO_ANSWER, "unknown item")
    }

    /** Fetches one manufacturer request and publishes its signals (and battery health for the BMU). */
    private suspend fun requestSignalsLocked(req: SignalRequest): Attempt {
        var payload: ByteArray? = null
        val a = attempt {
            val replies = elm.request(req.target, req.request)
            payload = replies.firstNotNullOfOrNull { SignalDecoder.payloadOf(req.request, it) }
                ?: throw ObdException.NoData(ObdHex.format(req.request, ""))
        }
        val p = payload
        if (a.outcome == Outcome.OK && p != null) {
            applySignals(req, p, clock())
        } else if (req.key == BMU_KEY) {
            val msg = when (a.outcome) {
                Outcome.INCOMPLETE -> Elm327.MSG_NO_FLOW_CONTROL
                Outcome.NEGATIVE -> "The drive battery ECU (BMU) refused the request (${a.detail})."
                else -> MSG_BMU_SILENT
            }
            _state.update { it.copy(batteryMessage = msg) }
        }
        return a
    }

    private fun applySignals(req: SignalRequest, payload: ByteArray, now: Long) {
        val updates = LinkedHashMap<String, ObdValue>()
        val invalid = ArrayList<String>()
        for (def in req.signals) {
            val v = SignalDecoder.decode(def, payload)
            if (v == null) {
                invalid += def.id
            } else {
                val text = if (def.flag) (if (v >= 0.5) "on" else "off") else null
                updates[def.id] = ObdValue(def.id, def.name, def.group, v, text, def.unit, def.confidence, now, req.target.rxId, def.note)
            }
        }
        var battery: BatteryHealth? = null
        if (req.key == OBC_KEY) {
            lastObcPayload = payload.copyOf()
            _state.value.batteryHealth?.rawBmu?.let { bmu -> battery = BatteryHealth.decode(bmu, payload, _state.value.batteryHealth?.updatedAtMs ?: now, referenceAh()) }
        }
        if (req.key == BMU_KEY) {
            battery = BatteryHealth.decode(payload, lastObcPayload, now, referenceAh())
            battery?.let { b -> updates += derivedBatteryValues(b, now) }
            if (battery == null) invalid += listOf(BatteryHealth.ID_SOH, BatteryHealth.ID_SPREAD, BatteryHealth.ID_ENERGY)
        }
        val b = battery
        _state.update {
            it.copy(
                values = (it.values - invalid.toSet()) + updates,
                batteryHealth = b ?: it.batteryHealth,
                batteryMessage = if (req.key == BMU_KEY && b != null) null else it.batteryMessage,
            )
        }
    }

    private fun derivedBatteryValues(b: BatteryHealth, now: Long): Map<String, ObdValue> {
        val group = PhevSignals.GROUP_BATTERY
        val rx = PhevSignals.BMU.rxId
        val out = LinkedHashMap<String, ObdValue>()
        b.sohPercent?.let {
            out[BatteryHealth.ID_SOH] = ObdValue(BatteryHealth.ID_SOH, "State of health (approx.)", group, it, null, "%", Confidence.COMMUNITY, now, rx,
                "Capacity / ${b.referenceAh} Ah × 100. Reference = Mitsubishi's capacity basis: 38 Ah before model year 2017, 40 Ah from 2017 (from the VIN). Approximate.")
        }
        b.spreadMv?.let {
            out[BatteryHealth.ID_SPREAD] = ObdValue(BatteryHealth.ID_SPREAD, "Cell voltage spread", group, it, null, "mV", Confidence.EXPERIMENTAL, now, rx,
                "Highest − lowest cell voltage; both inferred from the forum example.")
        }
        b.energyKwh?.let {
            out[BatteryHealth.ID_ENERGY] = ObdValue(BatteryHealth.ID_ENERGY, "Stored energy (approx.)", group, it, null, "kWh", Confidence.COMMUNITY, now, rx,
                "Current charge (Ah) × pack voltage / 1000. Approximate.")
        }
        return out
    }

    private suspend fun attempt(block: suspend () -> Unit): Attempt = try {
        block()
        Attempt(Outcome.OK, "")
    } catch (e: ObdException.NegativeResponse) {
        Attempt(Outcome.NEGATIVE, e.nrcName)
    } catch (e: ObdException.Incomplete) {
        Attempt(Outcome.INCOMPLETE, "reply stopped after ${e.received} of ${e.expected} bytes")
    } catch (e: ObdException.NoData) {
        Attempt(Outcome.NO_ANSWER, "no answer")
    } catch (e: ObdException.UnableToConnect) {
        Attempt(Outcome.NO_ANSWER, e.detail)
    } catch (e: ObdException.Timeout) {
        Attempt(Outcome.NO_ANSWER, TIMEOUT)
    } catch (e: ObdException.AdapterError) {
        Attempt(Outcome.NO_ANSWER, e.text)
    }

    private suspend fun commitCycle(results: List<Pair<PollItem, Attempt>>) {
        if (results.isEmpty()) return
        val anyAnswer = results.any { it.second.outcome != Outcome.NO_ANSWER }
        if (!anyAnswer) {
            // The whole car went quiet (ignition off?) or the adapter hung: don't blame individual ECUs.
            silentCycles++
            log.warn("OBD: nothing answered in this cycle (${results.size} requests)")
            _state.update { it.copy(carReachable = false, message = MSG_CAR_SILENT) }
            if (silentCycles >= 3 && results.all { it.second.detail == TIMEOUT }) {
                silentCycles = 0
                log.warn("OBD: adapter seems stuck — re-initialising")
                val info = try {
                    linkMutex.withLock { elm.initialize() }
                } catch (e: ObdException.Timeout) {
                    null
                }
                if (info == null) {
                    fail(Elm327.MSG_NO_ADAPTER)
                    liveJob?.cancel()
                } else {
                    _state.update { it.copy(adapter = info, carReachable = info.carReachable, message = info.message) }
                }
            }
            return
        }
        silentCycles = 0
        for ((item, a) in results) {
            health.record(item.key, item.ecuKey, item.label, a)?.let { log.warn("OBD: $it") }
        }
        _state.update { it.copy(unavailable = health.snapshot()) }
    }

    private fun finishCycle() {
        _state.update { it.copy(pollCycle = it.pollCycle + 1, lastPollMs = clock()) }
    }

    private suspend fun runLive() {
        try {
            while (true) {
                val started = monotonicMs()
                if (!pollOnce()) break
                val elapsed = monotonicMs() - started
                delay((liveIntervalMs - elapsed).coerceAtLeast(MIN_GAP_MS))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.error("OBD: live polling crashed: $t")
            fail("Live data stopped because of an unexpected error: $t")
        } finally {
            _state.update { if (it.phase == ObdPhase.POLLING) it.copy(phase = ObdPhase.READY) else it }
        }
    }

    // ------------------------------------------------------------------ DTC scan

    private suspend fun scanLocked(includeExtended: Boolean): DtcScanResult {
        val dtcs = ArrayList<DtcRecord>()
        val responders = LinkedHashSet<String>()
        val errors = ArrayList<String>()
        var carSilent = false
        for ((mode, kind) in listOf(0x03 to DtcKind.STORED, 0x07 to DtcKind.PENDING, 0x0A to DtcKind.PERMANENT)) {
            val resp = try {
                elm.exchange(EcuTarget.OBD_BROADCAST, bytesOf(mode))
            } catch (e: ObdException.Timeout) {
                errors += "Mode ${ObdHex.byte(mode)}: timeout"
                continue
            } catch (e: ObdException.AdapterError) {
                errors += "Mode ${ObdHex.byte(mode)}: ${e.text}"
                continue
            }
            for (r in resp.positive) {
                responders += Ecus.nameForRx(r.rxId)
                dtcs += DtcCodec.parseObdResponse(r, kind)
            }
            if (resp.replies.isEmpty() && resp.status.any { s -> ElmResponse.SILENT_BUS.any { s.startsWith(it) } }) carSilent = true
        }
        if (includeExtended) {
            for (ecu in Ecus.all) {
                if (health.isEcuSilent(ObdHex.canId(ecu.txId) + ":21")) continue
                val target = ecu.target
                val kwp = quiet { elm.exchange(target, bytesOf(0x18, 0x02, 0xFF, 0x00)) } ?: continue
                if (kwp.positive.isNotEmpty()) {
                    responders += ecu.name
                    kwp.positive.forEach { dtcs += DtcCodec.parseKwp18(it, ecu.name) }
                    continue
                }
                if (kwp.negative.isEmpty()) continue // nobody there
                responders += ecu.name
                val uds = quiet { elm.exchange(target, bytesOf(0x19, 0x02, 0xFF)) } ?: continue
                uds.positive.forEach { dtcs += DtcCodec.parseUds19(it, ecu.name) }
            }
        }
        val unique = dtcs.distinctBy { Triple(it.code, it.kind, it.ecuRxId) }
        if (responders.isEmpty()) errors += if (carSilent) Elm327.MSG_IGNITION else "No ECU answered the fault code requests."
        val message = when {
            responders.isEmpty() -> errors.last()
            unique.isEmpty() -> "No fault codes (${responders.size} control unit${if (responders.size == 1) "" else "s"} checked)."
            else -> "${unique.size} fault code${if (unique.size == 1) "" else "s"} found."
        }
        log.link("OBD: DTC scan: $message ${unique.joinToString { "${it.code}/${it.kind}/${it.ecuName}" }}")
        return DtcScanResult(unique, responders.toList(), errors, includeExtended, clock(), message)
    }

    // ------------------------------------------------------------------ helpers

    private fun canTalk(): Boolean {
        val p = _state.value.phase
        return !linkDead && (p == ObdPhase.READY || p == ObdPhase.POLLING)
    }

    /** Runs [block]; protocol-level failures → null. [ObdException.LinkLost] and cancellation propagate. */
    private suspend fun <T> quiet(block: suspend () -> T): T? = try {
        block()
    } catch (e: ObdException.LinkLost) {
        throw e
    } catch (e: ObdException) {
        null
    }

    private fun onLinkLost(e: ObdException.LinkLost) {
        linkDead = true
        liveJob?.cancel()
        elm.close()
        if (userClosed) return
        log.error("OBD: link lost: ${e.ioException.message}")
        _state.update { it.copy(phase = ObdPhase.ERROR, carReachable = false, message = MSG_LINK_LOST) }
    }

    private fun fail(message: String) {
        log.error("OBD: $message")
        _state.update { it.copy(phase = ObdPhase.ERROR, message = message) }
    }

    private fun describe(e: ObdException): String = when (e) {
        is ObdException.Timeout -> Elm327.MSG_NO_ADAPTER
        is ObdException.LinkLost -> MSG_LINK_LOST
        is ObdException.AdapterError -> "The adapter reported an error (${e.text}). Unplug it for a few seconds and reconnect."
        else -> e.message ?: e.toString()
    }

    private fun voltageValue(v: Double, now: Long) = ObdValue(
        ID_ADAPTER_VOLTAGE, "12 V battery (at the OBD port)", GROUP_ADAPTER, v, null, "V", Confidence.VERIFIED, now, null,
        "Measured by the adapter (ATRV); usually 0.1–0.3 V below the battery terminals.",
    )

    /** Consecutive-failure bookkeeping: what to skip for the rest of the session. Thread-safe. */
    private class PollHealth {
        private val reqFails = HashMap<String, Int>()
        private val ecuFails = HashMap<String, Int>()
        private val answered = HashSet<String>()
        private val disabledReq = LinkedHashMap<String, String>()
        private val disabledEcu = LinkedHashMap<String, String>()

        @Synchronized fun reset() {
            reqFails.clear(); ecuFails.clear(); answered.clear(); disabledReq.clear(); disabledEcu.clear()
        }

        @Synchronized fun isDisabled(key: String, ecuKey: String) = key in disabledReq || ecuKey in disabledEcu

        @Synchronized fun isEcuSilent(ecuKey: String) = ecuKey in disabledEcu

        @Synchronized fun everAnswered(ecuKey: String) = ecuKey in answered

        @Synchronized fun enable(key: String, ecuKey: String) {
            disabledReq.remove(key); disabledEcu.remove(ecuKey); reqFails.remove(key); ecuFails.remove(ecuKey)
        }

        /** Returns a log line when something got disabled. */
        @Synchronized fun record(key: String, ecuKey: String, label: String, a: Attempt): String? {
            when (a.outcome) {
                Outcome.OK -> {
                    reqFails.remove(key); ecuFails.remove(ecuKey); answered += ecuKey
                    return null
                }
                Outcome.NEGATIVE, Outcome.INCOMPLETE -> {
                    ecuFails.remove(ecuKey); answered += ecuKey
                }
                Outcome.NO_ANSWER -> {
                    val e = (ecuFails[ecuKey] ?: 0) + 1
                    ecuFails[ecuKey] = e
                    if (e >= 2 && ecuKey !in answered && ecuKey !in disabledEcu) {
                        val ecuName = Ecus.nameForTx(ecuKey.substringBefore(':').toInt(16))
                        disabledEcu[ecuKey] = "$ecuName: no answer twice in a row — not present or not reachable; skipped for this session"
                        return "disabled ${disabledEcu[ecuKey]}"
                    }
                }
            }
            val n = (reqFails[key] ?: 0) + 1
            reqFails[key] = n
            if (n >= 2 && key !in disabledReq) {
                disabledReq[key] = "$label: ${a.detail} twice in a row; skipped for this session"
                return "disabled ${disabledReq[key]}"
            }
            return null
        }

        @Synchronized fun snapshot(): Map<String, String> = LinkedHashMap<String, String>().apply {
            putAll(disabledEcu)
            putAll(disabledReq)
        }
    }

    companion object {
        const val ID_ADAPTER_VOLTAGE = "adapter.voltage"
        const val GROUP_ADAPTER = "Adapter"

        const val MSG_LINK_LOST =
            "Connection to the OBD adapter was lost. Check that it is still plugged in and in range, then reconnect."
        const val MSG_CAR_SILENT = "The car stopped answering — is the ignition still on? Retrying…"
        const val MSG_BMU_SILENT =
            "The drive battery ECU (BMU) did not answer. Battery data needs the car switched on (READY or ignition ON)."
        const val MSG_NOT_CONNECTED = "Not connected to the adapter."
        const val MSG_RECONNECT = "The link to the adapter is closed. Reconnect the adapter."

        private const val TIMEOUT = "timeout"
        private const val MIN_INTERVAL_MS = 100L
        private const val MIN_GAP_MS = 20L
        private const val VOLTAGE_PERIOD_MS = 10_000L
        private const val PROBE_BACKOFF_MS = 5_000L
        private const val PROBE_BACKOFF_MAX_MS = 30_000L
        private const val DISCOVERY_RETRY_MS = 30_000L

        private val BMU_KEY = SignalRequest.keyOf(PhevSignals.BMU, bytesOf(0x21, 0x01))
        private val OBC_KEY = SignalRequest.keyOf(PhevSignals.OBC, bytesOf(0x21, 0x01))

        private fun ecuKeyOf(req: SignalRequest) = ObdHex.canId(req.target.txId) + ":" + ObdHex.byte(req.request[0].toInt())
    }
}
