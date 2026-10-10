package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.Elm327
import io.github.anwo91.phevlink.core.obd.ElmConfig
import io.github.anwo91.phevlink.core.obd.EcuTarget
import io.github.anwo91.phevlink.core.obd.ObdException
import io.github.anwo91.phevlink.core.obd.ObdHex
import io.github.anwo91.phevlink.core.obd.ObdNegativeResponse
import io.github.anwo91.phevlink.core.obd.PhevSignals
import io.github.anwo91.phevlink.core.obd.emulator.FakeElm
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The ELM327 driver against the in-process emulator, including every fault knob. */
class Elm327Test {
    private val log = DiagLog()
    private val bmu2101 = byteArrayOf(0x21, 0x01)

    private fun run(block: suspend () -> Unit) = runBlocking {
        try {
            withTimeout(10_000) { block() }
        } catch (t: Throwable) {
            System.err.println("---- diag log ----\n" + log.dump())
            throw t
        }
    }

    private fun elm(fake: FakeElm, config: ElmConfig = ElmConfig.fast()) = Elm327(fake, log, config)

    @Test
    fun initialisesAgainstDefaultEmulator() = run {
        val fake = FakeElm()
        val info = elm(fake).initialize()
        assertEquals("ELM327 v1.5", info.version)
        assertEquals("OBDII to RS232 Interpreter", info.description)
        assertEquals(12.6, info.voltage!!, 1e-9)
        assertTrue(info.carReachable)
        assertTrue(info.supportsFlowControlHeader)
        assertTrue(info.supportsReceiveFilter)
        assertTrue(info.supportsAdaptiveTiming)
        assertTrue(info.protocol, info.protocol.contains("15765-4 CAN (11 bit, 500 kbaud)"))
        assertTrue("v1.5 is clone-only", info.isClone)
        assertNull(info.message)
        assertNull(info.batteryDataMessage)
        val cmds = fake.commands
        for (c in listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATSP6", "ATCAF1", "ATI", "AT@1", "ATRV", "ATFCSH761", "ATFCSD300000", "ATFCSM1", "ATFCSM0", "ATCRA", "0100", "ATDPN")) {
            assertTrue("missing $c in $cmds", c in cmds)
        }
    }

    @Test
    fun everyFaultKnobStillInitialises() = run {
        val variants: List<Pair<String, FakeElm.() -> Unit>> = listOf(
            "ble chunks" to { bleChunks = true },
            "cr only" to { crOnly = true },
            "lowercase" to { lowercase = true },
            "garbage" to { garbageBeforePrompt = true },
            "slow" to { slowMs = 5 },
            "no adaptive timing" to { rejectAdaptiveTiming = true },
            "no receive filter" to { rejectReceiveFilter = true },
            "everything" to {
                bleChunks = true; crOnly = true; lowercase = true; garbageBeforePrompt = true; slowMs = 2
                rejectAdaptiveTiming = true; rejectReceiveFilter = true
            },
        )
        for ((name, knobs) in variants) {
            val fake = FakeElm().apply(knobs)
            val e = elm(fake)
            val info = e.initialize()
            assertTrue("$name: car reachable", info.carReachable)
            assertTrue("$name: version '${info.version}'", info.version.equals("ELM327 v1.5", ignoreCase = true))
            val rpm = e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0C)).single()
            assertEquals("$name: rpm reply", "41 0C 1A F8", ObdHex.format(rpm.data))
            val bmu = e.request(PhevSignals.BMU, bmu2101).single()
            assertEquals("$name: BMU reply size", 55, bmu.data.size)
            val vin = e.request(EcuTarget.OBD_BROADCAST, byteArrayOf(0x09, 0x02)).single()
            assertEquals("$name: VIN size", 20, vin.data.size)
        }
    }

    @Test
    fun silentResetFallsBackToWarmStart() = run {
        val fake = FakeElm().apply { silentReset = true }
        assertTrue(elm(fake).initialize().carReachable)
        assertTrue("ATWS" in fake.commands)
    }

    @Test
    fun adapterThatNeverAnswersTimesOut() = run {
        val fake = FakeElm().apply { slowMs = 5_000 }
        try {
            elm(fake, ElmConfig.fast().copy(resetTimeoutMs = 100)).initialize()
            fail("expected timeout")
        } catch (e: ObdException.Timeout) {
            // expected
        }
    }

    @Test
    fun cloneWithoutFlowControl() = run {
        val fake = FakeElm(version = "ELM327 v2.1").apply { cloneRejectsFlowControl = true }
        val e = elm(fake)
        val info = e.initialize()
        assertFalse(info.supportsFlowControlHeader)
        assertTrue(info.carReachable)
        assertTrue(info.isClone)
        assertTrue(info.cloneNotes.joinToString(), info.cloneNotes.any { it.contains("ATFCSH") })
        assertEquals(Elm327.MSG_NO_FLOW_CONTROL, info.batteryDataMessage)
        try {
            e.request(PhevSignals.BMU, bmu2101)
            fail("expected an incomplete reply")
        } catch (x: ObdException.Incomplete) {
            assertEquals(0x762, x.rxId)
            assertEquals(6, x.received)
            assertEquals(55, x.expected)
        }
        // standard requests still work
        assertEquals("41 0D 32", ObdHex.format(e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0D)).single().data))
    }

    @Test
    fun stnChipIsNotAClone() = run {
        val fake = FakeElm(version = "ELM327 v1.4b").apply { stnChip = true }
        val info = elm(fake).initialize()
        assertEquals("STN1110 v4.2.1", info.stnChip)
        assertFalse(info.isClone)
    }

    @Test
    fun ignitionOffIsReportedAndProtocolRestored() = run {
        val fake = FakeElm().apply { ignitionOff = true }
        val info = elm(fake).initialize()
        assertFalse(info.carReachable)
        assertEquals(Elm327.MSG_IGNITION, info.message)
        assertTrue(info.message!!.contains("ignition"))
        val cmds = fake.commands
        val sp0 = cmds.indexOf("ATSP0")
        assertTrue("fallback to auto search", sp0 > 0)
        assertTrue("back to ATSP6 afterwards", cmds.subList(sp0, cmds.size).contains("ATSP6"))
    }

    @Test
    fun setupCommandsOnlyWhenSettingsChange() = run {
        val fake = FakeElm()
        val e = elm(fake)
        e.initialize()
        fake.clearCommandLog()
        e.request(PhevSignals.BMU, bmu2101)
        // FCSH761/FCSD300000 are still set from the capability probe; only the mode is switched
        assertEquals(listOf("ATSH761", "ATCRA762", "ATFCSM1", "2101"), fake.commands)
        fake.clearCommandLog()
        e.request(PhevSignals.BMU, bmu2101)
        assertEquals(listOf("2101"), fake.commands)
        fake.clearCommandLog()
        e.request(EcuTarget.OBD_BROADCAST, byteArrayOf(0x01, 0x00))
        assertEquals(listOf("ATSH7DF", "ATCRA", "ATFCSM0", "0100"), fake.commands)
        fake.clearCommandLog()
        e.request(EcuTarget.OBD_BROADCAST, byteArrayOf(0x01, 0x00))
        assertEquals(listOf("0100"), fake.commands)
        fake.clearCommandLog()
        e.request(PhevSignals.OBC, bmu2101)
        assertEquals(listOf("ATSH765", "ATCRA766", "ATFCSH765", "ATFCSM1", "2101"), fake.commands)
        // back to a standard ECU: automatic flow control again, else the VIN would stop after the first frame
        fake.clearCommandLog()
        val vin = e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x09, 0x02)).single()
        assertEquals(listOf("ATSH7E0", "ATCRA7E8", "ATFCSM0", "0902"), fake.commands)
        assertEquals(20, vin.data.size)
    }

    @Test
    fun broadcastCollectsSeveralEcus() = run {
        val fake = FakeElm().apply { secondObdEcu = true }
        val e = elm(fake)
        e.initialize()
        val replies = e.request(EcuTarget.OBD_BROADCAST, byteArrayOf(0x03))
        assertEquals(listOf(0x7E8, 0x7E9), replies.map { it.rxId })
        assertEquals("43 01 04 20", ObdHex.format(replies[0].data))
        assertEquals("43 01 C1 00", ObdHex.format(replies[1].data))
    }

    @Test
    fun negativeResponseIsTyped() = run {
        val fake = FakeElm()
        val e = elm(fake)
        e.initialize()
        try {
            e.request(PhevSignals.ENGINE, byteArrayOf(0x21, 0x01))
            fail("expected a negative response")
        } catch (x: ObdNegativeResponse) {
            assertEquals(0x7E8, x.rxId)
            assertEquals(0x21, x.serviceId)
            assertEquals(0x31, x.nrc)
            assertEquals("request out of range", x.nrcName)
        }
        // exchange() returns it instead of throwing
        val resp = e.exchange(PhevSignals.ENGINE, byteArrayOf(0x18, 0x02, 0xFF.toByte(), 0x00))
        assertEquals(0x11, resp.negative.single().nrc)
        // unknown ECU → NO DATA
        try {
            e.request(EcuTarget.physical(0x7B6, 0x7B7), byteArrayOf(0x21, 0x01))
            fail("expected NO DATA")
        } catch (x: ObdException.NoData) {
            // expected
        }
    }

    @Test
    fun responsePendingInTheSameBlockIsWaitedOut() = run {
        val fake = FakeElm().apply { pendingResponses = 2 }
        val e = elm(fake)
        e.initialize()
        val reply = e.request(PhevSignals.BMU, bmu2101).single()
        assertEquals(55, reply.data.size)
        assertEquals(1, fake.count("2101"))
    }

    @Test
    fun responsePendingWithPromptIsAskedAgain() = run {
        val fake = FakeElm().apply { pendingResponses = 2; pendingSeparatePrompt = true }
        val e = elm(fake)
        e.initialize()
        val reply = e.request(PhevSignals.BMU, bmu2101).single()
        assertEquals(55, reply.data.size)
        assertEquals(3, fake.count("2101"))
    }

    @Test
    fun timeoutThenRecovers() = run {
        val fake = FakeElm()
        val e = elm(fake, ElmConfig.fast().copy(commandTimeoutMs = 150, requestTimeoutMs = 150))
        e.initialize()
        suspend fun timesOut(pid: Int) {
            try {
                e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, pid.toByte()))
                fail("expected timeout")
            } catch (x: ObdException.Timeout) {
                // expected
            }
        }
        // 1. A late "OK" (ATSH7E0 timed out) arrives while the next command waits: skipped by the ATI sync.
        fake.slowMs = 250
        timesOut(0x0C)
        fake.slowMs = 0
        assertEquals("41 0D 32", ObdHex.format(e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0D)).single().data))
        // 2. A late OBD answer ("41 0C") that arrives before the next request: drained/skipped, not taken for 010D.
        fake.slowMs = 200
        timesOut(0x0C)
        fake.slowMs = 0
        Thread.sleep(80)
        assertEquals("41 0D 32", ObdHex.format(e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0D)).single().data))
        // 3. Same, but the late answer arrives after the next request was sent.
        fake.slowMs = 220
        timesOut(0x0C)
        fake.slowMs = 0
        assertEquals("41 0D 32", ObdHex.format(e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0D)).single().data))
        assertEquals("41 05 7B", ObdHex.format(e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x05)).single().data))
        assertTrue("the stale-answer path ran", log.snapshot().any { it.text.contains("discarded stale answer") })
    }

    @Test
    fun slowResetLeavesNoAnswersOutOfStep() = run {
        // ATZ answers after the reset timeout, so the driver falls back to ATWS and takes ATZ's late banner for it;
        // ATWS's own banner then arrives after the settle drain, in front of the ATE0 answer: one answer out of step.
        // The ATI sync must notice and re-apply the settings.
        val fake = FakeElm().apply { resetDelayMs = 600 }
        val e = elm(fake, ElmConfig.fast().copy(resetTimeoutMs = 400, commandTimeoutMs = 1_000, requestTimeoutMs = 1_000, searchTimeoutMs = 1_000))
        val info = e.initialize()
        assertTrue(info.carReachable)
        assertEquals("ELM327 v1.5", info.version)
        assertTrue(info.supportsFlowControlHeader)
        assertTrue("re-sync after the slow reset", log.snapshot().any { it.text.contains("out of step") })
        assertEquals("41 0C 1A F8", ObdHex.format(e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0C)).single().data))
        assertEquals(55, e.request(PhevSignals.BMU, bmu2101).single().data.size)
    }

    @Test
    fun rawTerminalAndSettingsReapplied() = run {
        val fake = FakeElm()
        val e = elm(fake)
        e.initialize()
        assertEquals("ELM327 v1.5", e.raw("ATI"))
        assertEquals("OK", e.raw("ath0"))
        assertEquals("41 0D 32", e.raw("010D"))
        fake.clearCommandLog()
        // the next driver request re-applies headers etc.
        val r = e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0D)).single()
        assertEquals(0x7E8, r.rxId)
        assertTrue(fake.commands.contains("ATH1"))
        assertEquals(12.6, e.voltage()!!, 1e-9)
        assertTrue(e.raw("ATXYZ").contains("?"))
    }

    @Test
    fun linkDropIsTyped() = run {
        val fake = FakeElm()
        val e = elm(fake)
        e.initialize()
        fake.dropLink = true
        try {
            e.request(EcuTarget.obd(0x7E8), byteArrayOf(0x01, 0x0C))
            fail("expected LinkLost")
        } catch (x: ObdException.LinkLost) {
            assertNotNull(x.ioException)
        }
    }
}
