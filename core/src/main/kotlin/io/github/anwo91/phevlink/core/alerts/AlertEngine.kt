package io.github.anwo91.phevlink.core.alerts

import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.TheftAlarm

enum class AlertType(val title: String) {
    CHARGE_COMPLETE("Charging complete"),
    CHARGE_STOPPED("Charging stopped"),
    CHARGE_STARTED("Charging started"),
    CLIMATE_STARTED("Climate started"),
    CLIMATE_FINISHED("Car is ready"),
    CLIMATE_CANCELLED("Climate stopped"),
    LEFT_UNLOCKED("Car left unlocked"),
    DOOR_OPEN("Door left open"),
    LIGHTS_ON("Lights left on"),
    THEFT_ALARM("Security alarm"),
    CHARGE_TIMER_FAILED("Charge did not start"),
    /** Evening reminder from the bridge: the car is home, low and not plugged in (see the bridge's reminders). */
    PLUG_IN_REMINDER("Plug in tonight?"),
    /** Sunday evening from the bridge: the week's charging (energy, cost) and trips; numbers in [Alert.params]. */
    WEEKLY_SUMMARY("Your week with the car"),
}

/** [params]: numbers the phones put into their own language (the weekly summary); [message] is the English text. */
data class Alert(val type: AlertType, val message: String, val timeMs: Long, val params: Map<String, Any?> = emptyMap()) {
    val title: String get() = type.title
}

data class AlertPrefs(
    val chargeComplete: Boolean = true,
    val chargeStopped: Boolean = true,
    val chargeStarted: Boolean = false,
    val climateStarted: Boolean = false,
    val climateFinished: Boolean = true,
    val climateCancelled: Boolean = true,
    val leftUnlocked: Boolean = true,
    val leftUnlockedMinutes: Int = 10,
    val doorOpen: Boolean = true,
    val doorOpenMinutes: Int = 10,
    val lightsOn: Boolean = true,
    val lightsOnMinutes: Int = 3,
    val theftAlarm: Boolean = true,
    val chargeTimerFailed: Boolean = true,
    val plugInReminder: Boolean = true,
    val weeklySummary: Boolean = true,
) {
    fun enabled(t: AlertType) = when (t) {
        AlertType.CHARGE_COMPLETE -> chargeComplete
        AlertType.CHARGE_STOPPED -> chargeStopped
        AlertType.CHARGE_STARTED -> chargeStarted
        AlertType.CLIMATE_STARTED -> climateStarted
        AlertType.CLIMATE_FINISHED -> climateFinished
        AlertType.CLIMATE_CANCELLED -> climateCancelled
        AlertType.LEFT_UNLOCKED -> leftUnlocked
        AlertType.DOOR_OPEN -> doorOpen
        AlertType.LIGHTS_ON -> lightsOn
        AlertType.THEFT_ALARM -> theftAlarm
        AlertType.CHARGE_TIMER_FAILED -> chargeTimerFailed
        AlertType.PLUG_IN_REMINDER -> plugInReminder
        AlertType.WEEKLY_SUMMARY -> weeklySummary
    }
}

/**
 * Turns state changes into notifications. Transition alerts (charging done,
 * climate finished) fire on the change itself; condition alerts (left
 * unlocked, lights on) fire once after the condition has held for a while
 * and re-arm when it clears.
 *
 * Feed it every new state with [onState] and call [onTick] periodically.
 * Pure logic, no I/O, so the phone app and the bridge share it.
 */
class AlertEngine(var prefs: AlertPrefs = AlertPrefs()) {
    private var last: CarState? = null
    private var unlockedSince: Long? = null
    private var doorOpenSince: Long? = null
    private var lightsOnSince: Long? = null
    private val conditionFired = mutableSetOf<AlertType>()

    fun reset() {
        last = null
        unlockedSince = null; doorOpenSince = null; lightsOnSince = null
        conditionFired.clear()
    }

    fun onState(state: CarState, nowMs: Long): List<Alert> {
        val prev = last
        last = state
        val out = mutableListOf<Alert>()
        if (prev != null) transitions(prev, state, nowMs, out)
        trackConditions(state, nowMs)
        out += conditions(nowMs)
        return out.filter { prefs.enabled(it.type) }
    }

    fun onTick(nowMs: Long): List<Alert> {
        if (last == null) return emptyList()
        return conditions(nowMs).filter { prefs.enabled(it.type) }
    }

    private fun transitions(a: CarState, b: CarState, now: Long, out: MutableList<Alert>) {
        val pct = b.batteryPercent?.let { " ($it%)" } ?: ""
        if (a.charging == true && b.charging == false) {
            val full = (b.batteryPercent ?: 0) >= 95 || (a.chargeMinutesRemaining ?: 99) <= 5
            when {
                full -> out += Alert(AlertType.CHARGE_COMPLETE, "Battery is full$pct.", now)
                b.plug == PlugState.UNPLUGGED -> Unit // the user unplugged it — no surprise
                else -> out += Alert(
                    AlertType.CHARGE_STOPPED,
                    "Charging stopped before full$pct. Power cut, charge timer, or the cable came loose?",
                    now,
                )
            }
        }
        if (a.charging == false && b.charging == true) {
            val eta = b.chargeMinutesRemaining?.let { " About ${formatMinutes(it)} to full." } ?: ""
            out += Alert(AlertType.CHARGE_STARTED, "Charging$pct.$eta", now)
        }
        val ca = a.climate.state
        val cb = b.climate.state
        if (ca != cb) {
            when {
                cb == PreAcState.RUNNING -> {
                    val what = listOfNotNull(b.climate.mode?.label, b.climate.duration?.let { "${it.minutes} min" }).joinToString(", ")
                    out += Alert(AlertType.CLIMATE_STARTED, if (what.isEmpty()) "Preconditioning is running." else "$what.", now)
                }
                ca == PreAcState.RUNNING && cb == PreAcState.OFF ->
                    out += Alert(AlertType.CLIMATE_FINISHED, "Preconditioning finished — the cabin is ready.", now)
                cb == PreAcState.CANCELLED ->
                    out += Alert(AlertType.CLIMATE_CANCELLED, "Preconditioning was stopped by the car (door opened or battery too low).", now)
            }
        }
        // Security alarm: the state turned to triggered, or the car sent a fresh alarm event (register 0x14).
        val newEvent = b.registers[0x14]?.hex?.let { it != a.registers[0x14]?.hex } == true
        if ((b.theftAlarm == TheftAlarm.TRIGGERED && a.theftAlarm != TheftAlarm.TRIGGERED) || newEvent) {
            out += Alert(AlertType.THEFT_ALARM, "The car's security alarm went off.", now)
        }
        if (!a.chargeTimerError && b.chargeTimerError) {
            out += Alert(AlertType.CHARGE_TIMER_FAILED, "The car could not start the scheduled charge. Check the cable and the timer.", now)
        }
    }

    private fun parked(s: CarState) = s.ignition == Ignition.OFF || s.ignition == Ignition.UNKNOWN

    private fun trackConditions(s: CarState, now: Long) {
        val unlocked = s.locked == false && parked(s)
        unlockedSince = if (unlocked) unlockedSince ?: now else null
        if (!unlocked) conditionFired -= AlertType.LEFT_UNLOCKED

        val doorOpen = s.doors?.anyOpen == true && parked(s)
        doorOpenSince = if (doorOpen) doorOpenSince ?: now else null
        if (!doorOpen) conditionFired -= AlertType.DOOR_OPEN

        val lights = (s.headlights == true || s.parkingLights == true) && parked(s)
        lightsOnSince = if (lights) lightsOnSince ?: now else null
        if (!lights) conditionFired -= AlertType.LIGHTS_ON
    }

    private fun conditions(now: Long): List<Alert> {
        val s = last ?: return emptyList()
        val out = mutableListOf<Alert>()
        fun check(type: AlertType, since: Long?, minutes: Int, message: () -> String) {
            if (since == null || type in conditionFired) return
            if (now - since >= minutes * 60_000L) {
                conditionFired += type
                out += Alert(type, message(), now)
            }
        }
        check(AlertType.LEFT_UNLOCKED, unlockedSince, prefs.leftUnlockedMinutes) {
            "Unlocked for ${prefs.leftUnlockedMinutes}+ minutes with the car switched off."
        }
        check(AlertType.DOOR_OPEN, doorOpenSince, prefs.doorOpenMinutes) {
            val which = s.doors?.openList()?.joinToString().orEmpty()
            "Open for ${prefs.doorOpenMinutes}+ minutes: $which."
        }
        check(AlertType.LIGHTS_ON, lightsOnSince, prefs.lightsOnMinutes) {
            val which = listOfNotNull(
                "headlights".takeIf { s.headlights == true },
                "parking lights".takeIf { s.parkingLights == true },
            ).joinToString(" and ")
            "The $which are on with the car switched off."
        }
        return out
    }

    companion object {
        fun formatMinutes(m: Int): String = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}
