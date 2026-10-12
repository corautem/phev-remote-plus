package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.obd.DtcKind
import io.github.anwo91.phevlink.core.obd.ElmConfig
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.emulator.FakeElm
import io.github.anwo91.phevlink.core.obd.livetest.LiveTestRunner
import io.github.anwo91.phevlink.core.obd.livetest.LiveTests
import io.github.anwo91.phevlink.core.obd.livetest.TestResult
import io.github.anwo91.phevlink.core.obd.livetest.TestStore
import io.github.anwo91.phevlink.core.obd.livetest.TestUiState
import io.github.anwo91.phevlink.core.obd.livetest.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's demo scenario ([FakeElm.demo]): a parked, charging car with a worn drive battery and P0A80. */
class DemoScenarioTest {
    private val log = DiagLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun demo(soc: Double? = null, charging: Boolean? = null) =
        FakeElm.demo(socPercent = { soc }, charging = { charging }, latencyMs = 0)

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
    fun quickScanShowsHeadlinerAndPendingCode() = run {
        val c = client(demo())
        assertTrue(c.connect())
        val r = c.scanDtcs(includeExtended = false)
        val found = r.dtcs.map { Triple(it.code, it.kind, it.ecuRxId) }.toSet()
        assertTrue(found.toString(), Triple("P0A80", DtcKind.STORED, 0x7EA) in found)
        assertTrue(found.toString(), Triple("P0A80", DtcKind.PERMANENT, 0x7EA) in found)
        assertTrue(found.toString(), Triple("P0456", DtcKind.PENDING, 0x7E8) in found)
        assertFalse("the default emulator's P0420 is not part of the demo", r.dtcs.any { it.code == "P0420" })
    }

    @Test
    fun fullScanAddsTheBatteryModuleAndReadsEveryModuleCleanly() = run {
        val c = client(demo())
        assertTrue(c.connect())
        val r = c.scanDtcs(includeExtended = true)
        val codes = r.dtcs.map { it.code to it.ecuRxId }.toSet()
        assertTrue(codes.toString(), "P0A7F" to 0x762 in codes)
        assertTrue(codes.toString(), "P0A80" to 0x7EA in codes)
        assertTrue(codes.toString(), "P0456" to 0x7E8 in codes)
        assertFalse("the demo OBC is clean: $codes", codes.any { it.second == 0x766 })
        assertTrue("OBC answered: ${r.respondingEcus}", r.respondingEcus.any { it.contains("OBC") })
        assertTrue("every module that answers should report cleanly: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun clearingLeavesThePermanentHeadliner() = run {
        val c = client(demo())
        assertTrue(c.connect())
        val r = c.clearDtcs()
        assertTrue(r.message, r.success)
        val left = r.remaining.map { it.code to it.kind }
        assertEquals(listOf("P0A80" to DtcKind.PERMANENT), left)
    }

    @Test
    fun batteryLooksWornAndFollowsTheCarsCharge() = run {
        val c = client(demo(soc = 80.0))
        assertTrue(c.connect())
        val b = c.readBattery()
        assertNotNull(b); b!!
        assertEquals(80.0, b.soc!!, 0.5)
        assertEquals(FakeElm.DEMO_CAPACITY_AH, b.capacityAh!!, 0.05)
        assertEquals(72.1, b.sohPercent!!, 0.2) // MY2015 VIN → 38 Ah reference
        assertEquals(FakeElm.DEMO_WEAK_CELL, b.minCellNo)
        assertTrue("spread ${b.spreadMv}", b.spreadMv!! in 60.0..70.0)
        assertEquals(FakeElm.DEMO_CAPACITY_AH * 0.8, b.remainingAh!!, 0.1)
        val cells = c.readCells()
        assertNotNull(cells); cells!!
        assertTrue("21 02 must agree with the 21 01 summary", cells.verified)
        assertEquals(FakeElm.DEMO_WEAK_CELL, cells.minCell)
    }

    @Test
    fun parkedAndChargingLiveData() = run {
        val charging = demo(charging = true)
        val c = client(charging)
        assertTrue(c.connect())
        c.pollOnce(force = true)
        val v = c.state.value.values
        assertEquals(0.0, v["obd.0C"]!!.value!!, 1e-9)
        assertEquals(0.0, v["obd.0D"]!!.value!!, 1e-9)
        assertEquals(0.0, v["motor.front_rpm"]!!.value!!, 1e-9)
        assertEquals(14.0, v["obc.ac_amps"]!!.value!!, 1e-9)

        val idle = client(demo(charging = false))
        assertTrue(idle.connect())
        idle.pollOnce(force = true)
        assertEquals(0.0, idle.state.value.values["obc.ac_amps"]!!.value!!, 1e-9)
    }

    @Test
    fun liveTestsTellTheSameStory() {
        val cap = runLiveTest("hv-capacity")
        assertEquals(cap.headline.key, Verdict.WARN, cap.verdict)
        val cells = runLiveTest("hv-cell-balance")
        assertEquals(cells.headline.key, Verdict.WARN, cells.verdict)
        val scan = runLiveTest("dtc-full-scan")
        assertTrue(scan.relatedCodes.toString(), scan.relatedCodes.containsAll(listOf("P0A80", "P0A7F", "P0456")))
        assertEquals(Verdict.FAIL, scan.verdict)
    }

    @Test
    fun demoCarChargesInWholeMinutesAtAQuarterRate() {
        val car = FakeCar().apply { minutesPerSecond = 0.25 }
        car.plugIn(charge = true)
        fun remaining(): Int = car.register(Reg.READ_CHARGE_STATUS)!!.let { ((it[2].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
        val start = remaining()
        assertEquals(((100 - 64) * 3.2).toInt(), start)
        repeat(3) { car.simulate() }
        assertEquals("three quarter-minutes don't make a minute yet", start, remaining())
        car.simulate()
        assertEquals(start - 1, remaining())
        repeat(8) { car.simulate() }
        assertEquals(start - 3, remaining())
        assertEquals((100 - (start - 3) / 3.2).toInt(), car.batteryPercent)
    }

    @Test
    fun defaultEmulatorIsUnchanged() = run {
        // Existing tests rely on the default codes; the demo knobs must stay off by default.
        val f = FakeElm()
        assertFalse(f.hybridEcu)
        assertTrue(f.driving)
        assertEquals(listOf(0x0420), f.storedDtcs.toList())
        assertEquals(listOf(0x0A7F), f.bmuDtcs.toList())
        assertEquals(listOf(0x0A80), f.obcDtcs.toList())
    }

    private fun runLiveTest(id: String): TestResult = runBlocking {
        withTimeout(20_000) {
            val client = ObdClient(demo(soc = 64.0), log, scope, elmConfig = ElmConfig.fast())
            assertTrue(client.connect())
            val runner = LiveTestRunner(scope, log, timeScale = 0.0)
            val done = CompletableDeferred<TestResult>()
            val watcher = launch {
                var lastPrompt: TestUiState.Prompt? = null
                while (true) {
                    val s = runner.state.value
                    if (s is TestUiState.Prompt && s !== lastPrompt) {
                        lastPrompt = s
                        runner.answer(true)
                    }
                    delay(5)
                }
            }
            runner.start(LiveTests.byId(id)!!, client, TestStore.Memory(), vin = null) { done.complete(it) }
            val r = done.await()
            watcher.cancel()
            client.disconnect()
            r
        }
    }
}
