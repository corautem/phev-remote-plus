package io.github.anwo91.phevlink.core.model

import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.toHex

/**
 * Folds register notifications from the car into [CarState].
 *
 * Decoding is defensive: register layouts differ between model years and some
 * fields are only partly understood, so anything that does not match the
 * expected shape is kept as a raw value only (visible in diagnostics).
 */
object RegisterDecoder {

    fun apply(state: CarState, register: Int, data: ByteArray, nowMs: Long): CarState {
        val u = IntArray(data.size) { data[it].toInt() and 0xff }
        val hex = data.toHex()
        val prev = state.registers[register]
        val raw = if (prev != null && prev.hex == hex) prev.copy(updatedAtMs = nowMs)
        else RawRegister(register, hex, nowMs, (prev?.changes ?: 0) + 1)
        var s = state.copy(registers = state.registers + (register to raw), updatedAtMs = nowMs)

        when (register) {
            Reg.READ_BATTERY -> if (u.size >= 3) {
                val level = u[0]
                // The car occasionally reports 0..5 % as a placeholder; a PHEV never
                // runs that low (hybrid mode holds a reserve), so keep the last good value.
                if (level in 6..100) s = s.copy(batteryPercent = level)
                // [1]==3: the car asks for the cable to protect a cold drive battery.
                s = s.copy(parkingLights = u[2] == 1, batteryTooCold = u[1] == 3)
            }

            Reg.READ_CHARGE_STATUS -> if (u.size >= 3) {
                val charging = u[0] == 1
                var remaining: Int? = if (u[2] != 0xff) (u[2] shl 8) or u[1] else null
                // The official app shows no estimate at or above 6000 (0x1770); 0xfffe is "unknown" (quick charge).
                if (remaining != null && remaining >= 6000) remaining = null
                s = s.copy(
                    charging = charging,
                    chargeMinutesRemaining = if (charging) remaining else null,
                )
                if (charging) s = s.copy(plug = PlugState.PLUGGED)
            }

            Reg.READ_CHARGE_GUN -> if (u.size >= 2) {
                // [1]==1: the drive-battery heater is on (official app: "Warming up").
                s = s.copy(batteryWarming = u[1] == 1)
            }

            Reg.READ_CHARGE_PLUG -> if (u.size >= 2) {
                // Observed: 0000 unplugged, 0001 plugged (timer waiting), 0002 plugged
                // (in charge window), 0202 charging, 0102/0003 transitions while unplugging.
                val plugged = u[0] > 0 || u[1] == 1 || u[1] == 2
                s = s.copy(
                    plug = if (plugged) PlugState.PLUGGED else PlugState.UNPLUGGED,
                    // [1]: 1 = waiting for the timer window, 3 = the car could not start a scheduled charge.
                    chargeWaiting = u[1] == 1,
                    chargeTimerError = u[1] == 3,
                )
            }

            Reg.READ_DOORS -> if (u.size >= 10) {
                s = s.copy(
                    locked = when (u[0]) {
                        1 -> true
                        2 -> false
                        else -> state.locked
                    },
                    doors = Doors(
                        driver = u[3] == 1,
                        frontPassenger = u[4] == 1,
                        rearRight = u[5] == 1,
                        rearLeft = u[6] == 1,
                        boot = u[7] == 1,
                        bonnet = u[8] == 1,
                    ),
                    headlights = u[9] == 1,
                )
            }

            Reg.READ_LIGHTS -> if (u.size >= 5) {
                s = s.copy(
                    hazardLights = (u[3] and 0b11) == 1,
                    // [4] bit 0: interior light on (values 1 and 3 both mean on).
                    interiorLights = (u[4] and 1) == 1,
                    // [1]: security alarm — 0 disarmed, 1/6/7 armed, 4/5 triggered.
                    theftAlarm = when (u[1]) {
                        0 -> TheftAlarm.DISARMED
                        1, 6, 7 -> TheftAlarm.ARMED
                        4, 5 -> TheftAlarm.TRIGGERED
                        else -> TheftAlarm.UNKNOWN
                    },
                )
            }

            Reg.READ_NAV_IN_USE -> if (u.isNotEmpty()) s = s.copy(navInUse = u[0] == 1)

            Reg.READ_EV_ECU_ERROR -> if (u.isNotEmpty()) s = s.copy(evEcuError = u[0] == 1)

            // Same number means something else (or nothing) on 2014–2017 cars.
            Reg.READ_WIFI_ALWAYS_ON_MY18 -> if (u.isNotEmpty() && (state.modelYear == ModelYear.MY18 || state.modelYear == ModelYear.MY24)) {
                s = s.copy(wifiAlwaysOn = u[0] == 1)
            }

            Reg.READ_PRE_AC_STATE -> if (u.isNotEmpty()) {
                val pre = when (u[0]) {
                    0 -> PreAcState.OFF
                    2 -> PreAcState.RUNNING
                    3 -> PreAcState.CANCELLED
                    else -> PreAcState.UNKNOWN
                }
                val started = when {
                    pre == PreAcState.RUNNING && !state.climate.isRunning -> nowMs
                    pre == PreAcState.RUNNING -> state.climate.startedAtMs ?: nowMs
                    else -> null
                }
                s = s.copy(climate = s.climate.copy(state = pre, startedAtMs = started))
            }

            Reg.READ_IGNITION_AC -> {
                if (u.isNotEmpty()) {
                    s = s.copy(
                        ignition = when (u[0]) {
                            0 -> Ignition.OFF
                            3 -> Ignition.ACC
                            4 -> Ignition.ON
                            else -> Ignition.UNKNOWN
                        }
                    )
                }
                if (u.size >= 2) s = s.copy(climate = s.climate.copy(operating = u[1] == 1))
            }

            Reg.READ_AC_MODE -> if (u.size == 1) {
                val mode = ClimateMode.fromCode(u[0] and 0x0f)
                val duration = ClimateDuration.fromCode((u[0] and 0xf0) shr 4)
                s = s.copy(climate = s.climate.copy(mode = mode ?: s.climate.mode, duration = duration ?: s.climate.duration))
            }

            Reg.READ_VIN -> if (u.size >= 18) {
                val vin = String(CharArray(17) { u[1 + it].toChar() })
                if (vin.all { it.isLetterOrDigit() }) s = s.copy(vin = vin)
                // [18]==1: the car is in registration standby; [19]: number of registered phones.
                if (u.size >= 19) s = s.copy(registrationStandby = u[18] == 1)
                if (u.size >= 20) s = s.copy(registrations = u[19])
            }

            Reg.READ_ECU_VERSION -> {
                val text = printable(u)
                if (text.isNotEmpty()) s = s.copy(ecuVersion = text)
            }

            Reg.READ_SSID -> {
                val text = printable(u.map { if (it == 0xff) 0 else it }.toIntArray())
                if (text.isNotEmpty()) s = s.copy(ssid = text)
            }

            Reg.READ_SETTINGS -> if (u.size >= 3) {
                // [S][A1][B1][A2][B2][A3][B3][00]: up to 3 settings per notification and
                // several notifications per dump, so merge into what we already know.
                var settings = s.settings
                for (i in 0 until 3) {
                    val ai = 1 + 2 * i
                    val bi = ai + 1
                    if (bi >= u.size) break
                    val (id, setting) = VehicleSettingsCatalog.decodePair(u[ai], u[bi]) ?: continue
                    settings = settings + (id to setting)
                }
                s = s.copy(settings = settings, settingsStatus = u[0])
            }

            Reg.READ_CAR_CLOCK -> if (u.size >= 6) {
                s = s.copy(
                    carClock = listOf(2000 + u[0], u[1], u[2], u[3], u[4], u[5]),
                    carClockReadAtMs = nowMs,
                )
            }
        }
        return s.copy(chargeKind = chargeKindOf(s))
    }

    /** How the car is charging, from the latest related registers (they arrive in separate notifications). */
    private fun chargeKindOf(s: CarState): ChargeKind {
        fun firstByte(reg: Int): Int? = s.registers[reg]?.hex?.take(2)?.toIntOrNull(16)
        // CHAdeMO quick charge: the normal "charging" flag (0x1f[0]) can read 0, so check the quick flags first.
        if (firstByte(Reg.READ_QUICK_CHARGE_D) == 3 || firstByte(Reg.READ_QUICK_CHARGE_C) == 5) return ChargeKind.QUICK
        if (s.charging != true) return ChargeKind.NONE
        return when (firstByte(Reg.READ_CHARGE_PLUG)) {
            2 -> ChargeKind.NORMAL
            1 -> ChargeKind.TRICKLE
            else -> ChargeKind.UNKNOWN
        }
    }

    private fun printable(u: IntArray): String {
        val sb = StringBuilder()
        for (c in u) {
            if (c == 0) break
            if (c in 0x20..0x7e) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
}
