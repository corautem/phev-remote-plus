package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.obd.BatteryHealth
import io.github.anwo91.phevlink.core.obd.Confidence
import io.github.anwo91.phevlink.core.obd.DtcCodec
import io.github.anwo91.phevlink.core.obd.DtcKind
import io.github.anwo91.phevlink.core.obd.EcuReply
import io.github.anwo91.phevlink.core.obd.Mode01
import io.github.anwo91.phevlink.core.obd.Mode09
import io.github.anwo91.phevlink.core.obd.ObdHex
import io.github.anwo91.phevlink.core.obd.PhevSignals
import io.github.anwo91.phevlink.core.obd.SignalDecoder
import io.github.anwo91.phevlink.core.obd.emulator.FakeElm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decoding: DTCs, mode 01 formulas, OBDb bit layout, battery bytes. */
class ObdDecodeTest {
    private fun hex(s: String) = ObdHex.parse(s)

    @Test
    fun dtcBytesDecode() {
        assertEquals("P0420", DtcCodec.decode(0x04, 0x20))
        assertEquals("P0A80", DtcCodec.decode(0x0A, 0x80))
        assertEquals("U0100", DtcCodec.decode(0xC1, 0x00))
        assertEquals("C0035", DtcCodec.decode(0x40, 0x35))
        assertEquals("B1234", DtcCodec.decode(0x92, 0x34))
        assertEquals("P0420", DtcCodec.decode(0x0420))
        for (c in listOf("P0420", "P0A80", "U0100", "C0035", "B1234", "U3FFF", "P1A15")) {
            assertEquals(c, DtcCodec.decode(DtcCodec.encode(c)))
        }
    }

    @Test
    fun mode03ResponseWithCountAndPadding() {
        val r = EcuReply(0x7E8, hex("43 03 04 20 0A 80 C1 00"))
        val codes = DtcCodec.parseObdResponse(r, DtcKind.STORED)
        assertEquals(listOf("P0420", "P0A80", "U0100"), codes.map { it.code })
        assertTrue(codes.all { it.kind == DtcKind.STORED && it.ecuRxId == 0x7E8 && it.ecuName == "Engine (ECM)" })

        // count byte + 00 00 padding
        assertEquals(listOf("P0420"), DtcCodec.parseObdResponse(EcuReply(0x7E8, hex("43 01 04 20 00 00")), DtcKind.STORED).map { it.code })
        // no codes
        assertTrue(DtcCodec.parseObdResponse(EcuReply(0x7E8, hex("43 00")), DtcKind.STORED).isEmpty())
        // legacy layout without count byte (odd remainder) is tolerated
        assertEquals(listOf("P0420"), DtcCodec.parseObdResponse(EcuReply(0x7E8, hex("43 04 20 00 00 00 00")), DtcKind.STORED).map { it.code })
        // pending kind
        assertEquals(DtcKind.PENDING, DtcCodec.parseObdResponse(EcuReply(0x7E8, hex("47 01 04 56")), DtcKind.PENDING).single().kind)
    }

    @Test
    fun kwpAndUdsDtcReplies() {
        val kwp = DtcCodec.parseKwp18(EcuReply(0x762, hex("58 02 0A 7F 60 C1 00 20")))
        assertEquals(listOf("P0A7F", "U0100"), kwp.map { it.code })
        assertEquals(0x60, kwp[0].status)
        assertEquals("ECU Drive battery (BMU) (KWP)", kwp[0].source)

        val uds = DtcCodec.parseUds19(EcuReply(0x7E8, hex("59 02 FF 0A 80 1B 09 04 20 00 50 C1 00 00 04")))
        // second entry (status 0x50: test not completed) carries no fault bit → skipped
        assertEquals(listOf("P0A80", "U0100"), uds.map { it.code })
        assertEquals(0x1B, uds[0].failureType)
        assertEquals(DtcKind.STORED, uds[0].kind)
        assertEquals(DtcKind.PENDING, uds[1].kind)
        assertTrue(uds[0].source.endsWith("(UDS)"))
    }

    @Test
    fun mode01Formulas() {
        fun v(pid: Int, data: String) = Mode01.decode(pid, hex(data)).value!!
        assertEquals(1726.0, v(0x0C, "1A F8"), 1e-9)
        assertEquals(83.0, v(0x05, "7B"), 1e-9)
        assertEquals(15.0, v(0x42, "3A 98"), 1e-9)
        assertEquals(50.0, v(0x0D, "32"), 1e-9)
        assertEquals(100.0, v(0x04, "FF"), 1e-9)
        assertEquals(0.0, v(0x06, "80"), 1e-9)
        assertEquals(-100.0, v(0x07, "00"), 1e-9)
        assertEquals(-64.0, v(0x0E, "00"), 1e-9)
        assertEquals(5.54, v(0x10, "02 2A"), 1e-9) // OBDb 2011 test vector 7E8 04 41 10 02 2A
        assertEquals(225.0, v(0x31, "00 E1"), 1e-9)
        assertEquals(15.0, v(0x46, "37"), 1e-9)
        assertEquals(1.0, v(0x44, "80 00"), 1e-9)
        assertEquals(12345.6, v(0xA6, "00 01 E2 40"), 1e-9)
        assertEquals("Hybrid electric & combustion", Mode01.decode(0x51, hex("15")).text)
        val mon = Mode01.decode(0x01, hex("81 07 E5 00"))
        assertEquals(1.0, mon.value!!, 0.0)
        assertEquals("MIL on, 1 DTC", mon.text)
        // unknown PID → raw hex
        assertEquals("01 02", Mode01.decode(0x13, hex("01 02")).text)
        assertNull(Mode01.decode(0x13, hex("01 02")).value)
    }

    @Test
    fun supportedPidBitmap() {
        val s = Mode01.parseSupported(0x00, hex("BE 1F A8 13"))
        assertEquals(listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), s.toList())
        assertEquals(setOf(0x42, 0x46), Mode01.parseSupported(0x40, hex("44 00 00 00")))
        assertEquals("obd.0C", Mode01.valueId(0x0C))
        assertEquals("obd.05@7E9", Mode01.valueId(0x05, 0x7E9))
    }

    @Test
    fun vinAndEcuName() {
        val vin = EcuReply(0x7E8, byteArrayOf(0x49, 0x02, 0x01) + "JMAXDGG2WFZ012345".toByteArray())
        assertEquals("JMAXDGG2WFZ012345", Mode09.parseVin(vin))
        val name = EcuReply(0x7E8, byteArrayOf(0x49, 0x0A, 0x01) + "ECM\u0000-EngineControl\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
        assertEquals("ECM-EngineControl", Mode09.parseEcuName(name))
    }

    @Test
    fun chargerLiveSignalsDecode() {
        fun sig(id: String, payloadAfterEcho: String) = SignalDecoder.decode(PhevSignals.byId(id)!!, hex(payloadAfterEcho))
        // 21 02 payload (after 61 02) from the emulator: AC 230.0 V / 14.0 A, DC 360.0 V / 9.0 A, CP duty 53 %.
        val p2 = "00 00 08 FC 8C 0E 10 5A 00 00 00 00 00 35"
        assertEquals(230.0, sig("obc.ac_volts", p2)!!, 1e-9)
        assertEquals(14.0, sig("obc.ac_amps", p2)!!, 1e-9)
        assertEquals(360.0, sig("obc.dc_volts", p2)!!, 1e-9)
        assertEquals(9.0, sig("obc.dc_amps", p2)!!, 1e-9)
        assertEquals(53.0, sig("obc.pilot_duty", p2)!!, 1e-9)
        // 21 03 payload: DC/DC 12 V output current 8.2 A at byte 7.
        assertEquals(8.2, sig("obc.dcdc_amps", "00 00 00 00 00 00 00 52")!!, 1e-9)
    }

    @Test
    fun obdbBitLayoutMatchesObdbTestVectors() {
        // OBDb Mitsubishi-Outlander tests/test_cases/2011 (7E0 service 21), expected values from those files.
        fun sig(id: String, payloadAfterEcho: String) = SignalDecoder.decode(PhevSignals.byId(id)!!, hex(payloadAfterEcho))
        assertEquals(89.41176, sig("ecm.knock_learned", "22 00 E4 04 04")!!, 1e-4)
        assertEquals(0.0, sig("ecm.knock_retard", "22 00 E4 04 04")!!, 1e-9)
        assertEquals(24.70588, sig("ecm.calc_load", "3F 34 00 00 00")!!, 1e-4)
        assertEquals(20.39216, sig("ecm.abs_load", "3F 34 00 00 00")!!, 1e-4)
        assertEquals(1.04492, sig("ecm.throttle_target_v", "02 17 0B 00 00")!!, 1e-4)
        assertEquals(4.31373, sig("ecm.throttle_servo", "02 17 0B 00 00")!!, 1e-4)
        val d = "82 01 40 00 00"
        assertEquals(1.0, sig("ecm.idle", d)!!, 0.0)
        assertEquals(0.0, sig("ecm.cranking", d)!!, 0.0)
        assertEquals(0.0, sig("ecm.off_condition", d)!!, 0.0)
        assertEquals(1.0, sig("ecm.neutral", d)!!, 0.0)
        assertEquals(1.0, sig("ecm.ignition_switch", d)!!, 0.0)
        assertEquals(0.0, sig("ecm.brake_switch", d)!!, 0.0)
        val r = "26 00 00 00 00"
        assertEquals(1.0, sig("ecm.relay_engine", r)!!, 0.0)
        assertEquals(1.0, sig("ecm.relay_throttle", r)!!, 0.0)
        assertEquals(1.0, sig("ecm.relay_fuel_pump", r)!!, 0.0)
        assertEquals(0.0, sig("ecm.relay_ac", r)!!, 0.0)
        // out of range → hidden
        assertNull(sig("cluster.fuel_level", "00 00 05")) // 5 − 16 < 0
        // payload too short → null
        assertNull(sig("abs.wheel_rr", "00 00 00 00"))
    }

    @Test
    fun bitExtraction() {
        val d = hex("A5 0F")
        assertEquals(0xA5L, SignalDecoder.extract(d, 0, 8))
        assertEquals(0x50L, SignalDecoder.extract(d, 4, 8))
        assertEquals(1L, SignalDecoder.extract(d, 0, 1))
        assertEquals(0L, SignalDecoder.extract(d, 1, 1))
        assertEquals(-91L, SignalDecoder.extract(d, 0, 8, signed = true))
        assertNull(SignalDecoder.extract(d, 9, 8))
    }

    @Test
    fun forumBmuBytesGiveBatteryHealth() {
        val reply = EcuReply(0x762, FakeElm.BMU_2101)
        assertEquals(55, reply.data.size)
        val payload = SignalDecoder.payloadOf(byteArrayOf(0x21, 0x01), reply)!!
        val b = BatteryHealth.decode(payload, null, 1234L)!!
        assertEquals(60.0, b.soc!!, 1e-9)
        assertEquals(60.5, b.soc2!!, 1e-9)
        assertEquals(318.2, b.packVoltage!!, 1e-9)
        assertEquals(34.0, b.capacityAh!!, 1e-9)
        assertEquals(20.4, b.remainingAh!!, 1e-9)
        assertEquals(3.979, b.maxCellV!!, 1e-9)
        assertEquals(36, b.maxCellNo)
        assertEquals(3.976, b.minCellV!!, 1e-9)
        assertEquals(3, b.minCellNo)
        assertEquals(3.0, b.spreadMv!!, 0.0)
        assertEquals(85.0, b.sohPercent!!, 1e-9)
        assertEquals(6.49128, b.energyKwh!!, 1e-6)
        assertEquals(0x82, b.rawBmu!![0].toInt() and 0xFF)
        assertEquals(53, b.rawBmu!!.size)
        assertNull(b.charges100V)

        val obc = SignalDecoder.payloadOf(byteArrayOf(0x21, 0x01), EcuReply(0x766, hex("61 01 00 2A 01 F4 00 00 00 00")))!!
        val withObc = BatteryHealth.decode(payload, obc, 1L)!!
        assertEquals(42, withObc.charges100V)
        assertEquals(500, withObc.charges200V)
    }

    @Test
    fun signalTableIsConsistent() {
        val ids = PhevSignals.all.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        assertTrue(PhevSignals.all.all { it.min <= it.max && it.bitLength in 1..32 })
        // BMU signals share one request → fetched once
        val reqs = PhevSignals.requests()
        assertEquals(1, reqs.count { it.key == "761:2101" })
        assertEquals(9, reqs.single { it.key == "761:2101" }.signals.size)
        assertTrue(PhevSignals.battery.all { it.target.flowControl })
        assertTrue(PhevSignals.engine.none { it.target.flowControl })
        // nothing from OBDb is claimed to be more than experimental
        assertTrue((PhevSignals.engine + PhevSignals.cluster + PhevSignals.climate + PhevSignals.abs + PhevSignals.awc).all { it.confidence == Confidence.EXPERIMENTAL })
        assertNotNull(PhevSignals.byId("motor.front_rpm"))
        assertEquals(PhevSignals.GROUP_BATTERY, PhevSignals.groups.first())
    }
}
