package io.github.anwo91.phevlink.core.obd

import kotlinx.coroutines.flow.Flow
import java.io.Writer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns [ObdState] snapshots into CSV: one row per completed poll cycle ([ObdState.pollCycle]).
 *
 * Columns: `time` (ISO-8601 local date-time with milliseconds, e.g. `2026-10-10T14:03:22.125`), then one column per
 * value id. Pass [columns] (e.g. [ObdClient.knownValueIds]) for a fixed header; otherwise columns are added in
 * first-seen order (ids that appear in the same snapshot sorted alphabetically). Numbers use '.' as decimal separator
 * (up to 4 decimals), on/off flags are 1/0, text-only values (raw hex of unknown PIDs) are written as text, missing
 * values are empty.
 *
 * Two ways to use it:
 * - in memory: [add] snapshots, then [csv] / [writeTo] (keeps at most [maxRows] rows);
 * - streaming: [append] (or [record]) writes each new row to a [Writer] as it comes. If new columns appear after the
 *   header was written, a new header line is written before the next row, so each block stays self-describing.
 *   Fixed [columns] avoid that.
 *
 * Thread-safe.
 */
class ObdCsvLogger(
    columns: List<String> = emptyList(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxRows: Int = 100_000,
) {
    private val fixed = columns.isNotEmpty()
    private val cols = ArrayList<String>(columns.distinct())
    private val rows = ArrayDeque<Row>()
    private var lastCycle = Long.MIN_VALUE
    private var streamedHeader: List<String>? = null

    private class Row(val timeMs: Long, val cells: Map<String, String>)

    /** Current columns (without `time`). */
    val columns: List<String> get() = synchronized(this) { cols.toList() }

    /** Rows kept in memory. */
    val rowCount: Int get() = synchronized(this) { rows.size }

    /**
     * Adds a row for [state] if it belongs to a new poll cycle (or [force]) and has values.
     * @return true when a row was added
     */
    fun add(state: ObdState, force: Boolean = false): Boolean = synchronized(this) { addLocked(state, force) != null }

    /** Adds a row like [add] and writes it to [writer] (header first if needed), then flushes. */
    fun append(writer: Writer, state: ObdState, force: Boolean = false): Boolean {
        val text = synchronized(this) {
            val row = addLocked(state, force) ?: return false
            val sb = StringBuilder()
            if (streamedHeader != cols) {
                sb.append(headerLocked()).append('\n')
                streamedHeader = cols.toList()
            }
            sb.append(formatRowLocked(row)).append('\n')
            sb.toString()
        }
        writer.write(text)
        writer.flush()
        return true
    }

    /** Collects [states] until cancelled, appending a row per poll cycle to [writer]. */
    suspend fun record(states: Flow<ObdState>, writer: Writer) {
        states.collect { append(writer, it) }
    }

    /** Header line: `time,<id>,<id>...`. */
    fun header(): String = synchronized(this) { headerLocked() }

    /** The whole CSV (header + rows) with the current columns. */
    fun csv(): String = synchronized(this) {
        val sb = StringBuilder(headerLocked()).append('\n')
        for (r in rows) sb.append(formatRowLocked(r)).append('\n')
        sb.toString()
    }

    /** Writes [csv] to [writer] and flushes. */
    fun writeTo(writer: Writer) {
        writer.write(csv())
        writer.flush()
    }

    /** Forgets all rows (columns stay). */
    fun clear() = synchronized(this) {
        rows.clear()
        streamedHeader = null
    }

    private fun addLocked(state: ObdState, force: Boolean): Row? {
        if (!force && state.pollCycle == lastCycle) return null
        if (state.values.isEmpty()) return null
        lastCycle = state.pollCycle
        if (!fixed) cols += state.values.keys.filter { it !in cols }.sorted()
        val cells = HashMap<String, String>()
        for ((id, v) in state.values) cells[id] = formatValue(v)
        val time = state.lastPollMs ?: state.values.values.maxOf { it.updatedAtMs }
        val row = Row(time, cells)
        rows.addLast(row)
        while (rows.size > maxRows) rows.removeFirst()
        return row
    }

    private fun headerLocked(): String = (listOf("time") + cols).joinToString(",") { escape(it) }

    private fun formatRowLocked(r: Row): String {
        val sb = StringBuilder(TIME.format(Instant.ofEpochMilli(r.timeMs).atZone(zone).toLocalDateTime()))
        for (c in cols) sb.append(',').append(escape(r.cells[c] ?: ""))
        return sb.toString()
    }

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

        /** Number with '.' decimals (max 4; on/off flags as 1/0), or the text form for text-only values (raw hex). */
        fun formatValue(v: ObdValue): String {
            val d = v.value ?: return v.text ?: ""
            if (d.isNaN() || d.isInfinite()) return ""
            val s = BigDecimal(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            return if (s == "-0") "0" else s
        }

        private fun escape(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
