package io.github.anwo91.phevlink.core.protocol

/**
 * Wire packet types used by the Outlander PHEV Wi-Fi remote protocol.
 *
 * Naming: "car" = the vehicle's Wi-Fi module (TCP server on 192.168.8.46:8080),
 * "client" = us. Most types come in pairs where the response type is the
 * request type with its nibbles swapped (0xf6 <-> 0x6f, 0x4e <-> 0xe4, ...).
 */
object PacketType {
    const val PING_REQ = 0xf3          // client -> car, keepalive
    const val PING_RESP = 0x3f         // car -> client
    const val SEND = 0xf6              // client -> car: register write (ack=0) or ack of a notification (ack=1)
    const val RESP = 0x6f              // car -> client: register notification (ack=0) or ack of our write (ack=1)
    const val START_REQ_MY14 = 0x4e    // car -> client: security init, 2014-2017 cars
    const val START_RESP_MY14 = 0xe4
    const val START_REQ_MY18 = 0x5e    // car -> client: security init, 2018+ cars
    const val START_RESP_MY18 = 0xe5
    const val START_REQ_MY24 = 0x6e
    const val START_RESP_MY24 = 0xe6
    const val KEEPALIVE_REQ = 0x2f     // car -> client: "are you there?" -> we answer with a ping
    const val START_SEND = 0xf2        // client -> car (used by phevcore; not needed by us)
    const val BAD_ENCODING = 0xbb      // car -> client: our XOR key was wrong, data[0] = expected key
    const val BAD_ENCODING_ALT = 0xcc  // car -> client: seen alongside 0xbb, meaning unclear
    const val UNKNOWN_2E = 0x2e

    private val unencoded = setOf(
        START_REQ_MY14, START_RESP_MY14,
        START_REQ_MY18, START_RESP_MY18,
        START_REQ_MY24, START_RESP_MY24,
    )

    fun isStartRequest(type: Int) =
        type == START_REQ_MY14 || type == START_REQ_MY18 || type == START_REQ_MY24

    /** Start/security packets are never XOR encoded. */
    fun isUnencoded(type: Int) = type in unencoded

    /** The response type for a request type (nibble swap). */
    fun responseFor(type: Int): Int = ((type and 0x0f) shl 4) or ((type and 0xf0) shr 4)

    fun name(type: Int): String = when (type) {
        PING_REQ -> "PING"
        PING_RESP -> "PONG"
        SEND -> "SEND"
        RESP -> "RESP"
        START_REQ_MY14 -> "START14"
        START_RESP_MY14 -> "START14-ACK"
        START_REQ_MY18 -> "START18"
        START_RESP_MY18 -> "START18-ACK"
        START_REQ_MY24 -> "START24"
        START_RESP_MY24 -> "START24-ACK"
        KEEPALIVE_REQ -> "KEEPALIVE"
        START_SEND -> "START-SEND"
        BAD_ENCODING -> "BAD-XOR"
        BAD_ENCODING_ALT -> "BAD-XOR2"
        else -> "0x%02x".format(type)
    }
}

/**
 * A decoded (plain, non-XORed) packet.
 *
 * On the wire: [type][length][ack][register][data...][checksum] where
 * length = total size - 2 and checksum = sum of all preceding bytes (mod 256).
 */
class Packet(
    val type: Int,
    val ack: Int,
    val register: Int,
    val data: ByteArray,
) {
    val isAck: Boolean get() = ack == 1

    /** Plain (un-XORed) wire bytes including length and checksum. */
    fun toPlainBytes(): ByteArray {
        require(data.size <= 250) { "data too long: ${data.size}" }
        val out = ByteArray(data.size + 5)
        out[0] = type.toByte()
        out[1] = (data.size + 3).toByte()
        out[2] = ack.toByte()
        out[3] = register.toByte()
        data.copyInto(out, 4)
        out[out.size - 1] = Codec.checksum(out, out.size - 1).toByte()
        return out
    }

    /** Wire bytes XORed with [xor]. */
    fun encode(xor: Int): ByteArray = Codec.xor(toPlainBytes(), xor)

    fun dataHex(): String = data.toHex()

    override fun toString(): String =
        "${PacketType.name(type)}${if (isAck) " ack" else ""} reg=0x%02x data=%s".format(register, data.toHex())

    override fun equals(other: Any?): Boolean =
        other is Packet && other.type == type && other.ack == ack &&
            other.register == register && other.data.contentEquals(data)

    override fun hashCode(): Int = ((type * 31 + ack) * 31 + register) * 31 + data.contentHashCode()
}

object Codec {
    fun checksum(bytes: ByteArray, count: Int): Int {
        var sum = 0
        for (i in 0 until count) sum += bytes[i].toInt() and 0xff
        return sum and 0xff
    }

    fun xor(bytes: ByteArray, key: Int): ByteArray {
        if (key == 0) return bytes.copyOf()
        val k = key.toByte()
        return ByteArray(bytes.size) { (bytes[it].toInt() xor k.toInt()).toByte() }
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    val s = filterNot { it.isWhitespace() || it == ':' }
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private val HEX = "0123456789abcdef".toCharArray()
