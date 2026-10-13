package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.protocol.ModelYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HomeAssistantTest {
    private val ha = HomeAssistant("phev_abc123", "nb")
    private val device = HomeAssistant.Device("Outlander", ModelYear.MY14, "JMAXDGG2WGZ000001", "1.0.56")

    @Test
    fun announcesOneDeviceWithItsEntities() {
        val d = ha.discovery(device)
        assertEquals(25, d.size)
        assertEquals("unique ids", d.size, d.map { Json.parseObject(it.second)["unique_id"] }.toSet().size)
        val byTopic = d.toMap()
        val battery = Json.parseObject(byTopic.getValue("homeassistant/sensor/phev_abc123/battery/config"))
        assertEquals("Batteri", battery["name"])
        assertEquals("battery", battery["device_class"])
        assertEquals("phevlink/phev_abc123/state", battery["state_topic"])
        assertEquals("phevlink/phev_abc123/availability", battery["availability_topic"])
        @Suppress("UNCHECKED_CAST")
        val dev = battery["device"] as Map<String, Any?>
        assertEquals("Outlander", dev["name"])
        assertEquals("Outlander PHEV (2014–2017)", dev["model"])
        val climate = Json.parseObject(byTopic.getValue("homeassistant/switch/phev_abc123/climate/config"))
        assertEquals("phevlink/phev_abc123/cmd/climate", climate["command_topic"])
        val mode = Json.parseObject(byTopic.getValue("homeassistant/select/phev_abc123/climate_mode/config"))
        assertEquals(listOf("Kjøling", "Varme", "Avising"), mode["options"])
        val event = Json.parseObject(byTopic.getValue("homeassistant/event/phev_abc123/event/config"))
        assertTrue((event["event_types"] as List<*>).contains("charge_finished"))
        assertEquals(d.map { it.first }, ha.discoveryTopics())
    }

    @Test
    fun stateHasEverythingAndNullsForUnknowns() {
        val car = CarState(
            modelYear = ModelYear.MY14, batteryPercent = 64, charging = true, chargeMinutesRemaining = 95, plug = PlugState.PLUGGED,
            locked = false, doors = Doors(boot = true), updatedAtMs = 1_760_000_000_000,
        )
        val x = HomeAssistant.Extra(home = true, linked = true, fullRangeKm = 45, quickMode = ClimateMode.WINDSCREEN, quickDuration = ClimateDuration.MIN_20, zone = ZoneId.of("Europe/Oslo"))
        val s = Json.parseObject(ha.state(car, x, 1_760_000_000_000))
        assertEquals(64L, (s["battery"] as Number).toLong())
        assertEquals(29L, (s["range_km"] as Number).toLong())
        assertEquals(true, s["charging"])
        assertEquals(true, s["plugged"])
        assertEquals(95L, (s["charge_minutes"] as Number).toLong())
        assertTrue(s["charge_done"].toString(), s["charge_done"].toString().startsWith("2025-10-09T"))
        assertEquals(false, s["locked"])
        assertEquals(true, s["doors_open"])
        assertEquals(true, (s["door_detail"] as Map<*, *>)["boot"])
        assertEquals("Avising", s["climate_mode"])
        assertEquals("20 min", s["climate_minutes"])
        assertNull(s["headlights"])
        assertEquals(true, s["home"])
    }

    @Test
    fun commandsAndWhatIsIgnored() {
        val t = "phevlink/phev_abc123/cmd/"
        assertEquals(HomeAssistant.Action.ClimateOn, ha.command(t + "climate", "ON", false))
        assertEquals(CarCommand.ClimateOff, (ha.command(t + "climate", "OFF", false) as HomeAssistant.Action.Car).command)
        assertTrue("a retained command would replay on every connect", ha.command(t + "climate", "ON", true) is HomeAssistant.Action.Ignore)
        assertEquals(mapOf("quickMode" to "WINDSCREEN"), (ha.command(t + "climate_mode", "Avising", false) as HomeAssistant.Action.Shared).changes)
        assertEquals(mapOf("quickMode" to "HEAT"), (ha.command(t + "climate_mode", "heat", false) as HomeAssistant.Action.Shared).changes)
        assertEquals(mapOf("quickDuration" to 30), (ha.command(t + "climate_minutes", "30 min", false) as HomeAssistant.Action.Shared).changes)
        assertEquals(CarCommand.Headlights(true), (ha.command(t + "headlights", "ON", false) as HomeAssistant.Action.Car).command)
        assertEquals(CarCommand.ChargeNow, (ha.command(t + "charge_now", "PRESS", false) as HomeAssistant.Action.Car).command)
        assertEquals(CarCommand.ResumeChargeTimer, (ha.command(t + "charge_timer", "PRESS", false) as HomeAssistant.Action.Car).command)
        assertTrue(ha.command(t + "unlock", "PRESS", false) is HomeAssistant.Action.Ignore)
        assertTrue(ha.command(t + "climate", "maybe", false) is HomeAssistant.Action.Ignore)
        assertTrue(ha.command("phevlink/other/cmd/climate", "ON", false) is HomeAssistant.Action.Ignore)
    }

    @Test
    fun activityBecomesEvents() {
        fun ev(a: Activity) = ha.event(a)?.let { Json.parseObject(it)["event_type"] }
        assertEquals("charge_finished", ev(Activity(1, Activity.CAR, "car.charge_stopped", mapOf("why" to "full", "pct" to 100))))
        assertEquals("charge_stopped", ev(Activity(1, Activity.CAR, "car.charge_stopped", mapOf("why" to "unplugged"))))
        assertEquals("car_home", ev(Activity(1, Activity.BRIDGE, "bridge.carwifi_back", mapOf("minutes" to 600))))
        assertEquals("car_away", ev(Activity(1, Activity.BRIDGE, "bridge.carwifi_lost", mapOf("inSight" to false))))
        assertEquals("alarm", ev(Activity(1, Activity.CAR, "car.alarm")))
        assertEquals("command_failed", ev(Activity(1, "Pixel", "cmd.climate", mapOf("mode" to "HEAT"), ok = false, detail = "Could not reach the car in time")))
        assertNull(ev(Activity(1, "Pixel", "cmd.climate", mapOf("mode" to "HEAT"))))
        assertNull(ev(Activity(1, "Pixel", "shared.changed")))
        val full = Json.parseObject(ha.event(Activity(1, Activity.CAR, "car.charge_stopped", mapOf("why" to "full", "pct" to 100)))!!)
        assertEquals("Charging finished — full", full["text"])
        assertEquals(100L, (full["pct"] as Number).toLong())
    }
}
