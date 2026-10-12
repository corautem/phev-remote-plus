package io.github.anwo91.phevlink.link

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.data.AppSettings
import io.github.anwo91.phevlink.data.SettingsStore
import io.github.anwo91.phevlink.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * Keeps what all phones share in step with the bridge (bridge with the "shared" feature): departures, the car's name,
 * full range, the quick-climate defaults and the weather place (plus this phone's time zone, so the bridge runs
 * departures on local time). Language, alerts and the connection itself stay on each phone.
 *
 * Per item: changed only here → sent to the bridge; changed on the bridge (by another phone) → taken over here; changed
 * on both since the last sync → the bridge wins. "Since the last sync" is the value both sides agreed on last time,
 * saved per bridge address. Every sync that moves something is logged.
 */
class SharedSync(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: DiagLog,
    private val scope: CoroutineScope,
) {
    /** How the last departure the bridge handled went ("started", "failed", "skipped" or "away"). */
    data class BridgeDeparture(val id: String, val atMs: Long, val outcome: String, val detail: String, val doneAtMs: Long)

    data class State(
        /** The bridge keeps shared data (null: not known yet; false: the bridge needs an update). */
        val supported: Boolean? = null,
        val lastSyncMs: Long? = null,
        /** The last sync reached the bridge. */
        val reachable: Boolean = false,
        val lastDeparture: BridgeDeparture? = null,
        val updatedBy: String? = null,
    ) {
        /** The bridge runs departures while the car is home. */
        val bridgeRunsDepartures: Boolean get() = supported == true
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        State(supported = if (prefs.contains(KEY_SUPPORTED)) prefs.getBoolean(KEY_SUPPORTED, false) else null),
    )
    val state: StateFlow<State> = _state.asStateFlow()
    private var started = false
    private var lastFailureLogMs = 0L

    /** Watches for changes made on this phone (sent a moment later) and syncs once now. Safe to call again. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.flow.map { if (it.hasBridge) local(it) else null }.distinctUntilChanged().drop(1).collectLatest { mine ->
                if (mine == null || _state.value.supported == false) return@collectLatest
                if (mine.mapValues { Json.write(it.value) } == base(settings.current.bridgeUrl)) return@collectLatest // took over from the bridge
                delay(1_500) // typing a name, dragging a slider: send once it settles
                sync("changed on this phone")
            }
        }
        scope.launch { sync("app start") }
        scheduleBackground()
    }

    /** Syncs unless that happened within [maxAgeMs] (screen back in front). */
    fun syncIfStale(maxAgeMs: Long = 60_000) {
        val last = _state.value.lastSyncMs ?: 0L
        if (System.currentTimeMillis() - last > maxAgeMs) scope.launch { sync("app opened") }
    }

    /** One round with the bridge. True when the bridge was reached and both sides now agree. */
    suspend fun sync(why: String): Boolean {
        mutex.withLock { return syncLocked(why) }
    }

    private suspend fun syncLocked(why: String): Boolean {
        val s = settings.current
        if (!s.hasBridge || s.bridgeToken.isBlank()) return false
        val url = s.bridgeUrl
        val r = BridgeSetupApi.shared(url, s.bridgeToken)
        if (r == null || r.code != 200) {
            val tooOld = r != null && r.code == 404
            setSupported(if (tooOld) false else _state.value.supported)
            _state.update { it.copy(reachable = false) }
            val now = System.currentTimeMillis()
            if (now - lastFailureLogMs > 30 * 60_000) {
                lastFailureLogMs = now
                log.link(
                    "Shared data ($why): " + when {
                        tooOld -> "this bridge doesn't keep shared data yet — update it under Settings → Updates"
                        r == null -> "bridge not reachable (fine when away from home without VPN)"
                        else -> "bridge answered HTTP ${r.code}"
                    },
                )
            }
            return false
        }
        setSupported(true)
        val remote = r.body
        val base = base(url)
        val mine = local(s)
        val mineJson = mine.mapValues { Json.write(it.value) }
        val take = LinkedHashMap<String, Any?>()
        val send = LinkedHashMap<String, Any?>()
        for (k in KEYS) {
            val m = mineJson[k]
            val b = base[k]
            if (!remote.containsKey(k)) {
                send[k] = mine[k] // the bridge doesn't have it yet
                continue
            }
            val rv = Json.write(remote[k])
            if (k == "departures" && b == null && rv != m && rv != "[]" && m != "[]") {
                // First sync on this phone, and both sides have departures: keep all of them.
                val merged = mergeDepartures(remote[k], mine[k])
                take[k] = merged
                send[k] = merged
                continue
            }
            when {
                rv == m -> Unit
                k in PUSH_ONLY -> if (m != b) send[k] = mine[k]
                m != b && rv == b -> send[k] = mine[k]
                else -> take[k] = remote[k]
            }
        }
        var agreed: Map<String, Any?> = remote
        if (send.isNotEmpty()) {
            val p = BridgeSetupApi.putShared(url, s.bridgeToken, send)
            if (p == null || p.code != 200) {
                log.warn("Shared data ($why): couldn't send ${send.keys.joinToString()} to the bridge (${p?.code ?: "no answer"}) — trying again later")
                _state.update { it.copy(reachable = false) }
                return false
            }
            @Suppress("UNCHECKED_CAST")
            agreed = (p.body["shared"] as? Map<String, Any?>) ?: (remote + send)
        }
        // Saved first, so the settings change below isn't mistaken for an edit on this phone.
        saveBase(url, KEYS.filter { agreed.containsKey(it) }.associateWith { Json.write(agreed[it]) })
        if (take.isNotEmpty()) takeOver(take)
        val last = lastDeparture(agreed)
        scheduleBackground()
        _state.update {
            it.copy(lastSyncMs = System.currentTimeMillis(), reachable = true, lastDeparture = last, updatedBy = agreed["updatedBy"] as? String)
        }
        if (take.isNotEmpty() || send.isNotEmpty() || why == "app start") {
            log.link(
                "Shared data ($why, rev ${(agreed["rev"] as? Number)?.toLong() ?: "?"}): " +
                    listOfNotNull(
                        take.keys.takeIf { it.isNotEmpty() }?.let { "took over ${it.joinToString()} (from ${remote["updatedBy"] ?: "another phone"})" },
                        send.keys.takeIf { it.isNotEmpty() }?.let { "sent ${it.joinToString()}" },
                    ).ifEmpty { listOf("in step") }.joinToString("; "),
            )
        }
        return true
    }

    /**
     * At a departure's start time: is the bridge with the car (on its Wi-Fi) so it starts the climate itself?
     * True: yes, the phone only reports; false: no, this phone does it; null: the bridge didn't answer.
     */
    suspend fun bridgeTakesDeparture(): Boolean? {
        val s = settings.current
        if (!s.hasBridge || !_state.value.bridgeRunsDepartures) return false
        val r = BridgeSetupApi.carWifi(s.bridgeUrl, s.bridgeToken) ?: return null
        if (r.code != 200) return null
        val w = BridgeWifi.from(r.body)
        return w.connected && !w.paused
    }

    /** Waits (up to [timeoutMs]) for the bridge to report the departure at [atMs]; null if it never does. */
    suspend fun awaitBridgeDeparture(id: String, atMs: Long, timeoutMs: Long = 4 * 60_000): BridgeDeparture? =
        withTimeoutOrNull(timeoutMs) {
            var found: BridgeDeparture? = null
            while (found == null) {
                val s = settings.current
                val r = BridgeSetupApi.shared(s.bridgeUrl, s.bridgeToken)
                val d = r?.takeIf { it.code == 200 }?.let { lastDeparture(it.body) }
                if (d != null && d.id == id && d.atMs == atMs) found = d else delay(15_000)
            }
            _state.update { it.copy(lastDeparture = found) }
            found
        }

    // ------------------------------------------------------------------ helpers

    private fun setSupported(v: Boolean?) {
        if (v == null || v == _state.value.supported) return
        _state.update { it.copy(supported = v) }
        prefs.edit().putBoolean(KEY_SUPPORTED, v).apply()
        log.link(if (v) "Shared data: the bridge keeps departures and settings for all phones" else "Shared data: the bridge needs an update first")
    }

    /** What this phone would share, as plain JSON values. */
    private fun local(s: AppSettings): Map<String, Any?> = linkedMapOf(
        "departures" to Json.parse(Codecs.departuresToJson(s.departures)),
        "carName" to s.carName.trim(),
        "fullRangeKm" to s.fullRangeKm,
        "quickMode" to s.quickMode.name,
        "quickDuration" to s.quickDuration.minutes,
        "quickSmart" to s.quickSmart,
        "homeLat" to s.homeLat,
        "homeLon" to s.homeLon,
        "timeZone" to ZoneId.systemDefault().id,
    )

    private suspend fun takeOver(take: Map<String, Any?>) {
        settings.update { cur ->
            var n = cur
            for ((k, v) in take) {
                n = when (k) {
                    "departures" -> n.copy(departures = Codecs.departuresFromJson(Json.write(v)))
                    "carName" -> n.copy(carName = (v as? String).orEmpty())
                    "fullRangeKm" -> (v as? Number)?.toInt()?.takeIf { it in 5..200 }?.let { n.copy(fullRangeKm = it) } ?: n
                    "quickMode" -> (v as? String)?.let { runCatching { ClimateMode.valueOf(it) }.getOrNull() }?.let { n.copy(quickMode = it) } ?: n
                    "quickDuration" -> (v as? Number)?.let { n.copy(quickDuration = ClimateDuration.fromMinutes(it.toInt())) } ?: n
                    "quickSmart" -> (v as? Boolean)?.let { n.copy(quickSmart = it) } ?: n
                    "homeLat" -> n.copy(homeLat = (v as? Number)?.toDouble())
                    "homeLon" -> n.copy(homeLon = (v as? Number)?.toDouble())
                    else -> n
                }
            }
            n
        }
    }

    /** Departures from both lists, the bridge's first, each id once. */
    private fun mergeDepartures(remote: Any?, mine: Any?): List<Any?> {
        val out = ArrayList<Any?>()
        val ids = HashSet<String>()
        for (item in (remote as? List<*>).orEmpty() + (mine as? List<*>).orEmpty()) {
            val id = (item as? Map<*, *>)?.get("id") as? String ?: continue
            if (ids.add(id)) out += item
        }
        return out
    }

    private fun lastDeparture(m: Map<String, Any?>): BridgeDeparture? {
        @Suppress("UNCHECKED_CAST")
        val d = m["lastDeparture"] as? Map<String, Any?> ?: return null
        return BridgeDeparture(
            id = d["id"] as? String ?: return null,
            atMs = (d["atMs"] as? Number)?.toLong() ?: return null,
            outcome = d["outcome"] as? String ?: "",
            detail = d["detail"] as? String ?: "",
            doneAtMs = (d["doneAtMs"] as? Number)?.toLong() ?: 0L,
        )
    }

    /** What both sides agreed on last time (JSON per key), for this bridge address. */
    private fun base(url: String): Map<String, String> {
        if (prefs.getString(KEY_URL, null) != url) return emptyMap()
        val m = runCatching { Json.parseObject(prefs.getString(KEY_BASE, null) ?: "{}") }.getOrDefault(emptyMap())
        return m.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    private fun saveBase(url: String, m: Map<String, String>) {
        prefs.edit().putString(KEY_URL, url).putString(KEY_BASE, Json.write(m)).apply()
    }

    private fun scheduleBackground() {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (!settings.current.hasBridge) {
            js.cancel(SharedSyncJob.JOB_ID)
            return
        }
        if (js.getPendingJob(SharedSyncJob.JOB_ID) != null) return
        val info = JobInfo.Builder(SharedSyncJob.JOB_ID, ComponentName(context, SharedSyncJob::class.java))
            .setPeriodic(60 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        val r = js.schedule(info)
        log.link("Shared data: background sync every hour " + if (r == JobScheduler.RESULT_SUCCESS) "scheduled" else "could NOT be scheduled")
    }

    companion object {
        /** Shared through the bridge. */
        val KEYS = listOf("departures", "carName", "fullRangeKm", "quickMode", "quickDuration", "quickSmart", "homeLat", "homeLon", "timeZone")
        /** Sent by phones, never taken over from the bridge (each phone keeps its own). */
        private val PUSH_ONLY = setOf("timeZone")
        private const val PREFS = "shared_sync"
        private const val KEY_URL = "url"
        private const val KEY_BASE = "base"
        private const val KEY_SUPPORTED = "supported"
    }
}

/** Hourly background sync, so a departure added on another phone reaches this one without opening the app. */
class SharedSyncJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val g = applicationContext.graph
        if (!g.settings.current.hasBridge) {
            getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            return false
        }
        work = g.scope.launch {
            try {
                g.sharedSync.sync("background")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        const val JOB_ID = 0x7B1E
    }
}
