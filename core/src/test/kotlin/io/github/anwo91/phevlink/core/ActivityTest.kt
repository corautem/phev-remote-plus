package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityGroup
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.activity.CarEventTracker
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ChargeKind
import io.github.anwo91.phevlink.core.model.Climate
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The activity log: what the car did, found from its successive states, and the English sentences. */
class ActivityTest {
    private var clock = 1_000_000L
    private val tracker = CarEventTracker { clock }
    private val parked = CarState(batteryPercent = 30, locked = true, doors = Doors(), plug = PlugState.UNPLUGGED, headlights = false, hazardLights = false, ignition = Ignition.OFF)

    private fun feed(s: CarState) = tracker.onState(s).map { it.code }

    @Test
    fun aWholeChargeWithProgressAndSummary() {
        assertEquals(emptyList<String>(), feed(parked)) // first state: nothing to compare
        assertEquals(listOf("car.plugged"), feed(parked.copy(plug = PlugState.PLUGGED)))
        val charging = parked.copy(plug = PlugState.PLUGGED, charging = true, chargeKind = ChargeKind.NORMAL, chargeMinutesRemaining = 200)
        assertEquals(listOf("car.charge_started"), feed(charging))
        assertEquals(emptyList<String>(), feed(charging.copy(batteryPercent = 38)))
        clock += 30 * 60_000
        assertEquals(listOf("car.charge_progress"), feed(charging.copy(batteryPercent = 41)))
        clock += 90 * 60_000
        val events = tracker.onState(charging.copy(batteryPercent = 100, charging = false, chargeKind = ChargeKind.NONE))
        assertEquals(listOf("car.charge_stopped", "charge.session"), events.map { it.code })
        assertEquals("full", events[0].str("why"))
        val session = events[1]
        assertEquals(30, session.int("fromPct"))
        assertEquals(100, session.int("toPct"))
        assertEquals(120, session.int("minutes"))
        assertEquals(7.0, session.params["kwh"])
        assertEquals("Charged 30 → 100 % in 2 h 0 min (≈ 7.0 kWh)", ActivityText.english(session))
        assertEquals(ActivityGroup.CHARGING, session.group)
        assertEquals(listOf("car.unplugged"), feed(parked.copy(batteryPercent = 100)))
    }

    @Test
    fun climateLocksDoorsLightsAndPower() {
        feed(parked)
        assertEquals(listOf("car.climate_on"), feed(parked.copy(climate = Climate(state = PreAcState.RUNNING, mode = ClimateMode.HEAT))))
        assertEquals(listOf("car.climate_cancelled"), feed(parked.copy(climate = Climate(state = PreAcState.CANCELLED))))
        assertEquals(listOf("car.unlocked", "car.door_open"), feed(parked.copy(locked = false, doors = Doors(driver = true))))
        assertEquals(listOf("car.doors_closed", "car.headlights_on", "car.switched_on"), feed(parked.copy(locked = false, headlights = true, ignition = Ignition.ON)))
        assertEquals(listOf("car.locked", "car.headlights_off", "car.switched_off"), feed(parked))
    }

    @Test
    fun unknownValuesNeverCount() {
        feed(parked)
        assertEquals(emptyList<String>(), feed(parked.copy(plug = PlugState.UNKNOWN, locked = null, ignition = Ignition.UNKNOWN, doors = null)))
        assertEquals(emptyList<String>(), feed(parked))
    }

    @Test
    fun travelsAsJsonAndReadsAsEnglish() {
        val a = Activity(5_000L, "Kari", "cmd.climate", mapOf("mode" to "HEAT", "minutes" to 20), via = "bridge")
        val back = Activity.fromMap(Json.parseObject(Json.write(a.toMap())))!!
        assertEquals(a.copy(params = back.params), back)
        assertEquals(20, back.int("minutes"))
        assertEquals("Kari started heating for 20 min", ActivityText.english(back))
        val failed = a.copy(ok = false, detail = "Car did not answer")
        assertTrue(ActivityText.english(failed).endsWith("— failed: Car did not answer"))
        assertEquals("Plugged in at 40 %", ActivityText.english(Activity(1, Activity.CAR, "car.plugged", mapOf("pct" to 40))))
    }
}
