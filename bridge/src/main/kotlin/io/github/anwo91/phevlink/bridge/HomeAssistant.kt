package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ChargeTimerProgram
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.TheftAlarm
import io.github.anwo91.phevlink.core.protocol.ModelYear
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The car as Home Assistant sees it, over MQTT with Home Assistant's "discovery": the bridge announces one device (the
 * car) with its sensors, switches, buttons and an event entity, and Home Assistant shows it without any setup there.
 * Other systems (openHAB, ioBroker, Homey's MQTT app, Node-RED) can use the same topics:
 *
 *   phevlink/<id>/availability   online / offline (the bridge; "offline" is the bridge's last will)
 *   phevlink/<id>/state          everything as one JSON object, retained
 *   phevlink/<id>/event          {"event_type": "charge_finished", …} — charging done, alarm, car came home, …
 *   phevlink/<id>/activity       every activity line (who did what) as JSON
 *   phevlink/<id>/cmd/<what>     commands: climate ON/OFF, climate_mode, climate_minutes, headlights ON/OFF,
 *                                parking_lights ON/OFF, locate, charge_now, charge_timer, refresh (PRESS)
 *
 * Names are in the app's language (Home Assistant shows names as given). Pure: no I/O, so it is easy to test.
 */
class HomeAssistant(val id: String, private val language: String = "en", val prefix: String = "homeassistant") {
    val base = "phevlink/$id"
    val availabilityTopic = "$base/availability"
    val stateTopic = "$base/state"
    val eventTopic = "$base/event"
    val activityTopic = "$base/activity"
    val commandTopics = "$base/cmd/+"
    val statusTopic = "$prefix/status"

    data class Device(val carName: String?, val modelYear: ModelYear, val vin: String?, val bridgeBuild: String)

    /** What a command message asks for. */
    sealed interface Action {
        data class Car(val command: CarCommand, val label: String) : Action
        /** "Climate ON": mode and duration from the shared climate defaults (and the weather when smart). */
        data object ClimateOn : Action
        data class Shared(val changes: Map<String, Any?>, val label: String) : Action
        data class Ignore(val why: String) : Action
    }

    /** Inputs for the state message, apart from the car. */
    data class Extra(val home: Boolean?, val linked: Boolean, val fullRangeKm: Int?, val quickMode: ClimateMode, val quickDuration: ClimateDuration, val zone: ZoneId)

    // ------------------------------------------------------------------ discovery

    private data class Entity(val component: String, val key: String, val config: Map<String, Any?>)

    private fun entities(): List<Entity> {
        val e = ArrayList<Entity>()
        fun add(component: String, key: String, vararg cfg: Pair<String, Any?>) {
            e += Entity(component, key, linkedMapOf<String, Any?>("name" to name(key)) + cfg.toMap())
        }
        fun bin(key: String) = "{{ none if value_json.$key is none else ('ON' if value_json.$key else 'OFF') }}"
        val st = "state_topic" to stateTopic
        fun cmd(key: String) = "command_topic" to "$base/cmd/$key"
        add("sensor", "battery", st, "value_template" to "{{ value_json.battery }}", "device_class" to "battery", "unit_of_measurement" to "%", "state_class" to "measurement")
        add("sensor", "range", st, "value_template" to "{{ value_json.range_km }}", "device_class" to "distance", "unit_of_measurement" to "km", "icon" to "mdi:map-marker-distance")
        add("binary_sensor", "charging", st, "value_template" to bin("charging"), "device_class" to "battery_charging")
        add("binary_sensor", "plugged", st, "value_template" to bin("plugged"), "device_class" to "plug")
        add("sensor", "charge_left", st, "value_template" to "{{ value_json.charge_minutes }}", "device_class" to "duration", "unit_of_measurement" to "min")
        add("sensor", "charge_done", st, "value_template" to "{{ value_json.charge_done }}", "device_class" to "timestamp")
        add("sensor", "charge_timer", st, "value_template" to "{{ value_json.charge_timer }}", "icon" to "mdi:timer-outline")
        add("button", "charge_now", cmd("charge_now"), "payload_press" to "PRESS", "icon" to "mdi:ev-station")
        add("button", "charge_on_timer", cmd("charge_timer"), "payload_press" to "PRESS", "icon" to "mdi:timer-play-outline")
        add("switch", "climate", st, cmd("climate"), "value_template" to bin("climate"), "payload_on" to "ON", "payload_off" to "OFF", "icon" to "mdi:fan")
        add("select", "climate_mode", st, cmd("climate_mode"), "value_template" to "{{ value_json.climate_mode }}", "options" to ClimateMode.entries.map { modeLabel(it) }, "icon" to "mdi:thermostat")
        add("select", "climate_minutes", st, cmd("climate_minutes"), "value_template" to "{{ value_json.climate_minutes }}", "options" to ClimateDuration.entries.map { "${it.minutes} min" }, "icon" to "mdi:timer-sand")
        add("sensor", "climate_ends", st, "value_template" to "{{ value_json.climate_ends }}", "device_class" to "timestamp")
        add("binary_sensor", "lock", st, "value_template" to "{{ none if value_json.locked is none else ('OFF' if value_json.locked else 'ON') }}", "device_class" to "lock")
        add("binary_sensor", "doors", st, "value_template" to bin("doors_open"), "device_class" to "door", "json_attributes_topic" to stateTopic, "json_attributes_template" to "{{ value_json.door_detail | tojson }}")
        add("switch", "headlights", st, cmd("headlights"), "value_template" to bin("headlights"), "payload_on" to "ON", "payload_off" to "OFF", "icon" to "mdi:car-light-high")
        add("switch", "parking_lights", st, cmd("parking_lights"), "value_template" to bin("parking_lights"), "payload_on" to "ON", "payload_off" to "OFF", "icon" to "mdi:car-parking-lights")
        add("button", "locate", cmd("locate"), "payload_press" to "PRESS", "icon" to "mdi:car-search")
        add("binary_sensor", "alarm", st, "value_template" to bin("alarm"), "device_class" to "safety")
        add("binary_sensor", "power", st, "value_template" to bin("power"), "device_class" to "power")
        add("binary_sensor", "home", st, "value_template" to bin("home"), "device_class" to "presence")
        add("binary_sensor", "link", st, "value_template" to bin("link"), "device_class" to "connectivity", "entity_category" to "diagnostic")
        add("sensor", "updated", st, "value_template" to "{{ value_json.updated }}", "device_class" to "timestamp", "entity_category" to "diagnostic")
        add("button", "refresh", cmd("refresh"), "payload_press" to "PRESS", "entity_category" to "diagnostic")
        add("event", "event", "state_topic" to eventTopic, "event_types" to EVENT_TYPES, "icon" to "mdi:car-info")
        return e
    }

    /** (topic, retained JSON) for every entity. */
    fun discovery(d: Device): List<Pair<String, String>> {
        val device = linkedMapOf<String, Any?>(
            "identifiers" to listOf(id),
            "name" to (d.carName?.takeIf { it.isNotBlank() } ?: "Outlander PHEV"),
            "manufacturer" to "Mitsubishi",
            // Only keys every Home Assistant since 2023 accepts (newer ones like model_id would make older ones
            // refuse the whole entity).
            "model" to "Outlander PHEV" + (d.modelYear.takeIf { it != ModelYear.UNKNOWN }?.let { " (${it.label})" } ?: ""),
            "sw_version" to "PHEV Link bridge ${d.bridgeBuild}",
        )
        val origin = mapOf("name" to "PHEV Link", "sw_version" to d.bridgeBuild)
        return entities().map { en ->
            val cfg = LinkedHashMap(en.config)
            cfg["unique_id"] = "${id}_${en.key}"
            cfg["availability_topic"] = availabilityTopic
            cfg["device"] = device
            cfg["origin"] = origin
            configTopic(en) to Json.write(cfg)
        }
    }

    /** Topics to clear (empty retained message) so Home Assistant removes the car when the link is switched off. */
    fun discoveryTopics(): List<String> = entities().map { configTopic(it) }

    private fun configTopic(en: Entity) = "$prefix/${en.component}/$id/${en.key}/config"

    // ------------------------------------------------------------------ state

    fun state(car: CarState, x: Extra, nowMs: Long): String {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(x.zone)
        fun ts(ms: Long?) = ms?.let { iso.format(Instant.ofEpochMilli(it)) }
        val d = car.doors
        val climateEnds = if (car.climate.isRunning) {
            (car.climate.startedAtMs ?: car.updatedAtMs)?.let { it + (car.climate.duration?.minutes ?: 30) * 60_000L }?.takeIf { it > nowMs }
        } else null
        val pct = car.batteryPercent
        val m = linkedMapOf<String, Any?>(
            "battery" to pct,
            "range_km" to if (pct != null && x.fullRangeKm != null) Math.round(pct * x.fullRangeKm / 100.0).toInt() else null,
            "charging" to if (car.charging == null && car.plug == PlugState.UNKNOWN) null else car.isCharging,
            "plugged" to when (car.plug) { PlugState.PLUGGED -> true; PlugState.UNPLUGGED -> false; else -> null },
            "charge_minutes" to car.chargeMinutesRemaining?.takeIf { car.isCharging && it in 1..1440 },
            "charge_done" to ts(car.chargeDoneAtMs(car.updatedAtMs ?: nowMs)?.takeIf { car.isCharging }),
            "charge_timer" to (ChargeTimerProgram.read(car)?.summary() ?: "–"),
            "climate" to car.climate.isRunning,
            "climate_mode" to modeLabel(x.quickMode),
            "climate_minutes" to "${x.quickDuration.minutes} min",
            "climate_ends" to ts(climateEnds),
            "locked" to car.locked,
            "doors_open" to d?.anyOpen,
            "door_detail" to (d?.let {
                linkedMapOf(
                    "driver" to it.driver, "passenger" to it.frontPassenger, "rear_left" to it.rearLeft,
                    "rear_right" to it.rearRight, "boot" to it.boot, "bonnet" to it.bonnet,
                )
            } ?: emptyMap<String, Any?>()),
            "headlights" to car.headlights,
            "parking_lights" to car.parkingLights,
            "alarm" to when (car.theftAlarm) { TheftAlarm.TRIGGERED -> true; TheftAlarm.UNKNOWN -> null; else -> false },
            "power" to when (car.ignition) { Ignition.ON -> true; Ignition.UNKNOWN -> null; else -> false },
            "home" to x.home,
            "link" to x.linked,
            "updated" to ts(car.updatedAtMs),
            "model_year" to car.modelYear.name,
        )
        return Json.write(m)
    }

    // ------------------------------------------------------------------ commands

    /** What a message on `cmd/<what>` asks for. Retained messages are never acted on (they'd replay on every connect). */
    fun command(topic: String, payload: String, retained: Boolean): Action {
        if (!topic.startsWith("$base/cmd/")) return Action.Ignore("not a command topic")
        val what = topic.removePrefix("$base/cmd/")
        if (retained) return Action.Ignore("retained message on $what — commands must not be retained")
        val p = payload.trim()
        fun onOff(): Boolean? = when (p.uppercase()) { "ON", "TRUE", "1" -> true; "OFF", "FALSE", "0" -> false; else -> null }
        return when (what) {
            "climate" -> when (onOff()) {
                true -> Action.ClimateOn
                false -> Action.Car(CarCommand.ClimateOff, "climate off")
                null -> Action.Ignore("climate: \"$p\" is not ON or OFF")
            }
            "climate_mode" -> parseMode(p)?.let { Action.Shared(mapOf("quickMode" to it.name), "climate mode ${it.name.lowercase()}") }
                ?: Action.Ignore("climate_mode: unknown mode \"$p\"")
            "climate_minutes" -> p.filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 5..30 }
                ?.let { Action.Shared(mapOf("quickDuration" to ClimateDuration.fromMinutes(it).minutes), "climate time ${ClimateDuration.fromMinutes(it).minutes} min") }
                ?: Action.Ignore("climate_minutes: \"$p\" is not 10, 20 or 30")
            "headlights" -> onOff()?.let { Action.Car(CarCommand.Headlights(it), "headlights ${if (it) "on" else "off"}") } ?: Action.Ignore("headlights: \"$p\" is not ON or OFF")
            "parking_lights" -> onOff()?.let { Action.Car(CarCommand.ParkingLights(it), "parking lights ${if (it) "on" else "off"}") } ?: Action.Ignore("parking_lights: \"$p\" is not ON or OFF")
            "locate" -> Action.Car(CarCommand.Locate(), "flash the lights")
            "charge_now" -> Action.Car(CarCommand.ChargeNow, "charge now")
            "charge_timer" -> Action.Car(CarCommand.ResumeChargeTimer, "charge on the timer")
            "refresh" -> Action.Car(CarCommand.RequestUpdate, "refresh")
            else -> Action.Ignore("unknown command \"$what\"")
        }
    }

    private fun parseMode(p: String): ClimateMode? =
        ClimateMode.entries.firstOrNull { m -> p.equals(m.name, true) || p.equals(m.label, true) || LANGS.any { p.equals(MODE_NAMES[it]?.get(m), true) } }

    // ------------------------------------------------------------------ events

    /** The event entity's message for an activity line, or null when it isn't one of [EVENT_TYPES]. */
    fun event(a: Activity): String? {
        val type = when (a.code) {
            "car.charge_started" -> "charge_started"
            "car.charge_stopped" -> if (a.str("why") == "full") "charge_finished" else "charge_stopped"
            "car.charge_failed" -> "charge_timer_failed"
            "car.plugged" -> "plugged"
            "car.unplugged" -> "unplugged"
            "car.climate_on" -> "climate_on"
            "car.climate_off" -> "climate_off"
            "car.climate_cancelled" -> "climate_cancelled"
            "car.alarm" -> "alarm"
            "car.locked" -> "locked"
            "car.unlocked" -> "unlocked"
            "car.door_open" -> "door_open"
            "car.doors_closed" -> "doors_closed"
            "car.switched_on" -> "switched_on"
            "car.switched_off" -> "switched_off"
            "bridge.carwifi_back" -> "car_home"
            "bridge.carwifi_lost" -> if (a.bool("inSight") == true) "bridge_off_car_wifi" else "car_away"
            "departure.started" -> "departure_started"
            "departure.failed" -> "departure_failed"
            else -> if (a.code.startsWith("cmd.") && !a.ok) "command_failed" else return null
        }
        val m = linkedMapOf<String, Any?>("event_type" to type, "text" to ActivityText.english(a), "who" to a.who)
        m.putAll(a.params.filterKeys { it != "event_type" })
        a.detail?.let { m["detail"] = it }
        return Json.write(m)
    }

    /** The bridge's own reminders that aren't already car events (left unlocked, door or lights left on). */
    fun event(alert: Alert): String? {
        val type = when (alert.type) {
            AlertType.LEFT_UNLOCKED -> "left_unlocked"
            AlertType.DOOR_OPEN -> "door_left_open"
            AlertType.LIGHTS_ON -> "lights_left_on"
            AlertType.PLUG_IN_REMINDER -> "plug_in_reminder"
            else -> return null
        }
        return Json.write(linkedMapOf("event_type" to type, "text" to alert.message))
    }

    // ------------------------------------------------------------------ names

    private fun lang(): String = language.lowercase().take(2).let { if (it == "no" || it == "nn") "nb" else it }.takeIf { it in LANGS } ?: "en"

    private fun name(key: String): String = NAMES[key]?.let { it[LANGS.indexOf(lang())] } ?: key

    fun modeLabel(m: ClimateMode): String = MODE_NAMES[lang()]?.get(m) ?: m.label

    companion object {
        val EVENT_TYPES = listOf(
            "charge_started", "charge_finished", "charge_stopped", "charge_timer_failed", "plugged", "unplugged",
            "climate_on", "climate_off", "climate_cancelled", "alarm", "locked", "unlocked", "door_open", "doors_closed",
            "switched_on", "switched_off", "car_home", "car_away", "bridge_off_car_wifi", "departure_started",
            "departure_failed", "left_unlocked", "door_left_open", "lights_left_on", "command_failed", "plug_in_reminder",
        )

        private val LANGS = listOf("en", "nb", "da", "de", "sv")

        /** Entity names: en, nb, da, de, sv. */
        private val NAMES: Map<String, List<String>> = mapOf(
            "battery" to listOf("Battery", "Batteri", "Batteri", "Batterie", "Batteri"),
            "range" to listOf("Electric range", "Elektrisk rekkevidde", "Elektrisk rækkevidde", "Elektrische Reichweite", "Elektrisk räckvidd"),
            "charging" to listOf("Charging", "Lader", "Lader", "Lädt", "Laddar"),
            "plugged" to listOf("Charging cable", "Ladekabel", "Ladekabel", "Ladekabel", "Laddkabel"),
            "charge_left" to listOf("Time to full", "Tid til fullt", "Tid til fuld", "Zeit bis voll", "Tid till full"),
            "charge_done" to listOf("Full at", "Fullt", "Fuld", "Voll um", "Full"),
            "charge_timer" to listOf("Charge timer", "Ladetimer", "Ladetimer", "Ladetimer", "Laddtimer"),
            "charge_now" to listOf("Charge now", "Lad nå", "Oplad nu", "Jetzt laden", "Ladda nu"),
            "charge_on_timer" to listOf("Charge on timer", "Lad etter timer", "Oplad efter timer", "Nach Timer laden", "Ladda enligt timer"),
            "climate" to listOf("Climate", "Klima", "Klima", "Klima", "Klimat"),
            "climate_mode" to listOf("Climate mode", "Klimamodus", "Klimatilstand", "Klimamodus", "Klimatläge"),
            "climate_minutes" to listOf("Climate time", "Klimatid", "Klimatid", "Klimadauer", "Klimattid"),
            "climate_ends" to listOf("Climate ends", "Klima slutter", "Klima slutter", "Klima endet", "Klimat slutar"),
            "lock" to listOf("Lock", "Lås", "Lås", "Schloss", "Lås"),
            "doors" to listOf("Doors", "Dører", "Døre", "Türen", "Dörrar"),
            "headlights" to listOf("Headlights", "Frontlys", "Forlygter", "Scheinwerfer", "Strålkastare"),
            "parking_lights" to listOf("Parking lights", "Parkeringslys", "Parkeringslys", "Standlicht", "Parkeringsljus"),
            "locate" to listOf("Flash lights", "Blink med lysene", "Blink med lygterne", "Lichter blinken", "Blinka med lyktorna"),
            "alarm" to listOf("Theft alarm", "Tyverialarm", "Tyverialarm", "Diebstahlalarm", "Stöldlarm"),
            "power" to listOf("Switched on", "Slått på", "Tændt", "Eingeschaltet", "Påslagen"),
            "home" to listOf("At home", "Hjemme", "Hjemme", "Zu Hause", "Hemma"),
            "link" to listOf("Bridge link", "Forbindelse via broen", "Forbindelse via broen", "Verbindung über die Bridge", "Anslutning via bryggan"),
            "updated" to listOf("Last update", "Sist oppdatert", "Sidst opdateret", "Zuletzt aktualisiert", "Senast uppdaterad"),
            "refresh" to listOf("Refresh", "Oppdater", "Opdater", "Aktualisieren", "Uppdatera"),
            "event" to listOf("Car event", "Bilhendelse", "Bilhændelse", "Autoereignis", "Bilhändelse"),
        )

        private val MODE_NAMES: Map<String, Map<ClimateMode, String>> = mapOf(
            "en" to mapOf(ClimateMode.HEAT to "Heat", ClimateMode.COOL to "Cool", ClimateMode.WINDSCREEN to "Defrost"),
            "nb" to mapOf(ClimateMode.HEAT to "Varme", ClimateMode.COOL to "Kjøling", ClimateMode.WINDSCREEN to "Avising"),
            "da" to mapOf(ClimateMode.HEAT to "Varme", ClimateMode.COOL to "Køling", ClimateMode.WINDSCREEN to "Afisning"),
            "de" to mapOf(ClimateMode.HEAT to "Heizen", ClimateMode.COOL to "Kühlen", ClimateMode.WINDSCREEN to "Enteisen"),
            "sv" to mapOf(ClimateMode.HEAT to "Värme", ClimateMode.COOL to "Kyla", ClimateMode.WINDSCREEN to "Avfrostning"),
        )
    }
}
