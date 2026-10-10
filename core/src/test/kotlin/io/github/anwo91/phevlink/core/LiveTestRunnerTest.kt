package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Runner robustness: reconnects during a test, waking the car up, stale taps, replacing a running test. */
class LiveTestRunnerTest {
    private val log = DiagLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun client(fake: FakeElm) = ObdClient(fake, log, scope, elmConfig = ElmConfig.fast())

    private fun run(block: suspend () -> Unit) = runBlocking {
        try {
            withTimeout(20_000) { block() }
        } catch (t: Throwable) {
            System.err.println("---- diag log ----\n" + log.dump())
            throw t
        }
    }

    private suspend fun LiveTestRunner.awaitPrompt(testId: String, step: Int): TestUiState.Prompt {
        while (true) {
            val s = state.value
            if (s is TestUiState.Prompt && s.testId == testId && s.step == step) return s
            delay(5)
        }
    }

    @Test
    fun checkCarProbesRightAway() = run {
        val fake = FakeElm().apply { ignitionOff = true }
        val c = client(fake)
        assertTrue(c.connect())
        assertFalse(c.state.value.carReachable)
        assertFalse(c.checkCar())
        fake.ignitionOff = false
        assertTrue(c.checkCar())
        assertTrue(c.state.value.carReachable)
        // Discovery ran with the probe, so the battery test has its data.
        assertTrue(c.state.value.supportedPids.isNotEmpty())
        c.disconnect()
        assertFalse(c.checkCar())
    }

    /** The user switches the car on at the first prompt; nothing polls in between (as when the live loop backs off). */
    @Test
    fun testWaitsForTheCarByProbingIt() = run {
        val fake = FakeElm().apply { ignitionOff = true }
        val c = client(fake)
        assertTrue(c.connect())
        val runner = LiveTestRunner(scope, log, timeScale = 0.0)
        val done = CompletableDeferred<TestResult>()
        runner.start(LiveTests.byId("hv-capacity")!!, c, TestStore.Memory(), null) { done.complete(it) }
        runner.awaitPrompt("hv-capacity", 1)
        fake.ignitionOff = false
        runner.answer(true, 1)
        val r = done.await()
        assertEquals(r.headline.key, Verdict.PASS, r.verdict)
        c.disconnect()
    }

    /** The adapter is reconnected (new client) while the test waits at a prompt: the test goes on with the new one. */
    @Test
    fun testFollowsTheReconnectedClient() = run {
        val first = client(FakeElm())
        assertTrue(first.connect())
        val second = client(FakeElm().apply { vin = "JMAXDGG2WHZ012345" }) // MY2017 → 40 Ah reference
        val current = AtomicReference<ObdClient?>(first)
        val runner = LiveTestRunner(scope, log, timeScale = 0.0)
        val done = CompletableDeferred<TestResult>()
        runner.start(LiveTests.byId("hv-capacity")!!, first, TestStore.Memory(), null, current = { current.get() }) { done.complete(it) }
        runner.awaitPrompt("hv-capacity", 1)
        // Link lost and re-established by the app meanwhile.
        current.set(null)
        first.disconnect()
        assertTrue(second.connect())
        current.set(second)
        runner.answer(true, 1)
        val r = done.await()
        assertEquals(r.headline.key, Verdict.PASS, r.verdict)
        assertEquals(40.0, r.measurements.first { it.label.key == "m.reference" }.value!!, 1e-9)
        assertTrue(log.dump().contains("continuing on the new OBD connection"))
        second.disconnect()
    }

    @Test
    fun lateTapForAnEarlierStepIsIgnored() = run {
        val c = client(FakeElm())
        assertTrue(c.connect())
        val runner = LiveTestRunner(scope, log, timeScale = 0.0)
        runner.start(LiveTests.byId("dcdc-output")!!, c, TestStore.Memory(), null)
        val p1 = runner.awaitPrompt("dcdc-output", 1)
        runner.answer(true, p1.step + 1) // a tap meant for a prompt that isn't shown: ignored
        delay(50)
        assertEquals(p1, runner.state.value)
        runner.answer(true, p1.step)
        // The next prompt ("switch on a load") is step 2; a repeated tap for step 1 must not answer it.
        val p2 = runner.awaitPrompt("dcdc-output", 2)
        runner.answer(true, 1)
        delay(50)
        assertEquals(p2, runner.state.value)
        runner.cancel()
        c.disconnect()
    }

    /** Starting a test while another runs: the old one's CANCELLED result must not cover the new test. */
    @Test
    fun replacingARunningTestShowsTheNewOne() = run {
        val c = client(FakeElm())
        assertTrue(c.connect())
        val runner = LiveTestRunner(scope, log, timeScale = 0.0)
        repeat(20) {
            runner.start(LiveTests.byId("twelve-volt-health")!!, c, TestStore.Memory(), null)
            runner.awaitPrompt("twelve-volt-health", 1)
            val done = CompletableDeferred<TestResult>()
            runner.start(LiveTests.byId("hv-capacity")!!, c, TestStore.Memory(), null) { done.complete(it) }
            runner.awaitPrompt("hv-capacity", 1)
            delay(20)
            val s = runner.state.value
            assertTrue("state was $s", s is TestUiState.Prompt && s.testId == "hv-capacity")
            runner.answer(true, 1)
            assertEquals(Verdict.PASS, done.await().verdict)
            assertEquals("hv-capacity", (runner.state.value as TestUiState.Finished).testId)
            runner.dismiss()
        }
        c.disconnect()
    }
}
