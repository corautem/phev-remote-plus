package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.energy.ChargeCosts
import io.github.anwo91.phevlink.core.energy.ChargeRecord
import io.github.anwo91.phevlink.core.energy.PriceAreas
import io.github.anwo91.phevlink.core.energy.PriceModel
import io.github.anwo91.phevlink.core.energy.PricePlan
import io.github.anwo91.phevlink.core.energy.PriceSlot
import io.github.anwo91.phevlink.core.energy.SpotApi
import io.github.anwo91.phevlink.core.energy.Summary
import io.github.anwo91.phevlink.core.energy.TripRecord
import io.github.anwo91.phevlink.core.energy.TripTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnergyTest {
    private val h = 3_600_000L
    private val t0 = 1_791_000_000_000L / h * h // a whole hour

    private fun flat(price: Double, hours: Int = 6, from: Long = t0) = (0 until hours * 4).map {
        PriceSlot(from + it * 900_000L, from + (it + 1) * 900_000L, price)
    }

    @Test
    fun energyFromTheWallFollowsTheBatteryLevels() {
        assertEquals(6.0, ChargeCosts.gridKwh(20, 70, 12.0), 1e-9)
        assertEquals(0.0, ChargeCosts.gridKwh(70, 60, 12.0), 1e-9)
    }

    @Test
    fun spotPriceGetsVatAndGridFee() {
        val plan = PricePlan(PriceModel.SPOT, area = "NO1", gridFeePerKwh = 0.40, support = true)
        val c = ChargeCosts.cost(10.0, t0, t0 + 2 * h, plan, "NO1", flat(0.60))
        // 0.60 before VAT (under the support threshold) → 0.75 with VAT, + 0.40 grid fee = 1.15/kWh.
        assertEquals(1.15, c.perKwh!!, 1e-9)
        assertEquals(11.5, c.cost!!, 1e-9)
        assertEquals("NOK", c.currency)
    }

    @Test
    fun supportTakesNinetyPercentAboveTheThresholdPerHour() {
        val plan = PricePlan(PriceModel.SPOT, support = true)
        val c = ChargeCosts.cost(1.0, t0, t0 + h, plan, "NO2", flat(1.77))
        // 1.77 - 0.9 × (1.77 - 0.77) = 0.87 before VAT → 1.0875 with VAT.
        assertEquals(1.09, c.perKwh!!, 1e-9)
        val off = ChargeCosts.cost(1.0, t0, t0 + h, plan.copy(support = false), "NO2", flat(1.77))
        assertEquals(2.21, off.perKwh!!, 1e-9)
        // No support in Sweden.
        val se = ChargeCosts.cost(1.0, t0, t0 + h, plan, "SE3", flat(1.77))
        assertEquals(2.21, se.perKwh!!, 1e-9)
        assertEquals("SEK", se.currency)
    }

    @Test
    fun spotCostIsUnknownWhenPricesAreMissing() {
        val c = ChargeCosts.cost(5.0, t0, t0 + 4 * h, PricePlan(), "NO1", flat(0.5, hours = 2))
        assertNull(c.cost)
        assertEquals(5.0, c.kwh, 1e-9)
    }

    @Test
    fun energyIsSpreadEvenlyOverTheCharge() {
        val slots = flat(0.40, hours = 1) + flat(0.80, hours = 1, from = t0 + h)
        val c = ChargeCosts.cost(2.0, t0, t0 + 2 * h, PricePlan(support = false), "NO4", slots)
        // Half at 0.40, half at 0.80, no VAT in NO4.
        assertEquals(0.60, c.perKwh!!, 1e-9)
        assertEquals(1.20, c.cost!!, 1e-9)
    }

    @Test
    fun norgesprisAndFixedPrice() {
        assertEquals(0.50, ChargeCosts.cost(1.0, t0, t0 + h, PricePlan(PriceModel.NORGESPRIS), "NO1", emptyList()).perKwh!!, 1e-9)
        assertEquals(0.40, ChargeCosts.cost(1.0, t0, t0 + h, PricePlan(PriceModel.NORGESPRIS), "NO4", emptyList()).perKwh!!, 1e-9)
        val fixed = ChargeCosts.cost(8.0, t0, t0 + h, PricePlan(PriceModel.FIXED, fixedPerKwh = 1.25, gridFeePerKwh = 0.25), "NO1", emptyList())
        assertEquals(12.0, fixed.cost!!, 1e-9)
        assertNull(ChargeCosts.cost(8.0, t0, t0 + h, PricePlan(PriceModel.FIXED), "NO1", emptyList()).cost)
    }

    @Test
    fun priceAreaGuessesForKnownPlaces() {
        val places = mapOf(
            "Oslo" to Triple(59.91, 10.75, "NO1"), "Hamar" to Triple(60.79, 11.07, "NO1"), "Bergen" to Triple(60.39, 5.32, "NO5"),
            "Stavanger" to Triple(58.97, 5.73, "NO2"), "Kristiansand" to Triple(58.15, 8.0, "NO2"), "Trondheim" to Triple(63.43, 10.39, "NO3"),
            "Ålesund" to Triple(62.47, 6.15, "NO3"), "Tromsø" to Triple(69.65, 18.96, "NO4"), "Bodø" to Triple(67.28, 14.4, "NO4"),
            "Halden" to Triple(59.12, 11.39, "NO1"), "Stockholm" to Triple(59.33, 18.07, "SE3"), "Göteborg" to Triple(57.7, 11.97, "SE3"),
            "Malmö" to Triple(55.6, 13.0, "SE4"), "Sundsvall" to Triple(62.39, 17.3, "SE2"), "Luleå" to Triple(65.58, 22.15, "SE1"),
            "Strömstad" to Triple(58.94, 11.17, "SE3"),
        )
        for ((name, p) in places) assertEquals(name, p.third, PriceAreas.guess(p.first, p.second))
        assertNull("Copenhagen", PriceAreas.guess(55.68, 12.57))
        assertNull("Berlin", PriceAreas.guess(52.52, 13.4))
        assertNull("Helsinki", PriceAreas.guess(60.17, 24.94))
    }

    @Test
    fun spotFilesAreParsed() {
        val text = """[{"NOK_per_kWh":0.8312,"EUR_per_kWh":0.0712,"EXR":11.67,"time_start":"2026-10-14T00:00:00+02:00","time_end":"2026-10-14T00:15:00+02:00"},
            {"NOK_per_kWh":0.79,"EUR_per_kWh":0.07,"EXR":11.67,"time_start":"2026-10-14T00:15:00+02:00","time_end":"2026-10-14T00:30:00+02:00"}]"""
        val slots = SpotApi.parse(text)!!
        assertEquals(2, slots.size)
        assertEquals(0.8312, slots[0].perKwh, 1e-9)
        assertEquals(900_000L, slots[0].endMs - slots[0].startMs)
        assertFalse(SpotApi.complete(slots, "NO1", LocalDate.of(2026, 10, 14)))
        assertNull(SpotApi.parse("<html>Not found</html>"))
        assertEquals("https://www.hvakosterstrommen.no/api/v1/prices/2026/10-04_NO1.json", SpotApi.url("no1", LocalDate.of(2026, 10, 4)))
        assertEquals("https://www.elprisetjustnu.se/api/v1/prices/2026/01-31_SE3.json", SpotApi.url("SE3", LocalDate.of(2026, 1, 31)))
        val sek = SpotApi.parse("""[{"SEK_per_kWh":1.5,"time_start":"2026-01-31T00:00:00+01:00","time_end":"2026-01-31T01:00:00+01:00"}]""")!!
        assertEquals(1.5, sek.single().perKwh, 1e-9)
    }

    @Test
    fun aWholeDayIsComplete() {
        val day = LocalDate.of(2026, 10, 14)
        val start = day.atStartOfDay(SpotApi.zone("NO1")).toInstant().toEpochMilli()
        assertTrue(SpotApi.complete(flat(0.5, hours = 24, from = start), "NO1", day))
        // The day summer time ends has 25 hours.
        val dst = LocalDate.of(2026, 10, 25)
        val s2 = dst.atStartOfDay(SpotApi.zone("NO1")).toInstant().toEpochMilli()
        assertFalse(SpotApi.complete(flat(0.5, hours = 24, from = s2), "NO1", dst))
        assertTrue(SpotApi.complete(flat(0.5, hours = 25, from = s2), "NO1", dst))
    }

    @Test
    fun aTripNeedsTheCarToReportAfterComingBack() {
        val tr = TripTracker()
        tr.left(t0, 80)
        tr.back(t0 + 2 * h)
        assertNull("no fresh data yet", tr.onCar(t0 - 1000, 80, t0 + 2 * h + 1000) { "x" })
        val trip = tr.onCar(t0 + 2 * h + 5000, 35, t0 + 2 * h + 6000) { "x" }!!
        assertEquals(80, trip.fromPct)
        assertEquals(35, trip.toPct)
        assertEquals(45, trip.usedPct)
        assertEquals(120, trip.minutes)
        assertNull(tr.open)
    }

    @Test
    fun aShortAbsenceIsNotATrip() {
        val tr = TripTracker()
        tr.left(t0, 80)
        tr.back(t0 + 5 * 60_000L)
        assertNull(tr.open)
        assertNull(tr.onCar(t0 + h, 80, t0 + h) { "x" })
    }

    @Test
    fun aTripClosesWithoutDataAfterAWhile() {
        val tr = TripTracker()
        tr.left(t0, 60)
        tr.back(t0 + h)
        assertNull(tr.onCar(null, null, t0 + h + 10 * 60_000L) { "x" })
        val trip = tr.onCar(null, null, t0 + h + 31 * 60_000L) { "x" }
        assertNotNull(trip)
        assertNull(trip!!.toPct)
        assertNull(trip.usedPct)
    }

    @Test
    fun weekSummaryAddsUp() {
        val charges = listOf(
            ChargeRecord("a", t0, t0 + h, 20, 70, 6.0, 7.5, 1.25, "NOK", "spot", "NO1"),
            ChargeRecord("b", t0 + 24 * h, t0 + 26 * h, 30, 100, 8.4, null, null, "NOK", "spot", "NO1"),
            ChargeRecord("old", t0 - 30 * 24 * h, t0 - 30 * 24 * h + h, 0, 100, 12.0, 15.0, 1.25, "NOK", "spot", "NO1"),
        )
        val trips = listOf(TripRecord("t", t0 + 2 * h, t0 + 4 * h, 70, 30), TripRecord("u", t0 + 5 * h, t0 + 6 * h, 30, 40))
        val s = Summary.of(charges, trips, t0 - h, t0 + 7 * 24 * h, fullRangeKm = 45)
        assertEquals(2, s.charges)
        assertEquals(14.4, s.kwh, 1e-9)
        assertEquals(7.5, s.cost!!, 1e-9)
        assertFalse(s.costComplete)
        assertEquals(2, s.trips)
        assertEquals(180, s.awayMinutes)
        assertEquals(40, s.usedPct)
        assertEquals(18, s.electricKm)
        assertTrue(Summary.of(emptyList(), emptyList(), 0, 1, null).empty)
    }
}
