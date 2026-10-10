package io.github.anwo91.phevlink.core.obd.livetest

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.ObdValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Thrown by a test to stop early with an inconclusive result (e.g. the car stopped answering). */
class TestAborted(val reason: Msg) : Exception(reason.key)

/** Samples collected by [TestContext.sample], by value id. */
class Samples {
    private val data = LinkedHashMap<String, MutableList<Double>>()

    fun add(id: String, v: Double?) {
        if (v == null || v.isNaN()) return
        data.getOrPut(id) { ArrayList() } += v
    }

    fun list(id: String): List<Double> = data[id].orEmpty()
    fun count(id: String): Int = data[id]?.size ?: 0
    fun median(id: String): Double? = list(id).median()
    fun min(id: String): Double? = list(id).minOrNull()
    fun max(id: String): Double? = list(id).maxOrNull()
    fun last(id: String): Double? = list(id).lastOrNull()
    fun all(): Map<String, List<Double>> = data.mapValues { it.value.toList() }

    /** Copies everything into [into] under "[prefix]id". */
    fun exportTo(into: MutableMap<String, List<Double>>, prefix: String = "") {
        for ((k, v) in data) into[prefix + k] = v.toList()
    }
}

fun List<Double>.median(): Double? {
    if (isEmpty()) return null
    val s = sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}

/**
 * What a [LiveTest] can do: talk to the user, read the car, measure over time. Implemented by [LiveTestRunner].
 */
interface TestContext {
    /** The current client. It changes when the app reconnects the adapter during a test: read it on every use. */
    val client: ObdClient
    val store: TestStore
    /** VIN known for the car (OBD or Wi-Fi), for model-year dependent limits. */
    val vin: String?
    fun now(): Long

    /** Shows [msg] and waits for the user. True = [positive], false = [negative]. */
    suspend fun prompt(msg: Msg, positive: Msg = Msg("btn.continue"), negative: Msg? = null): Boolean

    /** Shows a working state (no waiting). */
    fun working(msg: Msg, progress: Float? = null, secondsLeft: Int? = null, live: List<Measurement> = emptyList())

    /** Waits [ms] (scaled in unit tests), showing [msg] with a countdown. */
    suspend fun pause(msg: Msg, ms: Long)

    /**
     * Calls [read] every [periodMs] for [durationMs] (at least once), collecting the returned values, while showing
     * [msg], progress and [live] readings. [stopWhen] can end early. With [requireCar], aborts as inconclusive when
     * the car stops answering for more than 10 s.
     */
    suspend fun sample(
        msg: Msg,
        durationMs: Long,
        periodMs: Long,
        requireCar: Boolean = true,
        live: (Map<String, Double?>) -> List<Measurement> = { emptyList() },
        stopWhen: ((Samples) -> Boolean)? = null,
        read: suspend () -> Map<String, Double?>,
    ): Samples

    /** Polls [condition] every [periodMs] until true (returns true) or [timeoutMs] passes (false). */
    suspend fun waitFor(msg: Msg, timeoutMs: Long, periodMs: Long = 1_000, condition: suspend () -> Boolean): Boolean

    /** A live value from the client's state if it is younger than [maxAgeMs]. */
    fun fresh(id: String, maxAgeMs: Long = 5_000): ObdValue?
}

/**
 * Runs one [LiveTest] at a time and exposes its progress as [state] for the UI.
 *
 * @param timeScale multiplies every wait (1.0 normally; 0.0 in unit tests so they run instantly)
 */
class LiveTestRunner(
    private val scope: CoroutineScope,
    private val log: DiagLog? = null,
    private val timeScale: Double = 1.0,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<TestUiState?>(null)
    val state: StateFlow<TestUiState?> = _state.asStateFlow()

    /** The open prompt: its [step] (see [TestUiState.Prompt.step]) and the answer it waits for. */
    private class PendingAnswer(val step: Int, val deferred: CompletableDeferred<Boolean>)

    @Volatile private var job: Job? = null
    @Volatile private var answer: PendingAnswer? = null

    val running: Boolean get() = job?.isActive == true

    /**
     * Starts [test] (cancelling any running one). [onFinished] is called with the result (not when cancelled; the
     * CANCELLED result is still published in [state]).
     *
     * [current] is asked for the client every time the test uses it, so a test keeps working when the app
     * reconnects the adapter meanwhile (a reconnect creates a new [ObdClient]; the old one stays dead). While it
     * returns null (between connections) the last client is used: its reads then fail softly and the test aborts or
     * waits as usual. Default: always [client].
     */
    fun start(
        test: LiveTest,
        client: ObdClient,
        store: TestStore,
        vin: String?,
        current: () -> ObdClient? = { client },
        onFinished: (TestResult) -> Unit = {},
    ) {
        job?.cancel()
        val started = clock()
        // LAZY so that [job] already points at the new coroutine when its body runs: on a multi-threaded scope the
        // body could otherwise start before the assignment, and a cancelled predecessor could still publish its
        // CANCELLED result (or clear [answer]) over the new test — leaving the UI on a stale result whose "Done"
        // does nothing because a test is running.
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            val isCurrent = { job === me }
            val ctx = Ctx(test.id, client, current, store, vin, isCurrent)
            log?.link("Live test ${test.id}: started")
            val result = try {
                test.run(ctx)
            } catch (e: TestAborted) {
                TestResult(
                    test.id, Verdict.INCONCLUSIVE, e.reason, emptyList(), emptyList(),
                    listOf(Msg("next.check_connection")), test.relatedTips, startedAtMs = started, finishedAtMs = clock(),
                )
            } catch (e: CancellationException) {
                val r = TestResult(
                    test.id, Verdict.CANCELLED, Msg("test.cancelled"), emptyList(), emptyList(), emptyList(),
                    test.relatedTips, startedAtMs = started, finishedAtMs = clock(),
                )
                if (isCurrent()) _state.value = TestUiState.Finished(test.id, r)
                log?.link("Live test ${test.id}: cancelled" + if (isCurrent()) "" else " (replaced by another test)")
                throw e
            } catch (e: Exception) {
                log?.error("Live test ${test.id} crashed: $e")
                TestResult(
                    test.id, Verdict.INCONCLUSIVE, Msg("test.error", "error" to (e.message ?: e.toString())),
                    emptyList(), emptyList(), listOf(Msg("next.check_connection")), test.relatedTips,
                    startedAtMs = started, finishedAtMs = clock(),
                )
            }
            store.put("last.${test.id}", "${result.verdict.name}|${result.finishedAtMs}")
            log?.link("Live test ${test.id}: ${result.verdict} (${result.headline.key})")
            if (isCurrent()) _state.value = TestUiState.Finished(test.id, result)
            onFinished(result)
        }
        job = newJob
        newJob.start()
    }

    /**
     * Answers the current [TestUiState.Prompt]. With [step], only the prompt of that step is answered, so a quick
     * second tap meant for an earlier prompt cannot also confirm the next instruction (e.g. "start the engine").
     */
    fun answer(positive: Boolean, step: Int? = null) {
        val p = answer ?: return
        if (step != null && step != p.step) return
        p.deferred.complete(positive)
    }

    /** Cancels the running test (the state becomes a CANCELLED result). */
    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished test from [state]. */
    fun dismiss() {
        if (!running) _state.value = null
    }

    /** Last verdict and time of [testId] from [store], if it ever ran. */
    fun lastRun(store: TestStore, testId: String): Pair<Verdict, Long>? {
        val raw = store.get("last.$testId") ?: return null
        val parts = raw.split('|')
        val v = runCatching { Verdict.valueOf(parts[0]) }.getOrNull() ?: return null
        val t = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return v to t
    }

    private inner class Ctx(
        private val testId: String,
        first: ObdClient,
        private val current: () -> ObdClient?,
        override val store: TestStore,
        override val vin: String?,
        /** False once another test replaced this one: it must not touch [state] or [answer] any more. */
        private val isCurrent: () -> Boolean,
    ) : TestContext {
        private var step = 0
        @Volatile private var last: ObdClient = first

        /** The app's current client (see [start]); the last known one while there is none. */
        override val client: ObdClient
            get() {
                val c = try {
                    current()
                } catch (e: Exception) {
                    null
                }
                if (c != null && c !== last) {
                    last = c
                    log?.link("Live test $testId: continuing on the new OBD connection")
                }
                return last
            }

        override fun now(): Long = clock()

        override suspend fun prompt(msg: Msg, positive: Msg, negative: Msg?): Boolean {
            step++
            val p = PendingAnswer(step, CompletableDeferred())
            if (isCurrent()) {
                answer = p
                _state.value = TestUiState.Prompt(testId, step, msg, positive, negative)
            }
            return try {
                p.deferred.await()
            } finally {
                // Only clear our own prompt: a newer test may already have opened its own.
                if (answer === p) answer = null
            }
        }

        override fun working(msg: Msg, progress: Float?, secondsLeft: Int?, live: List<Measurement>) {
            if (isCurrent()) _state.value = TestUiState.Working(testId, step, msg, progress, secondsLeft, live)
        }

        private suspend fun sleep(ms: Long) {
            val scaled = (ms * timeScale).toLong()
            if (scaled > 0) delay(scaled)
        }

        override suspend fun pause(msg: Msg, ms: Long) {
            val steps = ceil(ms / 1000.0).toInt().coerceAtLeast(1)
            for (i in 0 until steps) {
                working(msg, i.toFloat() / steps, steps - i)
                sleep(minOf(1000L, ms - i * 1000L))
            }
        }

        override suspend fun sample(
            msg: Msg,
            durationMs: Long,
            periodMs: Long,
            requireCar: Boolean,
            live: (Map<String, Double?>) -> List<Measurement>,
            stopWhen: ((Samples) -> Boolean)?,
            read: suspend () -> Map<String, Double?>,
        ): Samples {
            val samples = Samples()
            val n = (durationMs / periodMs).toInt().coerceAtLeast(1)
            var silentSince: Long? = null
            working(msg, 0f, (durationMs / 1000).toInt())
            for (i in 0 until n) {
                val t0 = clock()
                val values = read()
                for ((k, v) in values) samples.add(k, v)
                if (requireCar) {
                    if (!client.state.value.carReachable) {
                        val since = silentSince ?: clock().also { silentSince = it }
                        if (clock() - since > CAR_SILENT_LIMIT_MS || timeScale == 0.0 && i >= 3) throw TestAborted(Msg("abort.car_silent"))
                    } else {
                        silentSince = null
                    }
                }
                val left = ((n - i - 1) * periodMs / 1000.0).roundToInt()
                working(msg, (i + 1).toFloat() / n, left, live(values))
                if (stopWhen != null && stopWhen(samples)) break
                if (i < n - 1) {
                    val spent = clock() - t0
                    sleep((periodMs - spent).coerceAtLeast(0L))
                }
            }
            return samples
        }

        override suspend fun waitFor(msg: Msg, timeoutMs: Long, periodMs: Long, condition: suspend () -> Boolean): Boolean {
            val n = (timeoutMs / periodMs).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                working(msg, null, ((n - i) * periodMs / 1000).toInt())
                if (condition()) return true
                sleep(periodMs)
            }
            return condition()
        }

        override fun fresh(id: String, maxAgeMs: Long): ObdValue? {
            val v = client.state.value.values[id] ?: return null
            return if (timeScale == 0.0 || clock() - v.updatedAtMs <= maxAgeMs) v else null
        }
    }

    companion object {
        /** How long a measurement waits for the car (covers an automatic adapter reconnect: backoff + setup). */
        const val CAR_SILENT_LIMIT_MS = 45_000L
    }
}
