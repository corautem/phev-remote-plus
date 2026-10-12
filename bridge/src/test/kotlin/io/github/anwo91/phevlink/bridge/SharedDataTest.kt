package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.planner.Departure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shared data on the bridge (departures, car name, …) and the bridge running departures while the car is home. */
class SharedDataTest {
    private val log = DiagLog()
    private val dir: File = Files.createTempDirectory("shared").toFile()
    private val file = File(dir, "shared.json")
    private val zone = ZoneId.of("Europe/Oslo")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun departuresJson(vararg d: Departure) = Json.parse(Codecs.departuresToJson(d.toList()))

    @Test
    fun storeKeepsOnlyKnownKeysAndBumpsTheRevision() {
        val s = SharedStore(file, log) { 1000L }
        assertEquals(0L, s.rev)
        s.update(mapOf("carName" to "Blue", "token" to "nope", "fullRangeKm" to "lots"), "Pixel 8")
        assertEquals(1L, s.rev)
        assertEquals("Blue", s.snapshot()["carName"])
        assertFalse(s.snapshot().containsKey("token"))
        assertFalse("wrong type is dropped", s.snapshot().containsKey("fullRangeKm"))
        assertEquals("Pixel 8", s.snapshot()["updatedBy"])
        assertTrue(log.tail(20).any { it.text.contains("ignored token, fullRangeKm") })

        // Nothing accepted: no new revision.
        s.update(mapOf("token" to "x"), "web")
        assertEquals(1L, s.rev)

        // Survives a restart.
        val again = SharedStore(file, log)
        assertEquals(1L, again.rev)
        assertEquals("Blue", again.snapshot()["carName"])
    }

    @Test
    fun storeReadsDeparturesZoneAndPlace() {
        val s = SharedStore(file, log)
        val dep = Departure("a", LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        s.update(mapOf("departures" to departuresJson(dep), "timeZone" to "Europe/Oslo", "homeLat" to 59.91, "homeLon" to 10.75), "phone")
        assertEquals(listOf(dep), s.departures())
        assertEquals(zone, s.zone())
        assertEquals(59.91 to 10.75, s.location())
        assertTrue(log.tail(20).any { it.text.contains("1 departure(s); 07:30 MOFR smart") })
    }

    @Test
    fun brokenFileStartsEmpty() {
        file.writeText("{not json")
        val s = SharedStore(file, log)
        assertEquals(0L, s.rev)
        assertTrue(s.departures().isEmpty())
        assertNull(s.location())
    }

    // ---------------------------------------------------------------------------------------- departures

    private class Rig(dir: File, val log: DiagLog, zone: ZoneId, dep: Departure, temp: Double?) {
        val store = SharedStore(File(dir, "shared.json"), log)
        var home = true
        var clock: ZonedDateTime = ZonedDateTime.of(2026, 10, 12, 6, 0, 0, 0, zone) // a Monday
        val sent = ArrayList<CarCommand>()
        var answer: CommandResult = CommandResult.Success(CarCommand.ClimateOff, 1200)
        var weatherAsked = 0
        val runner = DepartureRunner(store, log, { home }, { sent += it; answer }, { _, _, _ -> weatherAsked++; temp }, { clock })

        init {
            store.update(mapOf(
                "departures" to Json.parse(Codecs.departuresToJson(listOf(dep))),
                "homeLat" to 59.9, "homeLon" to 10.7, "timeZone" to zone.id,
            ), "test")
        }

        fun at(h: Int, m: Int) = runBlocking {
            clock = clock.withHour(h).withMinute(m)
            runner.tick()
        }

        @Suppress("UNCHECKED_CAST")
        val last get() = store.snapshot()["lastDeparture"] as Map<String, Any?>?
    }

    private val monday730 = Departure("d1", LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY))

    @Test
    fun coldMorningPlansThenStartsOnTime() {
        val r = Rig(dir, log, zone, monday730, -5.0)
        r.at(6, 50) // more than 35 min before: nothing yet
        assertEquals(0, r.weatherAsked)
        r.at(6, 56) // planned: -5 °C → defrost 20 min, start 07:10 (07:09 with margin)
        assertEquals(1, r.weatherAsked)
        assertTrue(r.sent.isEmpty())
        r.at(7, 5)
        assertTrue(r.sent.isEmpty())
        r.at(7, 9)
        assertEquals(listOf<CarCommand>(CarCommand.ClimateOn(ClimateMode.WINDSCREEN, ClimateDuration.MIN_20)), r.sent)
        assertEquals("started", r.last?.get("outcome"))
        assertEquals("d1", r.last?.get("id"))
        r.at(7, 10) // only once
        assertEquals(1, r.sent.size)
        assertEquals(1, r.weatherAsked)
    }

    @Test
    fun mildWeatherSkips() {
        val r = Rig(dir, log, zone, monday730, 16.0)
        r.at(7, 0)
        r.at(7, 25)
        assertTrue(r.sent.isEmpty())
        assertEquals("skipped", r.last?.get("outcome"))
    }

    @Test
    fun carAwayLeavesItToThePhone() {
        val r = Rig(dir, log, zone, monday730, 5.0)
        r.home = false
        r.at(7, 0)
        r.at(7, 19) // 5 °C → heat 20 min → start 07:10
        assertTrue(r.sent.isEmpty())
        assertEquals("away", r.last?.get("outcome"))
        assertTrue(log.tail(30).any { it.text.contains("a phone near the car starts it") })
    }

    @Test
    fun fixedDepartureNeedsNoWeatherAndFailureIsRecorded() {
        val fixed = monday730.copy(smart = false, mode = ClimateMode.COOL, duration = ClimateDuration.MIN_10)
        val r = Rig(dir, log, zone, fixed, null)
        r.answer = CommandResult.Failure(CarCommand.ClimateOff, "Car did not answer")
        r.at(7, 19)
        assertEquals(0, r.weatherAsked)
        assertEquals(listOf<CarCommand>(CarCommand.ClimateOn(ClimateMode.COOL, ClimateDuration.MIN_10)), r.sent)
        assertEquals("failed", r.last?.get("outcome"))
        assertEquals("Car did not answer", r.last?.get("detail"))
    }

    @Test
    fun tooLateOrOtherDayDoesNothing() {
        val r = Rig(dir, log, zone, monday730, -5.0)
        r.at(7, 45) // after the departure (bridge was off)
        r.clock = r.clock.plusDays(1) // Tuesday: not a departure day
        r.at(7, 15)
        assertTrue(r.sent.isEmpty())
        assertEquals(0, r.weatherAsked)
    }
}
