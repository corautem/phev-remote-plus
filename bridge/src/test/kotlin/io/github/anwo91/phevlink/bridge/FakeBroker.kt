package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.bridge.MqttClient.Packets
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny MQTT 3.1.1 broker for tests: login check, retained messages, subscriptions with + and #, last will on an
 * unclean drop, PINGRESP. Records everything published.
 */
class FakeBroker(private val user: String? = null, private val pass: String? = null) : AutoCloseable {
    data class Msg(val topic: String, val payload: String, val retain: Boolean)

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val published = CopyOnWriteArrayList<Msg>()
    val retained = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile var connects = 0

    inner class Client(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val subs = CopyOnWriteArrayList<String>()
        var will: Msg? = null
        var clean = false

        fun send(bytes: ByteArray) = synchronized(out) { runCatching { out.write(bytes); out.flush() } }

        fun run() {
            val input = DataInputStream(socket.getInputStream())
            try {
                val c = Packets.read(input)
                if (c.type != Packets.CONNECT) return
                val b = c.body
                var i = 2 + ((b[0].toInt() and 0xff) shl 8 or (b[1].toInt() and 0xff)) + 1
                val flags = b[i].toInt() and 0xff
                i += 3
                fun str(): String {
                    val n = (b[i].toInt() and 0xff) shl 8 or (b[i + 1].toInt() and 0xff)
                    val s = String(b, i + 2, n, Charsets.UTF_8)
                    i += 2 + n
                    return s
                }
                str() // client id
                if (flags and 0x04 != 0) will = Msg(str(), str(), flags and 0x20 != 0)
                val u = if (flags and 0x80 != 0) str() else null
                val p = if (flags and 0x40 != 0) str() else null
                if (user != null && (u != user || p != pass)) {
                    send(byteArrayOf(0x20, 2, 0, 4))
                    return
                }
                connects++
                send(byteArrayOf(0x20, 2, 0, 0))
                while (true) {
                    val pk = Packets.read(input)
                    when (pk.type) {
                        Packets.PUBLISH -> {
                            val m = Packets.parsePublish(pk.flags, pk.body)
                            deliver(Msg(m.topic, m.payload, m.retain))
                        }
                        Packets.SUBSCRIBE -> {
                            val body = pk.body
                            var j = 2
                            val got = ArrayList<String>()
                            while (j < body.size) {
                                val n = (body[j].toInt() and 0xff) shl 8 or (body[j + 1].toInt() and 0xff)
                                got += String(body, j + 2, n, Charsets.UTF_8)
                                j += 2 + n + 1
                            }
                            subs += got
                            send(Packets.packet(Packets.SUBACK, 0, byteArrayOf(body[0], body[1]) + ByteArray(got.size)))
                            retained.forEach { (t, v) -> if (got.any { matches(it, t) }) send(Packets.publish(t, v.toByteArray(), true)) }
                        }
                        Packets.PINGREQ -> send(byteArrayOf(0xD0.toByte(), 0))
                        Packets.DISCONNECT -> { clean = true; return }
                    }
                }
            } catch (e: Exception) {
                // dropped
            } finally {
                clients.remove(this)
                runCatching { socket.close() }
                if (!clean) will?.let { deliver(it) }
            }
        }
    }

    init {
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                val c = Client(s)
                clients += c
                Thread { c.run() }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    /** As if another client (Home Assistant) published. */
    fun publish(topic: String, payload: String, retain: Boolean = false) = deliver(Msg(topic, payload, retain))

    private fun deliver(m: Msg) {
        published += m
        if (m.retain) { if (m.payload.isEmpty()) retained.remove(m.topic) else retained[m.topic] = m.payload }
        clients.forEach { c -> if (c.subs.any { matches(it, m.topic) }) c.send(Packets.publish(m.topic, m.payload.toByteArray(), false)) }
    }

    /** Drops every client without a goodbye (their wills go out). */
    fun dropClients() = clients.forEach { runCatching { it.socket.close() } }

    fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    override fun close() {
        runCatching { server.close() }
        dropClients()
    }

    companion object {
        fun matches(filter: String, topic: String): Boolean {
            val f = filter.split('/')
            val t = topic.split('/')
            for (i in f.indices) {
                if (f[i] == "#") return true
                if (i >= t.size) return false
                if (f[i] != "+" && f[i] != t[i]) return false
            }
            return f.size == t.size
        }
    }
}
