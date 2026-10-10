package io.github.anwo91.phevlink.core.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogKind(val tag: String) {
    TX("→"),       // packet we sent
    RX("←"),       // packet from the car
    LINK("◆"),     // connection lifecycle (network, socket, handshake)
    CMD("▶"),      // user command progress
    STATE("●"),    // decoded state change
    WARN("!"),
    ERROR("✖"),
}

class LogEntry(
    val timeMs: Long,
    val kind: LogKind,
    val text: String,
    /** Raw wire bytes (hex) for TX/RX, as they appeared on the wire. */
    val rawHex: String? = null,
    /** XOR key used, for TX/RX. */
    val xor: Int? = null,
) {
    fun format(zone: ZoneId = ZoneId.systemDefault()): String {
        val t = TIME.withZone(zone).format(Instant.ofEpochMilli(timeMs))
        val extra = buildString {
            if (xor != null) append(" [x%02x]".format(xor))
            if (rawHex != null) append(" ").append(rawHex)
        }
        return "$t ${kind.tag} $text$extra"
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

/**
 * Thread-safe ring buffer of diagnostic entries. Every connection attempt,
 * packet and command step is recorded so a failed session can be explained
 * after the fact (and exported from the app).
 */
class DiagLog(private val capacity: Int = 4000, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = ArrayDeque<LogEntry>(capacity)
    private val listeners = mutableListOf<(LogEntry) -> Unit>()

    /** When false, pings/pongs are not recorded (they are very chatty). */
    @Volatile var recordPings: Boolean = false

    fun add(kind: LogKind, text: String, rawHex: String? = null, xor: Int? = null) {
        val e = LogEntry(clock(), kind, text, rawHex, xor)
        val ls: List<(LogEntry) -> Unit>
        synchronized(this) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(e)
            ls = listeners.toList()
        }
        ls.forEach { runCatching { it(e) } }
    }

    fun link(text: String) = add(LogKind.LINK, text)
    fun warn(text: String) = add(LogKind.WARN, text)
    fun error(text: String) = add(LogKind.ERROR, text)

    fun snapshot(): List<LogEntry> = synchronized(this) { entries.toList() }

    fun tail(n: Int): List<LogEntry> = synchronized(this) { entries.toList().takeLast(n) }

    fun clear() = synchronized(this) { entries.clear() }

    fun addListener(l: (LogEntry) -> Unit) = synchronized(this) { listeners += l }
    fun removeListener(l: (LogEntry) -> Unit) = synchronized(this) { listeners -= l }

    fun dump(zone: ZoneId = ZoneId.systemDefault()): String =
        snapshot().joinToString("\n") { it.format(zone) }
}
