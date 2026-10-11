package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ElmConfig
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.PhevSignals
import io.github.anwo91.phevlink.core.obd.Readiness
import io.github.anwo91.phevlink.core.obd.emulator.FakeElm
import io.github.anwo91.phevlink.core.obd.livetest.LiveTest
import io.github.anwo91.phevlink.core.obd.livetest.LiveTestRunner
import io.github.anwo91.phevlink.core.obd.livetest.LiveTestText
import io.github.anwo91.phevlink.core.obd.livetest.LiveTests
import io.github.anwo91.phevlink.core.obd.livetest.Measurement
import io.github.anwo91.phevlink.core.obd.livetest.Msg
import io.github.anwo91.phevlink.core.obd.livetest.Num
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
import java.io.File

/** The guided live tests against the ELM emulator, with all waits scaled to zero. */
class LiveTestsTest {
    private val log = DiagLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** Runs [test], answering every prompt with [answer], and returns the result. */
    private fun runTest(fake: FakeElm, test: LiveTest, store: TestStore = TestStore.Memory(), answer: (TestUiState.Prompt) -> Boolean = { true }): TestResult =
        runBlocking {
            withTimeout(20_000) {
                val client = ObdClient(fake, log, scope, elmConfig = ElmConfig.fast())
                assertTrue(client.connect())
                val runner = LiveTestRunner(scope, log, timeScale = 0.0)
                val done = CompletableDeferred<TestResult>()
                val watcher = launch {
                    var lastPrompt: TestUiState.Prompt? = null
                    while (true) {
                        val s = runner.state.value
                        if (s is TestUiState.Prompt && s !== lastPrompt) {
                            lastPrompt = s
                            runner.answer(answer(s))
                        }
                        delay(5)
                    }
                }
                runner.start(test, client, store, vin = null) { done.complete(it) }
                val r = done.await()
                watcher.cancel()
                client.disconnect()
                r
            }
        }

    @Test
    fun twelveVoltGoodBattery() {
        val r = runTest(FakeElm().apply { adapterVoltage = 12.7 }, LiveTests.byId("twelve-volt-health")!!)
        assertEquals(Verdict.PASS, r.verdict)
        assertEquals("t12.head.pass", r.headline.key)
        assertEquals(12.7, r.measurements.first { it.label.key == "m.v_rest" }.value!!, 1e-9)
    }

    @Test
    fun twelveVoltLowBattery() {
        val r = runTest(FakeElm().apply { adapterVoltage = 12.1 }, LiveTests.byId("twelve-volt-health")!!)
        assertEquals(Verdict.FAIL, r.verdict)
        assertTrue(r.findings.any { it.msg.key == "t12.rest_low" })
        assertTrue(r.nextSteps.any { it.key == "next.12v_charger" })
    }

    @Test
    fun dcdcChargingAndBaseline() {
        val store = TestStore.Memory()
        val r = runTest(FakeElm().apply { adapterVoltage = 14.2 }, LiveTests.byId("dcdc-output")!!, store)
        assertEquals(Verdict.PASS, r.verdict)
        assertEquals("14.2", store.get("dcdc.baseline"))
        val low = runTest(FakeElm().apply { adapterVoltage = 12.5 }, LiveTests.byId("dcdc-output")!!, store)
        assertEquals(Verdict.FAIL, low.verdict)
        assertTrue(low.findings.any { it.msg.key == "dc.below_baseline" })
    }

    @Test
    fun capacityUsesModelYearReference() {
        val store = TestStore.Memory()
        val r = runTest(FakeElm(), LiveTests.byId("hv-capacity")!!, store) // VIN year F = 2015 → 38 Ah
        assertEquals(34.0, r.measurements.first { it.label.key == "m.capacity" }.value!!, 1e-9)
        assertEquals(38.0, r.measurements.first { it.label.key == "m.reference" }.value!!, 1e-9)
        assertEquals(Verdict.PASS, r.verdict) // 89 %
        assertTrue(store.get("cap.history")!!.contains(":34.0"))
        val my17 = runTest(FakeElm().apply { vin = "JMAXDGG2WHZ012345" }, LiveTests.byId("hv-capacity")!!)
        assertEquals(40.0, my17.measurements.first { it.label.key == "m.reference" }.value!!, 1e-9)
        assertEquals(85.0, my17.measurements.first { it.label.key == "m.soh" }.value!!, 1e-9)
    }

    @Test
    fun cellBalanceFromForumExample() {
        val r = runTest(FakeElm(), LiveTests.byId("hv-cell-balance")!!)
        assertEquals(Verdict.PASS, r.verdict)
        assertTrue(r.findings.any { it.msg.key == "cell.good" })
        val lowest = r.findings.first { it.msg.key == "cell.lowest" }
        assertEquals(3, lowest.msg.args["cell"])
        assertEquals(1, lowest.msg.args["module"])
    }

    @Test
    fun batteryTestsWithoutFlowControlAreInconclusive() {
        val r = runTest(FakeElm().apply { cloneRejectsFlowControl = true }, LiveTests.byId("hv-capacity")!!)
        assertEquals(Verdict.INCONCLUSIVE, r.verdict)
        assertEquals("abort.no_bmu", r.headline.key)
    }

    @Test
    fun fullScanFindsEmulatorCodes() {
        val r = runTest(FakeElm(), LiveTests.byId("dtc-full-scan")!!)
        assertTrue("P0420" in r.relatedCodes)
        assertTrue("P0456" in r.relatedCodes)
        // P0420 stored with the MIL on → emission fault with the lamp on.
        assertEquals(Verdict.FAIL, r.verdict)
        assertTrue(r.findings.any { it.msg.key == "scan.emission_mil" })
    }

    @Test
    fun fullScanFlagsHybridCodeFromBmu() {
        // No generic codes; the emulated BMU reports P0A7F (KWP), and the OBC reports P0A80 through the
        // UDS read that only works after an extended diagnostic session is opened.
        val fake = FakeElm().apply { storedDtcs.clear(); pendingDtcs.clear() }
        val r = runTest(fake, LiveTests.byId("dtc-full-scan")!!)
        assertEquals(listOf("P0A7F", "P0A80"), r.relatedCodes)
        assertEquals(Verdict.FAIL, r.verdict)
        assertTrue(r.findings.any { it.msg.key == "scan.hybrid" })
        assertFalse(r.findings.any { it.msg.key == "scan.emission_mil" })
    }

    @Test
    fun inspectionReadinessReportsMilAndMonitors() {
        val r = runTest(FakeElm(), LiveTests.byId("inspection-readiness")!!)
        assertEquals(Verdict.FAIL, r.verdict)
        assertTrue(r.findings.any { it.msg.key == "insp.mil_or_codes" })
        assertTrue(r.findings.any { it.msg.key == "insp.monitor_done" })
        val clean = FakeElm().apply { storedDtcs.clear(); pendingDtcs.clear() }
        val ok = runTest(clean, LiveTests.byId("inspection-readiness")!!)
        assertFalse(ok.findings.any { it.msg.key == "insp.mil_or_codes" })
    }

    @Test
    fun cancelGivesCancelledResult() = runBlocking {
        withTimeout(10_000) {
            val client = ObdClient(FakeElm(), log, scope, elmConfig = ElmConfig.fast())
            assertTrue(client.connect())
            val runner = LiveTestRunner(scope, log, timeScale = 0.0)
            runner.start(LiveTests.byId("twelve-volt-health")!!, client, TestStore.Memory(), null)
            while (runner.state.value !is TestUiState.Prompt) delay(5)
            runner.cancel()
            while (runner.state.value !is TestUiState.Finished) delay(5)
            assertEquals(Verdict.CANCELLED, (runner.state.value as TestUiState.Finished).result.verdict)
            client.disconnect()
        }
    }

    @Test
    fun readinessDecoding() {
        // MIL on, 1 DTC; spark; misfire/fuel/components supported & complete; catalyst+EVAP+O2+O2 heater+EGR supported, EVAP incomplete.
        val r = Readiness.decode(byteArrayOf(0x81.toByte(), 0x07, 0xE5.toByte(), 0x04))!!
        assertTrue(r.milOn!!)
        assertEquals(1, r.dtcCount)
        assertTrue(r.sparkIgnition)
        assertEquals(listOf("evap"), r.incomplete.map { it.key })
        assertEquals(8, r.supported.size)
    }

    @Test
    fun modelYearFromVin() {
        assertEquals(2015, PhevSignals.modelYearFromVin("JMAXDGG2WFZ012345"))
        assertEquals(2017, PhevSignals.modelYearFromVin("JMAXDGG2WHZ012345"))
        assertEquals(38.0, PhevSignals.referenceCapacityAh(null), 0.0)
        assertEquals(40.0, PhevSignals.referenceCapacityAh("JMAXDGG2WJZ012345"), 0.0)
    }

    @Test
    fun textResolutionAndNumberFormats() {
        val msg = Msg("t12.rest_good", "v" to Num(12.62, 2))
        assertEquals("At rest the 12 V battery shows 12.62 V: well charged.", LiveTestText.text(msg, "en"))
        assertEquals("12,62", LiveTestText.number(12.62, 2, "nb"))
        assertEquals("12.6 V", LiveTestText.value(Measurement(Msg("m.v12"), 12.6, "V", 1), "en"))
        assertEquals("—", LiveTestText.value(Measurement(Msg("m.v12"), null, "V"), "en"))
        assertEquals("unknown.key", LiveTestText.text(Msg("unknown.key"), "en"))
    }

    /** Every key the code can produce exists in English, and every translation has exactly the English keys and slots. */
    @Test
    fun textKeysComplete() {
        val src = listOf(File("src/main/kotlin"), File("core/src/main/kotlin")).first { it.exists() }
        val code = File(src, "io/github/anwo91/phevlink/core/obd/livetest").listFiles()!!.filter { it.name.endsWith(".kt") }.joinToString("\n") { it.readText() }
        val literal = Regex("""Msg\("([a-z0-9_.\-]+)"""").findAll(code).map { it.groupValues[1] }.toSet()
        val f = Regex("""f\(Verdict\.[A-Z]+, "([a-z0-9_.\-]+)"""").findAll(code).map { it.groupValues[1] }.toSet()
        val dynamic = buildSet {
            for (p in listOf("t12", "dc", "cap", "cell", "chg", "warm", "trim", "insp", "scan", "wheel")) for (v in listOf("pass", "info", "warn", "fail", "inconclusive")) add("$p.head.$v")
            for (t in LiveTests.all) { add(t.titleKey); add(t.descriptionKey) }
            for (w in listOf("a", "b")) for (k in listOf("good", "high", "bad")) add("trim.${k}_$w")
            for (p in listOf("wheel.axle", "wheel.ratio")) for (k in listOf("good", "small", "large")) add("$p.$k")
            for (w in listOf("fl", "fr", "rl", "rr")) { add("wheel.$w"); add("m.wheel_$w") }
            for (k in listOf("misfire", "fuel_system", "components", "catalyst", "heated_catalyst", "evap", "secondary_air", "ac_refrigerant",
                "o2_sensor", "o2_heater", "egr", "nmhc_catalyst", "nox_scr", "boost_pressure", "exhaust_sensor", "pm_filter")) add("mon.$k")
        }
        val en = LiveTestText.keys()
        val missing = (literal + f + dynamic).filter { it !in en && !it.endsWith(".") }
        assertTrue("missing English texts: $missing", missing.isEmpty())
        val slot = Regex("""\{([A-Za-z0-9_]+)\}""")
        for (lang in LiveTestText.LANGUAGES.drop(1)) {
            val file = LiveTestText::class.java.getResource("/io/github/anwo91/phevlink/core/obd/livetest/strings_$lang.txt") ?: continue
            assertNotNull(file)
            for (k in en) {
                assertTrue("$lang lacks $k", LiveTestText.has(k, lang))
                val a = slot.findAll(LiveTestText.text(k, "en").let { k.let { key -> rawEn(key) } }).map { it.value }.toSet()
                val b = slot.findAll(rawLang(k, lang)).map { it.value }.toSet()
                assertEquals("$lang slots for $k", a, b)
            }
        }
    }

    private fun rawEn(key: String) = rawLang(key, "en")
    private fun rawLang(key: String, lang: String): String =
        LiveTestText::class.java.getResourceAsStream("/io/github/anwo91/phevlink/core/obd/livetest/strings_$lang.txt")!!
            .bufferedReader().readLines().firstOrNull { it.startsWith("$key\t") }?.substringAfter('\t') ?: ""
}
