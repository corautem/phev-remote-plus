package io.github.anwo91.phevlink.core.json

import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarSetting
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.Climate
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.RawRegister
import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
import io.github.anwo91.phevlink.core.planner.Departure
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.core.protocol.toHex
import io.github.anwo91.phevlink.core.session.LinkStatus
import java.time.DayOfWeek
import java.time.LocalTime

/** JSON mapping for state snapshots (app cache, bridge API) and departures (settings). */
object Codecs {

    fun carStateToMap(s: CarState): Map<String, Any?> = linkedMapOf(
        "modelYear" to s.modelYear.name,
        "vin" to s.vin,
        "registrations" to s.registrations,
        "ecuVersion" to s.ecuVersion,
        "ssid" to s.ssid,
        "batteryPercent" to s.batteryPercent,
        "charging" to s.charging,
        "chargeMinutesRemaining" to s.chargeMinutesRemaining,
        "plug" to s.plug.name,
        "locked" to s.locked,
        "doors" to s.doors?.let {
            linkedMapOf(
                "driver" to it.driver, "frontPassenger" to it.frontPassenger,
                "rearLeft" to it.rearLeft, "rearRight" to it.rearRight,
                "boot" to it.boot, "bonnet" to it.bonnet,
            )
        },
        "headlights" to s.headlights,
        "parkingLights" to s.parkingLights,
        "hazardLights" to s.hazardLights,
        "interiorLights" to s.interiorLights,
        "ignition" to s.ignition.name,
        "climate" to linkedMapOf(
            "state" to s.climate.state.name,
            "operating" to s.climate.operating,
            "mode" to s.climate.mode?.name,
            "durationMinutes" to s.climate.duration?.minutes,
            "startedAtMs" to s.climate.startedAtMs,
        ),
        "carClock" to s.carClock,
        "carClockReadAtMs" to s.carClockReadAtMs,
        "settingsStatus" to s.settingsStatus,
        "settings" to s.settings.toSortedMap().entries.associateTo(linkedMapOf()) { (id, v) ->
            id.toString() to linkedMapOf("mask" to v.mask, "value" to v.value)
        },
        "registers" to s.registers.values.sortedBy { it.register }.map {
            linkedMapOf("reg" to it.register, "hex" to it.hex, "at" to it.updatedAtMs, "changes" to it.changes)
        },
        "updatedAtMs" to s.updatedAtMs,
    )

    fun carStateFromMap(m: Map<String, Any?>): CarState {
        val doors = m.obj("doors")?.let {
            Doors(
                driver = it.bool("driver") == true, frontPassenger = it.bool("frontPassenger") == true,
                rearLeft = it.bool("rearLeft") == true, rearRight = it.bool("rearRight") == true,
                boot = it.bool("boot") == true, bonnet = it.bool("bonnet") == true,
            )
        }
        val c = m.obj("climate")
        val climate = Climate(
            state = enumOr(c?.str("state"), PreAcState.UNKNOWN),
            operating = c?.bool("operating"),
            mode = c?.str("mode")?.let { runCatching { ClimateMode.valueOf(it) }.getOrNull() },
            duration = c?.int("durationMinutes")?.let { ClimateDuration.fromMinutes(it) },
            startedAtMs = c?.long("startedAtMs"),
        )
        val regs = m.list("registers")?.mapNotNull { r ->
            @Suppress("UNCHECKED_CAST") val rm = r as? Map<String, Any?> ?: return@mapNotNull null
            val reg = rm.int("reg") ?: return@mapNotNull null
            reg to RawRegister(reg, rm.str("hex") ?: "", rm.long("at") ?: 0, rm.int("changes") ?: 1)
        }?.toMap() ?: emptyMap()
        val settings = m.obj("settings")?.entries?.mapNotNull { (k, v) ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST") val sm = v as? Map<String, Any?> ?: return@mapNotNull null
            id to CarSetting(sm.int("mask") ?: return@mapNotNull null, sm.int("value") ?: return@mapNotNull null)
        }?.toMap() ?: emptyMap()
        return CarState(
            modelYear = enumOr(m.str("modelYear"), ModelYear.UNKNOWN),
            vin = m.str("vin"),
            registrations = m.int("registrations"),
            ecuVersion = m.str("ecuVersion"),
            ssid = m.str("ssid"),
            batteryPercent = m.int("batteryPercent"),
            charging = m.bool("charging"),
            chargeMinutesRemaining = m.int("chargeMinutesRemaining"),
            plug = enumOr(m.str("plug"), PlugState.UNKNOWN),
            locked = m.bool("locked"),
            doors = doors,
            headlights = m.bool("headlights"),
            parkingLights = m.bool("parkingLights"),
            hazardLights = m.bool("hazardLights"),
            interiorLights = m.bool("interiorLights"),
            ignition = enumOr(m.str("ignition"), Ignition.UNKNOWN),
            climate = climate,
            carClock = m.list("carClock")?.mapNotNull { (it as? Number)?.toInt() },
            carClockReadAtMs = m.long("carClockReadAtMs"),
            settings = settings,
            settingsStatus = m.int("settingsStatus"),
            registers = regs,
            updatedAtMs = m.long("updatedAtMs"),
        )
    }

    /**
     * The vehicle settings a UI should show, with labels resolved, for clients
     * that cannot use [VehicleSettingsCatalog] (the bridge's web page):
     * `[{"title":…, "items":[{"id", "name", "value", "valueLabel", "options":[{"value","label"}]}]}]`.
     */
    fun visibleSettingsToList(settings: Map<Int, CarSetting>): List<Map<String, Any?>> =
        VehicleSettingsCatalog.visibleGroups(settings).map { g ->
            linkedMapOf(
                "title" to g.title,
                "items" to g.items.map { item ->
                    linkedMapOf(
                        "id" to item.id,
                        "name" to item.name,
                        "value" to item.value,
                        "valueLabel" to item.currentLabel,
                        "options" to item.options.map { (v, label) -> linkedMapOf("value" to v, "label" to label) },
                    )
                },
            )
        }

    fun linkStatusToMap(s: LinkStatus): Map<String, Any?> = when (s) {
        is LinkStatus.Idle -> mapOf("phase" to "idle", "error" to s.lastError)
        is LinkStatus.Connecting -> mapOf("phase" to "connecting", "attempt" to s.attempt)
        is LinkStatus.Handshaking -> mapOf("phase" to "handshaking")
        is LinkStatus.Connected -> mapOf("phase" to "connected", "sinceMs" to s.sinceMs, "modelYear" to s.modelYear.name)
        is LinkStatus.Retrying -> mapOf("phase" to "retrying", "retryAtMs" to s.retryAtMs, "attempt" to s.attempt, "error" to s.lastError)
    }

    fun linkStatusFromMap(m: Map<String, Any?>): LinkStatus = when (m.str("phase")) {
        "connecting" -> LinkStatus.Connecting(m.int("attempt") ?: 1)
        "handshaking" -> LinkStatus.Handshaking
        "connected" -> LinkStatus.Connected(m.long("sinceMs") ?: 0, enumOr(m.str("modelYear"), ModelYear.UNKNOWN))
        "retrying" -> LinkStatus.Retrying(m.long("retryAtMs") ?: 0, m.int("attempt") ?: 1, m.str("error") ?: "")
        else -> LinkStatus.Idle(m.str("error"))
    }

    fun departuresToJson(list: List<Departure>): String = Json.write(list.map {
        linkedMapOf(
            "id" to it.id, "time" to it.time.toString(), "days" to it.days.map { d -> d.name },
            "enabled" to it.enabled, "smart" to it.smart, "mode" to it.mode.name,
            "duration" to it.duration.minutes, "label" to it.label,
        )
    })

    fun departuresFromJson(text: String?): List<Departure> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { Json.parse(text) as? List<*> }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST") val m = item as? Map<String, Any?> ?: return@mapNotNull null
            runCatching {
                Departure(
                    id = m.str("id") ?: return@mapNotNull null,
                    time = LocalTime.parse(m.str("time")),
                    days = m.list("days")?.mapNotNull { d -> runCatching { DayOfWeek.valueOf(d as String) }.getOrNull() }?.toSet() ?: emptySet(),
                    enabled = m.bool("enabled") ?: true,
                    smart = m.bool("smart") ?: true,
                    mode = enumOr(m.str("mode"), ClimateMode.HEAT),
                    duration = ClimateDuration.fromMinutes(m.int("duration") ?: 20),
                    label = m.str("label") ?: "",
                )
            }.getOrNull()
        }
    }

    /** Commands as JSON, shared by the bridge API and the app's bridge client. */
    fun commandToMap(c: CarCommand): Map<String, Any?> = when (c) {
        is CarCommand.ClimateOn -> mapOf("action" to "climate", "mode" to c.mode.name, "minutes" to c.duration.minutes, "delay" to c.delayMinutes)
        CarCommand.ClimateOff -> mapOf("action" to "climate_off")
        is CarCommand.Headlights -> mapOf("action" to "headlights", "on" to c.on)
        is CarCommand.ParkingLights -> mapOf("action" to "parking_lights", "on" to c.on)
        is CarCommand.Locate -> mapOf("action" to "locate", "seconds" to c.seconds)
        CarCommand.RequestUpdate -> mapOf("action" to "refresh")
        CarCommand.RegisterPhone -> mapOf("action" to "register")
        CarCommand.UnregisterPhone -> mapOf("action" to "unregister")
        CarCommand.AckClimateCancelled -> mapOf("action" to "ack_climate")
        CarCommand.ChargeNow -> mapOf("action" to "charge_now")
        is CarCommand.SyncClock -> mapOf("action" to "sync_clock", "time" to c.time.toString())
        is CarCommand.ChangeSetting -> mapOf("action" to "setting", "id" to c.id, "value" to c.value)
        CarCommand.ResetSettings -> mapOf("action" to "reset_settings")
        is CarCommand.Raw -> mapOf("action" to "raw", "register" to c.register, "data" to c.data.toHex())
    }

    fun commandFromMap(m: Map<String, Any?>): CarCommand {
        fun bool(k: String) = when (val v = m[k]) {
            is Boolean -> v
            is String -> v.equals("true", true) || v == "1" || v.equals("on", true)
            is Number -> v.toInt() != 0
            else -> throw IllegalArgumentException("Missing '$k'")
        }
        fun int(k: String, def: Int? = null) = when (val v = m[k]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: throw IllegalArgumentException("Bad number '$k'")
            null -> def ?: throw IllegalArgumentException("Missing '$k'")
            else -> throw IllegalArgumentException("Bad number '$k'")
        }
        return when (val a = m["action"]?.toString()?.lowercase()) {
            "climate" -> CarCommand.ClimateOn(
                mode = ClimateMode.valueOf((m["mode"]?.toString() ?: "HEAT").uppercase()),
                duration = ClimateDuration.fromMinutes(int("minutes", 20)),
                delayMinutes = int("delay", 0),
            )
            "climate_off" -> CarCommand.ClimateOff
            "headlights" -> CarCommand.Headlights(bool("on"))
            "parking_lights" -> CarCommand.ParkingLights(bool("on"))
            "locate" -> CarCommand.Locate(int("seconds", 5))
            "refresh" -> CarCommand.RequestUpdate
            "register" -> CarCommand.RegisterPhone
            "unregister" -> CarCommand.UnregisterPhone
            "ack_climate" -> CarCommand.AckClimateCancelled
            "charge_now" -> CarCommand.ChargeNow
            "sync_clock" -> CarCommand.SyncClock(
                m["time"]?.toString()?.let { java.time.LocalDateTime.parse(it) } ?: java.time.LocalDateTime.now()
            )
            "setting" -> CarCommand.ChangeSetting(int("id"), int("value"))
            "reset_settings" -> CarCommand.ResetSettings
            "raw" -> CarCommand.Raw(int("register"), (m["data"]?.toString() ?: "").hexToBytes())
            else -> throw IllegalArgumentException("Unknown action '$a'")
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
