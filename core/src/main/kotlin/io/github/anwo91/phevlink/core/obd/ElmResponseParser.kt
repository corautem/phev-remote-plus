package io.github.anwo91.phevlink.core.obd

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Cleans raw ELM327 output into lines.
 *
 * Handles NUL bytes and other control characters, `\r`-only or `\r\n` line endings, lower-case clones,
 * the command echo (when `ATE0` was ignored), `SEARCHING...` and `BUS INIT: ...OK` progress lines.
 */
object ElmText {
    /**
     * @param raw text received before the `>` prompt
     * @param echo the command that was sent; a first line equal to it (ignoring spaces/case) is dropped
     * @param upper upper-case the lines (parsing); false keeps the adapter's case (display)
     */
    fun lines(raw: String, echo: String? = null, upper: Boolean = true): List<String> {
        val split = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString().trim()
            sb.setLength(0)
            if (t.isNotEmpty()) split += if (upper) t.uppercase(Locale.ROOT) else t
        }
        for (ch in raw) {
            when {
                ch == '\r' || ch == '\n' || ch == '>' -> flush()
                ch == '\t' -> sb.append(' ')
                ch.code < 0x20 || ch.code > 0x7E -> Unit // NUL bytes, noise
                else -> sb.append(ch)
            }
        }
        flush()
        var start = 0
        if (echo != null && split.isNotEmpty()) {
            val e = normalize(echo)
            if (e.isNotEmpty() && normalize(split[0]) == e) start = 1
        }
        val out = ArrayList<String>(split.size)
        for (i in start until split.size) {
            var line = split[i]
            val u = line.uppercase(Locale.ROOT)
            if (u.startsWith("SEARCHING")) {
                // "SEARCHING..." (some clones continue with data on the same line)
                line = line.substring("SEARCHING".length).trimStart('.', ' ')
                if (line.isEmpty()) continue
            } else if (u.startsWith("BUS INIT")) {
                if (u.contains("ERROR")) out += "BUS INIT ERROR"
                val after = line.substring(u.indexOf("OK").let { if (it < 0) line.length else it + 2 }).trim()
                if (after.isNotEmpty()) out += after
                continue
            }
            out += line
        }
        return out
    }

    /** Upper-case, without whitespace and control characters (for comparing commands). */
    fun normalize(s: String): String =
        s.filterNot { it.isWhitespace() || it.code < 0x20 || it.code > 0x7E }.uppercase(Locale.ROOT)
}

/**
 * Parses cleaned ELM327 lines (see [ElmText]) into reassembled ISO-TP messages, per sender.
 *
 * Expected format (ATH1, ATS1, ATCAF1): `7E8 06 41 00 BE 3F A8 13` (single frame, PCI `0N`),
 * `762 10 37 61 01 82 83 0F 8B` (first frame, PCI `1L LL`), `762 21 ...` (consecutive frames `2N`).
 * Also tolerated: no spaces (`7E8064100...`), a DLC digit after the id (ATD1), header-less output
 * (`037` length line + `0: ...` indexed lines, or plain single-frame data without PCI),
 * 29-bit headers (skipped), `<DATA ERROR`/`<RX ERROR` lines (dropped), and status lines.
 */
object ElmResponseParser {

    /**
     * A multi-frame message that did not complete.
     *
     * @param sid its first data byte (the response service id of a real reply), -1 when nothing arrived; lets the
     *   driver tell a cut-off answer from a module that merely chatters on the bus (the PHEV's 7BC sends frames
     *   whose first byte counts 01..3F, which look like ISO-TP first frames of hundreds of bytes)
     */
    data class Partial(val rxId: Int, val received: Int, val expected: Int, val sid: Int = -1)

    /**
     * @param messages complete replies in completion order (an ECU can appear more than once,
     *   e.g. "response pending" followed by the real answer)
     * @param incomplete multi-frame messages that stopped early
     * @param status non-data lines: `NO DATA`, `CAN ERROR`, `?`, banners, `OK`, ...
     * @param skippedLines lines ignored (29-bit frames, orphan consecutive frames, flow-control frames)
     */
    class Parsed(
        val messages: List<EcuReply>,
        val incomplete: List<Partial>,
        val status: List<String>,
        val skippedLines: Int,
    )

    private val STATUS_PREFIXES = listOf(
        "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "BUFFER FULL", "FB ERROR",
        "DATA ERROR", "RX ERROR", "STOPPED", "LV RESET", "ACT ALERT", "BUS INIT ERROR", "ERR", "?",
    )
    private val WS = Regex("\\s+")
    private val INDEX_LINE = Regex("^([0-9A-F])\\s*:\\s*([0-9A-F ]*)$")

    private sealed interface Kind
    private class Frame(val id: Int, val bytes: ByteArray) : Kind
    private class Raw(val bytes: ByteArray) : Kind
    private class LengthLine(val length: Int) : Kind
    private class IndexLine(val index: Int, val bytes: ByteArray) : Kind
    private object Skip : Kind

    private class Assembly(val rxId: Int, val total: Int) {
        val buf = ByteArrayOutputStream()
        var nextSeq = 1
        val complete: Boolean get() = buf.size() >= total
        fun append(b: ByteArray, from: Int) {
            if (from < b.size) buf.write(b, from, b.size - from)
        }
        fun reply() = EcuReply(rxId, buf.toByteArray().copyOf(total))
        fun partial(): Partial {
            val bytes = buf.toByteArray()
            return Partial(rxId, minOf(bytes.size, total), total, if (bytes.isEmpty()) -1 else bytes.u8(0))
        }
    }

    /**
     * @param headersExpected the adapter was set to ATH1 (lines without a 3-digit id that start with a
     *   byte <= 0x1F are then treated as 29-bit frames and skipped)
     * @param headerlessRxId sender id to use for data printed without CAN ids
     */
    fun parse(lines: List<String>, headersExpected: Boolean = true, headerlessRxId: Int = EcuReply.UNKNOWN_RX): Parsed {
        val messages = ArrayList<EcuReply>()
        val partials = ArrayList<Partial>()
        val status = ArrayList<String>()
        var skipped = 0
        val open = LinkedHashMap<Int, Assembly>()
        var headerless: Assembly? = null
        var headerlessNext = 0

        for (rawLine in lines) {
            val line = rawLine.trim().uppercase(Locale.ROOT)
            if (line.isEmpty()) continue
            if (line.contains("<DATA ERROR") || line.contains("<RX ERROR")) {
                status += if (line.contains("<RX ERROR")) "RX ERROR" else "DATA ERROR"
                continue
            }
            if (STATUS_PREFIXES.any { line.startsWith(it) } && !looksLikeHex(line)) {
                status += line
                continue
            }
            when (val k = classify(line, headersExpected)) {
                null -> status += line
                Skip -> skipped++
                is Raw -> messages += EcuReply(headerlessRxId, k.bytes)
                is LengthLine -> {
                    headerless?.let { partials += it.partial() }
                    headerless = if (k.length > 0) Assembly(headerlessRxId, k.length) else null
                    headerlessNext = 0
                }
                is IndexLine -> {
                    val asm = headerless
                    if (asm == null) {
                        skipped++
                    } else if (k.index != headerlessNext) {
                        partials += asm.partial()
                        headerless = null
                        status += "SEQUENCE ERROR"
                    } else {
                        asm.append(k.bytes, 0)
                        headerlessNext = (headerlessNext + 1) and 0x0F
                        if (asm.complete) {
                            messages += asm.reply()
                            headerless = null
                        }
                    }
                }
                is Frame -> {
                    val b = k.bytes
                    if (b.isEmpty()) {
                        skipped++
                        continue
                    }
                    val pci = b.u8(0)
                    when (pci ushr 4) {
                        0 -> { // single frame
                            open.remove(k.id)?.let { partials += it.partial() }
                            var len = pci and 0x0F
                            var start = 1
                            if (len == 0 && b.size >= 2) { // CAN-FD style escape
                                len = b.u8(1)
                                start = 2
                            }
                            if (len == 0 || b.size - start < len) {
                                skipped++
                            } else {
                                messages += EcuReply(k.id, b.copyOfRange(start, start + len))
                            }
                        }
                        1 -> { // first frame
                            open.remove(k.id)?.let { partials += it.partial() }
                            val total = if (b.size >= 2) ((pci and 0x0F) shl 8) or b.u8(1) else 0
                            if (total == 0) {
                                skipped++
                            } else {
                                val asm = Assembly(k.id, total)
                                asm.append(b, 2)
                                if (asm.complete) messages += asm.reply() else open[k.id] = asm
                            }
                        }
                        2 -> { // consecutive frame
                            val asm = open[k.id]
                            if (asm == null) {
                                skipped++
                            } else if ((pci and 0x0F) != asm.nextSeq) {
                                open.remove(k.id)
                                partials += asm.partial()
                                status += "SEQUENCE ERROR ${ObdHex.canId(k.id)}"
                            } else {
                                asm.append(b, 1)
                                asm.nextSeq = (asm.nextSeq + 1) and 0x0F
                                if (asm.complete) {
                                    open.remove(k.id)
                                    messages += asm.reply()
                                }
                            }
                        }
                        else -> skipped++ // 3 = flow control (seen when monitoring), others invalid
                    }
                }
            }
        }
        open.values.forEach { partials += it.partial() }
        headerless?.let { partials += it.partial() }
        return Parsed(messages, partials, status, skipped)
    }

    private fun looksLikeHex(line: String): Boolean = line.all { it == ' ' || Character.digit(it, 16) >= 0 }

    private fun isHexToken(t: String) = t.isNotEmpty() && t.all { Character.digit(it, 16) >= 0 }

    private fun classify(line: String, headersExpected: Boolean): Kind? {
        INDEX_LINE.matchEntire(line)?.let { m ->
            val bytes = hexBytesOrNull(m.groupValues[2]) ?: return null
            return IndexLine(m.groupValues[1].toInt(16), bytes)
        }
        val tokens: List<String> = if (line.contains(' ')) {
            line.split(WS).filter { it.isNotEmpty() }
        } else {
            if (!isHexToken(line)) return null
            when {
                line.length == 3 -> listOf(line)
                line.length % 2 == 1 && line.length >= 5 -> listOf(line.substring(0, 3)) + line.substring(3).chunked(2)
                line.length % 2 == 0 -> line.chunked(2)
                else -> return null
            }
        }
        if (tokens.isEmpty() || tokens.any { !isHexToken(it) }) return null
        if (tokens.size == 1 && tokens[0].length == 3) return LengthLine(tokens[0].toInt(16))
        if (tokens[0].length == 3) {
            var rest = tokens.subList(1, tokens.size)
            if (rest.isNotEmpty() && rest[0].length == 1) rest = rest.subList(1, rest.size) // DLC digit (ATD1)
            if (rest.any { it.length != 2 }) return null
            return Frame(tokens[0].toInt(16), ByteArray(rest.size) { rest[it].toInt(16).toByte() })
        }
        if (tokens.all { it.length == 2 }) {
            val bytes = ByteArray(tokens.size) { tokens[it].toInt(16).toByte() }
            // 29-bit header "18 DA F1 10 ..." (priority byte <= 0x1F); a response SID is always >= 0x40
            if (headersExpected && bytes.size >= 5 && bytes.u8(0) <= 0x1F) return Skip
            return Raw(bytes)
        }
        return null
    }

    private fun hexBytesOrNull(s: String): ByteArray? {
        val compact = s.filterNot { it == ' ' }
        if (compact.length % 2 != 0 || !compact.all { Character.digit(it, 16) >= 0 }) return null
        return ByteArray(compact.length / 2) { compact.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
