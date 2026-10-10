package io.github.anwo91.phevlink.core.obd

import java.util.Locale

/**
 * Hex helpers used by the OBD code. Public because the UI shows raw bytes
 * (e.g. the BMU reply table and the terminal) in the same format.
 */
object ObdHex {
    private const val DIGITS = "0123456789ABCDEF"

    /** Formats bytes as upper-case hex, `"61 01 82"` (or `"610182"` with an empty [separator]). */
    fun format(bytes: ByteArray, separator: String = " "): String {
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        for (i in bytes.indices) {
            if (i > 0) sb.append(separator)
            val v = bytes[i].toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Parses hex text, ignoring whitespace. Throws [IllegalArgumentException] for odd length / non-hex. */
    fun parse(text: String): ByteArray {
        val s = text.filterNot { it.isWhitespace() }
        require(s.length % 2 == 0) { "odd number of hex digits: '$text'" }
        return ByteArray(s.length / 2) { i ->
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "not hex: '$text'" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** CAN identifier as text: 11-bit ids as 3 hex digits (`"7E0"`), larger ids as 8. */
    fun canId(id: Int): String =
        if (id in 0..0x7FF) String.format(Locale.ROOT, "%03X", id) else String.format(Locale.ROOT, "%08X", id)

    /** One byte as two hex digits. */
    fun byte(v: Int): String = String.format(Locale.ROOT, "%02X", v and 0xFF)
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun bytesOf(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
