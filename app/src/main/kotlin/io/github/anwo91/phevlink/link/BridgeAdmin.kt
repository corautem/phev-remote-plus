package io.github.anwo91.phevlink.link

import android.content.Context
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.str
import io.github.anwo91.phevlink.core.session.LinkStatus
import io.github.anwo91.phevlink.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The bridge page's brain: what the bridge is doing (status, car link, car Wi-Fi, computer, its own log) and the
 * things you can do with it (connect to the car, restart, restart the computer, update). Polls only while the page
 * is open ([open]/[close]); app-wide so an update keeps being followed across a rotation. Every action is logged.
 */
class BridgeAdminController(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: DiagLog,
    private val scope: CoroutineScope,
    private val repo: () -> CarRepository,
) {
    enum class UpdatePhase { IDLE, STARTING, RUNNING, RESTARTING, DONE, FAILED }
    enum class Action { CONNECT, RESTART, REBOOT, CHECK, PAUSE, RESUME, INVITE }
    enum class Outcome { OK, FAILED }

    data class UpdateInfo(
        val current: String?,
        val latest: String?,
        val available: Boolean,
        val running: Boolean,
        val result: String?,
        val detail: String?,
        val log: List<String>,
        val canUpdate: Boolean,
    )

    data class State(
        val url: String = "",
        /** null = not asked yet. */
        val reachable: Boolean? = null,
        val name: String? = null,
        /** Release the bridge was built in ("1.0.40"); null for bridges from before in-app updates. */
        val build: String? = null,
        val apiVersion: String? = null,
        val features: List<String> = emptyList(),
        val emulator: Boolean = false,
        val startedAtMs: Long? = null,
        val system: Map<String, Any?> = emptyMap(),
        val wifi: BridgeWifi? = null,
        val link: LinkStatus? = null,
        val carUpdatedAtMs: Long? = null,
        val battery: Int? = null,
        val log: List<String> = emptyList(),
        val update: UpdateInfo? = null,
        val phase: UpdatePhase = UpdatePhase.IDLE,
        val phaseDetail: String? = null,
        val busy: Action? = null,
        val lastAction: Pair<Action, Outcome>? = null,
        val lastActionDetail: String? = null,
        /** "Add another phone": until when the bridge lets one more phone pair. */
        val inviteUntilMs: Long? = null,
        /** The bridge is away because it was asked to restart / update: show calm progress, not "offline". */
        val restarting: Boolean = false,
        val updateCheckedAtMs: Long? = null,
    ) {
        val canUpdate: Boolean get() = "update" in features && update?.canUpdate != false
        val canRestart: Boolean get() = "restart" in features
        val canPause: Boolean get() = "carwifi-pause" in features
        val canInvite: Boolean get() = "pair-invite" in features
        val inviteOpen: Boolean get() = (inviteUntilMs ?: 0L) > System.currentTimeMillis()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var poller: Job? = null
    private var updateJob: Job? = null
    private var openCount = 0
    /** Until when "doesn't answer" is expected (restart, reboot, update) rather than a problem. */
    @Volatile private var expectAwayUntilMs = 0L

    private fun expectAway(ms: Long) {
        expectAwayUntilMs = System.currentTimeMillis() + ms
    }

    private val awayExpected: Boolean
        get() = System.currentTimeMillis() < expectAwayUntilMs || updateJob?.isActive == true

    private val url get() = settings.current.bridgeUrl.trimEnd('/')
    private val token get() = settings.current.bridgeToken

    /** The page is shown: start polling (status every 5 s, the computer and updates less often). */
    fun open() {
        openCount++
        if (poller?.isActive == true) return
        log.link("Bridge page: opened (${url.ifBlank { "no bridge set up" }})")
        poller = scope.launch {
            var round = 0
            while (isActive) {
                refreshNow(full = round % 6 == 0, updates = round % 12 == 0)
                round++
                delay(5_000)
            }
        }
    }

    fun close() {
        openCount = (openCount - 1).coerceAtLeast(0)
        if (openCount == 0) {
            poller?.cancel()
            poller = null
        }
    }

    private suspend fun refreshNow(full: Boolean, updates: Boolean) {
        val u = url
        if (u.isBlank() || token.isBlank()) {
            _state.update { it.copy(url = u, reachable = false) }
            return
        }
        val hello = BridgeSetupApi.hello(u)
        if (hello == null) {
            if (awayExpected) {
                // Asked to restart or update: it's supposed to be away for a bit — keep the page as it was.
                _state.update { it.copy(url = u, restarting = true) }
                return
            }
            if (_state.value.reachable != false) log.warn("Bridge page: $u doesn't answer")
            _state.update { it.copy(url = u, reachable = false, restarting = false) }
            return
        }
        if (_state.value.restarting) log.link("Bridge page: the bridge is back")
        expectAwayUntilMs = 0L
        _state.update {
            it.copy(
                url = u, reachable = true, restarting = false, name = hello.name, apiVersion = hello.version,
                features = hello.features, emulator = hello.emulator, build = hello.build,
            )
        }
        BridgeClient(u, token, log, context).fetchStatus(5_000)?.let { snap ->
            _state.update { it.copy(link = snap.link, carUpdatedAtMs = snap.car.updatedAtMs, battery = snap.car.batteryPercent) }
        }
        BridgeSetupApi.carWifi(u, token)?.takeIf { it.code == 200 }?.let { r -> _state.update { it.copy(wifi = BridgeWifi.from(r.body)) } }
        BridgeSetupApi.log(u, token, LOG_LINES)?.let { text -> _state.update { it.copy(log = text.lines().filter { l -> l.isNotBlank() }) } }
        if (full && "system" in hello.features) {
            BridgeSetupApi.system(u, token)?.takeIf { it.code == 200 }?.let { r ->
                _state.update { it.copy(system = r.body, startedAtMs = (r.body["startedAtMs"] as? Number)?.toLong()) }
            }
        }
        if (updates && "update" in hello.features && updateJob?.isActive != true) fetchUpdate(check = false)
    }

    private suspend fun fetchUpdate(check: Boolean): UpdateInfo? {
        val r = BridgeSetupApi.updateStatus(url, token, check)?.takeIf { it.code == 200 } ?: return null
        val b = r.body
        val info = UpdateInfo(
            current = b.str("current"), latest = b.str("latest"), available = b["available"] == true,
            running = b["running"] == true, result = b.str("result"), detail = b.str("detail"),
            log = (b["log"] as? List<*>)?.map { it.toString() }.orEmpty(), canUpdate = b["canUpdate"] != false,
        )
        _state.update { it.copy(update = info, updateCheckedAtMs = System.currentTimeMillis()) }
        return info
    }

    /** For Settings → Updates (no page polling there): who the bridge is and whether it has an update. */
    suspend fun refreshForSettings(check: Boolean = false) {
        val u = url
        if (u.isBlank() || token.isBlank()) return
        val hello = BridgeSetupApi.hello(u)
        if (hello == null) {
            _state.update { it.copy(url = u, reachable = if (awayExpected) it.reachable else false, restarting = awayExpected) }
            return
        }
        _state.update {
            it.copy(url = u, reachable = true, restarting = false, name = hello.name, apiVersion = hello.version,
                features = hello.features, emulator = hello.emulator, build = hello.build)
        }
        if ("update" in hello.features && updateJob?.isActive != true) fetchUpdate(check)
    }

    /** At app start: ask the bridge about updates at most every few hours (for the dot on Settings). */
    fun checkUpdatesIfDue() {
        val last = _state.value.updateCheckedAtMs ?: 0L
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return
        scope.launch { runCatching { refreshForSettings(check = false) } }
    }

    // ------------------------------------------------------------------ actions

    private fun action(a: Action, block: suspend () -> String?) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = a, lastAction = null, lastActionDetail = null) }
        scope.launch {
            val why = runCatching { block() }.getOrElse { it.message ?: it.javaClass.simpleName }
            log.link("Bridge page: ${a.name.lowercase()} → ${why ?: "ok"}")
            _state.update { it.copy(busy = null, lastAction = a to if (why == null) Outcome.OK else Outcome.FAILED, lastActionDetail = why) }
        }
    }

    private fun BridgeSetupApi.Reply?.problem(): String? {
        val r = this ?: return "no answer"
        return if (r.code in 200..299 && r.body["ok"] != false) null else r.body.str("error") ?: "HTTP ${r.code}"
    }

    fun connectCar() = action(Action.CONNECT) { BridgeSetupApi.connect(url, token).problem() }

    fun restart() = action(Action.RESTART) {
        expectAway(60_000)
        BridgeSetupApi.restart(url, token).problem()
    }

    fun reboot() = action(Action.REBOOT) {
        expectAway(4 * 60_000L)
        BridgeSetupApi.reboot(url, token).problem()
    }

    fun checkForUpdate() = action(Action.CHECK) { if (fetchUpdate(check = true) == null) "no answer" else null }

    /**
     * The bridge leaves the car's Wi-Fi for [minutes] so this phone can use it (to register the phone): the car takes
     * one device at a time. The app goes straight to the car's Wi-Fi meanwhile; the bridge comes back by itself.
     */
    fun pauseCarWifi(minutes: Int = 10) = action(Action.PAUSE) {
        val r = BridgeSetupApi.pauseCarWifi(url, token, minutes)
        val why = r.problem()
        if (why == null) {
            val until = (r?.body?.get("pausedUntilMs") as? Number)?.toLong() ?: (System.currentTimeMillis() + minutes * 60_000L)
            repo().preferCarWifi(until, "the bridge stepped aside so this phone can use the car's Wi-Fi")
        }
        refreshWifi()
        why
    }

    /** "Add another phone": the bridge lets one more phone pair for the next 10 minutes. */
    fun invitePhone(minutes: Int = 10) = action(Action.INVITE) {
        val r = BridgeSetupApi.openPairing(url, token, minutes)
        val why = r.problem()
        if (why == null) {
            val seconds = (r?.body?.get("secondsLeft") as? Number)?.toLong() ?: (minutes * 60L)
            _state.update { it.copy(inviteUntilMs = System.currentTimeMillis() + seconds * 1000) }
        }
        why
    }

    fun resumeCarWifi() = action(Action.RESUME) {
        val why = BridgeSetupApi.resumeCarWifi(url, token).problem()
        repo().preferCarWifi(0, "the bridge is back on the car's Wi-Fi")
        refreshWifi()
        why
    }

    /** Fresh car Wi-Fi status (also used by Settings, which doesn't run the page's polling). */
    suspend fun refreshWifi() {
        if (url.isBlank() || token.isBlank()) return
        BridgeSetupApi.carWifi(url, token)?.takeIf { it.code == 200 }?.let { r -> _state.update { it.copy(wifi = BridgeWifi.from(r.body)) } }
        BridgeSetupApi.hello(url)?.let { h -> _state.update { it.copy(features = h.features, build = h.build, reachable = true) } }
    }

    /**
     * Starts the update and follows it: the bridge downloads and installs the newest release, then restarts. Done
     * when it answers again with a newer build; failed when its update log says so or after 10 minutes.
     */
    fun startUpdate() {
        if (updateJob?.isActive == true) return
        val from = _state.value.build ?: _state.value.update?.current
        val target = _state.value.update?.latest
        _state.update { it.copy(phase = UpdatePhase.STARTING, phaseDetail = null) }
        log.link("Bridge page: update requested (installed ${from ?: "?"}, newest ${target ?: "?"})")
        updateJob = scope.launch {
            val r = BridgeSetupApi.startUpdate(url, token)
            val why = r.problem()
            if (why != null) {
                log.warn("Bridge page: the bridge didn't start the update: $why")
                _state.update { it.copy(phase = UpdatePhase.FAILED, phaseDetail = why) }
                return@launch
            }
            _state.update { it.copy(phase = UpdatePhase.RUNNING) }
            val since = System.currentTimeMillis()
            var wentAway = false
            while (System.currentTimeMillis() - since < 10 * 60_000L) {
                delay(3_000)
                val hello = BridgeSetupApi.hello(url)
                if (hello == null) {
                    if (!wentAway) log.link("Bridge page: the bridge is restarting on the new version…")
                    wentAway = true
                    _state.update { it.copy(phase = UpdatePhase.RESTARTING, restarting = true) }
                    continue
                }
                val build = hello.build
                if (wentAway && build != null && build != from) {
                    log.link("Bridge page: updated from ${from ?: "?"} to $build")
                    _state.update { it.copy(phase = UpdatePhase.DONE, phaseDetail = build, build = build) }
                    fetchUpdate(check = false)
                    return@launch
                }
                val info = fetchUpdate(check = false)
                if (info != null && !info.running && info.result == "update-failed") {
                    log.warn("Bridge page: update failed: ${info.detail}")
                    _state.update { it.copy(phase = UpdatePhase.FAILED, phaseDetail = info.detail) }
                    return@launch
                }
                if (info != null && !info.running && info.result == "update-ok" && build != null && build != from) {
                    log.link("Bridge page: updated from ${from ?: "?"} to $build")
                    _state.update { it.copy(phase = UpdatePhase.DONE, phaseDetail = build, build = build) }
                    return@launch
                }
            }
            log.warn("Bridge page: no sign of the new version after 10 minutes")
            _state.update { it.copy(phase = UpdatePhase.FAILED, phaseDetail = "timeout") }
        }
    }

    fun dismissUpdateResult() = _state.update { it.copy(phase = UpdatePhase.IDLE, phaseDetail = null) }

    companion object {
        const val LOG_LINES = 200
    }
}
