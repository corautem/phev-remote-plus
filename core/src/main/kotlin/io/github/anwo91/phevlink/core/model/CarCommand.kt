package io.github.anwo91.phevlink.core.model

import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.core.protocol.toHex
import java.time.DayOfWeek
import java.time.LocalDateTime

/** One register write, optionally followed by a pause before the next step. */
class RegisterWrite(val register: Int, val data: ByteArray, val pauseAfterMs: Long = 0) {
    override fun toString() = "write 0x%02x=%s".format(register, data.toHex()) +
        if (pauseAfterMs > 0) " then wait ${pauseAfterMs}ms" else ""
}

/** Things the user can ask the car to do. */
sealed interface CarCommand {
    val label: String

    /**
     * Register writes for this command on the given model year. [state] gives
     * access to current register values for commands that must echo them back.
     */
    fun toWrites(modelYear: ModelYear, state: CarState = CarState()): List<RegisterWrite>

    /** Registers whose current value this command echoes back (waited for briefly). */
    fun requiredRegisters(modelYear: ModelYear): Set<Int> = emptySet()

    data class ClimateOn(
        val mode: ClimateMode,
        val duration: ClimateDuration,
        /** Start delay in minutes (0/5/10). Only 2018+ cars support it. */
        val delayMinutes: Int = 0,
    ) : CarCommand {
        override val label = "${mode.label} for ${duration.minutes} min" +
            if (delayMinutes > 0) " in $delayMinutes min" else ""

        override fun requiredRegisters(modelYear: ModelYear): Set<Int> =
            if (modelYear == ModelYear.MY14 || modelYear == ModelYear.UNKNOWN) setOf(0x22, 0x19) else emptySet()

        override fun toWrites(modelYear: ModelYear, state: CarState): List<RegisterWrite> = when (modelYear) {
            ModelYear.MY14, ModelYear.UNKNOWN -> listOf(
                RegisterWrite(Reg.WRITE_AC_SCHEDULE_MY14, my14Schedule(mode.code or (duration.code shl 4), state)),
                RegisterWrite(Reg.WRITE_AC_SWITCH_MY14, byteArrayOf(0x02)),
            )
            ModelYear.MY18, ModelYear.MY24 -> {
                val delay = when {
                    delayMinutes >= 10 -> 2
                    delayMinutes >= 5 -> 1
                    else -> 0
                }
                listOf(RegisterWrite(Reg.WRITE_AC_MY18, bytes(0x02, mode.code, duration.code, delay)))
            }
        }
    }

    data object ClimateOff : CarCommand {
        override val label = "Climate off"
        override fun toWrites(modelYear: ModelYear, state: CarState): List<RegisterWrite> = when (modelYear) {
            // The official app only flips the switch register to stop.
            ModelYear.MY14, ModelYear.UNKNOWN -> listOf(RegisterWrite(Reg.WRITE_AC_SWITCH_MY14, byteArrayOf(0x01)))
            ModelYear.MY18, ModelYear.MY24 -> listOf(RegisterWrite(Reg.WRITE_AC_MY18, bytes(0x01, 0, 0, 0)))
        }
    }

    data class Headlights(val on: Boolean) : CarCommand {
        override val label = "Headlights ${if (on) "on" else "off"}"
        override fun toWrites(modelYear: ModelYear, state: CarState) =
            listOf(RegisterWrite(Reg.WRITE_HEADLIGHTS, bytes(if (on) 1 else 2)))
    }

    data class ParkingLights(val on: Boolean) : CarCommand {
        override val label = "Parking lights ${if (on) "on" else "off"}"
        override fun toWrites(modelYear: ModelYear, state: CarState) =
            listOf(RegisterWrite(Reg.WRITE_PARKING_LIGHTS, bytes(if (on) 1 else 2)))
    }

    /** Find-my-car: headlights on for a few seconds, then off. */
    data class Locate(val seconds: Int = 5) : CarCommand {
        override val label = "Flash headlights"
        override fun toWrites(modelYear: ModelYear, state: CarState) = listOf(
            RegisterWrite(Reg.WRITE_HEADLIGHTS, bytes(1), pauseAfterMs = seconds.coerceIn(1, 30) * 1000L),
            RegisterWrite(Reg.WRITE_HEADLIGHTS, bytes(2)),
        )
    }

    data object RequestUpdate : CarCommand {
        override val label = "Refresh"
        override fun toWrites(modelYear: ModelYear, state: CarState) = listOf(RegisterWrite(Reg.WRITE_REQUEST_UPDATE, bytes(3)))
    }

    /** Register this phone. The car must be in registration mode. */
    data object RegisterPhone : CarCommand {
        override val label = "Register phone"
        override fun toWrites(modelYear: ModelYear, state: CarState) = listOf(RegisterWrite(Reg.WRITE_REGISTER_CLIENT, bytes(1)))
    }

    data object UnregisterPhone : CarCommand {
        override val label = "Unregister phone"
        override fun toWrites(modelYear: ModelYear, state: CarState) = listOf(RegisterWrite(Reg.WRITE_UNREGISTER_CLIENT, bytes(1)))
    }

    /** Acknowledge "climate cancelled" (door opened / low battery) so it can run again. */
    data object AckClimateCancelled : CarCommand {
        override val label = "Reset climate warning"
        override fun toWrites(modelYear: ModelYear, state: CarState) = listOf(RegisterWrite(Reg.WRITE_ACK_AC_TERMINATION, bytes(1)))
    }

    /** Override the charge timer and charge now. Experimental (seen on MY18). */
    data object ChargeNow : CarCommand {
        override val label = "Charge now"
        override fun toWrites(modelYear: ModelYear, state: CarState) = listOf(
            RegisterWrite(Reg.WRITE_CHARGE_TIMER_CANCEL, bytes(0x01)),
            RegisterWrite(Reg.WRITE_CHARGE_TIMER_CANCEL, bytes(0x11)),
        )
    }

    /**
     * Set the car's clock (its charge and climate timers run on it). Same format
     * as the official app: [yy mm dd hh mi ss dow flag], dow with Sunday = 0;
     * the official app sets flag = 1 only on rooted phones.
     */
    data class SyncClock(val time: LocalDateTime) : CarCommand {
        override val label = "Sync car clock"
        override fun toWrites(modelYear: ModelYear, state: CarState): List<RegisterWrite> {
            val dow = if (time.dayOfWeek == DayOfWeek.SUNDAY) 0 else time.dayOfWeek.value
            return listOf(
                RegisterWrite(
                    Reg.WRITE_CLOCK_SYNC,
                    bytes(time.year - 2000, time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second, dow, 0),
                )
            )
        }
    }

    /** Arbitrary register write, for experiments from the diagnostics screen. */
    class Raw(val register: Int, val data: ByteArray) : CarCommand {
        init {
            require(register in 0..255) { "Register must be 00..ff" }
            require(data.size in 1..64) { "Data must be 1..64 bytes" }
        }

        override val label = "Raw 0x%02x=%s".format(register, data.toHex())
        override fun toWrites(modelYear: ModelYear, state: CarState) = listOf(RegisterWrite(register, data))
    }

    companion object {
        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /**
         * MY14 climate register 0x02, 15 bytes, built like the official app does:
         * the car's current register 0x22 (6 bytes), the mode byte
         * (mode | duration << 4), then bytes 1..8 of register 0x19. Echoing those
         * leaves the car's own climate timers untouched. If the car has not sent
         * them yet, fall back to the pattern used by phev2mqtt/phevcore.
         */
        internal fun my14Schedule(modeByte: Int, state: CarState): ByteArray {
            val r22 = state.registers[0x22]?.hex?.let(::hexOrNull)
            val r19 = state.registers[0x19]?.hex?.let(::hexOrNull)
            val d = ByteArray(15) { 0xff.toByte() }
            if (r22 != null && r22.size >= 6 && r19 != null && r19.size >= 9) {
                r22.copyInto(d, 0, 0, 6)
                r19.copyInto(d, 7, 1, 9)
            } else {
                d[0] = 0; d[1] = 0
            }
            d[6] = modeByte.toByte()
            return d
        }

        private fun hexOrNull(h: String): ByteArray? = runCatching { h.hexToBytes() }.getOrNull()
    }
}

sealed interface CommandResult {
    data class Success(val command: CarCommand, val tookMs: Long) : CommandResult
    /** [retryable] = failed because the connection dropped, safe to resend after reconnect. */
    data class Failure(val command: CarCommand, val reason: String, val retryable: Boolean = false) : CommandResult
}
