package io.github.anwo91.phevlink.core.session

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.protocol.ModelYear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Opens a TCP socket to the car. Android supplies one bound to the car's Wi-Fi network. */
fun interface SocketConnector {
    @Throws(IOException::class)
    suspend fun connect(): Socket
}

/** Plain TCP connector (bridge, tests, emulator). */
class TcpConnector(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 5_000,
    private val socketFactory: () -> Socket = { Socket() },
) : SocketConnector {
    override suspend fun connect(): Socket = withContext(Dispatchers.IO) {
        val s = socketFactory()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s
        } catch (e: Throwable) {
            runCatching { s.close() }
            throw e
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.8.46"
        const val DEFAULT_PORT = 8080
    }
}

sealed interface LinkStatus {
    /** Not trying to connect. */
    data class Idle(val lastError: String? = null) : LinkStatus
    data class Connecting(val attempt: Int) : LinkStatus
    /** TCP is up, waiting for the car to start the session. */
    data object Handshaking : LinkStatus
    data class Connected(val sinceMs: Long, val modelYear: ModelYear) : LinkStatus
    /** Between attempts. */
    data class Retrying(val retryAtMs: Long, val attempt: Int, val lastError: String) : LinkStatus

    val isConnected: Boolean get() = this is Connected
}

data class ConnectionConfig(
    val session: SessionConfig = SessionConfig(),
    val connectTimeoutMs: Long = 8_000,
    /** Delay before attempt n+1 after n consecutive failures. */
    val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000),
    /** A session that lasted at least this long resets the backoff. */
    val stableAfterMs: Long = 30_000,
    val tickMs: Long = 100,
)

/**
 * Keeps a [PhevSession] alive over TCP: connects, runs the session, and on any
 * failure reconnects with backoff. Commands sent while disconnected wait for
 * the next session and are transparently resent if the link drops mid-command.
 */
class PhevConnection(
    private val connector: SocketConnector,
    private val log: DiagLog,
    private val scope: CoroutineScope,
    private val config: ConnectionConfig = ConnectionConfig(),
    initialState: CarState = CarState(),
    private val clock: SessionClock = SessionClock.System,
) {
    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Idle())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _car = MutableStateFlow(initialState)
    val car: StateFlow<CarState> = _car.asStateFlow()

    private class Exec(val command: CarCommand, val result: CompletableDeferred<CommandResult>) {
        val abandoned = AtomicBoolean(false)
    }

    private val commands = Channel<Exec>(Channel.UNLIMITED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    @Volatile private var running = false
    /** Terminal: once closed, this instance never connects again. */
    @Volatile private var closed = false

    val isRunning: Boolean get() = running

    /** Start connecting (no-op if already running). */
    @Synchronized
    fun start() {
        if (closed) {
            log.warn("Ignored start() on a closed connection")
            return
        }
        if (loop?.isActive == true) return
        running = true
        loop = scope.launch(Dispatchers.IO) { runLoop() }
    }

    /**
     * Stop and disconnect. Pending commands fail. With [terminal] the instance
     * can never be started again (used when its owner discards it).
     */
    suspend fun stop(reason: String = "Stopped", terminal: Boolean = false) {
        val j = synchronized(this) {
            running = false
            if (terminal) closed = true
            loop.also { loop = null }
        }
        j?.cancelAndJoin()
        drainCommands("Disconnected: $reason")
        _status.value = LinkStatus.Idle()
        log.link("Link stopped: $reason")
    }

    /** Skip the current backoff wait and try again immediately (e.g. the network came back). */
    fun reconnectNow() {
        wake.trySend(Unit)
    }

    /**
     * Run a command, connecting first if needed. Survives reconnects until
     * [timeoutMs] runs out.
     */
    suspend fun execute(command: CarCommand, timeoutMs: Long = 45_000): CommandResult {
        if (closed) return CommandResult.Failure(command, "Link closed", retryable = true)
        start()
        // Waiting for the car to confirm the effect (climate running, setting changed) is not "unreachable".
        val deadline = clock.monoMs() + timeoutMs + command.confirmMs
        var last: CommandResult.Failure? = null
        while (true) {
            val remaining = deadline - clock.monoMs()
            if (remaining <= 0) return last ?: CommandResult.Failure(command, "Could not reach the car in time")
            val exec = Exec(command, CompletableDeferred())
            commands.send(exec)
            val r = withTimeoutOrNull(remaining) { exec.result.await() }
            if (r == null) {
                exec.abandoned.set(true)
                return last?.copy(reason = "Could not reach the car in time (${last.reason})")
                    ?: CommandResult.Failure(command, "Could not reach the car in time")
            }
            if (r is CommandResult.Failure && r.retryable && running) {
                last = r
                continue
            }
            return r
        }
    }

    private fun drainCommands(reason: String) {
        while (true) {
            val e = commands.tryReceive().getOrNull() ?: break
            e.result.complete(CommandResult.Failure(e.command, reason))
        }
    }

    // ------------------------------------------------------------------------

    private suspend fun runLoop() {
        var failures = 0
        try {
            while (scope.isActive && running) {
                _status.value = LinkStatus.Connecting(failures + 1)
                log.link("Connecting (attempt ${failures + 1})")
                var error: String? = null
                val socket: Socket? = try {
                    withTimeout(config.connectTimeoutMs) { connector.connect() }
                } catch (e: CancellationException) {
                    if (!running || !scope.isActive) throw e
                    error = "Connect timed out"
                    null
                } catch (e: Exception) {
                    error = describe(e)
                    null
                }
                if (socket == null) {
                    failures++
                    backoff(failures, error ?: "Connect failed")
                    continue
                }
                val started = clock.monoMs()
                val reason = runSession(socket)
                if (!running) break
                if (clock.monoMs() - started >= config.stableAfterMs) failures = 0
                failures++
                backoff(failures, reason)
            }
        } finally {
            if (_status.value !is LinkStatus.Idle) _status.value = LinkStatus.Idle()
        }
    }

    private suspend fun backoff(failures: Int, reason: String) {
        val d = config.backoffMs[(failures - 1).coerceIn(0, config.backoffMs.lastIndex)]
        log.link("Retrying in ${d / 1000.0}s — $reason")
        _status.value = LinkStatus.Retrying(clock.wallMs() + d, failures, reason)
        val t0 = clock.monoMs()
        withTimeoutOrNull(d) { wake.receive() }
        val took = clock.monoMs() - t0
        if (took > d + 5_000) {
            // Field log: a 1 s wait that ended 2 minutes later — the app was frozen in the background.
            log.warn("The ${d / 1000.0}s wait took ${took / 1000} s — Android paused the app (background or battery restrictions)")
        }
    }

    private sealed interface Msg {
        class Bytes(val data: ByteArray) : Msg
        class Closed(val reason: String) : Msg
        data object Tick : Msg
    }

    private suspend fun runSession(socket: Socket): String = coroutineScopeCompat { sessionScope ->
        val inbox = Channel<Msg>(Channel.UNLIMITED)
        val out = Channel<ByteArray>(Channel.UNLIMITED)
        var closedReason: String? = null

        val listener = object : SessionListener {
            override fun onPhase(phase: SessionPhase) {
                _status.value = when (phase) {
                    SessionPhase.HANDSHAKE -> LinkStatus.Handshaking
                    SessionPhase.READY -> LinkStatus.Connected(clock.wallMs(), modelYearOf())
                    SessionPhase.CLOSED -> _status.value
                }
            }

            override fun onState(state: CarState) {
                _car.value = state
            }

            override fun onClosed(reason: String) {
                closedReason = reason
            }
        }
        lateinit var session: PhevSession
        session = PhevSession(
            transmit = { out.trySend(it) },
            listener = listener,
            log = log,
            clock = clock,
            config = config.session,
            initialState = _car.value,
        )
        modelYearOf = { session.modelYear }

        val reader = sessionScope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            val input = socket.getInputStream()
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) {
                        inbox.send(Msg.Closed("The car closed the connection")); break
                    }
                    if (n > 0) inbox.send(Msg.Bytes(buf.copyOf(n)))
                }
            } catch (e: IOException) {
                inbox.trySend(Msg.Closed("Read failed: ${describe(e)}"))
            }
        }
        val writer = sessionScope.launch(Dispatchers.IO) {
            val output = socket.getOutputStream()
            try {
                for (bytes in out) {
                    output.write(bytes)
                    output.flush()
                }
            } catch (e: IOException) {
                inbox.trySend(Msg.Closed("Write failed: ${describe(e)}"))
            }
        }
        val ticker = sessionScope.launch {
            while (isActive) {
                delay(config.tickMs)
                inbox.send(Msg.Tick)
            }
        }

        _status.value = LinkStatus.Handshaking
        session.start()
        try {
            while (session.phase != SessionPhase.CLOSED) {
                try {
                    select<Unit> {
                        inbox.onReceive { msg ->
                            when (msg) {
                                is Msg.Bytes -> session.onBytes(msg.data)
                                is Msg.Tick -> session.tick()
                                is Msg.Closed -> session.onTransportClosed(msg.reason)
                            }
                        }
                        commands.onReceive { exec ->
                            if (!exec.abandoned.get()) session.execute(exec.command) { exec.result.complete(it) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A bug must not take the whole link down silently: log it and reconnect.
                    log.error("Internal error: ${e.javaClass.simpleName}: ${e.message}")
                    session.close("Internal error (${e.javaClass.simpleName})")
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (session.phase != SessionPhase.CLOSED) session.close("Connection stopped")
                ticker.cancel()
                // Let the writer flush anything queued (e.g. a final ack), then close.
                out.close()
                withTimeoutOrNull(500) { writer.join() }
                runCatching { socket.close() }
                reader.cancel()
                writer.cancel()
            }
        }
        closedReason ?: "Session ended"
    }

    @Volatile private var modelYearOf: () -> ModelYear = { ModelYear.UNKNOWN }

    private fun describe(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("ECONNREFUSED") || msg.contains("refused", true) -> "Car refused the connection ($msg)"
            msg.contains("EHOSTUNREACH") || msg.contains("unreachable", true) -> "Car not reachable — not on its Wi-Fi? ($msg)"
            msg.contains("ENETUNREACH") -> "No route to the car's network ($msg)"
            msg.contains("timed out", true) || msg.contains("timeout", true) -> "Timed out reaching the car ($msg)"
            else -> msg
        }
    }
}

/** coroutineScope { } that hands its scope to the block (keeps the code above readable). */
private suspend fun <T> coroutineScopeCompat(block: suspend (CoroutineScope) -> T): T =
    kotlinx.coroutines.coroutineScope { block(this) }
