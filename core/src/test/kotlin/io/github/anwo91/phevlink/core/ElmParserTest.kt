package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.obd.BatteryHealth
import io.github.anwo91.phevlink.core.obd.ElmResponseParser
import io.github.anwo91.phevlink.core.obd.ElmText
import io.github.anwo91.phevlink.core.obd.EcuReply
import io.github.anwo91.phevlink.core.obd.Nrc
import io.github.anwo91.phevlink.core.obd.ObdHex
import io.github.anwo91.phevlink.core.obd.SignalDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text cleaning and ISO-TP reassembly of ELM327 output. */
class ElmParserTest {
    private val forumLines = listOf(
        "762 10 37 61 01 82 83 0F 8B",
        "762 21 24 0F 88 03 0C 6E 52",
        "762 22 03 4D 1C 01 99 00 00",
        "762 23 00 00 00 01 00 01 30",
        "762 24 0F 0F 01 54 00 CC 9C",
        "762 25 FE 00 03 0F 88 86 8B",
        "762 26 64 00 00 00 00 00 00",
        "762 27 00 00 0F 8A 00 02 00",
    )

    @Test
    fun forumBmuExampleReassemblesToBatteryHealth() {
        val p = ElmResponseParser.parse(forumLines)
        assertEquals(1, p.messages.size)
        assertTrue(p.incomplete.isEmpty())
        val m = p.messages.single()
        assertEquals(0x762, m.rxId)
        assertEquals(0x37, m.data.size)
        assertEquals("61 01 82 83 0F 8B 24", ObdHex.format(m.data.copyOf(7)))
        val b = BatteryHealth.decode(SignalDecoder.payloadOf(byteArrayOf(0x21, 0x01), m)!!, null, 0L)!!
        assertEquals(60.0, b.soc!!, 1e-9)
        assertEquals(318.2, b.packVoltage!!, 1e-9)
        assertEquals(34.0, b.capacityAh!!, 1e-9)
        assertEquals(20.4, b.remainingAh!!, 1e-9)
        assertEquals(3.979, b.maxCellV!!, 1e-9)
        assertEquals(36, b.maxCellNo)
        assertEquals(3.976, b.minCellV!!, 1e-9)
        assertEquals(3, b.minCellNo)
        assertEquals(3.0, b.spreadMv!!, 0.0)
        assertEquals(85.0, b.sohPercent!!, 1e-9)
    }

    @Test
    fun onlyFirstFrameIsIncomplete() {
        val p = ElmResponseParser.parse(forumLines.take(1))
        assertTrue(p.messages.isEmpty())
        assertEquals(ElmResponseParser.Partial(0x762, 6, 0x37, 0x61), p.incomplete.single())
    }

    @Test
    fun multiEcuBroadcastAndInterleavedMultiFrame() {
        val lines = listOf(
            "7E8 06 41 00 BE 3F A8 13 00",
            "7EA 10 0A 43 04 04 20 0A 80",
            "7E9 06 41 00 98 18 80 01 AA",
            "7EA 21 C1 00 40 35 00 00 00",
        )
        val p = ElmResponseParser.parse(lines)
        assertEquals(listOf(0x7E8, 0x7E9, 0x7EA), p.messages.map { it.rxId })
        assertEquals("41 00 BE 3F A8 13", ObdHex.format(p.messages[0].data)) // padding cut
        assertEquals("43 04 04 20 0A 80 C1 00 40 35", ObdHex.format(p.messages[2].data))
    }

    @Test
    fun negativeAndPendingAreSeparateMessages() {
        val p = ElmResponseParser.parse(listOf("762 03 7F 21 78") + forumLines)
        assertEquals(2, p.messages.size)
        val pending = p.messages[0]
        assertTrue(pending.isNegative)
        assertEquals(0x21, pending.negativeServiceId)
        assertEquals(Nrc.RESPONSE_PENDING, pending.nrc)
        assertEquals("response pending", Nrc.name(0x78))
        assertEquals("request out of range", Nrc.name(0x31))
        assertEquals("service not supported", Nrc.name(0x11))
        assertEquals("conditions not correct", Nrc.name(0x22))
        assertEquals(0x61, p.messages[1].sid)
    }

    @Test
    fun toleratesCompactHexDlcHeaderlessAnd29Bit() {
        assertEquals("41 00 BE 3F A8 13", ObdHex.format(ElmResponseParser.parse(listOf("7E8064100BE3FA813")).messages.single().data))
        assertEquals("41 0D 32", ObdHex.format(ElmResponseParser.parse(listOf("7E8 8 03 41 0D 32 00 00 00 00")).messages.single().data))
        // ATH0 + CAF1: length line + indexed lines
        val h0 = ElmResponseParser.parse(
            listOf("014", "0: 49 02 01 4A 4D 41", "1: 58 44 47 47 32 57 46", "2: 5A 30 31 32 33 34 35"),
            headersExpected = false, headerlessRxId = 0x7E8,
        )
        assertEquals(0x7E8, h0.messages.single().rxId)
        assertEquals(20, h0.messages.single().data.size)
        // ATH0 single frame: data without PCI
        assertEquals("41 0C 1A F8", ObdHex.format(ElmResponseParser.parse(listOf("41 0C 1A F8"), headersExpected = false).messages.single().data))
        // 29-bit frames are skipped, not misread
        val p29 = ElmResponseParser.parse(listOf("18 DA F1 10 06 41 00 BE 3F A8 13", "7E8 03 41 0D 32"))
        assertEquals(1, p29.skippedLines)
        assertEquals(0x7E8, p29.messages.single().rxId)
    }

    @Test
    fun statusAndErrorLines() {
        val p = ElmResponseParser.parse(listOf("7E8 06 41 00 BE 3F A8 13 <DATA ERROR", "CAN ERROR", "NO DATA", "?", "BUFFER FULL"))
        assertTrue(p.messages.isEmpty())
        assertEquals(listOf("DATA ERROR", "CAN ERROR", "NO DATA", "?", "BUFFER FULL"), p.status)
        // orphan consecutive frame and wrong sequence
        val seq = ElmResponseParser.parse(listOf("762 22 00 00 00 00 00 00 00", "762 10 37 61 01 82 83 0F 8B", "762 22 24 0F 88 03 0C 6E 52"))
        assertTrue(seq.messages.isEmpty())
        assertEquals(1, seq.skippedLines)
        assertTrue(seq.status.any { it.startsWith("SEQUENCE ERROR") })
    }

    @Test
    fun textCleaning() {
        val raw = "\u0000\u0000atz\r\r\nelm327 v1.5\r\r"
        assertEquals(listOf("ELM327 V1.5"), ElmText.lines(raw, echo = "ATZ"))
        assertEquals(listOf("elm327 v1.5"), ElmText.lines(raw, echo = "ATZ", upper = false))
        assertEquals(listOf("7E8 06 41 00 BE 3F A8 13"), ElmText.lines("0100\rSEARCHING...\r7e8 06 41 00 be 3f a8 13\r\r", echo = "0100"))
        assertEquals(listOf("41 00 BE"), ElmText.lines("BUS INIT: ...OK\r41 00 BE\r"))
        assertEquals(listOf("41 00 BE"), ElmText.lines("SEARCHING...41 00 BE\n"))
        assertEquals(listOf("BUS INIT ERROR"), ElmText.lines("BUS INIT: ...ERROR\r"))
    }

    @Test
    fun ecuReplyEquality() {
        assertEquals(EcuReply(0x7E8, byteArrayOf(0x41, 0x00)), EcuReply(0x7E8, byteArrayOf(0x41, 0x00)))
        assertArrayEquals(byteArrayOf(0x41), EcuReply(0x7E8, byteArrayOf(0x41)).data)
    }
}
