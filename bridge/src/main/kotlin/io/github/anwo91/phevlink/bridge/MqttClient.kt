package io.github.anwo91.phevlink.bridge

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small MQTT 3.1.1 client — just what the smart-home link needs, without a library: log in (user/password, a "last
 * will"), publish (QoS 0, retained or not), subscribe, keep the line alive, and hand incoming messages over.
 *
 * [connect] opens one session; when it ends (broker gone, network down) [Session.onClosed] says why and the owner
 * connects again (see [MqttLink]).
 */
object MqttClient {
    data class Will(val topic: String, val payload: String, val retain: Boolean = true)

    data class Options(
        val host: String,
        val port: Int = 1883,
        val clientId: String,
        val username: String? = null,
        val password: String? = null,
        val keepAliveSec: Int = 30,
        val will: Will? = null,
        val connectTimeoutMs: Int = 6_000,
    )

    /** The broker said no (CONNACK return code [code]) or the line broke while logging in. */
    class Refused(val code: Int, message: String) : IOException(message)

    /** Words for a CONNACK return code. */
    fun refusal(code: Int): String = when (code) {
        1 -> "the broker doesn't speak MQTT 3.1.1"
        2 -> "the broker refused this client id"
        3 -> "the broker is not available right now"
        4 -> "wrong user name or password"
        5 -> "not allowed to connect (user name and password needed?)"
        else -> "refused (code $code)"
    }

    /** Opens a session (blocking up to the connect timeout); throws [Refused] or an [IOException]. */
    fun connect(o: Options, socketFactory: () -> Socket = { Socket() }): Session {
        val s = socketFactory()
        try {
            s.connect(InetSocketAddress(o.host, o.port), o.connectTimeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = o.connectTimeoutMs
            val out = s.getOutputStream()
            out.write(Packets.connect(o))
            out.flush()
            val input = DataInputStream(s.getInputStream().buffered())
            val (type, _, body) = Packets.read(input)
            if (type != Packets.CONNACK || body.size < 2) throw Refused(-1, "the other end isn't an MQTT broker")
            val rc = body[1].toInt() and 0xff
            if (rc != 0) throw Refused(rc, refusal(rc))
            s.soTimeout = 0
            return Session(s, input, o.keepAliveSec)
        } catch (e: SocketTimeoutException) {
            runCatching { s.close() }
            throw IOException("no answer from ${o.host}:${o.port}")
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
    }

    /**
     * One connected session. Incoming messages go to [onMessage] (topic, payload, retained) on the session's reader
     * thread; [onClosed] runs once when the session ends, with the reason.
     */
    class Session internal constructor(
        private val socket: Socket,
        private val input: DataInputStream,
        private val keepAliveSec: Int,
    ) : Closeable {
        @Volatile var onMessage: (String, String, Boolean) -> Unit = { _, _, _ -> }
        @Volatile var onClosed: (String) -> Unit = {}
        @Volatile var closed = false
            private set
        private val out: OutputStream = socket.getOutputStream()
        private val ids = AtomicInteger(1)
        @Volatile private var lastHeardMs = System.currentTimeMillis()
        private var reader: Thread? = null
        private var pinger: Thread? = null

        /** Starts reading and pinging (after setting [onMessage]/[onClosed]). */
        fun start() {
            reader = Thread({ readLoop() }, "mqtt-read").apply { isDaemon = true; start() }
            pinger = Thread({ pingLoop() }, "mqtt-ping").apply { isDaemon = true; start() }
        }

        fun publish(topic: String, payload: String, retain: Boolean = false) =
            write(Packets.publish(topic, payload.toByteArray(Charsets.UTF_8), retain))

        fun subscribe(topics: List<String>) = write(Packets.subscribe(nextId(), topics))

        /** Polite goodbye: the broker then does NOT send the last will. */
        override fun close() {
            if (closed) return
            runCatching { write(byteArrayOf(Packets.DISCONNECT.shl(4).toByte(), 0)) }
            end("closed")
        }

        private fun nextId(): Int = ids.getAndUpdate { if (it >= 65_535) 1 else it + 1 }

        private fun write(bytes: ByteArray) {
            if (closed) throw IOException("not connected")
            try {
                synchronized(out) {
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: IOException) {
                end("write failed: ${e.message}")
                throw e
            }
        }

        private fun readLoop() {
            try {
                while (!closed) {
                    val (type, flags, body) = Packets.read(input)
                    lastHeardMs = System.currentTimeMillis()
                    when (type) {
                        Packets.PUBLISH -> {
                            val m = Packets.parsePublish(flags, body)
                            if (m.packetId != null) write(Packets.puback(m.packetId))
                            runCatching { onMessage(m.topic, m.payload, m.retain) }
                        }
                        else -> Unit // SUBACK, PUBACK, PINGRESP: nothing to do
                    }
                }
            } catch (e: EOFException) {
                end("the broker closed the connection")
            } catch (e: IOException) {
                end(e.message ?: e.javaClass.simpleName)
            }
        }

        private fun pingLoop() {
            val every = (keepAliveSec * 1000L / 2).coerceAtLeast(1_000)
            while (!closed) {
                try {
                    Thread.sleep(every)
                } catch (e: InterruptedException) {
                    return
                }
                if (closed) return
                if (System.currentTimeMillis() - lastHeardMs > keepAliveSec * 1500L) {
                    end("no answer from the broker for ${keepAliveSec * 3 / 2} s")
                    return
                }
                runCatching { write(byteArrayOf(Packets.PINGREQ.shl(4).toByte(), 0)) }
            }
        }

        private fun end(why: String) {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            runCatching { socket.close() }
            pinger?.interrupt()
            runCatching { onClosed(why) }
        }
    }

    /** MQTT 3.1.1 packets (only what is used here). Public for tests. */
    object Packets {
        const val CONNECT = 1
        const val CONNACK = 2
        const val PUBLISH = 3
        const val PUBACK = 4
        const val SUBSCRIBE = 8
        const val SUBACK = 9
        const val PINGREQ = 12
        const val PINGRESP = 13
        const val DISCONNECT = 14
        private const val MAX_PACKET = 1 shl 20

        data class Publish(val topic: String, val payload: String, val retain: Boolean, val packetId: Int?)
        data class Raw(val type: Int, val flags: Int, val body: ByteArray)

        fun connect(o: Options): ByteArray {
            val vh = ByteArrayOutputStream()
            str(vh, "MQTT")
            vh.write(4) // protocol level 3.1.1
            var flags = 0x02 // clean session
            if (o.will != null) flags = flags or 0x04 or (if (o.will.retain) 0x20 else 0)
            if (!o.username.isNullOrEmpty()) flags = flags or 0x80
            if (!o.username.isNullOrEmpty() && o.password != null) flags = flags or 0x40
            vh.write(flags)
            vh.write(o.keepAliveSec shr 8 and 0xff); vh.write(o.keepAliveSec and 0xff)
            str(vh, o.clientId)
            o.will?.let { str(vh, it.topic); bin(vh, it.payload.toByteArray(Charsets.UTF_8)) }
            if (!o.username.isNullOrEmpty()) {
                str(vh, o.username)
                if (o.password != null) bin(vh, o.password.toByteArray(Charsets.UTF_8))
            }
            return packet(CONNECT, 0, vh.toByteArray())
        }

        fun publish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
            val b = ByteArrayOutputStream()
            str(b, topic)
            b.write(payload)
            return packet(PUBLISH, if (retain) 1 else 0, b.toByteArray())
        }

        fun subscribe(id: Int, topics: List<String>): ByteArray {
            val b = ByteArrayOutputStream()
            b.write(id shr 8 and 0xff); b.write(id and 0xff)
            topics.forEach { str(b, it); b.write(0) } // QoS 0
            return packet(SUBSCRIBE, 0x02, b.toByteArray())
        }

        fun puback(id: Int): ByteArray = packet(PUBACK, 0, byteArrayOf((id shr 8).toByte(), id.toByte()))

        fun parsePublish(flags: Int, body: ByteArray): Publish {
            val tlen = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
            val topic = String(body, 2, tlen, Charsets.UTF_8)
            var i = 2 + tlen
            val qos = (flags shr 1) and 3
            val id = if (qos > 0) (((body[i].toInt() and 0xff) shl 8) or (body[i + 1].toInt() and 0xff)).also { i += 2 } else null
            return Publish(topic, String(body, i, body.size - i, Charsets.UTF_8), flags and 1 == 1, id)
        }

        /** Reads one packet: type, flags, body. */
        fun read(input: DataInputStream): Raw {
            val first = input.readUnsignedByte()
            var len = 0
            var mul = 1
            var n = 0
            while (true) {
                val b = input.readUnsignedByte()
                len += (b and 0x7f) * mul
                if (b and 0x80 == 0) break
                mul *= 128
                if (++n >= 4) throw IOException("bad packet length")
            }
            if (len > MAX_PACKET) throw IOException("packet too big ($len bytes)")
            val body = ByteArray(len)
            input.readFully(body)
            return Raw(first shr 4, first and 0x0f, body)
        }

        fun packet(type: Int, flags: Int, body: ByteArray): ByteArray {
            val b = ByteArrayOutputStream(body.size + 5)
            b.write(type shl 4 or flags)
            var len = body.size
            do {
                var d = len % 128
                len /= 128
                if (len > 0) d = d or 0x80
                b.write(d)
            } while (len > 0)
            b.write(body)
            return b.toByteArray()
        }

        private fun str(b: ByteArrayOutputStream, s: String) = bin(b, s.toByteArray(Charsets.UTF_8))

        private fun bin(b: ByteArrayOutputStream, bytes: ByteArray) {
            b.write(bytes.size shr 8 and 0xff); b.write(bytes.size and 0xff)
            b.write(bytes)
        }
    }
}
