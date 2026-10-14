package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Keeps the bridge on the car's Wi-Fi.
 *
 * NetworkManager gives up on a network after failed joins. The car's Wi-Fi takes one device at a time: while a phone
 * held it, the car turned the bridge away, NetworkManager took that for a wrong password and stopped trying until
 * someone joins by hand. Field log: the bridge was off the car's Wi-Fi from about 01:06 for the rest of the night with
 * the car parked next to it, and the morning departure failed.
 *
 * So, every minute: when the bridge has the car's network saved, isn't stepping aside for a phone, and isn't on it, ask
 * NetworkManager to join again — after 1, 2, 4, 8 and then every 10 minutes while that fails (the car may simply be
 * away), and at once when the car's network comes back in sight. A phone that is about to use the car's Wi-Fi itself
 * asks first ([check] with rejoinNow, POST /api/carwifi/rejoin).
 *
 * Losing and getting back the car's Wi-Fi is logged with how long, the signal and NetworkManager's own words, and goes
 * into the activity log when it lasted a few minutes — which also shows when the car left and came home.
 *
 * It also works out whether the car can be reached at all ([reach]), for the phones to show plainly: on its Wi-Fi =
 * in reach; off it for [AWAY_AFTER_MS] and not seen in a real scan (at most [SCAN_EVERY_MS] old) = not seen (away, or
 * its Wi-Fi asleep — the radio can't tell those apart); in between, or in sight while joining = checking. Seeing the
 * car always wins over not seeing it.
 */
class CarWifiWatch(
    private val wifi: CarWifi,
    private val log: DiagLog,
    private val onActivity: (Activity) -> Unit = {},
    /** Back on the car's Wi-Fi: wake the car link now rather than at its next retry. */
    private val onBack: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** When the bridge last heard from the car (its saved state): "last seen" before this run saw it. */
    private val lastContact: () -> Long? = { null },
) {
    /** Told when [reach] changes (the bridge pushes it to the phones at once). */
    @Volatile var onReachChange: () -> Unit = {}

    @Volatile private var reachNow = REACH_UNKNOWN
    @Volatile private var reachSince = now()
    @Volatile private var lastSeenMs: Long? = null
    @Volatile private var checkedAtMs: Long? = null
    @Volatile private var lastInSight: Boolean? = null
    private var lastScanAt = 0L

    /**
     * Can the car be reached? `state`: in_reach | checking | not_seen | stepped_aside | unknown (nothing to watch);
     * `sinceMs` when that began; `lastSeenMs` last time the bridge was on the car's Wi-Fi or saw it (or last heard
     * from it); `checkedAtMs` when it last looked; `inSight` at that look.
     */
    fun reach(): Map<String, Any?> = linkedMapOf(
        "state" to reachNow,
        "sinceMs" to reachSince,
        "lastSeenMs" to (lastSeenMs ?: runCatching { lastContact() }.getOrNull()),
        "checkedAtMs" to checkedAtMs,
        "inSight" to lastInSight,
    )

    /** [reach]'s state alone. */
    val reachState: String get() = reachNow

    private fun setReach(state: String, why: String) {
        if (state == reachNow) return
        val was = reachNow
        reachNow = state
        reachSince = now()
        log.link("Reach: $was → $state — $why")
        runCatching { onReachChange() }
    }

    /** "last seen 14:32" for the log. */
    private fun seenText(): String = (lastSeenMs ?: runCatching { lastContact() }.getOrNull())?.let { "last seen ${clock(it)}" } ?: "never seen by this run"
    /** One look: [connected] now; [tried] = asked NetworkManager to join; [signal] of the car's network when in sight. */
    data class Result(val connected: Boolean, val tried: Boolean, val message: String, val signal: Int?) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "connected" to connected, "tried" to tried, "message" to message, "inSight" to (signal != null), "signal" to signal,
        )
    }

    /** Null until the first look; then whether the bridge was on the car's Wi-Fi at the last look. */
    @Volatile private var connected: Boolean? = null

    /** On the car's Wi-Fi at the last look (the car is home); null before the first look or when there's nothing to watch. */
    val onCarWifi: Boolean? get() = connected
    /** When the current stretch (on or off the car's Wi-Fi) began. */
    private var since = 0L
    private var lastSignal: Int? = null
    private var tries = 0
    private var nextTryAt = 0L
    private var wasInSight = false
    private var lostRecorded = false
    private var nmLogged = false
    private var idleWhy: String? = null

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        delay(START_DELAY_MS) // let NetworkManager settle after a boot or restart
        while (isActive) {
            runCatching { check() }.onFailure { log.warn("Car Wi-Fi watch: ${it.javaClass.simpleName}: ${it.message}") }
            delay(INTERVAL_MS)
        }
    }

    /** One look, and a join when one is due (or right away with [rejoinNow], on behalf of [who]). */
    @Synchronized
    fun check(rejoinNow: Boolean = false, who: String? = null): Result {
        if (wifi.paused) {
            setReach(REACH_STEPPED_ASIDE, "the bridge made room for a phone on the car's Wi-Fi")
            return Result(false, false, "paused for a phone", null)
        }
        val st = wifi.status()
        val iface = st.iface
        val idle = when {
            iface == null -> st.problem ?: CarWifi.PROBLEM_NO_WIFI
            st.problem == CarWifi.PROBLEM_HELPER_MISSING -> st.problem
            !st.configured -> "the car's network isn't set up"
            else -> null
        }
        if (idle != null || iface == null) {
            val why = idle ?: CarWifi.PROBLEM_NO_WIFI
            if (why != idleWhy) log.link("Car Wi-Fi watch: nothing to watch — $why")
            idleWhy = why
            connected = null
            setReach(REACH_UNKNOWN, why)
            return Result(false, false, why, null)
        }
        idleWhy = null
        val t = now()
        if (st.connected) {
            lastSeenMs = t
            checkedAtMs = t
            lastInSight = true
            setReach(REACH_IN_REACH, "on the car's Wi-Fi (${st.ssid}, signal ${st.signal ?: "?"} %)")
            when (connected) {
                false -> back(t, st)
                null -> {
                    since = t
                    log.link("Car Wi-Fi watch: on the car's Wi-Fi (${st.ssid}, signal ${st.signal ?: "?"} %)")
                }
                true -> Unit
            }
            connected = true
            st.signal?.let { lastSignal = it }
            return Result(true, false, "connected", st.signal)
        }
        if (connected != false) lost(t, st)
        var signal = wifi.inSight(iface, st.ssid)
        var scanned = false
        // "Not seen" needs a real look made well after the drop (one at the moment of the drop proves nothing).
        val lookDue = t - since >= AWAY_AFTER_MS && (checkedAtMs ?: 0L) < since + AWAY_AFTER_MS
        if (signal == null && (rejoinNow || lookDue || t - lastScanAt >= SCAN_EVERY_MS)) {
            // NetworkManager's list can be minutes old: look for real (a few seconds; the radio is idle meanwhile).
            // A phone waiting (rejoinNow) also saves a 30-second join attempt when the car isn't there.
            val (nets, error) = wifi.scan(null, quiet = !rejoinNow)
            if (error == null) {
                lastScanAt = t // a failed scan is tried again at the next look
                scanned = true
                checkedAtMs = t
            }
            signal = nets.firstOrNull { it.ssid == st.ssid }?.signal
        }
        lastInSight = signal != null
        if (signal != null) lastSeenMs = t
        updateReachOff(t, signal, scanned)
        if (rejoinNow && signal == null) {
            log.link("Car Wi-Fi watch: ${who ?: "a phone"} asked to rejoin, but the car's network isn't in sight — the car is away or asleep")
            wasInSight = false
            return Result(false, false, "the car's network is not in sight", null)
        }
        if (signal != null && !wasInSight && tries > 0 && t < nextTryAt) {
            log.link("Car Wi-Fi watch: the car's network is in sight again ($signal %) — joining now")
            nextTryAt = 0L
        }
        wasInSight = signal != null
        if (!lostRecorded && t - since >= RECORD_AFTER_MS) {
            lostRecorded = true
            onActivity(Activity(since, Activity.BRIDGE, "bridge.carwifi_lost", mapOf("signal" to lastSignal, "inSight" to (signal != null))))
        }
        if (!rejoinNow && t < nextTryAt) return Result(false, false, "next try at ${clock(nextTryAt)}", signal)
        return attempt(iface, signal, who)
    }

    /** Off the car's Wi-Fi: checking while it may just be a moment, not seen once a real look says so. */
    private fun updateReachOff(t: Long, signal: Int?, scanned: Boolean) {
        val off = t - since
        val looked = checkedAtMs?.let { it >= since + AWAY_AFTER_MS && t - it <= SCAN_EVERY_MS + INTERVAL_MS } == true
        when {
            signal != null -> setReach(REACH_CHECKING, "off the car's Wi-Fi for ${dur(off)}, but the car is in sight ($signal %) — joining")
            off >= AWAY_AFTER_MS && looked ->
                setReach(REACH_NOT_SEEN, "off the car's Wi-Fi for ${dur(off)} and not in sight (looked ${dur(t - (checkedAtMs ?: t))} ago${if (scanned) ", just now" else ""}); ${seenText()}")
            else -> setReach(REACH_CHECKING, "off the car's Wi-Fi for ${dur(off)}, not in sight yet — looking again before calling it away")
        }
    }

    private fun attempt(iface: String, signal: Int?, who: String?): Result {
        tries++
        val (ok, message) = wifi.rejoin()
        val t = now()
        log.link(
            "Car Wi-Fi watch: off the car's Wi-Fi for ${dur(t - since)} — asked NetworkManager to join again" +
                (who?.let { " for $it" } ?: "") + " (try $tries; car's network " + (signal?.let { "in sight at $it %" } ?: "not in sight") + "): " +
                if (ok) "joined" else "not joined — $message"
        )
        if (ok) {
            val after = wifi.status()
            if (after.connected) {
                back(now(), after)
                connected = true
                after.signal?.let { lastSignal = it }
                lastSeenMs = now()
                checkedAtMs = lastSeenMs
                lastInSight = true
                setReach(REACH_IN_REACH, "joined the car's Wi-Fi (signal ${after.signal ?: "?"} %)")
                return Result(true, true, "joined", after.signal)
            }
        }
        // In sight but it won't join: NetworkManager's own words say why (once per stretch off the car's Wi-Fi).
        if (signal != null && !nmLogged) {
            nmLogged = true
            logNm(iface, "why joining fails")
        }
        nextTryAt = t + backoffMs(tries)
        log.link("Car Wi-Fi watch: next try at ${clock(nextTryAt)}")
        return Result(false, true, message, signal)
    }

    private fun lost(t: Long, st: CarWifi.Status) {
        val first = connected == null
        if (first) {
            log.link("Car Wi-Fi watch: not on the car's Wi-Fi (${st.ssid}) — asking NetworkManager to join it")
            nextTryAt = 0L
        } else {
            log.link("Car Wi-Fi watch: lost the car's Wi-Fi on ${st.iface} after ${dur(t - since)} on it (last signal ${lastSignal ?: "?"} %)")
            st.iface?.let { logNm(it, "what happened") }
            nextTryAt = t + backoffMs(1) // NetworkManager tries by itself first
        }
        connected = false
        since = t
        tries = 0
        lostRecorded = first // not "lost" when it never was on (the bridge started without the car)
        nmLogged = false
        wasInSight = true
    }

    private fun back(t: Long, st: CarWifi.Status) {
        val off = t - since
        log.link(
            "Car Wi-Fi watch: back on the car's Wi-Fi after ${dur(off)} (signal ${st.signal ?: "?"} %" +
                (if (tries > 0) ", after $tries tr${if (tries == 1) "y" else "ies"} by the watch" else ", NetworkManager joined by itself") + ")"
        )
        if (off >= RECORD_AFTER_MS) {
            onActivity(Activity(t, Activity.BRIDGE, "bridge.carwifi_back", mapOf("minutes" to (off / 60_000).toInt(), "signal" to st.signal, "tries" to tries)))
        }
        since = t
        tries = 0
        nextTryAt = 0L
        lostRecorded = false
        nmLogged = false
        runCatching { onBack() }
    }

    private fun logNm(iface: String, why: String) {
        val lines = runCatching { wifi.recentNmLog(iface) }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            log.link("Car Wi-Fi watch: NetworkManager's log ($why): nothing (or an older helper — update the bridge)")
            return
        }
        log.link("Car Wi-Fi watch: NetworkManager's log ($why), last ${lines.size.coerceAtMost(NM_LINES)} lines:")
        lines.takeLast(NM_LINES).forEach { log.link("  NM: ${it.take(300)}") }
    }

    private fun dur(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else ActivityText.hm((ms / 60_000).toInt())

    private fun clock(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms))

    companion object {
        const val REACH_IN_REACH = "in_reach"
        const val REACH_CHECKING = "checking"
        const val REACH_NOT_SEEN = "not_seen"
        const val REACH_STEPPED_ASIDE = "stepped_aside"
        const val REACH_UNKNOWN = "unknown"

        /** Off the car's Wi-Fi at least this long (and not seen in a real scan) before the car counts as not seen. */
        const val AWAY_AFTER_MS = 90_000L

        /** While off the car's Wi-Fi and not seeing it, a real scan at most this often. */
        const val SCAN_EVERY_MS = 5 * 60_000L

        const val INTERVAL_MS = 60_000L
        private const val START_DELAY_MS = 20_000L
        /** Off the car's Wi-Fi this long goes into the activity log (shorter drops only in the diagnostic log). */
        const val RECORD_AFTER_MS = 3 * 60_000L
        private const val NM_LINES = 15
        private val BACKOFF_MIN = longArrayOf(1, 2, 4, 8, 10)
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

        fun backoffMs(tries: Int): Long = BACKOFF_MIN[(tries - 1).coerceIn(0, BACKOFF_MIN.lastIndex)] * 60_000L
    }
}
