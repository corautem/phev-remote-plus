package io.github.anwo91.phevlink.core.session

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.diag.LogKind
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.RegisterDecoder
import io.github.anwo91.phevlink.core.model.RegisterWrite
import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.Packet
import io.github.anwo91.phevlink.core.protocol.PacketType
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.SecurityKey
import io.github.anwo91.phevlink.core.protocol.StreamDecoder
import io.github.anwo91.phevlink.core.protocol.toHex

interface SessionClock {
    /** Monotonic milliseconds, for timeouts. */
    fun monoMs(): Long
    /** Wall-clock epoch milliseconds, for timestamps shown to people. */
    fun wallMs(): Long

    object System : SessionClock {
        override fun monoMs() = java.lang.System.nanoTime() / 1_000_000
        override fun wallMs() = java.lang.System.currentTimeMillis()
    }
}

data class SessionConfig(
    /** Car must send its start packet within this time after TCP connect. */
    val handshakeTimeoutMs: Long = 20_000,
    /** Drop the connection if nothing at all arrives for this long. */
    val silenceTimeoutMs: Long = 12_000,
    /** Ping when nothing was received for this long... */
    val pingIdleMs: Long = 500,
    /** ...but not more often than this. */
    val pingIntervalMs: Long = 200,
    /** How long to wait for the car to acknowledge a register write. */
    val writeTimeoutMs: Long = 4_000,
    val maxWriteAttempts: Int = 3,
    /** Ask the car for a full register refresh this often while connected (0 = never). */
    val refreshIntervalMs: Long = 0,
    /** Ask for a refresh this long after the session starts if we still know nothing. */
    val initialRefreshDelayMs: Long = 6_000,
    val badXorLimit: Int = 30,
    val badXorWindowMs: Long = 15_000,
    /** Partial packet sitting in the buffer this long is treated as garbage. */
    val staleBufferMs: Long = 1_500,
    /** Used for commands if the car has not told us its model year yet. */
    val fallbackModelYear: ModelYear = ModelYear.MY14,
    /** Set the car's clock once per session, like the official app does. */
    val syncClockOnConnect: Boolean = true,
    val clockSyncDelayMs: Long = 3_000,
    /** After this many encoding complaints, ask the car for a fresh key (official app behaviour). */
    val rekeyAfterBadXor: Int = 8,
    /** Commands that echo registers wait up to this long after session start for them to arrive. */
    val registerWaitMs: Long = 5_000,
)

enum class SessionPhase { HANDSHAKE, READY, CLOSED }

interface SessionListener {
    fun onPhase(phase: SessionPhase) {}
    fun onState(state: CarState) {}
    /** The session is over; the transport must be closed. Called exactly once. */
    fun onClosed(reason: String) {}
}

/**
 * The protocol engine, free of any I/O ("sans-IO"): feed it bytes and clock
 * ticks, it emits bytes through [transmit] and events through [listener].
 * Not thread-safe; drive it from a single thread or coroutine.
 *
 * Responsibilities:
 *  - keepalive pings and dead-link detection,
 *  - security handshake and XOR keying (with resync on 0xbb),
 *  - acknowledging every register notification,
 *  - a command queue: one register write in flight, ack tracking, retries,
 *  - folding notifications into [CarState].
 */
class PhevSession(
    private val transmit: (ByteArray) -> Unit,
    private val listener: SessionListener,
    private val log: DiagLog,
    private val clock: SessionClock = SessionClock.System,
    private val config: SessionConfig = SessionConfig(),
    initialState: CarState = CarState(),
) {
    private val key = SecurityKey()
    private val decoder = StreamDecoder()

    var phase: SessionPhase = SessionPhase.HANDSHAKE
        private set
    var state: CarState = initialState
        private set
    var modelYear: ModelYear = ModelYear.UNKNOWN
        private set

    private var startedAt = 0L
    private var lastRxAt = 0L
    private var lastBytesAt = 0L
    private var lastPingAt = Long.MIN_VALUE / 2
    private var pingSeq = 0x0a
    private var readyAt = 0L
    private var lastRefreshAt = 0L
    private var initialRefreshDone = false
    /** Registers the car has sent in *this* session (the initial state may be an old cache). */
    private val seenThisSession = HashSet<Int>()
    private var clockSynced = false
    private var lastRekeyRequestAt = Long.MIN_VALUE / 2
    /** Recent 0xf6 packets (write or ack) with send time, so rejected ones can be resent after a 0xbb. */
    private val recentSends = ArrayDeque<Pair<Long, Packet>>()
    private var lastBadXorExpected = -1
    private var lastBadXorAt = Long.MIN_VALUE / 2
    private val badXorTimes = ArrayDeque<Long>()
    private var closeReason: String? = null

    private class Job(
        val command: CarCommand,
        val callback: ((CommandResult) -> Unit)?,
        val queuedAt: Long,
        val silent: Boolean,
    ) {
        /** Built when the job starts, so it sees the latest register values. */
        var writes: List<RegisterWrite> = emptyList()
        var step = 0
        var attempts = 0
        var awaitingAck = false
        var deadline = 0L
        var resumeAt: Long? = null
        /**
         * Set while the current (already acked) step waits for the car to confirm it
         * through a notification ([RegisterWrite.awaitState]); deadline in mono ms.
         */
        var awaitUntil: Long? = null
        /** First "car did not confirm" message; turns the final result into a failure. */
        var unconfirmed: String? = null
        val current: RegisterWrite get() = writes[step]
    }

    private val queue = ArrayDeque<Job>()
    private var job: Job? = null

    /** Call once, right after the TCP connection is established. */
    fun start() {
        val now = clock.monoMs()
        startedAt = now
        lastRxAt = now
        lastBytesAt = now
        log.link("Connected to car, waiting for it to start the session")
        sendPing()
    }

    fun onBytes(bytes: ByteArray) {
        if (phase == SessionPhase.CLOSED) return
        lastBytesAt = clock.monoMs()
        for (item in decoder.feed(bytes)) handleItem(item)
    }

    /** Call periodically (every ~100 ms). */
    fun tick() {
        if (phase == SessionPhase.CLOSED) return
        val now = clock.monoMs()

        if (decoder.buffered > 0 && now - lastBytesAt > config.staleBufferMs) {
            for (item in decoder.flushStale()) handleItem(item)
            if (phase == SessionPhase.CLOSED) return
        }
        if (phase == SessionPhase.HANDSHAKE && now - startedAt > config.handshakeTimeoutMs) {
            close(
                "The car accepted the connection but never started a session. " +
                    "This phone is probably not registered with the car (or the registration was lost)."
            )
            return
        }
        if (now - lastRxAt > config.silenceTimeoutMs) {
            close("No data from the car for ${config.silenceTimeoutMs / 1000} s (out of range or Wi-Fi dropped)")
            return
        }
        if (now - lastRxAt >= config.pingIdleMs && now - lastPingAt >= config.pingIntervalMs) sendPing()

        pumpJobs(now)

        if (phase == SessionPhase.READY && config.syncClockOnConnect && !clockSynced &&
            now - readyAt >= config.clockSyncDelayMs && job == null
        ) {
            clockSynced = true
            val local = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(clock.wallMs()), java.time.ZoneId.systemDefault()
            )
            enqueue(CarCommand.SyncClock(local), { r ->
                if (r is CommandResult.Success) log.link("Car clock set to phone time")
            }, silent = true)
        }

        if (phase == SessionPhase.READY && job == null && queue.isEmpty()) {
            val needInitial = !initialRefreshDone && now - readyAt > config.initialRefreshDelayMs
            val needPeriodic = config.refreshIntervalMs > 0 && now - lastRefreshAt > config.refreshIntervalMs
            if (needInitial || needPeriodic) {
                initialRefreshDone = true
                if (needInitial && Reg.READ_BATTERY in seenThisSession && !needPeriodic) {
                    // The car already sent its registers; no need to ask.
                } else {
                    enqueue(CarCommand.RequestUpdate, null, silent = true)
                }
                lastRefreshAt = now
            }
        }
    }

    /** Queue a command. [callback] is invoked exactly once with the outcome. */
    fun execute(command: CarCommand, callback: ((CommandResult) -> Unit)? = null) {
        if (phase == SessionPhase.CLOSED) {
            callback?.invoke(CommandResult.Failure(command, closeReason ?: "Not connected", retryable = true))
            return
        }
        enqueue(command, callback, silent = false)
    }

    /** The transport died underneath us. */
    fun onTransportClosed(reason: String) = close(reason)

    fun close(reason: String) {
        if (phase == SessionPhase.CLOSED) return
        phase = SessionPhase.CLOSED
        closeReason = reason
        log.link("Session closed: $reason")
        val pending = listOfNotNull(job) + queue
        job = null
        queue.clear()
        for (j in pending) j.callback?.invoke(CommandResult.Failure(j.command, "Connection lost: $reason", retryable = true))
        listener.onPhase(SessionPhase.CLOSED)
        listener.onClosed(reason)
    }

    // ---------------------------------------------------------------- receive

    private fun handleItem(item: StreamDecoder.Item) {
        when (item) {
            is StreamDecoder.Item.Garbage ->
                log.add(LogKind.WARN, "Skipped ${item.bytes.size} unparseable byte(s)", item.bytes.toHex())
            is StreamDecoder.Item.Decoded -> handlePacket(item.packet, item.xor, item.raw)
        }
    }

    private fun handlePacket(p: Packet, xor: Int, raw: ByteArray) {
        if (phase == SessionPhase.CLOSED) return
        val now = clock.monoMs()
        lastRxAt = now
        val chatty = p.type == PacketType.PING_RESP
        if (!chatty || log.recordPings) log.add(LogKind.RX, describe(p, incoming = true), raw.toHex(), xor)

        when {
            PacketType.isStartRequest(p.type) -> handleStart(p)
            p.type == PacketType.PING_RESP -> Unit
            p.type == PacketType.KEEPALIVE_REQ -> sendPing()
            p.type == PacketType.RESP -> handleResp(p)
            p.type == PacketType.BAD_ENCODING -> handleBadXor(p, now)
            p.type == PacketType.BAD_ENCODING_ALT -> log.warn("Car sent 0xcc (encoding complaint, meaning unknown)")
            else -> log.warn("Unhandled packet type ${PacketType.name(p.type)}")
        }
    }

    private fun handleStart(p: Packet) {
        val my = ModelYear.fromStartType(p.type)
        key.update(p.toPlainBytes())
        send(Packet(PacketType.responseFor(p.type), ack = 1, register = 0x01, data = byteArrayOf(0)))
        if (p.ack == 0 && my != ModelYear.UNKNOWN) modelYear = my
        if (phase == SessionPhase.HANDSHAKE && p.ack == 0) {
            phase = SessionPhase.READY
            readyAt = clock.monoMs()
            lastRefreshAt = readyAt
            state = state.copy(modelYear = modelYear)
            log.link(
                "Session started (${modelYear.label} protocol, security key " +
                    "0x%02x)".format(key.securityKey)
            )
            listener.onPhase(SessionPhase.READY)
            listener.onState(state)
            pumpJobs(readyAt)
        } else {
            log.link("Car refreshed the session key (0x%02x)".format(key.securityKey))
        }
    }

    private fun handleResp(p: Packet) {
        key.recvKey(advance = true)
        if (p.ack == 0) {
            // Notification: acknowledge it, then fold it into the state.
            send(Packet(PacketType.SEND, ack = 1, register = p.register, data = byteArrayOf(0)))
            val before = state
            seenThisSession += p.register
            state = RegisterDecoder.apply(state, p.register, p.data, clock.wallMs())
            if (p.register == Reg.READ_REGISTRATION_EVENT) log.link("Car reports a registration event (0x2a=${p.dataHex()})")
            logStateDiff(before, state)
            listener.onState(state)
            checkAwaitedState()
        } else {
            val j = job
            if (j != null && j.awaitingAck && j.current.register == p.register) onStepAcked(j)
        }
    }

    private fun handleBadXor(p: Packet, now: Long) {
        val expected = p.data.firstOrNull()?.toInt()?.and(0xff)
        if (expected != null && expected == lastBadXorExpected && now - lastBadXorAt < 2_000) {
            // Same complaint again: it refers to a packet that was already in flight
            // before we re-synchronised and resent. Acting on it again would desync us.
            log.add(LogKind.WARN, "Duplicate encoding complaint (0x%02x) ignored".format(expected))
            return
        }
        lastBadXorExpected = expected ?: -1
        lastBadXorAt = now
        badXorTimes.addLast(now)
        while (badXorTimes.isNotEmpty() && now - badXorTimes.first() > config.badXorWindowMs) badXorTimes.removeFirst()
        if (expected != null) {
            val found = key.resyncSend(expected)
            log.warn(
                "Car rejected our encoding, expected key 0x%02x — ".format(expected) +
                    if (found) "re-synchronised" else "will force it on next send"
            )
        } else {
            log.warn("Car rejected our encoding (no key hint)")
        }
        if (badXorTimes.size > config.badXorLimit) {
            close("Too many encoding errors (${badXorTimes.size} in ${config.badXorWindowMs / 1000} s)")
            return
        }
        if (badXorTimes.size >= config.rekeyAfterBadXor && now - lastRekeyRequestAt > 10_000) {
            // The official app's recovery: a start-response with ack=0 asks the car for a new key.
            lastRekeyRequestAt = now
            val type = when (modelYear) {
                ModelYear.MY18 -> PacketType.START_RESP_MY18
                ModelYear.MY24 -> PacketType.START_RESP_MY24
                else -> PacketType.START_RESP_MY14
            }
            log.warn("Repeated encoding errors — asking the car for a new session key")
            send(Packet(type, ack = 0, register = 0x01, data = byteArrayOf(0)))
        }
        // Everything we sent recently with the wrong key was dropped by the car:
        // resend the latest unacknowledged acks (the car may be waiting for them),
        // then the pending command write, using the corrected key.
        val recentAcks = recentSends.filter { now - it.first < 1_500 && it.second.ack == 1 }
            .map { it.second }.distinctBy { it.register }.takeLast(4)
        recentSends.clear()
        for (ack in recentAcks) send(ack)
        val j = job
        if (j != null && j.awaitingAck) {
            if (j.attempts >= config.maxWriteAttempts + 2) {
                finishJob(j, CommandResult.Failure(j.command, "Car kept rejecting the command encoding"))
            } else {
                sendStep(j, retry = true)
            }
        }
    }

    // ------------------------------------------------------------------- jobs

    private fun enqueue(command: CarCommand, callback: ((CommandResult) -> Unit)?, silent: Boolean) {
        val j = Job(command, callback, clock.monoMs(), silent)
        queue.addLast(j)
        if (!silent) log.add(LogKind.CMD, "Queued: ${command.label}" + if (phase != SessionPhase.READY) " (waiting for session)" else "")
        pumpJobs(clock.monoMs())
    }

    private fun pumpJobs(now: Long) {
        if (phase != SessionPhase.READY) return
        val j = job
        if (j != null) {
            val until = j.awaitUntil
            val resume = j.resumeAt
            if (until != null) {
                if (now >= until) {
                    // The car acked the write but never reported its effect. Carry on with
                    // the remaining writes (e.g. "save settings", like the official app),
                    // but remember to report the command as unconfirmed.
                    j.awaitUntil = null
                    val w = j.current
                    log.warn("The car never reported the result of ${describeWrite(w)} (waited ${w.awaitMs / 1000.0}s) — continuing")
                    if (j.unconfirmed == null) j.unconfirmed = w.unconfirmedMessage
                    stepSettled(j)
                }
            } else if (resume != null) {
                if (now >= resume) {
                    j.resumeAt = null
                    advance(j)
                }
            } else if (j.awaitingAck && now > j.deadline) {
                if (j.attempts < config.maxWriteAttempts) {
                    log.warn("No ack for ${describeWrite(j.current)} — retrying (${j.attempts + 1}/${config.maxWriteAttempts})")
                    sendStep(j, retry = true)
                } else {
                    finishJob(j, CommandResult.Failure(j.command, "The car did not confirm \"${j.command.label}\""))
                }
            }
            return
        }
        val next = queue.firstOrNull() ?: return
        val my = if (modelYear != ModelYear.UNKNOWN) modelYear else config.fallbackModelYear
        // Commands that echo registers back wait briefly for the car's initial dump.
        val missing = next.command.requiredRegisters(my).filter { it !in seenThisSession }
        if (missing.isNotEmpty() && now - readyAt < config.registerWaitMs) return
        queue.removeFirst()
        next.writes = next.command.toWrites(my, state)
        if (next.writes.isEmpty()) {
            finishJob(next, CommandResult.Success(next.command, 0))
            return
        }
        job = next
        sendStep(next, retry = false)
    }

    private fun sendStep(j: Job, retry: Boolean) {
        val w = j.current
        j.attempts = if (retry) j.attempts + 1 else 1
        j.awaitingAck = true
        j.deadline = clock.monoMs() + config.writeTimeoutMs
        if (!j.silent || retry) log.add(LogKind.CMD, (if (retry) "Retry " else "Send ") + describeWrite(w))
        send(Packet(PacketType.SEND, ack = 0, register = w.register, data = w.data))
    }

    private fun onStepAcked(j: Job) {
        j.awaitingAck = false
        val w = j.current
        val confirmed = w.awaitState
        if (confirmed != null && !confirmed(state)) {
            // Acked, but the car has not reported the effect yet: wait for a notification
            // that does (checkAwaitedState) or for the deadline (pumpJobs).
            j.awaitUntil = clock.monoMs() + w.awaitMs
            if (!j.silent) log.add(LogKind.CMD, "Waiting up to ${w.awaitMs / 1000.0}s for the car to report the result of ${describeWrite(w)}")
            return
        }
        stepSettled(j)
    }

    /** Called after every notification: does it confirm the step the current job waits on? */
    private fun checkAwaitedState() {
        val j = job ?: return
        if (j.awaitUntil == null) return
        if (j.current.awaitState?.invoke(state) != true) return
        j.awaitUntil = null
        if (!j.silent) log.add(LogKind.CMD, "Car reported the result of ${describeWrite(j.current)}")
        stepSettled(j)
    }

    /** The current step is acked (and confirmed, or gave up waiting): pause if asked, then move on. */
    private fun stepSettled(j: Job) {
        val w = j.current
        if (w.pauseAfterMs > 0) {
            j.resumeAt = clock.monoMs() + w.pauseAfterMs
        } else {
            advance(j)
        }
    }

    private fun advance(j: Job) {
        j.step++
        if (j.step >= j.writes.size) {
            val unconfirmed = j.unconfirmed
            finishJob(
                j,
                if (unconfirmed != null) CommandResult.Failure(j.command, unconfirmed, retryable = false)
                else CommandResult.Success(j.command, clock.monoMs() - j.queuedAt),
            )
        } else {
            sendStep(j, retry = false)
        }
    }

    private fun finishJob(j: Job, result: CommandResult) {
        if (job === j) job = null
        when (result) {
            is CommandResult.Success -> if (!j.silent) log.add(LogKind.CMD, "Done: ${j.command.label} (${result.tookMs} ms)")
            is CommandResult.Failure -> log.add(LogKind.ERROR, "Failed: ${j.command.label} — ${result.reason}")
        }
        j.callback?.invoke(result)
        pumpJobs(clock.monoMs())
    }

    // ------------------------------------------------------------------- send

    private fun send(p: Packet) {
        val xor = when {
            PacketType.isUnencoded(p.type) -> 0
            else -> key.sendKey(advance = p.type == PacketType.SEND)
        }
        val bytes = p.encode(xor)
        if (p.type == PacketType.SEND) {
            recentSends.addLast(clock.monoMs() to p)
            while (recentSends.size > 16) recentSends.removeFirst()
        }
        if (p.type != PacketType.PING_REQ || log.recordPings) {
            log.add(LogKind.TX, describe(p, incoming = false), bytes.toHex(), xor)
        }
        transmit(bytes)
    }

    private fun sendPing() {
        lastPingAt = clock.monoMs()
        send(Packet(PacketType.PING_REQ, ack = 0, register = pingSeq, data = byteArrayOf(0)))
        pingSeq = if (pingSeq >= 0x63) 0 else pingSeq + 1
    }

    // ------------------------------------------------------------- describing

    private fun describe(p: Packet, incoming: Boolean): String = when (p.type) {
        PacketType.RESP -> if (p.ack == 0) "notify ${Reg.readName(p.register)} = ${p.dataHex()}"
        else "car confirmed ${Reg.writeName(p.register)}"
        PacketType.SEND -> if (p.ack == 1) "ack ${Reg.readName(p.register)}"
        else "set ${Reg.writeName(p.register)} = ${p.dataHex()}"
        PacketType.PING_REQ -> "ping #${p.register}"
        PacketType.PING_RESP -> "pong #${p.register}"
        else -> p.toString()
    }

    private fun describeWrite(w: RegisterWrite) = "${Reg.writeName(w.register)} = ${w.data.toHex()}"

    private fun logStateDiff(before: CarState, after: CarState) {
        fun sem(s: CarState) = s.copy(registers = emptyMap(), updatedAtMs = null, carClockReadAtMs = null, carClock = null)
        val a = sem(before)
        val b = sem(after)
        if (a == b) return
        val changes = buildList {
            if (a.batteryPercent != b.batteryPercent) add("battery ${b.batteryPercent}%")
            if (a.charging != b.charging) add(if (b.charging == true) "charging" else "not charging")
            if (a.chargeMinutesRemaining != b.chargeMinutesRemaining && b.chargeMinutesRemaining != null) add("${b.chargeMinutesRemaining} min to full")
            if (a.plug != b.plug) add("plug ${b.plug.name.lowercase()}")
            if (a.locked != b.locked) add(if (b.locked == true) "locked" else "unlocked")
            if (a.doors != b.doors) add(b.doors?.openList()?.takeIf { it.isNotEmpty() }?.joinToString(prefix = "open: ") ?: "all doors closed")
            if (a.headlights != b.headlights) add("headlights ${onOff(b.headlights)}")
            if (a.parkingLights != b.parkingLights) add("parking lights ${onOff(b.parkingLights)}")
            if (a.hazardLights != b.hazardLights) add("hazards ${onOff(b.hazardLights)}")
            if (a.interiorLights != b.interiorLights) add("interior light ${onOff(b.interiorLights)}")
            if (a.ignition != b.ignition) add("ignition ${b.ignition.name.lowercase()}")
            if (a.climate != b.climate) add("climate ${b.climate.state.name.lowercase()} ${b.climate.mode?.label ?: ""} ${b.climate.duration?.minutes ?: ""}".trim())
            if (a.vin != b.vin) add("VIN ${b.vin}")
            if (a.registrations != b.registrations) add("${b.registrations} registered phone(s)")
            if (a.ecuVersion != b.ecuVersion) add("ECU ${b.ecuVersion}")
            if (a.settingsStatus != b.settingsStatus) {
                add("settings status ${b.settingsStatus}" +
                    if (VehicleSettingsCatalog.statusAllowsChanges(b.settingsStatus)) "" else " (car refuses changes)")
            }
            if (a.settings != b.settings) {
                for ((id, s) in b.settings.toSortedMap()) {
                    if (a.settings[id]?.value == s.value) continue
                    val def = VehicleSettingsCatalog.def(id)
                    add(if (def != null) "setting ${def.name}: ${VehicleSettingsCatalog.optionLabel(id, s.value, s)}" else "setting #$id = ${s.value}")
                }
            }
        }
        if (changes.isNotEmpty()) log.add(LogKind.STATE, changes.joinToString(", "))
    }

    private fun onOff(b: Boolean?) = when (b) {
        true -> "on"; false -> "off"; null -> "?"
    }
}
