package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps one MQTT session to the broker: connects, runs [onConnected] (announce, subscribe), reconnects after a drop
 * (2, 5, 10, 30, then every 60 s; 5 minutes after a wrong password, so the broker isn't hammered), and stops on
 * [stop]. Everything it does is in the bridge log, prefixed "Smart home:".
 */
class MqttLink(
    private val log: DiagLog,
    private val connector: (MqttClient.Options) -> MqttClient.Session = { MqttClient.connect(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Phase { OFF, CONNECTING, CONNECTED, ERROR }

    data class Status(val phase: Phase, val broker: String? = null, val error: String? = null, val sinceMs: Long = 0L, val sent: Long = 0, val received: Long = 0) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "phase" to phase.name.lowercase(), "broker" to broker, "error" to error, "sinceMs" to sinceMs,
            "sent" to sent, "received" to received,
        )
    }

    @Volatile var onConnected: () -> Unit = {}
    @Volatile var onMessage: (String, String, Boolean) -> Unit = { _, _, _ -> }

    @Volatile private var phase = Phase.OFF
    @Volatile private var broker: String? = null
    @Volatile private var error: String? = null
    @Volatile private var sinceMs = 0L
    private val sent = AtomicLong()
    private val received = AtomicLong()
    @Volatile private var session: MqttClient.Session? = null
    @Volatile private var generation = 0
    private var thread: Thread? = null
    private val wake = Object()

    val status: Status get() = Status(phase, broker, error, sinceMs, sent.get(), received.get())
    val connected: Boolean get() = phase == Phase.CONNECTED

    @Synchronized
    fun start(o: MqttClient.Options) {
        stopLocked("settings changed")
        val gen = ++generation
        broker = "${o.host}:${o.port}"
        set(Phase.CONNECTING, null)
        thread = Thread({ loop(gen, o) }, "mqtt-link").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop(why: String) {
        stopLocked(why)
        broker = null
        set(Phase.OFF, null)
    }

    private fun stopLocked(why: String) {
        if (thread == null) return
        generation++
        session?.let {
            log.link("Smart home: disconnecting from the broker ($why)")
            runCatching { it.close() }
        }
        session = null
        synchronized(wake) { wake.notifyAll() }
        thread = null
    }

    /** Waits until the link is connected or has failed (up to [ms]); for the app's "Connect" button. */
    fun awaitOutcome(ms: Long): Status {
        val until = now() + ms
        while (now() < until && phase == Phase.CONNECTING) Thread.sleep(100)
        return status
    }

    /** Sends when connected; false (and nothing sent) when not — the state is sent again on the next connect anyway. */
    fun publish(topic: String, payload: String, retain: Boolean): Boolean {
        val s = session ?: return false
        return try {
            s.publish(topic, payload, retain)
            sent.incrementAndGet()
            true
        } catch (e: IOException) {
            false
        }
    }

    fun subscribe(topics: List<String>) {
        runCatching { session?.subscribe(topics) }
    }

    private fun loop(gen: Int, o: MqttClient.Options) {
        var fails = 0
        while (gen == generation) {
            // Retries keep showing the last error (not a flickering "connecting"); start() shows "connecting" once.
            var waitMs: Long
            try {
                val s = connector(o)
                val done = CountDownLatch(1)
                var why = "?"
                s.onMessage = { t, p, r ->
                    received.incrementAndGet()
                    onMessage(t, p, r)
                }
                s.onClosed = { w -> why = w; done.countDown() }
                synchronized(this) {
                    if (gen != generation) {
                        runCatching { s.close() }
                        return
                    }
                    session = s
                }
                s.start()
                set(Phase.CONNECTED, null)
                log.link("Smart home: connected to the broker at ${o.host}:${o.port}" + (o.username?.let { " as $it" } ?: " (no login)") + (if (fails > 0) " after $fails failed tries" else ""))
                fails = 0
                runCatching { onConnected() }.onFailure { log.warn("Smart home: announcing failed: ${it.message}") }
                done.await()
                session = null
                if (gen != generation) return
                log.warn("Smart home: lost the broker — $why")
                set(Phase.ERROR, why)
                waitMs = 2_000
            } catch (e: MqttClient.Refused) {
                fails++
                val auth = e.code == 4 || e.code == 5
                waitMs = if (auth) 5 * 60_000L else BACKOFF_S[(fails - 1).coerceIn(0, BACKOFF_S.lastIndex)] * 1000L
                log.warn("Smart home: the broker at ${o.host}:${o.port} said no — ${e.message}; trying again in ${waitMs / 1000} s")
                set(Phase.ERROR, e.message)
            } catch (e: IOException) {
                fails++
                waitMs = BACKOFF_S[(fails - 1).coerceIn(0, BACKOFF_S.lastIndex)] * 1000L
                val msg = describe(e)
                // Quiet after a few: one line a minute is enough while the broker is away.
                if (fails <= 3 || fails % 10 == 0) log.warn("Smart home: can't reach the broker at ${o.host}:${o.port} — $msg; trying again in ${waitMs / 1000} s (try $fails)")
                set(Phase.ERROR, msg)
            }
            synchronized(wake) {
                if (gen == generation) runCatching { wake.wait(waitMs) }
            }
        }
    }

    private fun set(p: Phase, err: String?) {
        if (p != phase) sinceMs = now()
        phase = p
        error = err
    }

    companion object {
        private val BACKOFF_S = longArrayOf(2, 5, 10, 30, 60)

        fun describe(e: IOException): String {
            val m = e.message ?: e.javaClass.simpleName
            return when {
                e is java.net.UnknownHostException -> "unknown address \"${e.message}\""
                m.contains("refused", true) -> "nothing answers on that port (is the broker running?)"
                m.contains("timed out", true) || m.contains("no answer", true) -> "no answer (wrong address, or a firewall?)"
                m.contains("unreachable", true) -> "network unreachable"
                else -> m
            }
        }
    }
}
