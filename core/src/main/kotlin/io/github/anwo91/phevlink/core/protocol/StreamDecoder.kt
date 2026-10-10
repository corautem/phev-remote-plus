package io.github.anwo91.phevlink.core.protocol

/**
 * Turns a TCP byte stream into packets.
 *
 * Improvements over the reference implementations:
 *  - packets split across TCP reads are buffered instead of dropped,
 *  - several packets in one read are all decoded,
 *  - garbage is skipped byte-by-byte and reported for diagnostics.
 *
 * The XOR key of an incoming packet is recovered from the packet itself: the
 * plain ack byte is always 0 or 1, so the key is either raw[2] or raw[2]^1;
 * the right one is whichever makes the checksum valid.
 */
class StreamDecoder(private val maxBuffered: Int = 8192) {

    sealed interface Item {
        /** A valid packet; [xor] is the key it was encoded with, [raw] the bytes as received. */
        class Decoded(val packet: Packet, val xor: Int, val raw: ByteArray) : Item
        /** Bytes that could not be parsed and were skipped. */
        class Garbage(val bytes: ByteArray) : Item
    }

    private var buf = ByteArray(0)

    val buffered: Int get() = buf.size

    fun feed(bytes: ByteArray): List<Item> {
        buf = if (buf.isEmpty()) bytes.copyOf() else buf + bytes
        return drain(force = false)
    }

    /**
     * Called when no new bytes arrived for a while but a partial packet is still
     * buffered. Treats the head as garbage so a corrupt length byte cannot stall
     * the stream.
     */
    fun flushStale(): List<Item> = if (buf.isEmpty()) emptyList() else drain(force = true)

    fun reset() {
        buf = ByteArray(0)
    }

    private fun drain(force: Boolean): List<Item> {
        val out = ArrayList<Item>()
        val garbage = java.io.ByteArrayOutputStream()
        var forceOnce = force
        while (buf.size >= MIN_PACKET) {
            val result = tryDecodeHead()
            if (result is Head.Ok) {
                if (garbage.size() > 0) {
                    out += Item.Garbage(garbage.toByteArray()); garbage.reset()
                }
                out += Item.Decoded(result.packet, result.xor, buf.copyOfRange(0, result.total))
                buf = buf.copyOfRange(result.total, buf.size)
                continue
            }
            if (result == Head.NeedMore && !forceOnce && buf.size < maxBuffered) {
                // The head might be a partial packet, or garbage whose "length" byte
                // points far ahead. If a complete, known packet starts later in the
                // buffer, the head is garbage: skip to it instead of waiting.
                val next = findLaterPacket()
                if (next < 0) break
                garbage.write(buf, 0, next)
                buf = buf.copyOfRange(next, buf.size)
                continue
            }
            // Head cannot be a packet (or is stale): skip one byte and resync.
            forceOnce = false
            garbage.write(buf[0].toInt())
            buf = buf.copyOfRange(1, buf.size)
        }
        if (garbage.size() > 0) out += Item.Garbage(garbage.toByteArray())
        return out
    }

    private sealed interface Head {
        class Ok(val packet: Packet, val xor: Int, val total: Int) : Head
        data object NeedMore : Head
        data object Invalid : Head
    }

    private fun findLaterPacket(): Int {
        for (i in 1..buf.size - MIN_PACKET) {
            val r = tryDecodeAt(i)
            if (r is Head.Ok && r.packet.type in KNOWN_TYPES) return i
        }
        return -1
    }

    private fun tryDecodeHead(): Head = tryDecodeAt(0)

    private fun tryDecodeAt(off: Int): Head {
        val avail = buf.size - off
        if (avail < MIN_PACKET) return Head.NeedMore
        val b2 = buf[off + 2].toInt() and 0xff
        var needMore = false
        for (xor in intArrayOf(b2, b2 xor 1)) {
            val type = (buf[off].toInt() and 0xff) xor xor
            val total = ((buf[off + 1].toInt() and 0xff) xor xor) + 2
            if (total < MIN_PACKET) continue
            if (total > avail) {
                needMore = true; continue
            }
            val plain = ByteArray(total) { ((buf[off + it].toInt() and 0xff) xor xor).toByte() }
            if (Codec.checksum(plain, total - 1) != (plain[total - 1].toInt() and 0xff)) continue
            val ack = plain[2].toInt() and 0xff
            if (ack > 1) continue
            val packet = Packet(type, ack, plain[3].toInt() and 0xff, plain.copyOfRange(4, total - 1))
            return Head.Ok(packet, xor, total)
        }
        return if (needMore) Head.NeedMore else Head.Invalid
    }

    companion object {
        const val MIN_PACKET = 5
        private val KNOWN_TYPES = setOf(
            PacketType.PING_REQ, PacketType.PING_RESP, PacketType.SEND, PacketType.RESP,
            PacketType.START_REQ_MY14, PacketType.START_RESP_MY14, PacketType.START_REQ_MY18,
            PacketType.START_RESP_MY18, PacketType.START_REQ_MY24, PacketType.START_RESP_MY24,
            PacketType.KEEPALIVE_REQ, PacketType.BAD_ENCODING, PacketType.BAD_ENCODING_ALT,
        )
    }
}
