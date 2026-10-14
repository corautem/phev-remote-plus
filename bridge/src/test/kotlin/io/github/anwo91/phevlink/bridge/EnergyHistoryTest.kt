package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.model.CarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class EnergyHistoryTest {
    private val log = DiagLog()
    private val zone = ZoneId.of("Europe/Oslo")
    private val dir: File = Files.createTempDirectory("history").toFile()
    private var t = ZonedDateTime.of(2026, 10, 11, 2, 0, 0, 0, zone) // a Sunday night
    private val fetched = ArrayList<String>()
    private val raised = ArrayList<Alert>()

    /** Every hour of every day at 0.60 (before VAT), in 15-minute slots. */
    private fun fakeDay(url: String): String? {
        fetched += url
        val date = Regex("""(\d{4})/(\d{2})-(\d{2})_""").find(url)!!.groupValues.let { java.time.LocalDate.of(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
        var s = date.atStartOfDay(zone)
        val end = date.plusDays(1).atStartOfDay(zone)
        val items = ArrayList<String>()
        while (s.isBefore(end)) {
            val e = s.plusMinutes(15)
            items += """{"NOK_per_kWh":0.60,"time_start":"${s.toOffsetDateTime()}","time_end":"${e.toOffsetDateTime()}"}"""
            s = e
        }
        return items.joinToString(",", "[", "]")
    }

    private fun rig(shared: Map<String, Any?> = emptyMap(), fetch: (String) -> String? = ::fakeDay): Triple<EnergyHistory, SharedStore, ActivityLog> {
        val store = SharedStore(File(dir, "shared.json"), log)
        store.update(mapOf("timeZone" to "Europe/Oslo", "homeLat" to 59.91, "homeLon" to 10.75, "fullRangeKm" to 40) + shared, "test")
        val activity = ActivityLog(File(dir, "activity.jsonl"), log)
        val h = EnergyHistory(
            dir, store, SpotPrices(File(dir, "prices"), log, fetch = fetch, now = { t.toInstant().toEpochMilli() }), activity, log,
            CoroutineScope(SupervisorJob() + Dispatchers.Default), now = { t.toInstant().toEpochMilli() },
        )
        h.raise = { raised += it }
        return Triple(h, store, activity)
    }

    private fun session(endAt: ZonedDateTime, from: Int, to: Int, minutes: Int) = Activity(
        endAt.toInstant().toEpochMilli(), Activity.CAR, "charge.session", linkedMapOf("fromPct" to from, "toPct" to to, "minutes" to minutes, "kwh" to 0.0),
    )

    @Suppress("UNCHECKED_CAST")
    private fun charges(h: EnergyHistory) = h.describe(100)["charges"] as List<Map<String, Any?>>

    @Test
    fun aChargeIsPricedFromTheSpotPricesOfItsHours() {
        val (h) = rig(mapOf("priceGridFee" to 0.4))
        h.record(session(t, 20, 70, 180))
        val c = charges(h).single()
        assertEquals(6.0, c["kwh"] as Double, 1e-9) // 50 % of 12 kWh
        // Area guessed from Oslo: NO1. 0.60 + 25 % VAT + 0.40 grid fee = 1.15/kWh.
        assertEquals("NO1", c["area"])
        assertEquals(1.15, c["perKwh"] as Double, 1e-9)
        assertEquals(6.9, c["cost"] as Double, 1e-9)
        // 23:00–02:00: both days' prices.
        assertEquals(listOf("10-10_NO1.json", "10-11_NO1.json"), fetched.map { it.substringAfterLast('/') })
        // Kept: a new instance reads it back, and the prices were kept too (no second fetch).
        val (again) = rig()
        assertEquals(1, charges(again).size)
        assertTrue(File(dir, "prices").listFiles()!!.isNotEmpty())
    }

    @Test
    fun aCostMissingAtFirstIsFilledInLater() {
        var online = false
        val (h) = rig(fetch = { if (online) fakeDay(it) else null })
        h.record(session(t, 40, 90, 120))
        assertNull(charges(h).single()["cost"])
        online = true
        t = t.plusHours(1) // past the 30-minute wait before asking again
        h.fillCosts()
        assertEquals(6.0 * 0.75, charges(h).single()["cost"] as Double, 1e-9)
        assertTrue(log.dump().contains("filled in"))
    }

    @Test
    fun fixedAndNorgesprisNeedNoPrices() {
        val (h, store) = rig(mapOf("priceModel" to "fixed", "priceFixed" to 2.0, "fullChargeKwh" to 13.0))
        h.record(session(t, 0, 100, 300))
        assertEquals(26.0, charges(h).single()["cost"] as Double, 1e-9)
        store.update(mapOf("priceModel" to "norgespris"), "test")
        h.record(session(t.plusHours(10), 50, 100, 150))
        assertEquals(6.5 * 0.5, charges(h).first()["cost"] as Double, 1e-9)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun theSupportUsesTheWholeHourAlsoForAHalfHourCharge() {
        // 22:00–22:30 at 2.00, 22:30–23:00 at 0.80: the hour averages 1.40, so the support is 0.9 × (1.40 − 0.77).
        val (h) = rig(fetch = { url ->
            fakeDay(url)!!.replace(Regex(""""NOK_per_kWh":0.60,"time_start":"(\d{4}-\d{2}-\d{2})T22:(00|15)""")) { m ->
                """"NOK_per_kWh":2.00,"time_start":"${m.groupValues[1]}T22:${m.groupValues[2]}"""
            }.replace(Regex(""""NOK_per_kWh":0.60,"time_start":"(\d{4}-\d{2}-\d{2})T22:(30|45)""")) { m ->
                """"NOK_per_kWh":0.80,"time_start":"${m.groupValues[1]}T22:${m.groupValues[2]}"""
            }
        })
        h.record(session(t.minusDays(1).withHour(23).withMinute(0), 50, 60, 30))
        // (0.80 − 0.567) × 1.25 = 0.29/kWh.
        assertEquals(0.29, charges(h).single()["perKwh"] as Double, 1e-9)
    }

    @Test
    fun aParkedCarWithItsWifiAsleepIsNotATrip() {
        val (h) = rig()
        val left = t.toInstant().toEpochMilli()
        h.tick(mapOf("state" to "not_seen", "lastSeenMs" to left), CarState(batteryPercent = 64, updatedAtMs = left))
        t = t.plusDays(2)
        h.tick(mapOf("state" to "in_reach", "sinceMs" to t.toInstant().toEpochMilli()), CarState(batteryPercent = 63, updatedAtMs = t.toInstant().toEpochMilli() + 1))
        @Suppress("UNCHECKED_CAST")
        assertTrue((h.describe(10)["trips"] as List<Any?>).isEmpty())
        assertTrue(log.dump().contains("Wi-Fi asleep"))
    }

    @Test
    fun nothingChargedIsNotKept() {
        val (h) = rig()
        h.record(session(t, 70, 70, 5))
        assertTrue(charges(h).isEmpty())
    }

    @Test
    fun aTripFromLeavingToComingBack() {
        val (h, _, activity) = rig()
        val left = t.toInstant().toEpochMilli()
        var car = CarState(batteryPercent = 85, updatedAtMs = left)
        h.tick(mapOf("state" to "in_reach", "sinceMs" to left), car)
        t = t.plusMinutes(3)
        h.tick(mapOf("state" to "not_seen", "sinceMs" to t.toInstant().toEpochMilli(), "lastSeenMs" to left), car)
        t = t.plusHours(2)
        h.tick(mapOf("state" to "in_reach", "sinceMs" to t.toInstant().toEpochMilli()), car)
        car = CarState(batteryPercent = 30, updatedAtMs = t.toInstant().toEpochMilli() + 5000)
        t = t.plusMinutes(1)
        h.tick(mapOf("state" to "in_reach"), car)
        @Suppress("UNCHECKED_CAST")
        val trip = (h.describe(10)["trips"] as List<Map<String, Any?>>).single()
        assertEquals(85, trip["fromPct"])
        assertEquals(30, trip["toPct"])
        assertEquals(left, trip["leftMs"])
        val a = activity.recent(5).first { it.code == "car.trip" }
        assertEquals("Back after 2 h 3 min — battery 85 → 30 %", io.github.anwo91.phevlink.core.activity.ActivityText.english(a))
    }

    @Test
    fun anOpenTripSurvivesARestart() {
        val (h) = rig()
        val left = t.toInstant().toEpochMilli()
        h.tick(mapOf("state" to "not_seen", "lastSeenMs" to left), CarState(batteryPercent = 70, updatedAtMs = left))
        assertTrue(File(dir, "trip_open.json").exists())
        val (h2) = rig()
        t = t.plusHours(3)
        h2.tick(mapOf("state" to "in_reach"), CarState(batteryPercent = 70, updatedAtMs = left))
        h2.tick(mapOf("state" to "in_reach"), CarState(batteryPercent = 52, updatedAtMs = t.toInstant().toEpochMilli() + 1))
        @Suppress("UNCHECKED_CAST")
        val trip = (h2.describe(10)["trips"] as List<Map<String, Any?>>).single()
        assertEquals(18 + 0, (trip["fromPct"] as Int) - (trip["toPct"] as Int))
    }

    @Test
    fun theWeeklySummaryGoesOutSundayEveningOnce() {
        val (h) = rig(mapOf("priceModel" to "fixed", "priceFixed" to 1.0))
        h.record(session(t, 20, 70, 180))
        t = t.withHour(18).withMinute(59)
        h.tick(null, CarState(), tripsOn = false)
        assertTrue(raised.isEmpty())
        t = t.withHour(19).withMinute(2)
        h.tick(null, CarState(), tripsOn = false)
        val a = raised.single()
        assertEquals(AlertType.WEEKLY_SUMMARY, a.type)
        assertEquals(1, a.params["charges"])
        assertEquals(6.0, a.params["cost"] as Double, 1e-9)
        assertEquals("1 charge(s), 6.0 kWh, ≈ 6 NOK", a.message)
        t = t.plusMinutes(5)
        h.tick(null, CarState(), tripsOn = false)
        val (restarted) = rig()
        restarted.raise = { raised += it }
        restarted.tick(null, CarState(), tripsOn = false)
        assertEquals("once a week, also after a restart", 1, raised.size)
    }

    @Test
    fun aQuietWeekSendsNothing() {
        val (h) = rig()
        t = t.withHour(19).withMinute(5)
        h.tick(null, CarState(), tripsOn = false)
        assertTrue(raised.isEmpty())
        assertNotNull(log.dump().lines().firstOrNull { it.contains("nothing to send") })
    }
}
