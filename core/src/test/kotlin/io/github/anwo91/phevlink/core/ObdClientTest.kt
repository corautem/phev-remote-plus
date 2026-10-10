package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.Confidence
import io.github.anwo91.phevlink.core.obd.DtcKind
import io.github.anwo91.phevlink.core.obd.Elm327
import io.github.anwo91.phevlink.core.obd.ElmConfig
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.ObdCsvLogger
import io.github.anwo91.phevlink.core.obd.ObdPhase
import io.github.anwo91.phevlink.core.obd.emulator.FakeElm
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.time.ZoneId
import java.util.Collections

/** The high-level client against the emulator: discovery, live data, battery, DTCs, failures. */
class ObdClientTest {
    private val log = DiagLog()
    private val uncaught = Collections.synchronizedList(ArrayList<Throwable>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> uncaught += t })

    @After
    fun tearDown() {
        scope.cancel()
        assertTrue("exceptions escaped: $uncaught", uncaught.isEmpty())
    }

    private fun client(fake: FakeElm) = ObdClient(fake, log, scope, elmConfig = ElmConfig.fast())

    private fun run(block: suspend () -> Unit) = runBlocking {
        try {
            withTimeout(15_000) { block() }
        } catch (t: Throwable) {
            System.err.println("---- diag log ----\n" + log.dump())
            throw t
        }
    }

    @Test
    fun connectDiscoversCarEcusAndBattery() = run {
        val c = client(FakeElm())
        assertTrue(c.connect())
        val s = c.state.value
        assertEquals(ObdPhase.READY, s.phase)
        assertTrue(s.carReachable)
        assertEquals("JMAXDGG2WFZ012345", s.vin)
        assertEquals(12.6, s.adapterVoltage!!, 1e-9)
        val pids = s.supportedPids[0x7E8]!!
        for (p in listOf(0x01, 0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x11, 0x1F, 0x20, 0x21, 0x2F, 0x31, 0x33, 0x40, 0x42, 0x46)) assertTrue("PID $p", p in pids)
        val names = s.ecus.map { it.name }
        for (n in listOf("Engine (ECM)", "Drive battery (BMU)", "On-board charger (OBC)", "Front motor controller", "Rear motor controller")) {
            assertTrue("$n in $names", n in names)
        }
        assertFalse("Instrument cluster" in names)
        assertEquals("ECM-EngineControl", s.ecus.first { it.rxId == 0x7E8 }.reportedName)
        val b = s.batteryHealth!!
        assertEquals(60.0, b.soc!!, 1e-9)
        assertEquals(318.2, b.packVoltage!!, 1e-9)
        assertEquals(85.0, b.sohPercent!!, 1e-9)
        assertEquals(3.0, b.spreadMv!!, 0.0)
        assertEquals(42, b.charges100V)
        assertEquals(500, b.charges200V)
        assertEquals(53, b.rawBmu!!.size)
        assertNull(s.batteryMessage)
        assertTrue(s.message!!, s.message!!.startsWith("Connected"))
    }

    @Test
    fun pollDecodesStandardAndManufacturerValues() = run {
        val c = client(FakeElm())
        c.connect()
        assertTrue(c.pollOnce(force = true))
        val s = c.state.value
        val v = s.values
        assertEquals(1726.0, v["obd.0C"]!!.value!!, 1e-9)
        assertEquals("1726 rpm", v["obd.0C"]!!.formatted())
        assertEquals(83.0, v["obd.05"]!!.value!!, 1e-9)
        assertEquals(15.0, v["obd.42"]!!.value!!, 1e-9)
        assertEquals(1500.0, v["obd.31"]!!.value!!, 1e-9)
        assertEquals("MIL on, 1 DTC", v["obd.01"]!!.text)
        assertEquals(Confidence.VERIFIED, v["obd.0C"]!!.confidence)
        assertEquals(60.0, v["bmu.soc"]!!.value!!, 1e-9)
        assertEquals(Confidence.COMMUNITY, v["bmu.soc"]!!.confidence)
        assertEquals(Confidence.EXPERIMENTAL, v["bmu.cell_max_v"]!!.confidence)
        assertEquals(85.0, v["bmu.soh_pct"]!!.value!!, 1e-9)
        assertEquals(1000.0, v["motor.front_rpm"]!!.value!!, 1e-9)
        assertEquals(0.0, v["motor.rear_rpm"]!!.value!!, 1e-9)
        assertEquals(42.0, v["obc.charges_100v"]!!.value!!, 1e-9)
        assertEquals(89.41176, v["ecm.knock_learned"]!!.value!!, 1e-4)
        assertEquals("on", v["ecm.idle"]!!.text)
        assertEquals(12.6, v["adapter.voltage"]!!.value!!, 1e-9)
        assertTrue(s.pollCycle >= 1)
        assertNotNull(s.lastPollMs)
        assertTrue(c.knownValueIds().containsAll(listOf("adapter.voltage", "obd.0C", "bmu.soc", "bmu.soh_pct")))
    }

    @Test
    fun liveLoopAndTerminalDoNotInterleave() = run {
        val c = client(FakeElm())
        c.connect()
        c.startLive(20)
        assertEquals(ObdPhase.POLLING, c.state.value.phase)
        withTimeout(5_000) { c.state.first { it.pollCycle >= 2 } }
        repeat(10) { assertEquals("ELM327 v1.5", c.terminal("ATI")) }
        assertEquals("12.6V", c.terminal("ATRV"))
        withTimeout(5_000) { c.state.first { it.pollCycle >= 4 } }
        c.stopLive()
        assertEquals(ObdPhase.READY, c.state.value.phase)
        assertEquals(1726.0, c.state.value.values["obd.0C"]!!.value!!, 1e-9)
    }

    @Test
    fun cloneWithoutFlowControlKeepsStandardData() = run {
        val fake = FakeElm(version = "ELM327 v2.1").apply { cloneRejectsFlowControl = true }
        val c = client(fake)
        assertTrue(c.connect())
        val s = c.state.value
        assertTrue(s.carReachable)
        assertNull(s.batteryHealth)
        assertEquals(Elm327.MSG_NO_FLOW_CONTROL, s.batteryMessage)
        assertTrue(s.message!!, s.message!!.contains("ATFCSH"))
        assertTrue(s.adapter!!.isClone)
        assertNull(c.readBattery())
        assertEquals(Elm327.MSG_NO_FLOW_CONTROL, c.state.value.batteryMessage)
        c.pollOnce(force = true)
        assertEquals(1726.0, c.state.value.values["obd.0C"]!!.value!!, 1e-9)
        assertNull(c.state.value.values["bmu.soc"])
    }

    @Test
    fun ignitionOffGivesAdviceAndRecovers() = run {
        val fake = FakeElm().apply { ignitionOff = true }
        val c = client(fake)
        assertTrue(c.connect())
        val s = c.state.value
        assertEquals(ObdPhase.READY, s.phase)
        assertFalse(s.carReachable)
        assertTrue(s.message!!, s.message!!.contains("turn the ignition on"))
        // A poll while the car is off only probes, and doesn't blame (disable) any ECU.
        fake.clearCommandLog()
        c.pollOnce()
        assertEquals(listOf("0100"), fake.commands)
        assertTrue(c.state.value.unavailable.isEmpty())
        // Within the probe pause nothing is sent at all.
        c.pollOnce()
        assertEquals(1, fake.count("0100"))
        fake.ignitionOff = false
        c.pollOnce(force = true)
        val after = c.state.value
        assertTrue(after.carReachable)
        assertEquals("JMAXDGG2WFZ012345", after.vin)
        assertEquals(1726.0, after.values["obd.0C"]!!.value!!, 1e-9)
    }

    @Test
    fun carSwitchedOffDuringPollingIsNotBlamedOnEcus() = run {
        val fake = FakeElm()
        val c = client(fake)
        c.connect()
        c.pollOnce(force = true)
        val before = c.state.value.unavailable.keys
        fake.ignitionOff = true
        c.pollOnce(force = true)
        c.pollOnce(force = true)
        assertFalse(c.state.value.carReachable)
        assertEquals(ObdClient.MSG_CAR_SILENT, c.state.value.message)
        assertEquals(before, c.state.value.unavailable.keys)
        assertFalse(c.state.value.unavailable.containsKey("761:2101"))
    }

    @Test
    fun linkDropEndsInErrorWithoutEscapingExceptions() = run {
        val fake = FakeElm()
        val c = client(fake)
        c.connect()
        c.startLive(20)
        withTimeout(5_000) { c.state.first { it.pollCycle >= 1 } }
        fake.dropLink = true
        val s = withTimeout(5_000) { c.state.first { it.phase == ObdPhase.ERROR } }
        assertEquals(ObdClient.MSG_LINK_LOST, s.message)
        assertFalse(s.carReachable)
        // everything keeps returning instead of throwing
        assertFalse(c.connect())
        assertTrue(c.terminal("ATI").startsWith("ERROR"))
        assertNull(c.readBattery())
        assertFalse(c.scanDtcs().success)
        assertFalse(c.clearDtcs().success)
        assertFalse(c.pollOnce())
        c.disconnect()
        assertEquals(ObdPhase.DISCONNECTED, c.state.value.phase)
    }

    @Test
    fun adapterNotAnsweringIsAnError() = run {
        val fake = FakeElm().apply { slowMs = 10_000 }
        val c = client(fake)
        assertFalse(c.connect())
        assertEquals(ObdPhase.ERROR, c.state.value.phase)
        assertEquals(Elm327.MSG_NO_ADAPTER, c.state.value.message)
    }

    @Test
    fun scanAndClearGenericDtcs() = run {
        val fake = FakeElm().apply { secondObdEcu = true }
        val c = client(fake)
        c.connect()
        val r = c.scanDtcs()
        assertEquals(setOf("P0420" to DtcKind.STORED, "P0456" to DtcKind.PENDING, "U0100" to DtcKind.STORED), r.dtcs.map { it.code to it.kind }.toSet())
        assertEquals("Engine (ECM)", r.dtcs.first { it.code == "P0420" }.ecuName)
        assertEquals("Transmission/7E9", r.dtcs.first { it.code == "U0100" }.ecuName)
        assertEquals(r.dtcs, c.state.value.dtcs)
        assertNotNull(c.state.value.lastDtcScanMs)
        val clear = c.clearDtcs()
        assertTrue(clear.message, clear.success)
        assertEquals(listOf("Engine (ECM)", "Transmission/7E9"), clear.confirmedBy)
        assertTrue(clear.remaining.isEmpty())
        assertTrue(c.state.value.dtcs.isEmpty())
        assertTrue(c.scanDtcs().dtcs.isEmpty())
        assertEquals(1, fake.count("04"))
    }

    @Test
    fun extendedScanFindsKwpDtcAndStaysReadOnly() = run {
        val fake = FakeElm()
        val c = client(fake)
        c.connect()
        val r = c.scanDtcs(includeExtended = true)
        val bmu = r.dtcs.single { it.code == "P0A7F" }
        assertEquals(0x762, bmu.ecuRxId)
        assertEquals("ECU Drive battery (BMU) (KWP)", bmu.source)
        assertEquals(0x60, bmu.status)
        assertTrue(bmu.extended)
        assertTrue(r.dtcs.any { it.code == "P0420" && !it.extended })
        assertTrue("Drive battery (BMU)" in r.respondingEcus)
        assertTrue(r.includedExtended)
        // read-only: no clearing service (04, 14) and no routines/writes were sent
        assertTrue(fake.commands.none { it == "04" || it.startsWith("14") || it.startsWith("31") || it.startsWith("2E") || it.startsWith("3B") })
    }

    @Test
    fun repeatedFailuresDisableRequestsForTheSession() = run {
        val fake = FakeElm()
        val c = client(fake)
        c.connect()
        c.pollOnce(force = true)
        c.pollOnce(force = true)
        val u = c.state.value.unavailable
        assertTrue(u.keys.toString(), "6A0:21" in u.keys) // cluster: never answers → whole ECU skipped
        assertTrue(u.keys.toString(), "7E0:2101" in u.keys) // engine refuses 21 01 twice → that request skipped
        assertFalse("761:2101" in u.keys)
        assertFalse("7E0:2108" in u.keys)
        fake.clearCommandLog()
        c.pollOnce(force = true)
        val cmds = fake.commands
        assertFalse(cmds.toString(), "ATSH6A0" in cmds)
        assertFalse(cmds.toString(), "ATSH688" in cmds)
        assertFalse(cmds.toString(), "ATSH784" in cmds)
        assertEquals(2, cmds.count { it == "2101" }) // BMU and charger yes; engine 21 01 and ABS no
        assertTrue("2108" in cmds)
        assertTrue("010C" in cmds)
        c.resetUnavailable()
        assertTrue(c.state.value.unavailable.isEmpty())
    }

    @Test
    fun csvLoggerWritesOneRowPerPollCycle() = run {
        val c = client(FakeElm())
        c.connect()
        val logger = ObdCsvLogger(c.knownValueIds(), ZoneId.of("UTC"))
        val w = StringWriter()
        c.pollOnce(force = true)
        assertTrue(logger.append(w, c.state.value))
        assertFalse(logger.append(w, c.state.value)) // same cycle
        c.pollOnce(force = true)
        assertTrue(logger.append(w, c.state.value))
        val lines = w.toString().trimEnd().lines()
        assertEquals(3, lines.size)
        val header = lines[0].split(",")
        assertEquals("time", header[0])
        val row = lines[2].split(",")
        assertEquals(header.size, row.size)
        assertTrue(row[0], row[0].matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))
        assertEquals("1726", row[header.indexOf("obd.0C")])
        assertEquals("15", row[header.indexOf("obd.42")])
        assertEquals("60", row[header.indexOf("bmu.soc")])
        assertEquals("3.979", row[header.indexOf("bmu.cell_max_v")])
        assertEquals("89.4118", row[header.indexOf("ecm.knock_learned")])
        assertEquals("1", row[header.indexOf("ecm.idle")])
        assertEquals("", row[header.indexOf("cluster.odometer")])
    }
}
