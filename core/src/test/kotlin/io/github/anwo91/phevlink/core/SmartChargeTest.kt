package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.energy.ChargePlan
import io.github.anwo91.phevlink.core.energy.ChargeRecord
import io.github.anwo91.phevlink.core.energy.PriceSlot
import io.github.anwo91.phevlink.core.energy.SmartCharge
import io.github.anwo91.phevlink.core.model.ChargeTimerProgram
import io.github.anwo91.phevlink.core.model.ChargeWindow
import io.github.anwo91.phevlink.core.planner.Departure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SmartChargeTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private val evening = ZonedDateTime.of(2026, 10, 14, 18, 3, 0, 0, zone) // a Wednesday

    private val carTime: (Long) -> LocalDateTime = { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }

    private fun ms(h: Int, m: Int = 0, dayOffset: Long = 0) = evening.toLocalDate().plusDays(dayOffset).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    /** Hourly prices from 18:00 today until 24:00 tomorrow: expensive evening, cheap 01–05. */
    private fun prices(): List<PriceSlot> {
        val out = ArrayList<PriceSlot>()
        for (i in 0 until 30) {
            val start = evening.withMinute(0).plusHours(i.toLong())
            val p = when (start.hour) {
                in 17..21 -> 2.0
                in 1..4 -> 0.5
                else -> 1.0
            }
            out += PriceSlot(start.toInstant().toEpochMilli(), start.plusHours(1).toInstant().toEpochMilli(), p)
        }
        return out
    }

    @Test
    fun theCheapestHoursBeforeMorning() {
        val plan = SmartCharge.cheapest(evening.toInstant().toEpochMilli(), ms(7, dayOffset = 1), 240, prices())!!
        assertEquals(ms(1, dayOffset = 1), plan.startMs)
        assertEquals(ms(5, dayOffset = 1), plan.endMs)
        assertEquals(0.5, plan.avgPrice!!, 1e-9)
        // Right away (18:10–22:10): 2.0 for 3 h 50 min, then 1.0.
        assertTrue(plan.nowPrice!! > 1.7)
    }

    @Test
    fun noPlanWhenThereIsNoTimeOrNoPrices() {
        assertNull(SmartCharge.cheapest(ms(5, dayOffset = 1), ms(7, dayOffset = 1), 240, prices()))
        assertNull(SmartCharge.cheapest(evening.toInstant().toEpochMilli(), ms(7, dayOffset = 1), 240, prices().take(4)))
    }

    @Test
    fun durationFromTheBatteryAndTheCarsOwnPace() {
        assertEquals(0, SmartCharge.minutesNeeded(100, 100, 18.0))
        // 60 % at 18 %/h = 200 min, +15 % = 230.
        assertEquals(230, SmartCharge.minutesNeeded(40, 100, 18.0))
        val history = listOf(
            ChargeRecord("a", 0, 240 * 60_000L, 20, 100, 9.6, null, null, "NOK", "spot", "NO1"), // 20 %/h
            ChargeRecord("b", 0, 120 * 60_000L, 50, 100, 6.0, null, null, "NOK", "spot", "NO1"), // 25 %/h
            ChargeRecord("c", 0, 20 * 60_000L, 90, 95, 0.6, null, null, "NOK", "spot", "NO1"), // too short, ignored
        )
        assertEquals(25.0, SmartCharge.pctPerHour(history), 1e-9)
        assertEquals(SmartCharge.DEFAULT_PCT_PER_HOUR, SmartCharge.pctPerHour(emptyList()), 1e-9)
    }

    @Test
    fun readyByIsTheEarlierOfTheTimeAndTheNextDeparture() {
        assertEquals(ms(7, dayOffset = 1), SmartCharge.readyBy(evening, LocalTime.of(7, 0), emptyList(), true).toInstant().toEpochMilli())
        val early = Departure("d", LocalTime.of(6, 15), setOf(DayOfWeek.THURSDAY))
        assertEquals(ms(6, 15, dayOffset = 1), SmartCharge.readyBy(evening, LocalTime.of(7, 0), listOf(early), true).toInstant().toEpochMilli())
        assertEquals(ms(7, dayOffset = 1), SmartCharge.readyBy(evening, LocalTime.of(7, 0), listOf(early), false).toInstant().toEpochMilli())
    }

    @Test
    fun theTimerKeepsTheOwnersOtherDays() {
        val owner = ChargeTimerProgram(
            listOf(ChargeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)), null, null),
            listOf(0, 1, 1, 1, 1, 1, 0), setOf(6),
        )
        val plan = ChargePlan(ms(1, dayOffset = 1), ms(5, 10, dayOffset = 1), ms(7, dayOffset = 1), 0.5, 1.8)
        val p = SmartCharge.program(owner, plan, evening.toInstant().toEpochMilli(), carTime)!!
        // Thursday (4), when it starts, and Wednesday (3), tonight (its 23:00 window would start too early), get a
        // timer of their own, 01:00–05:10 with full charge; the other weekdays keep timer 1.
        val mine = ChargeWindow(LocalTime.of(1, 0), LocalTime.of(5, 10))
        assertEquals(mine, p.window(4))
        assertEquals(mine, p.window(3))
        assertTrue(3 in p.fullDays && 4 in p.fullDays && 6 in p.fullDays)
        for (d in listOf(1, 2, 5)) assertEquals(owner.windows[0], p.window(d))
        assertNull(p.window(0))
        // No timer before: just tonight's.
        val fresh = SmartCharge.program(null, plan, evening.toInstant().toEpochMilli(), carTime)!!
        assertEquals(listOf(3, 4), (0..6).filter { fresh.window(it) != null })
        assertNotNull(ChargePlan.fromMap(plan.toMap()))
        assertEquals(plan, ChargePlan.fromMap(plan.toMap()))
    }

    @Test
    fun yesterdaysOvernightWindowStillRunningIsTakenOverToo() {
        val owner = ChargeTimerProgram(listOf(ChargeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)), null, null), listOf(0, 1, 1, 1, 1, 1, 0), emptySet())
        // Plugged in Thursday 00:30, while Wednesday's 23:00–07:00 window runs.
        val now = ms(0, 30, dayOffset = 1)
        val plan = ChargePlan(ms(2, dayOffset = 1), ms(5, dayOffset = 1), ms(7, dayOffset = 1), 0.5, 1.0)
        val p = SmartCharge.program(owner, plan, now, carTime)!!
        assertEquals(ChargeWindow(LocalTime.of(2, 0), LocalTime.of(5, 0)), p.window(3))
        assertEquals(ChargeWindow(LocalTime.of(2, 0), LocalTime.of(5, 0)), p.window(4))
        assertEquals(owner.windows[0], p.window(5))
    }

    @Test
    fun noFreeTimerMeansNoPlan() {
        val w = ChargeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))
        val owner = ChargeTimerProgram(listOf(w, ChargeWindow(LocalTime.of(0, 0), LocalTime.of(4, 0)), ChargeWindow(LocalTime.of(1, 0), LocalTime.of(5, 0))), listOf(1, 2, 3, 0, 0, 1, 2), emptySet())
        val plan = ChargePlan(ms(1, dayOffset = 1), ms(4, dayOffset = 1), ms(7, dayOffset = 1), 0.5, 1.0)
        assertNull(SmartCharge.program(owner, plan, evening.toInstant().toEpochMilli(), carTime))
    }

    @Test
    fun windowsFollowTheCarsClockInTenMinuteSteps() {
        // A car clock an hour behind (e.g. not moved to summer time): the window is written in its time.
        val behind: (Long) -> LocalDateTime = { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone).minusHours(1) }
        val plan = ChargePlan(ms(1, 3, dayOffset = 1), ms(4, 47, dayOffset = 1), ms(7, dayOffset = 1), 0.5, 1.0)
        val p = SmartCharge.program(null, plan, evening.toInstant().toEpochMilli(), behind)!!
        assertEquals(ChargeWindow(LocalTime.of(0, 0), LocalTime.of(3, 50)), p.window(4))
    }
}
