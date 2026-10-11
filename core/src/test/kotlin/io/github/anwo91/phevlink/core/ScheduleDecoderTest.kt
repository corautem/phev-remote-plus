package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.RawRegister
import io.github.anwo91.phevlink.core.model.ScheduleDecoder
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.core.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ScheduleDecoderTest {
    // Encoders that mirror the official app's packing, so the test proves the decoder inverts it.
    private fun pack(fields: IntArray, bitWidth: Int, byteLen: Int, unset: Int): ByteArray {
        var value = 0L
        for (d in 0..6) value = value or ((if (d < fields.size) fields[d] else unset).toLong() and ((1L shl bitWidth) - 1) shl (bitWidth * d))
        return ByteArray(byteLen) { ((value shr (8 * it)) and 0xff).toByte() }
    }

    private fun hours(vararg dayToHour: Pair<Int, Int>) =
        IntArray(7) { 31 }.also { for ((d, h) in dayToHour) it[d] = h }

    private fun mins(vararg dayToMin10: Pair<Int, Int>) =
        IntArray(7) { 7 }.also { for ((d, m) in dayToMin10) it[d] = m }

    private fun state(regs: Map<Int, ByteArray>) = CarState(
        registers = regs.mapValues { (r, b) -> RawRegister(r, b.toHex(), 0, 1) },
    )

    @Test
    fun decodesChargeAndClimateSchedules() {
        // Charge Monday (day 1) 22:30 → 06:00, full-charge on Monday. Climate Tuesday (day 2) 07:00.
        val charge = pack(hours(1 to 22), 5, 5, 31) + pack(mins(1 to 3), 3, 3, 7) +
            pack(hours(1 to 6), 5, 5, 31) + pack(mins(1 to 0), 3, 3, 7)
        val climate = byteArrayOf(0x02) + pack(hours(2 to 7), 5, 5, 31) + pack(mins(2 to 0), 3, 3, 7)
        val s = state(
            mapOf(
                0x18 to charge,
                0x0e to byteArrayOf(0b10), // full-charge bit for day 1
                0x19 to climate,
                0x1b to byteArrayOf(0x02),
            ),
        )
        val c = ScheduleDecoder.charge(s)
        assertEquals(7, c.size)
        val mon = c[1]
        assertEquals(LocalTime.of(22, 30), mon.start)
        assertEquals(LocalTime.of(6, 0), mon.end)
        assertTrue(mon.fullCharge)
        assertFalse(c[0].programmed) // Sunday unset
        assertEquals(1, c.count { it.programmed })

        val cl = ScheduleDecoder.climate(s)
        assertEquals(LocalTime.of(7, 0), cl[2].start)
        assertEquals(1, cl.count { it.programmed })

        assertTrue(ScheduleDecoder.chargeTimerOn(s))
        assertTrue(ScheduleDecoder.climateTimerOn(s))
        assertTrue(ScheduleDecoder.hasAny(s))
    }

    @Test
    fun fieldLogAllUnsetMeansNoSchedule() {
        // Exactly what the owner's car streamed: every slot unset.
        val s = state(
            mapOf(
                0x18 to "ffffffff07ffff1fffffffff07ffff1f".hexToBytes(),
                0x19 to "01ffffffff07ffff1f".hexToBytes(),
            ),
        )
        assertTrue(ScheduleDecoder.charge(s).none { it.programmed })
        assertTrue(ScheduleDecoder.climate(s).none { it.programmed })
        assertFalse(ScheduleDecoder.climateTimerOn(s)) // byte 0 == 1 → off
        assertFalse(ScheduleDecoder.hasAny(s))
    }

    @Test
    fun missingRegistersGiveEmpty() {
        val s = CarState()
        assertTrue(ScheduleDecoder.charge(s).isEmpty())
        assertTrue(ScheduleDecoder.climate(s).isEmpty())
        assertNull(ScheduleDecoder.charge(s).firstOrNull())
    }
}
