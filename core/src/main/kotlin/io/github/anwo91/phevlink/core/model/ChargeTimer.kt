package io.github.anwo91.phevlink.core.model

import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import java.time.LocalTime

/** A charge window: the car may charge from [start] until [end] (overnight when [end] is before [start]). */
data class ChargeWindow(val start: LocalTime, val end: LocalTime) {
    init {
        require(start.minute % 10 == 0 && end.minute % 10 == 0) { "Charge times are in 10-minute steps" }
        require(start != end) { "Start and end must differ" }
    }

    override fun toString() = "%02d:%02d–%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
}

/**
 * The car's charge timer on 2014–2017 cars, as Mitsubishi's app shows it: three windows ("Timer 1–3"), each day
 * uses one of them or none, and "full charge" days keep charging past the window until the battery is full.
 * Days are numbered Sunday 0 … Saturday 6, like the car.
 */
data class ChargeTimerProgram(
    /** Timer 1–3; null = not set. */
    val windows: List<ChargeWindow?>,
    /** Per day (Sunday first): 0 = no timer that day, 1–3 = that timer. */
    val dayTimer: List<Int>,
    /** Days (0 = Sunday) with "Full charge". */
    val fullDays: Set<Int>,
) {
    init {
        require(windows.size == 3) { "Three timers" }
        require(dayTimer.size == 7 && dayTimer.all { it in 0..3 }) { "Seven days, timer 0–3 each" }
        require(fullDays.all { it in 0..6 })
    }

    /** The window [day] uses, if any. */
    fun window(day: Int): ChargeWindow? = dayTimer[day].takeIf { it in 1..3 }?.let { windows[it - 1] }

    /**
     * Same windows on the same days — full-charge days aside (the car may clear those itself after a full charge).
     * What the bridge's smart charging compares when it decides whose timer the car carries.
     */
    fun sameTimes(other: ChargeTimerProgram?): Boolean {
        if (other == null) return false
        val a = normalized()
        val b = other.normalized()
        return (0..6).all { a.window(it) == b.window(it) }
    }

    /** At least one day has a window: the car waits for it when plugged in. */
    val active: Boolean get() = (0..6).any { window(it) != null }

    /** Days pointing at an empty timer are cleared, so what is sent is exactly what the car will report back. */
    fun normalized(): ChargeTimerProgram = copy(dayTimer = dayTimer.mapIndexed { d, t -> if (window(d) != null) t else 0 })

    /** For logs: "T1 23:00–07:00 Mon–Fri; full Sat". */
    fun summary(): String {
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val parts = (1..3).mapNotNull { t ->
            val w = windows[t - 1] ?: return@mapNotNull null
            val days = (0..6).filter { dayTimer[it] == t }
            "T$t $w " + if (days.isEmpty()) "(no days)" else days.joinToString(",") { names[it] }
        }
        val full = fullDays.sorted().joinToString(",") { names[it] }
        return (parts.ifEmpty { listOf("no timer") } + listOfNotNull(full.ifEmpty { null }?.let { "full $it" })).joinToString("; ")
    }

    companion object {
        val EMPTY = ChargeTimerProgram(listOf(null, null, null), List(7) { 0 }, emptySet())

        /**
         * The program the car reports: timers and day choices from 0x20, full-charge days from 0x0e. Cars that only
         * sent the per-day windows (0x18) get timers rebuilt from the distinct windows. Null when neither arrived.
         */
        fun read(state: CarState): ChargeTimerProgram? {
            val full = bytes(state, Reg.READ_FULL_CHARGE_DAYS)?.firstOrNull()?.toInt()?.and(0x7f) ?: 0
            val fullDays = (0..6).filter { (full shr it) and 1 == 1 }.toSet()
            bytes(state, Reg.READ_CHARGE_PRESETS)?.takeIf { it.size >= 10 }?.let { b ->
                val (windows, days) = ChargeTimerCodec.decodePresets(b)
                return ChargeTimerProgram(windows, days, fullDays)
            }
            val perDay = ScheduleDecoder.charge(state)
            if (perDay.isEmpty()) return null
            val windows = ArrayList<ChargeWindow>()
            val days = MutableList(7) { 0 }
            for (d in perDay) {
                val w = ChargeTimerCodec.window(d.start, d.end) ?: continue
                var i = windows.indexOf(w)
                if (i < 0 && windows.size < 3) { windows += w; i = windows.size - 1 }
                if (i >= 0) days[d.day] = i + 1
            }
            return ChargeTimerProgram(List(3) { windows.getOrNull(it) }, days, fullDays)
        }

        private fun bytes(state: CarState, reg: Int): ByteArray? =
            state.registers[reg]?.hex?.let { runCatching { it.hexToBytes() }.getOrNull() }
    }
}

/**
 * Packing of the charge timer as the official app writes it on 2014–2017 cars (register 0x01, 28 bytes):
 *
 *     [0..1]   per-day timer choice, 2 bits per day (Sunday first): 0 = none, 1–3 = Timer 1–3   (echoed in 0x20[0..1])
 *     [2]      full-charge days, bit d = day d                                                   (echoed in 0x0e)
 *     [3..4]   Timer 1–3 start hours, 5 bits each        [5..6]  start minutes ÷ 10, 3 bits each (echoed in 0x20[2..9])
 *     [7..8]   Timer 1–3 end hours                       [9..10] end minutes ÷ 10
 *     [11]     timer state: 1 = no timer; otherwise the car's own "on" value (≥ 2)               (echoed in 0x1b)
 *     [12..27] per day: start hours (5 B), start minutes ÷ 10 (3 B), end hours (5 B), end minutes (3 B) — as 0x18
 *
 * Bit fields are little-endian bit streams; an unset hour is 31, an unset minute 7.
 */
object ChargeTimerCodec {
    const val LENGTH = 28
    private const val NO_HOUR = 31
    private const val NO_MIN = 7

    fun encodeMy14(p: ChargeTimerProgram, stateByte: Int): ByteArray {
        val n = p.normalized()
        val out = ByteArray(LENGTH)
        val sel = Bits(out, 0)
        for (d in 0..6) sel.put(n.dayTimer[d], 2, d * 2)
        out[2] = n.fullDays.fold(0) { m, d -> m or (1 shl d) }.toByte()
        val w = n.windows
        for (t in 0..2) {
            Bits(out, 3).put(w[t]?.start?.hour ?: NO_HOUR, 5, t * 5)
            Bits(out, 5).put(w[t]?.start?.let { it.minute / 10 } ?: NO_MIN, 3, t * 3)
            Bits(out, 7).put(w[t]?.end?.hour ?: NO_HOUR, 5, t * 5)
            Bits(out, 9).put(w[t]?.end?.let { it.minute / 10 } ?: NO_MIN, 3, t * 3)
        }
        out[11] = stateByte.toByte()
        for (d in 0..6) {
            val win = n.window(d)
            Bits(out, 12).put(win?.start?.hour ?: NO_HOUR, 5, d * 5)
            Bits(out, 17).put(win?.start?.let { it.minute / 10 } ?: NO_MIN, 3, d * 3)
            Bits(out, 20).put(win?.end?.hour ?: NO_HOUR, 5, d * 5)
            Bits(out, 25).put(win?.end?.let { it.minute / 10 } ?: NO_MIN, 3, d * 3)
        }
        return out
    }

    /** Timers and day choices from register 0x20 (10 bytes). */
    fun decodePresets(b: ByteArray): Pair<List<ChargeWindow?>, List<Int>> {
        val days = (0..6).map { Bits(b, 0).get(2, it * 2) }
        val windows = (0..2).map { t ->
            window(
                time(Bits(b, 2).get(5, t * 5), Bits(b, 4).get(3, t * 3)),
                time(Bits(b, 6).get(5, t * 5), Bits(b, 8).get(3, t * 3)),
            )
        }
        return windows to days
    }

    /**
     * The state byte to send: 1 when no day has a window (the car then charges as soon as it's plugged in);
     * otherwise the car's own value when its timer is on ([current] low nibble ≥ 2), else 2.
     */
    fun stateByte(p: ChargeTimerProgram, current: Int?): Int = when {
        !p.active -> 1
        current != null && (current and 0x0f) >= 2 -> current and 0xff
        else -> 2
    }

    fun window(start: LocalTime?, end: LocalTime?): ChargeWindow? =
        if (start == null || end == null || start == end) null else ChargeWindow(start, end)

    private fun time(h: Int, m10: Int): LocalTime? = if (h in 0..23 && m10 in 0..5) LocalTime.of(h, m10 * 10) else null

    /** A little-endian bit stream starting at byte [base]. */
    private class Bits(val b: ByteArray, val base: Int) {
        fun get(bits: Int, at: Int): Int {
            var v = 0
            for (i in 0 until bits) {
                val bit = at + i
                val byte = base + bit / 8
                if (byte < b.size && (b[byte].toInt() shr (bit % 8)) and 1 == 1) v = v or (1 shl i)
            }
            return v
        }

        fun put(value: Int, bits: Int, at: Int) {
            for (i in 0 until bits) {
                val bit = at + i
                val byte = base + bit / 8
                val mask = 1 shl (bit % 8)
                b[byte] = if ((value shr i) and 1 == 1) (b[byte].toInt() or mask).toByte() else (b[byte].toInt() and mask.inv()).toByte()
            }
        }
    }
}
