package io.github.anwo91.phevlink.core.energy

import io.github.anwo91.phevlink.core.model.ChargeTimerProgram
import io.github.anwo91.phevlink.core.model.ChargeWindow
import io.github.anwo91.phevlink.core.planner.ClimatePlanner
import io.github.anwo91.phevlink.core.planner.Departure
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.ceil

/**
 * When to charge tonight: the cheapest stretch long enough to fill the battery before the car is needed.
 * [startMs]..[endMs] is the window (10-minute steps, as the car's timer takes them); [avgPrice] its average spot price
 * (before VAT, null for a flat price) and [nowPrice] what charging right away would average, for the saving.
 */
data class ChargePlan(
    val startMs: Long,
    val endMs: Long,
    val readyByMs: Long,
    val avgPrice: Double?,
    val nowPrice: Double?,
) {
    val minutes: Int get() = ((endMs - startMs) / 60_000).toInt()

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "startMs" to startMs, "endMs" to endMs, "readyByMs" to readyByMs, "avgPrice" to avgPrice, "nowPrice" to nowPrice,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>?): ChargePlan? = runCatching {
            m ?: return null
            ChargePlan(
                (m["startMs"] as Number).toLong(), (m["endMs"] as Number).toLong(), (m["readyByMs"] as Number).toLong(),
                (m["avgPrice"] as? Number)?.toDouble(), (m["nowPrice"] as? Number)?.toDouble(),
            )
        }.getOrNull()
    }
}

/** The planning behind smart charging (pure; the bridge runs it and talks to the car). */
object SmartCharge {
    /** Without history: about 5½ hours for a full charge at home (230 V, 10 A). */
    const val DEFAULT_PCT_PER_HOUR = 18.0
    private const val STEP_MS = 10 * 60_000L

    /**
     * How fast this car charges at home, % per hour: the median of recent charges that gained at least 20 % (short
     * top-ups are dominated by the slow start and the balancing at the end). Falls back to [DEFAULT_PCT_PER_HOUR].
     */
    fun pctPerHour(charges: List<ChargeRecord>): Double {
        val rates = charges.takeLast(20)
            .filter { it.toPct - it.fromPct >= 20 && it.minutes >= 30 }
            .map { (it.toPct - it.fromPct) * 60.0 / it.minutes }
            .filter { it in 5.0..60.0 }
            .sorted()
        return if (rates.isEmpty()) DEFAULT_PCT_PER_HOUR else rates[rates.size / 2]
    }

    /** Minutes to go from [pct] to [target] %, with 15 % extra and rounded up to 10 minutes. */
    fun minutesNeeded(pct: Int, target: Int, pctPerHour: Double): Int {
        val need = (target - pct).coerceAtLeast(0)
        if (need == 0) return 0
        val m = need / pctPerHour * 60.0 * 1.15
        return (ceil(m / 10.0) * 10).toInt().coerceAtLeast(10)
    }

    /**
     * When the car is needed: the next enabled departure within [horizonHours] if [useDepartures], else the next
     * [readyAt] (e.g. 07:00) — whichever comes first.
     */
    fun readyBy(now: ZonedDateTime, readyAt: LocalTime, departures: List<Departure>, useDepartures: Boolean, horizonHours: Long = 24): ZonedDateTime {
        var at = now.toLocalDate().atTime(readyAt).atZone(now.zone)
        if (!at.isAfter(now)) at = at.plusDays(1)
        if (useDepartures) {
            ClimatePlanner.nextDeparture(departures, now)?.second
                ?.takeIf { it.isAfter(now) && it.isBefore(now.plusHours(horizonHours)) && it.isBefore(at) }
                ?.let { at = it }
        }
        return at
    }

    /**
     * The cheapest window of [minutes] that starts at or after [earliestMs] and ends by [readyByMs], from [slots]
     * (spot prices; only windows fully covered count). Null when nothing fits or the prices don't reach far enough.
     */
    fun cheapest(earliestMs: Long, readyByMs: Long, minutes: Int, slots: List<PriceSlot>): ChargePlan? {
        if (minutes <= 0) return null
        val len = minutes * 60_000L
        val first = ceilStep(earliestMs)
        val sorted = slots.sortedBy { it.startMs }
        val nowPrice = average(first, first + len, sorted)
        var best: Pair<Long, Double>? = null
        var s = first
        while (s + len <= readyByMs) {
            val p = average(s, s + len, sorted)
            // Strictly cheaper only: between equal prices the earlier start wins (finished sooner, more margin).
            if (p != null && (best == null || p < best.second - 1e-9)) best = s to p
            s += STEP_MS
        }
        return best?.let { (start, p) -> ChargePlan(start, start + len, readyByMs, p, nowPrice) }
    }

    /**
     * The owner's [owner] timer with tonight's window, in the car's own clock ([carTime]: epoch ms → the car's local
     * time). Tonight's days — the day the window starts, today (the plug-in day: an owner's window later this evening
     * would start the charge early) and yesterday when its overnight window is still running — all point at one timer
     * slot no other day uses, with "full charge", so the car waits for the window and then keeps going until full.
     * Null when no slot is free (every timer is used by other days) or the window can't be written.
     */
    fun program(owner: ChargeTimerProgram?, plan: ChargePlan, nowMs: Long, carTime: (Long) -> LocalDateTime): ChargeTimerProgram? {
        val base = (owner ?: ChargeTimerProgram.EMPTY).normalized()
        if (plan.endMs - plan.startMs >= 23 * 3_600_000L) return null
        val start = floorStep(carTime(plan.startMs))
        val end = ceilStep(carTime(plan.endMs))
        val window = runCatching { ChargeWindow(start.toLocalTime(), end.toLocalTime()) }.getOrNull() ?: return null
        val now = carTime(nowMs)
        val today = day(now)
        val yesterday = (today + 6) % 7
        val days = linkedSetOf(day(start), today)
        base.window(yesterday)?.let { w -> if (w.end < w.start && now.toLocalTime() < w.end) days += yesterday }
        val usedByOthers = (0..6).filter { it !in days }.map { base.dayTimer[it] }.filter { it in 1..3 }.toSet()
        val slot = (1..3).firstOrNull { base.windows[it - 1] == null && it !in usedByOthers }
            ?: (1..3).firstOrNull { it !in usedByOthers }
            ?: return null
        val windows = base.windows.toMutableList().also { it[slot - 1] = window }
        val dayTimer = base.dayTimer.toMutableList().also { l -> days.forEach { l[it] = slot } }
        return ChargeTimerProgram(windows, dayTimer, base.fullDays + days).normalized()
    }

    /** The car counts Sunday as day 0. */
    private fun day(t: LocalDateTime): Int = t.dayOfWeek.value % 7

    private fun floorStep(t: LocalDateTime): LocalDateTime = t.withSecond(0).withNano(0).let { it.minusMinutes((it.minute % 10).toLong()) }
    private fun ceilStep(t: LocalDateTime): LocalDateTime {
        val f = floorStep(t)
        return if (f == t.withNano(0) && t.second == 0) f else f.plusMinutes(10)
    }

    /** Average price over [fromMs]..[toMs] (time-weighted), null unless the slots cover all of it. */
    fun average(fromMs: Long, toMs: Long, slots: List<PriceSlot>): Double? {
        var covered = 0L
        var sum = 0.0
        for (s in slots) {
            val a = maxOf(s.startMs, fromMs)
            val b = minOf(s.endMs, toMs)
            if (b <= a) continue
            sum += s.perKwh * (b - a)
            covered += b - a
        }
        return if (covered >= toMs - fromMs && covered > 0) sum / covered else null
    }

    private fun ceilStep(ms: Long) = (ms + STEP_MS - 1) / STEP_MS * STEP_MS
    private fun floorStep(ms: Long) = ms / STEP_MS * STEP_MS
}
