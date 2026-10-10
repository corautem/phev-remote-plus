package io.github.anwo91.phevlink.obd

import io.github.anwo91.phevlink.core.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opening a link failed for a reason the UI can explain. [ObdRepository] turns [reason] into a localised message.
 */
class ObdConnectException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** The phone has no Bluetooth adapter. */
        NO_BLUETOOTH,

        /** Bluetooth is switched off. */
        BLUETOOTH_OFF,

        /** BLUETOOTH_CONNECT (Android 12+) is not granted. */
        PERMISSION,

        /** The stored address is not a valid MAC address / host:port. */
        BAD_ADDRESS,

        /** The Bluetooth adapter didn't accept the connection: off, out of range, or busy with another phone/app. */
        UNREACHABLE,

        /** BLE: connected, but no GATT service with a notify and a write characteristic. */
        NO_UART_SERVICE,

        /** Wi-Fi: no TCP connection to host:port (phone not on the adapter's Wi-Fi?). */
        WIFI_UNREACHABLE,

        /** Took longer than the overall connect budget. */
        TIMEOUT,

        /** Classic: pairing was cancelled, refused (wrong PIN) or never finished. */
        PAIRING_FAILED,
    }

    /** Retrying immediately can help (flaky radio) — not for missing hardware, permission, or Bluetooth off. */
    val retryable: Boolean
        get() = reason != Reason.NO_BLUETOOTH && reason != Reason.BLUETOOTH_OFF &&
            reason != Reason.PERMISSION && reason != Reason.BAD_ADDRESS && reason != Reason.PAIRING_FAILED
}

/**
 * Incoming bytes of one link: producers [offer] chunks from any thread (reader thread, GATT binder thread),
 * the ELM driver takes them with [receive]. After [fail], buffered chunks are still delivered, then [receive]
 * throws the failure ([IOException]).
 *
 * A chunk that was taken from the channel at the moment a [receive] timed out is not lost: the channel's
 * undelivered-element hook puts it into a stash that the next [receive] returns first.
 */
internal class ByteInbox {
    private val stash = ConcurrentLinkedQueue<ByteArray>()
    private val channel = Channel<ByteArray>(Channel.UNLIMITED, onUndeliveredElement = { stash.add(it) })

    @Volatile
    var failure: IOException? = null
        private set

    /** Queues [bytes] (ignored when empty or after [fail]). Never blocks. */
    fun offer(bytes: ByteArray) {
        if (bytes.isNotEmpty()) channel.trySend(bytes)
    }

    /** Marks the link as failed; the first failure wins. */
    fun fail(e: IOException) {
        synchronized(this) {
            if (failure == null) failure = e
        }
        channel.close(e)
    }

    /** Next chunk within [timeoutMs], null on timeout; throws [IOException] once the link has failed and is drained. */
    suspend fun receive(timeoutMs: Long): ByteArray? {
        stash.poll()?.let { return it }
        try {
            if (timeoutMs <= 0) {
                val r = channel.tryReceive()
                if (r.isSuccess) return r.getOrNull()
                if (r.isClosed) throw closedError()
                return null
            }
            return withTimeoutOrNull(timeoutMs) { channel.receive() }
        } catch (e: ClosedReceiveChannelException) {
            throw closedError()
        }
    }

    private fun closedError(): IOException = failure ?: IOException("OBD link closed")
}

/**
 * Runs a blocking connect ([connect]) on [Dispatchers.IO] with a hard [timeoutMs]. A watchdog calls [abort]
 * (closing the socket, which makes the blocking call throw) on timeout **and** when the caller is cancelled,
 * so a hanging connect never outlives its coroutine.
 */
internal suspend fun connectBlocking(timeoutMs: Long, abort: () -> Unit, connect: () -> Unit) {
    val connected = AtomicBoolean(false)
    val timedOut = AtomicBoolean(false)
    coroutineScope {
        val watchdog = launch(Dispatchers.Default) {
            try {
                delay(timeoutMs)
                timedOut.set(true)
            } finally {
                if (!connected.get()) runCatching { abort() }
            }
        }
        try {
            withContext(Dispatchers.IO) { connect() }
            connected.set(true)
        } catch (e: IOException) {
            // Cancelled: the IOException only comes from our own abort — report the cancellation instead.
            ensureActive()
            if (timedOut.get()) throw IOException("no connection within $timeoutMs ms", e)
            throw e
        } finally {
            watchdog.cancel()
        }
    }
    if (timedOut.get()) {
        // The watchdog fired just as the connect succeeded: it has closed the socket.
        runCatching { abort() }
        throw IOException("no connection within $timeoutMs ms")
    }
}

/**
 * Byte pipe over a connected stream socket (Bluetooth RFCOMM or TCP).
 *
 * - A dedicated daemon thread blocks in [InputStream.read] and queues chunks into a [ByteInbox]; end of stream
 *   or a read error fail the inbox, so [receive] throws [IOException].
 * - Writes run on a single-thread executor and are awaited with a timeout, so a stalled socket never blocks a
 *   coroutine thread; a write still stuck after [STUCK_WRITE_MS] marks the link dead on the next [send].
 * - [close] is idempotent and closes the socket (which also ends the reader thread).
 *
 * Call [start] once after construction.
 */
internal class StreamPipe(
    private val label: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val closeTransport: () -> Unit,
    private val log: DiagLog,
) {
    private val inbox = ByteInbox()
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile private var writing = false
    @Volatile private var writeStartNs = 0L
    @Volatile private var bytesIn = 0L
    @Volatile private var bytesOut = 0L

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "obd-$label-writer").apply { isDaemon = true }
    }
    private val reader = Thread({ readLoop() }, "obd-$label-reader").apply { isDaemon = true }

    fun start() {
        if (started.compareAndSet(false, true)) reader.start()
    }

    private fun readLoop() {
        val buf = ByteArray(READ_BUFFER)
        var error: IOException? = null
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) {
                    error = IOException("$label connection closed by the adapter")
                    break
                }
                if (n > 0) {
                    bytesIn += n
                    inbox.offer(buf.copyOf(n))
                }
            }
        } catch (e: IOException) {
            error = IOException("$label connection lost (${e.message})", e)
        } catch (e: RuntimeException) {
            error = IOException("$label connection failed (${e.javaClass.simpleName}: ${e.message})", e)
        }
        if (!closed.get()) {
            val err = error ?: IOException("$label connection closed")
            log.warn("OBD: ${err.message} — after $bytesIn bytes in, $bytesOut bytes out")
            fail(err)
        }
    }

    suspend fun send(bytes: ByteArray) {
        if (closed.get()) throw inbox.failure ?: IOException("$label link is closed")
        inbox.failure?.let { throw it }
        if (writing && System.nanoTime() - writeStartNs > STUCK_WRITE_MS * 1_000_000L) {
            val e = IOException("$label adapter stopped accepting data (write blocked for more than ${STUCK_WRITE_MS / 1000} s)")
            log.warn("OBD: ${e.message}")
            fail(e)
            throw e
        }
        val done = CompletableDeferred<Unit>()
        try {
            writer.execute {
                writeStartNs = System.nanoTime()
                writing = true
                try {
                    output.write(bytes)
                    output.flush()
                    bytesOut += bytes.size
                    done.complete(Unit)
                } catch (t: Throwable) {
                    done.completeExceptionally(t)
                } finally {
                    writing = false
                }
            }
        } catch (e: RejectedExecutionException) {
            throw IOException("$label link is closed", e)
        }
        val ok = try {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val err = IOException("$label write failed (${e.message})", e)
            fail(err)
            throw err
        } catch (e: Exception) {
            val err = IOException("$label write failed (${e.javaClass.simpleName}: ${e.message})", e)
            fail(err)
            throw err
        }
        if (ok == null) {
            val err = IOException("$label write not finished within ${WRITE_TIMEOUT_MS / 1000} s")
            log.warn("OBD: ${err.message}")
            fail(err)
            throw err
        }
    }

    suspend fun receive(timeoutMs: Long): ByteArray? = inbox.receive(timeoutMs)

    /** Records [e] as the reason and closes everything. */
    private fun fail(e: IOException) {
        inbox.fail(e)
        close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        inbox.fail(IOException("$label link closed"))
        runCatching { closeTransport() }
        writer.shutdownNow()
    }

    companion object {
        private const val READ_BUFFER = 1024
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val STUCK_WRITE_MS = 4_000L
    }
}
