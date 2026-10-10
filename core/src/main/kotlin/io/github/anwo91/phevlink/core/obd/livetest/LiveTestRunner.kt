package io.github.anwo91.phevlink.core.obd.livetest

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.ObdValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

    @Volatile private var job: Job? = null
    @Volatile private var answer: CompletableDeferred<Boolean>? = null

    val running: Boolean get() = job?.isActive == true

    /** Starts [test] (cancelling any running one). [onFinished] is called with the result (also when cancelled). */
    fun start(test: LiveTest, client: ObdClient, store: TestStore, vin: String?, onFinished: (TestResult) -> Unit = {}) {
        job?.cancel()
        val started = clock()
        job = scope.launch {
            val ctx = Ctx(test.id, client, store, vin)
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
                _state.value = TestUiState.Finished(test.id, r)
                log?.link("Live test ${test.id}: cancelled")
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
            _state.value = TestUiState.Finished(test.id, result)
            onFinished(result)
        }
    }

    /** Answers the current [TestUiState.Prompt]. */
    fun answer(positive: Boolean) {
        answer?.complete(positive)
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
        override val client: ObdClient,
        override val store: TestStore,
        override val vin: String?,
    ) : TestContext {
        private var step = 0

        override fun now(): Long = clock()

        override suspend fun prompt(msg: Msg, positive: Msg, negative: Msg?): Boolean {
            step++
            val d = CompletableDeferred<Boolean>()
            answer = d
            _state.value = TestUiState.Prompt(testId, step, msg, positive, negative)
            return try {
                d.await()
            } finally {
                answer = null
            }
        }

        override fun working(msg: Msg, progress: Float?, secondsLeft: Int?, live: List<Measurement>) {
            _state.value = TestUiState.Working(testId, step, msg, progress, secondsLeft, live)
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
        const val CAR_SILENT_LIMIT_MS = 10_000L
    }
}
