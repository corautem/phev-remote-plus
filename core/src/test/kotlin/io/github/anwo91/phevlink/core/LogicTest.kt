package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.alerts.AlertEngine
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.Climate
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.RawRegister
import io.github.anwo91.phevlink.core.model.RegisterDecoder
import io.github.anwo91.phevlink.core.planner.ClimatePlanner
import io.github.anwo91.phevlink.core.planner.Departure
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.core.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTest {

    @Test
    fun jsonRoundTrips() {
        val text = io.github.anwo91.phevlink.core.json.Json.write(
            mapOf("a" to 1, "b" to listOf(true, null, 2.5, "x\"y\n"), "c" to mapOf("d" to -3))
        )
        assertEquals("""{"a":1,"b":[true,null,2.5,"x\"y\n"],"c":{"d":-3}}""", text)
        val back = io.github.anwo91.phevlink.core.json.Json.parseObject(text)
        assertEquals(1L, back["a"])
        assertEquals(listOf(true, null, 2.5, "x\"y\n"), back["b"])

        var st = CarState(batteryPercent = 55, charging = true, chargeMinutesRemaining = 80, vin = "JMA123",
            doors = Doors(boot = true), climate = Climate(PreAcState.RUNNING, true, ClimateMode.WINDSCREEN, ClimateDuration.MIN_30, 1000))
        st = RegisterDecoder.apply(st, 0x22, "000100020304".hexToBytes(), 5)
        val codec = io.github.anwo91.phevlink.core.json.Codecs
        val again = codec.carStateFromMap(io.github.anwo91.phevlink.core.json.Json.parseObject(
            io.github.anwo91.phevlink.core.json.Json.write(codec.carStateToMap(st))))
        assertEquals(st, again)

        val deps = listOf(Departure("a", LocalTime.of(7, 15), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), smart = false, mode = ClimateMode.COOL))
        assertEquals(deps, codec.departuresFromJson(codec.departuresToJson(deps)))

        for (c in listOf(CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_20), CarCommand.Headlights(true), CarCommand.Locate(3), CarCommand.RequestUpdate)) {
            assertEquals(c, codec.commandFromMap(io.github.anwo91.phevlink.core.json.Json.parseObject(io.github.anwo91.phevlink.core.json.Json.write(codec.commandToMap(c)))))
        }
    }

    @Test
    fun decodesKnownRegisters() {
        var s = CarState()
        s = RegisterDecoder.apply(s, 0x1d, "48000100".hexToBytes(), 1)
        assertEquals(72, s.batteryPercent)
        assertEquals(true, s.parkingLights)
        s = RegisterDecoder.apply(s, 0x1d, "03000000".hexToBytes(), 2) // placeholder value ignored
        assertEquals(72, s.batteryPercent)
        s = RegisterDecoder.apply(s, 0x1f, "015a00".hexToBytes(), 3)
        assertEquals(true, s.charging)
        assertEquals(90, s.chargeMinutesRemaining)
        s = RegisterDecoder.apply(s, 0x24, "02000001000000010000".hexToBytes(), 4)
        assertEquals(false, s.locked)
        assertEquals(true, s.doors!!.driver)
        assertEquals(true, s.doors!!.boot)
        s = RegisterDecoder.apply(s, 0x1c, "13".hexToBytes(), 5)
        assertEquals(ClimateMode.WINDSCREEN, s.climate.mode)
        assertEquals(ClimateDuration.MIN_20, s.climate.duration)
        s = RegisterDecoder.apply(s, 0x10, "02".hexToBytes(), 6)
        assertEquals(PreAcState.RUNNING, s.climate.state)
        s = RegisterDecoder.apply(s, 0x1a, "0400".hexToBytes(), 7)
        assertEquals(Ignition.ON, s.ignition)
        s = RegisterDecoder.apply(s, 0xc0, "30303532303232303030110000".hexToBytes(), 8)
        assertEquals("0052022000", s.ecuVersion)
        assertEquals(7, s.registers.size)
    }

    @Test
    fun my14ClimateWriteEchoesTimerRegisters() {
        val st = CarState(
            registers = mapOf(
                0x22 to RawRegister(0x22, "aabbccddeeff", 0, 1),
                0x19 to RawRegister(0x19, "00111213141516171800".substring(0, 18), 0, 1),
            )
        )
        val w = CarCommand.ClimateOn(ClimateMode.COOL, ClimateDuration.MIN_30).toWrites(ModelYear.MY14, st)
        assertEquals(2, w.size)
        assertEquals("aabbccddeeff" + "21" + "1112131415161718", w[0].data.toHex())
        assertEquals("02", w[1].data.toHex())
        // Without the registers: phev2mqtt/phevcore fallback pattern
        val fb = CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_10).toWrites(ModelYear.MY14, CarState())
        assertEquals("0000ffffffff02ffffffffffffffff", fb[0].data.toHex())
        assertEquals(listOf(0x04), CarCommand.ClimateOff.toWrites(ModelYear.MY14).map { it.register })
    }

    @Test
    fun clockSyncFormat() {
        val w = CarCommand.SyncClock(LocalDateTime.of(2026, 10, 11, 7, 5, 9)).toWrites(ModelYear.MY14)
        // 2026-10-11 is a Sunday -> dow 0
        assertEquals("1a0a0b0705090000", w[0].data.toHex())
    }

    @Test
    fun alertsOnChargeTransitions() {
        val e = AlertEngine()
        val base = CarState(batteryPercent = 80, charging = true, chargeMinutesRemaining = 30, plug = PlugState.PLUGGED)
        assertTrue(e.onState(base, 0).isEmpty())
        val stopped = e.onState(base.copy(charging = false, chargeMinutesRemaining = null), 1)
        assertEquals(listOf(AlertType.CHARGE_STOPPED), stopped.map { it.type })
        e.onState(base.copy(batteryPercent = 99), 2)
        val done = e.onState(base.copy(batteryPercent = 100, charging = false), 3)
        assertEquals(listOf(AlertType.CHARGE_COMPLETE), done.map { it.type })
        val unplugged = e.onState(base.copy(charging = true), 4) + e.onState(base.copy(charging = false, plug = PlugState.UNPLUGGED, batteryPercent = 60), 5)
        assertTrue(unplugged.none { it.type == AlertType.CHARGE_STOPPED })
    }

    @Test
    fun conditionAlertsFireOnceAfterDelay() {
        val e = AlertEngine()
        val parkedUnlocked = CarState(locked = false, ignition = Ignition.OFF, doors = Doors())
        assertTrue(e.onState(parkedUnlocked, 0).isEmpty())
        assertTrue(e.onTick(9 * 60_000L).isEmpty())
        val fired = e.onTick(10 * 60_000L)
        assertEquals(listOf(AlertType.LEFT_UNLOCKED), fired.map { it.type })
        assertTrue(e.onTick(20 * 60_000L).isEmpty())
        e.onState(parkedUnlocked.copy(locked = true), 21 * 60_000L)
        e.onState(parkedUnlocked, 22 * 60_000L)
        assertEquals(1, e.onTick(33 * 60_000L).size)
    }

    @Test
    fun climateAlerts() {
        val e = AlertEngine()
        val running = CarState(climate = Climate(PreAcState.RUNNING, mode = ClimateMode.HEAT))
        e.onState(CarState(climate = Climate(PreAcState.OFF)), 0)
        e.onState(running, 1)
        assertEquals(listOf(AlertType.CLIMATE_FINISHED), e.onState(CarState(climate = Climate(PreAcState.OFF)), 2).map { it.type })
        e.onState(running, 3)
        assertEquals(listOf(AlertType.CLIMATE_CANCELLED), e.onState(CarState(climate = Climate(PreAcState.CANCELLED)), 4).map { it.type })
    }

    @Test
    fun plannerPicksModeFromWeather() {
        val d = Departure("w", LocalTime.of(7, 30), DayOfWeek.entries.toSet())
        assertEquals(ClimateMode.WINDSCREEN to ClimateDuration.MIN_30, ClimatePlanner.decide(d, -14.0)!!.let { it.mode to it.duration })
        assertEquals(ClimateMode.WINDSCREEN to ClimateDuration.MIN_20, ClimatePlanner.decide(d, -5.0)!!.let { it.mode to it.duration })
        assertEquals(ClimateMode.WINDSCREEN to ClimateDuration.MIN_10, ClimatePlanner.decide(d, 1.0)!!.let { it.mode to it.duration })
        assertEquals(ClimateMode.HEAT, ClimatePlanner.decide(d, 8.0)!!.mode)
        assertNull(ClimatePlanner.decide(d, 17.0))
        assertEquals(ClimateMode.COOL to ClimateDuration.MIN_20, ClimatePlanner.decide(d, 30.0)!!.let { it.mode to it.duration })
        assertEquals(ClimateMode.HEAT, ClimatePlanner.decide(d.copy(smart = false), 30.0)!!.mode)
    }

    @Test
    fun nextDepartureRespectsWeekdays() {
        val zone = ZoneId.of("Europe/Oslo")
        val fri = ZonedDateTime.of(2026, 10, 9, 8, 0, 0, 0, zone) // Friday 08:00, after 07:30
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val d = Departure("w", LocalTime.of(7, 30), weekdays)
        val (_, at) = ClimatePlanner.nextDeparture(listOf(d), fri)!!
        assertEquals(ZonedDateTime.of(2026, 10, 12, 7, 30, 0, 0, zone), at)
        val plan = ClimatePlanner.decide(d, -5.0)!!
        assertEquals(ZonedDateTime.of(2026, 10, 12, 7, 10, 0, 0, zone), ClimatePlanner.startTime(at, plan))
    }
}
