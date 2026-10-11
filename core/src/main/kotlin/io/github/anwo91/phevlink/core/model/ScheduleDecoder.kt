package io.github.anwo91.phevlink.core.model

import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import java.time.LocalTime

/** A per-day charge-timer entry (the window the car is allowed to charge). Day 0 = Sunday … 6 = Saturday. */
data class ChargeDay(val day: Int, val start: LocalTime?, val end: LocalTime?, val fullCharge: Boolean) {
    val programmed: Boolean get() = start != null || end != null || fullCharge
}

/** A per-day climate-timer entry (when the car preconditions). */
data class ClimateDay(val day: Int, val start: LocalTime?) {
    val programmed: Boolean get() = start != null
}

/**
 * Reads the car's own charge- and climate-timer schedules out of the registers it streams. These are the same
 * timers the official app programs; here they are decoded read-only, exactly as the official app packs them
 * (verified against the field log, where every slot reads as "unset").
 *
 * Bit packing (MY14, from the official app): each schedule is a little-endian bit stream. Hours use a 5-bit field
 * per day (unset = 31), minutes a 3-bit field per day holding minutes÷10 (unset = 7). Day index = Sunday 0.
 */
object ScheduleDecoder {

    fun charge(state: CarState): List<ChargeDay> {
        val b = bytesOf(state, Reg.READ_CHARGE_TIMER_PROG) ?: return emptyList()
        if (b.size < 16) return emptyList()
        val fullMask = bytesOf(state, Reg.READ_FULL_CHARGE_DAYS)?.firstOrNull()?.toInt()?.and(0xff) ?: 0
        val startH = bits5(b, 0); val startM = bits3(b, 5)
        val endH = bits5(b, 8); val endM = bits3(b, 13)
        return (0..6).map { d ->
            ChargeDay(
                day = d,
                start = time(startH[d], startM[d]),
                end = time(endH[d], endM[d]),
                fullCharge = (fullMask shr d) and 1 == 1,
            )
        }
    }

    fun climate(state: CarState): List<ClimateDay> {
        val b = bytesOf(state, Reg.READ_CLIMATE_TIMER_PROG) ?: return emptyList()
        if (b.size < 9) return emptyList()
        val h = bits5(b, 1); val m = bits3(b, 6) // byte 0 is the on/off state
        return (0..6).map { d -> ClimateDay(d, time(h[d], m[d])) }
    }

    /** The charge timer is enabled (register 0x1b low nibble ≥ 2; 1 = suspended / charge-now). */
    fun chargeTimerOn(state: CarState): Boolean =
        bytesOf(state, Reg.READ_CHARGE_TIMER_STATE_B)?.firstOrNull()?.toInt()?.and(0x0f)?.let { it >= 2 } ?: false

    /** The climate timer is enabled (register 0x19 byte 0 == 2). */
    fun climateTimerOn(state: CarState): Boolean =
        bytesOf(state, Reg.READ_CLIMATE_TIMER_PROG)?.firstOrNull()?.toInt()?.and(0xff) == 2

    /** Any charge or climate day programmed, or a timer switched on — worth showing a schedule card. */
    fun hasAny(state: CarState): Boolean =
        charge(state).any { it.programmed } || climate(state).any { it.programmed } || chargeTimerOn(state) || climateTimerOn(state)

    // ---- bit helpers

    private fun bytesOf(state: CarState, reg: Int): ByteArray? =
        state.registers[reg]?.hex?.let { runCatching { it.hexToBytes() }.getOrNull() }

    /** Seven 5-bit hour fields packed little-endian from [byteOffset] (5 bytes); 31 = unset → null. */
    private fun bits5(b: ByteArray, byteOffset: Int): Array<Int?> = field(b, byteOffset, 5, 5, 31)

    /** Seven 3-bit minute÷10 fields packed little-endian from [byteOffset] (3 bytes); 7 = unset → null. */
    private fun bits3(b: ByteArray, byteOffset: Int): Array<Int?> = field(b, byteOffset, 3, 3, 7)

    private fun field(b: ByteArray, byteOffset: Int, byteLen: Int, bitWidth: Int, unset: Int): Array<Int?> {
        var value = 0L
        for (i in 0 until byteLen) {
            val idx = byteOffset + i
            if (idx < b.size) value = value or ((b[idx].toLong() and 0xff) shl (8 * i))
        }
        val mask = (1L shl bitWidth) - 1
        return Array(7) { d ->
            val raw = ((value shr (bitWidth * d)) and mask).toInt()
            if (raw == unset) null else raw
        }
    }

    private fun time(hour: Int?, min10: Int?): LocalTime? {
        if (hour == null || hour > 23) return null
        val m = (min10 ?: 0).coerceIn(0, 5) * 10
        return LocalTime.of(hour, m)
    }
}
