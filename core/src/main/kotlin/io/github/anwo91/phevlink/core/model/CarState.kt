package io.github.anwo91.phevlink.core.model

import io.github.anwo91.phevlink.core.protocol.ModelYear

enum class ClimateMode(val code: Int, val label: String) {
    COOL(1, "Cool"),
    HEAT(2, "Heat"),
    WINDSCREEN(3, "Defrost");

    companion object {
        fun fromCode(code: Int): ClimateMode? = entries.firstOrNull { it.code == code }
    }
}

enum class ClimateDuration(val code: Int, val minutes: Int) {
    MIN_10(0, 10),
    MIN_20(1, 20),
    MIN_30(2, 30);

    companion object {
        fun fromCode(code: Int): ClimateDuration? = entries.firstOrNull { it.code == code }
        fun fromMinutes(minutes: Int): ClimateDuration = when {
            minutes <= 10 -> MIN_10
            minutes <= 20 -> MIN_20
            else -> MIN_30
        }
    }
}

/** Climate / pre-conditioning state as reported by register 0x10. */
enum class PreAcState { OFF, RUNNING, CANCELLED, UNKNOWN }

enum class Ignition { OFF, ACC, ON, UNKNOWN }

enum class PlugState { UNPLUGGED, PLUGGED, UNKNOWN }

/** How the car is charging (register 0x1e[0] plus the quick-charge flags 0x0c/0x0d). */
enum class ChargeKind { NONE, NORMAL, TRICKLE, QUICK, UNKNOWN }

/** Security-alarm state from register 0x23[1] (0 disarmed; 1/6/7 armed; 4/5 triggered). */
enum class TheftAlarm { DISARMED, ARMED, TRIGGERED, UNKNOWN }

data class Doors(
    val driver: Boolean = false,
    val frontPassenger: Boolean = false,
    val rearLeft: Boolean = false,
    val rearRight: Boolean = false,
    val boot: Boolean = false,
    val bonnet: Boolean = false,
) {
    val anyOpen: Boolean get() = driver || frontPassenger || rearLeft || rearRight || boot || bonnet
    fun openList(): List<String> = buildList {
        if (driver) add("driver door")
        if (frontPassenger) add("passenger door")
        if (rearLeft) add("rear left door")
        if (rearRight) add("rear right door")
        if (boot) add("boot")
        if (bonnet) add("bonnet")
    }
}

data class Climate(
    val state: PreAcState = PreAcState.UNKNOWN,
    val operating: Boolean? = null,
    val mode: ClimateMode? = null,
    val duration: ClimateDuration? = null,
    /** Wall-clock time we first saw it running (for "x min left" estimates). */
    val startedAtMs: Long? = null,
) {
    val isRunning: Boolean get() = state == PreAcState.RUNNING || operating == true
}

/** Last raw value seen for a register, for diagnostics and reverse engineering. */
data class RawRegister(val register: Int, val hex: String, val updatedAtMs: Long, val changes: Int)

/**
 * Everything we know about the car. Fields are null until the car reports them.
 * Immutable: every update produces a new instance.
 */
data class CarState(
    val modelYear: ModelYear = ModelYear.UNKNOWN,
    val vin: String? = null,
    val registrations: Int? = null,
    val ecuVersion: String? = null,
    val ssid: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val chargeMinutesRemaining: Int? = null,
    /** How it is charging (normal / trickle / quick); NONE when not charging. */
    val chargeKind: ChargeKind = ChargeKind.UNKNOWN,
    /** Plugged in and waiting for the charge timer window (register 0x1e[1]==1). */
    val chargeWaiting: Boolean = false,
    /** The car reported it could not start a scheduled charge (0x1e[1]==3). */
    val chargeTimerError: Boolean = false,
    val plug: PlugState = PlugState.UNKNOWN,
    val locked: Boolean? = null,
    val doors: Doors? = null,
    val headlights: Boolean? = null,
    val parkingLights: Boolean? = null,
    val hazardLights: Boolean? = null,
    val interiorLights: Boolean? = null,
    val ignition: Ignition = Ignition.UNKNOWN,
    val climate: Climate = Climate(),
    /** Car's own clock as [year, month, day, hour, minute, second], if reported. */
    val carClock: List<Int>? = null,
    val carClockReadAtMs: Long? = null,
    /**
     * The car's customisable functions (register 0x16), accumulated by id
     * (1..60) across notifications. See [VehicleSettingsCatalog] for names
     * and [VehicleSettingsCatalog.visibleGroups] for what to show.
     */
    val settings: Map<Int, CarSetting> = emptyMap(),
    /** Status byte of the last 0x16 notification (normally 2; 1 or 3 = car refuses changes). */
    val settingsStatus: Int? = null,
    /** MY18+: the car keeps its Wi-Fi on while switched on ("Always Communicate"). Null = not reported. */
    val wifiAlwaysOn: Boolean? = null,
    /** The drive battery's heater is running to warm a cold pack (register 0x02[1]==1). */
    val batteryWarming: Boolean = false,
    /** The car asks for the cable to be plugged in to protect a cold drive battery (0x1d[1]==3). */
    val batteryTooCold: Boolean = false,
    /** Security-alarm state (register 0x23[1]). */
    val theftAlarm: TheftAlarm = TheftAlarm.UNKNOWN,
    /** The car reports an EV-ECU communication error (register 0x2c==1). */
    val evEcuError: Boolean = false,
    /** The car's own screen/navigation is in use, so it won't take some remote commands (0x26==1). */
    val navInUse: Boolean = false,
    /** The car is in registration standby (register 0x15[18]==1): a phone can register now. */
    val registrationStandby: Boolean = false,
    val registers: Map<Int, RawRegister> = emptyMap(),
    /** Wall-clock time of the last register received from the car. */
    val updatedAtMs: Long? = null,
) {
    val hasData: Boolean get() = batteryPercent != null || locked != null || charging != null

    /** True while energy is going into the drive battery, including a CHAdeMO quick charge. */
    val isCharging: Boolean get() = charging == true || chargeKind == ChargeKind.QUICK

    /** Estimated time (epoch ms) when charging completes. */
    fun chargeDoneAtMs(nowMs: Long): Long? {
        val minutes = chargeMinutesRemaining ?: return null
        if (charging != true || minutes <= 0) return null
        return nowMs + minutes * 60_000L
    }
}
