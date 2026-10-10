package io.github.anwo91.phevlink.core.obd.livetest

/** Outcome of a live test (or of one finding within it). Ordered from best to worst for [worst]. */
enum class Verdict {
    PASS, INFO, WARN, FAIL, INCONCLUSIVE, CANCELLED;

    companion object {
        /**
         * The overall verdict of [verdicts]: FAIL > WARN > INCONCLUSIVE > PASS. INFO findings don't change the
         * verdict; it is INFO only when there is nothing else.
         */
        fun worst(verdicts: Collection<Verdict>): Verdict = when {
            FAIL in verdicts -> FAIL
            WARN in verdicts -> WARN
            INCONCLUSIVE in verdicts -> INCONCLUSIVE
            PASS in verdicts -> PASS
            else -> INFO
        }
    }
}

/** A number to show in a message, formatted for the reader's language (decimal comma etc.). */
data class Num(val value: Double, val decimals: Int = 1)

/**
 * A localisable message: a key from `strings_<lang>.txt` plus arguments for its `{slots}`.
 * Argument values may be [String], [Int]/[Long], [Num], [Double] (1 decimal) or another [Msg].
 */
data class Msg(val key: String, val args: Map<String, Any> = emptyMap()) {
    constructor(key: String, vararg args: Pair<String, Any>) : this(key, mapOf(*args))
}

/** One measured value in a result or live display. [value] null → shows [text] or "—". */
data class Measurement(
    val label: Msg,
    val value: Double?,
    val unit: String,
    val decimals: Int = 1,
    val text: Msg? = null,
)

data class Finding(val verdict: Verdict, val msg: Msg)

/**
 * The result of one run.
 * @param headline one-line summary, @param findings the individual checks (worst first in the UI),
 * @param nextSteps what to do next, @param relatedTips ids from the tips knowledge base,
 * @param relatedCodes fault codes worth looking up, @param samples raw samples by value id, for export/history
 */
data class TestResult(
    val testId: String,
    val verdict: Verdict,
    val headline: Msg,
    val findings: List<Finding>,
    val measurements: List<Measurement>,
    val nextSteps: List<Msg>,
    val relatedTips: List<String>,
    val relatedCodes: List<String> = emptyList(),
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val samples: Map<String, List<Double>> = emptyMap(),
)

/** What the UI shows while a test runs. */
sealed interface TestUiState {
    val testId: String

    /**
     * The test waits for the user. [positive] continues (e.g. "Continue", "Done"), [negative] (if not null) is an
     * alternative answer (e.g. "Skip"); cancelling is always possible via [LiveTestRunner.cancel].
     */
    data class Prompt(
        override val testId: String,
        val step: Int,
        val msg: Msg,
        val positive: Msg,
        val negative: Msg? = null,
    ) : TestUiState

    /** Measuring or waiting. [progress] 0..1 or null (indeterminate); [live] latest readings. */
    data class Working(
        override val testId: String,
        val step: Int,
        val msg: Msg,
        val progress: Float?,
        val secondsLeft: Int?,
        val live: List<Measurement>,
    ) : TestUiState

    data class Finished(override val testId: String, val result: TestResult) : TestUiState
}

/** Small key/value store for baselines and history (the app backs it with SharedPreferences). */
interface TestStore {
    fun get(key: String): String?
    fun put(key: String, value: String)

    /** In-memory store (tests, or when nothing should be remembered). */
    class Memory : TestStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }
}

/**
 * A guided live test.
 * @param titleKey / [descriptionKey] text keys; [needsEngine] the engine must run (the UI explains how);
 * @param minutes rough duration for the list; [road] part of the test is done while driving
 */
abstract class LiveTest(
    val id: String,
    val needsEngine: Boolean,
    val minutes: Int,
    val road: Boolean = false,
    val relatedTips: List<String> = emptyList(),
) {
    val titleKey: String get() = "test.$id.title"
    val descriptionKey: String get() = "test.$id.desc"

    abstract suspend fun run(ctx: TestContext): TestResult
}
