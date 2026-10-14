package io.github.anwo91.phevlink.core.activity

import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.TheftAlarm
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One line of the activity log: who did what, or what the car did, and when. [code] says what happened
 * ("cmd.climate", "car.charge_started", …) and [params] the details, so each app can say it in its own language;
 * [ActivityText.english] is the English sentence (bridge log and web page).
 *
 * [who]: a phone's name, [BRIDGE] or [CAR]. [via]: how a phone reached the car ("bridge" or "direct").
 */
data class Activity(
    val atMs: Long,
    val who: String,
    val code: String,
    val params: Map<String, Any?> = emptyMap(),
    val ok: Boolean = true,
    val detail: String? = null,
    val via: String? = null,
    val id: String = newId(atMs),
) {
    val group: ActivityGroup get() = groupOf(code)

    fun str(k: String): String? = params[k]?.toString()
    fun int(k: String): Int? = (params[k] as? Number)?.toInt() ?: (params[k] as? String)?.toIntOrNull()
    fun bool(k: String): Boolean? = params[k] as? Boolean

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "atMs" to atMs, "who" to who, "code" to code, "params" to params,
        "ok" to ok, "detail" to detail, "via" to via,
    )

    companion object {
        const val BRIDGE = "bridge"
        const val CAR = "car"

        fun newId(atMs: Long): String = "%x-%06x".format(atMs, Random.nextInt(0x1000000))

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): Activity? = runCatching {
            Activity(
                atMs = (m["atMs"] as Number).toLong(),
                who = (m["who"] as? String)?.take(60) ?: return null,
                code = (m["code"] as? String)?.take(60) ?: return null,
                params = (m["params"] as? Map<String, Any?>).orEmpty(),
                ok = m["ok"] as? Boolean ?: true,
                detail = (m["detail"] as? String)?.take(300),
                via = m["via"] as? String,
                id = (m["id"] as? String)?.take(40) ?: newId((m["atMs"] as Number).toLong()),
            )
        }.getOrNull()

        fun groupOf(code: String): ActivityGroup = when {
            code.startsWith("charge.") || code.startsWith("smart.") || code in CHARGING_CODES -> ActivityGroup.CHARGING
            code.startsWith("departure.") || code in CLIMATE_CODES -> ActivityGroup.CLIMATE
            code.startsWith("car.") -> ActivityGroup.CAR
            code.startsWith("bridge.") || code.startsWith("shared.") -> ActivityGroup.BRIDGE
            else -> ActivityGroup.OTHER
        }

        private val CHARGING_CODES = setOf(
            "car.plugged", "car.unplugged", "car.charge_started", "car.charge_progress", "car.charge_stopped",
            "car.charge_waiting", "car.charge_failed", "car.battery_heating",
            "cmd.charge_now", "cmd.resume_charge_timer", "cmd.charge_timer",
        )
        private val CLIMATE_CODES = setOf("car.climate_on", "car.climate_off", "car.climate_cancelled", "cmd.climate", "cmd.climate_off", "cmd.ack_climate")
    }
}

/** Filters in the activity page. */
enum class ActivityGroup { CHARGING, CLIMATE, CAR, BRIDGE, OTHER }

/**
 * Turns successive car states into activity: plugged in/out, charging started/stopped (and why, as far as the car
 * tells), every 10 % while charging, a summary per charge, the timer waiting, climate on/off, locks, doors, lights,
 * power switch and the alarm. Only real changes between two states with data count; unknown values never do.
 */
class CarEventTracker(private val now: () -> Long = System::currentTimeMillis) {
    private var prev: CarState? = null
    private var sessionStartPct: Int? = null
    private var sessionStartMs: Long = 0
    private var lastTenth: Int? = null

    /** Rough energy per percent of the drive battery (≈ 10 kWh usable on a 2014–2017 car). */
    var kwhPerPercent = 0.10

    fun onState(s: CarState): List<Activity> {
        if (!s.hasData) return emptyList()
        val p = prev
        prev = s
        if (p == null || !p.hasData) {
            if (s.isCharging) startSession(s)
            return emptyList()
        }
        val t = now()
        val out = ArrayList<Activity>()
        fun car(code: String, vararg params: Pair<String, Any?>) {
            out += Activity(t, Activity.CAR, code, linkedMapOf(*params))
        }
        val pct = s.batteryPercent

        // Plug and charging.
        if (p.plug == PlugState.UNPLUGGED && s.plug == PlugState.PLUGGED) car("car.plugged", "pct" to pct)
        if (!p.isCharging && s.isCharging) {
            car("car.charge_started", "pct" to pct, "kind" to s.chargeKind.name.lowercase(), "minutesLeft" to s.chargeMinutesRemaining)
            startSession(s)
        }
        if (s.isCharging && pct != null) {
            val tenth = pct / 10
            val last = lastTenth
            if (last != null && tenth > last && pct < 100) car("car.charge_progress", "pct" to tenth * 10, "minutesLeft" to s.chargeMinutesRemaining)
            lastTenth = tenth
        }
        if (p.isCharging && !s.isCharging) {
            val why = when {
                s.plug == PlugState.UNPLUGGED -> "unplugged"
                (pct ?: 0) >= 99 -> "full"
                s.chargeWaiting -> "timer"
                else -> "stopped"
            }
            car("car.charge_stopped", "pct" to pct, "why" to why)
            sessionStartPct?.let { from ->
                val to = pct ?: from
                val minutes = ((t - sessionStartMs) / 60_000).toInt()
                out += Activity(
                    t, Activity.CAR, "charge.session",
                    linkedMapOf(
                        "fromPct" to from, "toPct" to to, "minutes" to minutes,
                        "kwh" to ((to - from).coerceAtLeast(0) * kwhPerPercent * 10).roundToInt() / 10.0,
                    ),
                )
            }
            sessionStartPct = null
            lastTenth = null
        }
        if (p.plug == PlugState.PLUGGED && s.plug == PlugState.UNPLUGGED) car("car.unplugged", "pct" to pct)
        if (!p.chargeWaiting && s.chargeWaiting) car("car.charge_waiting", "pct" to pct)
        if (!p.chargeTimerError && s.chargeTimerError) car("car.charge_failed", "pct" to pct)
        if (!p.batteryWarming && s.batteryWarming) car("car.battery_heating")

        // Climate.
        val wasOn = p.climate.isRunning
        val isOn = s.climate.isRunning
        if (!wasOn && isOn) car("car.climate_on", "mode" to s.climate.mode?.name, "minutes" to s.climate.duration?.minutes)
        if (wasOn && !isOn) car(if (s.climate.state == PreAcState.CANCELLED) "car.climate_cancelled" else "car.climate_off")

        // Locks, doors, lights, power switch, alarm.
        if (p.locked == true && s.locked == false) car("car.unlocked")
        if (p.locked == false && s.locked == true) car("car.locked")
        val pd = p.doors
        val sd = s.doors
        if (pd != null && sd != null) {
            listOf(
                "driver" to (pd.driver to sd.driver), "passenger" to (pd.frontPassenger to sd.frontPassenger),
                "rear_left" to (pd.rearLeft to sd.rearLeft), "rear_right" to (pd.rearRight to sd.rearRight),
                "boot" to (pd.boot to sd.boot), "bonnet" to (pd.bonnet to sd.bonnet),
            ).forEach { (name, ch) -> if (!ch.first && ch.second) car("car.door_open", "door" to name) }
            if (pd.anyOpen && !sd.anyOpen) car("car.doors_closed")
        }
        if (p.headlights == false && s.headlights == true) car("car.headlights_on")
        if (p.headlights == true && s.headlights == false) car("car.headlights_off")
        if (p.hazardLights == false && s.hazardLights == true) car("car.hazards_on")
        if (p.hazardLights == true && s.hazardLights == false) car("car.hazards_off")
        if (p.ignition == Ignition.OFF && (s.ignition == Ignition.ON || s.ignition == Ignition.ACC)) car("car.switched_on")
        if ((p.ignition == Ignition.ON || p.ignition == Ignition.ACC) && s.ignition == Ignition.OFF) car("car.switched_off", "pct" to pct)
        if (p.theftAlarm != TheftAlarm.TRIGGERED && s.theftAlarm == TheftAlarm.TRIGGERED) car("car.alarm")
        return out
    }

    private fun startSession(s: CarState) {
        sessionStartPct = s.batteryPercent
        sessionStartMs = now()
        lastTenth = s.batteryPercent?.let { it / 10 }
    }
}

/** English sentences for the activity log (the bridge's log and web page; the app uses its own language). */
object ActivityText {
    fun english(a: Activity): String {
        val who = when (a.who) {
            Activity.CAR -> "The car"
            Activity.BRIDGE -> "The bridge"
            else -> a.who
        }
        val pct = a.int("pct")?.let { " at $it %" } ?: ""
        val text = when (a.code) {
            "cmd.climate" -> "$who started ${mode(a.str("mode"))} for ${a.int("minutes") ?: "?"} min"
            "cmd.climate_off" -> "$who stopped the climate"
            "cmd.headlights" -> "$who switched the headlights ${onOff(a.bool("on"))}"
            "cmd.parking_lights" -> "$who switched the parking lights ${onOff(a.bool("on"))}"
            "cmd.locate" -> "$who flashed the lights"
            "cmd.refresh" -> "$who asked the car for news"
            "cmd.charge_now" -> "$who started charging now (timer skipped)"
            "cmd.resume_charge_timer" -> "$who put charging back on the timer"
            "cmd.charge_timer" -> "$who set the charge timer"
            "cmd.setting" -> "$who changed car setting #${a.int("id")} to option ${a.int("value")}"
            "cmd.reset_settings" -> "$who reset the car settings"
            "cmd.sync_clock" -> "$who set the car's clock"
            "cmd.register" -> "$who registered with the car"
            "cmd.unregister" -> "$who removed a registration from the car"
            "cmd.ack_climate" -> "$who cleared the climate warning"
            "cmd.wifi_always_on" -> "$who switched \"Wi-Fi always on\" ${onOff(a.bool("on"))}"
            "car.plugged" -> "Plugged in$pct"
            "car.unplugged" -> "Unplugged$pct"
            "car.charge_started" -> "Charging started$pct" + (if (a.str("kind") == "quick") " (quick charge)" else "") +
                (a.int("minutesLeft")?.takeIf { it in 1..1440 }?.let { ", about ${hm(it)} to full" } ?: "")
            "car.charge_progress" -> "Charging: ${a.int("pct")} %" + (a.int("minutesLeft")?.takeIf { it in 1..1440 }?.let { ", ${hm(it)} to full" } ?: "")
            "car.charge_stopped" -> when (a.str("why")) {
                "full" -> "Charging finished — full"
                "unplugged" -> "Charging stopped — unplugged$pct"
                "timer" -> "Charging paused — waiting for the charge timer$pct"
                else -> "Charging stopped$pct"
            }
            "charge.session" -> "Charged ${a.int("fromPct")} → ${a.int("toPct")} % in ${hm(a.int("minutes") ?: 0)} (≈ ${a.params["kwh"]} kWh)"
            "car.charge_waiting" -> "Waiting for the charge timer$pct"
            "car.charge_failed" -> "The charge timer didn't start charging"
            "car.battery_heating" -> "The drive battery is warming itself up"
            "car.climate_on" -> "Climate on" + (a.str("mode")?.let { " (${mode(it)})" } ?: "")
            "car.climate_off" -> "Climate off"
            "car.climate_cancelled" -> "The car stopped the climate (door opened or battery low)"
            "car.locked" -> "Locked"
            "car.unlocked" -> "Unlocked"
            "car.door_open" -> "${door(a.str("door"))} opened"
            "car.doors_closed" -> "All doors closed"
            "car.headlights_on" -> "Headlights on"
            "car.headlights_off" -> "Headlights off"
            "car.hazards_on" -> "Hazard lights on"
            "car.hazards_off" -> "Hazard lights off"
            "car.switched_on" -> "Car switched on"
            "car.switched_off" -> "Car switched off$pct"
            "car.alarm" -> "ALARM: the theft alarm went off"
            "car.trip" -> "Back after ${hm(a.int("minutes") ?: 0)}" +
                (if (a.int("fromPct") != null && a.int("toPct") != null) " — battery ${a.int("fromPct")} → ${a.int("toPct")} %" else "")
            "departure.started" -> "$who started ${mode(a.str("mode"))} for ${a.int("minutes")} min for the ${a.str("at")} departure"
            "departure.skipped" -> "Departure ${a.str("at")}: mild weather, nothing to do"
            "departure.failed" -> "Departure ${a.str("at")}: the car didn't start the climate"
            "departure.away" -> "Departure ${a.str("at")}: the car wasn't within the bridge's reach; a phone near it can start it"
            "shared.changed" -> "$who changed ${a.str("keys") ?: "shared settings"}"
            "bridge.started" -> "The bridge started (${a.str("build") ?: "?"})"
            "bridge.update" -> "$who started a bridge update"
            "bridge.restart" -> "$who restarted the bridge"
            "bridge.reboot" -> "$who restarted the bridge computer"
            "bridge.carwifi_paused" -> "$who asked the bridge to step aside for ${a.int("minutes")} min"
            "bridge.carwifi_resumed" -> "The bridge is back on the car's Wi-Fi"
            "bridge.carwifi_lost" -> "The bridge lost the car's Wi-Fi" +
                (if (a.bool("inSight") == true) " — the car is still in range; the bridge keeps trying to get back on" else " — the car has probably left")
            "bridge.carwifi_back" -> "The bridge is back on the car's Wi-Fi" + (a.int("minutes")?.let { " after ${hm(it)}" } ?: "")
            "bridge.smarthome_on" -> "$who connected the car to the smart home" + (a.str("broker")?.let { " (MQTT broker $it)" } ?: "")
            "bridge.smarthome_off" -> "$who switched the smart-home link off"
            "bridge.matter_on" -> "$who shared the car with Google Home / Matter"
            "bridge.matter_off" -> "$who stopped sharing the car through Matter"
            "bridge.matter_reset" -> "$who removed the car's Matter pairings"
            "bridge.paired" -> "$who was paired with the bridge"
            "smart.planned" -> "Smart charging: tonight ${a.str("start")}–${a.str("end")}" + ((a.params["saving"] as? Number)?.let { ", saves ≈ %.2f %s".format(it.toDouble(), a.str("currency") ?: "") } ?: "")
            "smart.done" -> "Smart charging: the owner's charge timer is back (${a.str("reason")})"
            "smart.failed" -> "Smart charging: the car didn't take the timer — charging as usual"
            "smart.stepped_back" -> "Smart charging: the timer was changed on a phone — left as it was set"
            "bridge.invite" -> "$who opened pairing for another phone"
            else -> "$who: ${a.code}"
        }
        return text + (if (!a.ok) " — failed" + (a.detail?.let { ": $it" } ?: "") else "")
    }

    fun hm(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    private fun onOff(b: Boolean?) = if (b == true) "on" else "off"
    private fun mode(m: String?) = when (m?.uppercase()) {
        "HEAT" -> "heating"
        "COOL" -> "cooling"
        "WINDSCREEN" -> "defrost"
        else -> "the climate"
    }

    private fun door(d: String?) = when (d) {
        "driver" -> "Driver's door"
        "passenger" -> "Passenger door"
        "rear_left" -> "Rear left door"
        "rear_right" -> "Rear right door"
        "boot" -> "Boot"
        "bonnet" -> "Bonnet"
        else -> "A door"
    }

    /** Is this event worth a line in the bridge's own diagnostic log (charging and the like, in detail)? */
    fun forDiagLog(a: Activity): Boolean = a.group == ActivityGroup.CHARGING || a.code.startsWith("car.") || a.code.startsWith("departure.")

    /** Activity for a phone's command: "cmd.<action>" with the command's own fields. */
    fun commandCode(action: String) = "cmd.$action"

    /** Kinds of commands that are not worth a line ("asked for news", clock sync, raw). */
    val QUIET_COMMANDS = setOf("refresh", "sync_clock", "raw")
}
