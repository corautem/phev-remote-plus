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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SmartChargeTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private val evening = ZonedDateTime.of(2026, 10, 14, 18, 3, 0, 0, zone) // a Wednesday

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
    fun aFlatPriceFinishesJustBeforeItsNeeded() {
        val plan = SmartCharge.latest(evening.toInstant().toEpochMilli(), ms(7, dayOffset = 1), 330)!!
        assertEquals(ms(1, 30, dayOffset = 1), plan.startMs)
        assertEquals(ms(7, dayOffset = 1), plan.endMs)
        assertNull(SmartCharge.latest(ms(6, dayOffset = 1), ms(7, dayOffset = 1), 330))
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
        val p = SmartCharge.program(owner, plan, zone)!!
        // Thursday (4) gets its own timer 01:00–05:10 with full charge; the other weekdays keep timer 1.
        assertEquals(ChargeWindow(LocalTime.of(1, 0), LocalTime.of(5, 10)), p.window(4))
        assertTrue(4 in p.fullDays && 6 in p.fullDays)
        for (d in listOf(1, 2, 3, 5)) assertEquals(owner.windows[0], p.window(d))
        assertNull(p.window(0))
        // No timer before: just tonight's.
        val fresh = SmartCharge.program(null, plan, zone)!!
        assertEquals(listOf(4), (0..6).filter { fresh.window(it) != null })
        assertNotNull(ChargePlan.fromMap(plan.toMap()))
        assertEquals(plan, ChargePlan.fromMap(plan.toMap()))
    }
}
