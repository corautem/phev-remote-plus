package io.github.anwo91.phevlink.link

import android.content.Context
import androidx.annotation.StringRes
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertEngine
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.planner.ClimatePlanner
import io.github.anwo91.phevlink.core.planner.Departure
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.session.ConnectionConfig
import io.github.anwo91.phevlink.core.session.LinkStatus
import io.github.anwo91.phevlink.core.session.PhevConnection
import io.github.anwo91.phevlink.core.session.SessionConfig
import io.github.anwo91.phevlink.core.session.TcpConnector
import io.github.anwo91.phevlink.data.AppSettings
import io.github.anwo91.phevlink.data.Route
import io.github.anwo91.phevlink.data.SettingsStore
import io.github.anwo91.phevlink.data.StateCache
import io.github.anwo91.phevlink.notify.Notifier
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import io.github.anwo91.phevlink.weather.MetNo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import io.github.anwo91.phevlink.core.emulator.FakeCar

enum class LinkPhase { OFFLINE, SEARCHING, CONNECTING, HANDSHAKE, CONNECTED, RETRYING, FAILED }

/** Connection status as shown to people (texts in the app language). */
data class LinkUi(
    val phase: LinkPhase,
    val via: Route? = null,
    val text: String,
    val detail: String? = null,
    val sinceMs: Long? = null,
) {
    val connected: Boolean get() = phase == LinkPhase.CONNECTED
}

/**
 * The single place that knows how to reach the car. Screens, the widget, the
 * quick-settings tile, alarms and the background service all go through here.
 *
 * Connections are reference counted: whoever needs the car calls [hold] and
 * later [release]; the link shuts down shortly after the last holder leaves,
 * so the car's Wi-Fi module and the phone's battery are not kept busy.
 *
 * All activation/deactivation happens under [activation], and a discarded
 * connection is closed for good, so two connections can never compete for
 * the car.
 */
class CarRepository(
    private val context: Context,
    val settings: SettingsStore,
    val log: DiagLog,
    private val cache: StateCache,
    private val notifier: Notifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val network = CarNetwork(context, log)

    private val _car = MutableStateFlow(cache.load())
    val car: StateFlow<CarState> = _car.asStateFlow()

    private val _link = MutableStateFlow(LinkUi(LinkPhase.OFFLINE, text = str(R.string.link_not_connected)))
    val link: StateFlow<LinkUi> = _link.asStateFlow()
    private val linkLock = Any()
    /** Builds the current [_link]; kept so the texts can be rebuilt after a language change. */
    private var describeLink: () -> LinkUi = { LinkUi(LinkPhase.OFFLINE, text = str(R.string.link_not_connected)) }
    /** The app language [_link] was built in. */
    private var linkLanguage: String = language()

    private val holderLock = Any()
    private val _holders = MutableStateFlow<Set<String>>(emptySet())
    private val _backgroundWork = MutableStateFlow(false)
    /** True while something other than the open app needs the car. */
    val backgroundWork: StateFlow<Boolean> = _backgroundWork.asStateFlow()

    private val activation = Mutex()
    @Volatile private var direct: PhevConnection? = null
    @Volatile private var bridge: BridgeClient? = null
    @Volatile private var demoCar: FakeCar? = null
    @Volatile private var activeRoute: Route? = null
    private val routeJobs = mutableListOf<Job>()
    private var lingerJob: Job? = null
    private val alerts = AlertEngine()
    private val counter = AtomicInteger()
    /** After the bridge failed to reach the car, prefer the car's Wi-Fi for a while. */
    @Volatile private var preferDirectUntil = 0L

    /** Hook for the widget/tile to refresh when the state changes. */
    var onStateSaved: (() -> Unit)? = null

    /** Outside temperature measured by the car (°C, wall-clock ms of the reading), if one is known. */
    var carTemperature: (() -> Pair<Double, Long>?)? = null

    init {
        log.recordPings = settings.current.recordPings
        network.onAvailable = { direct?.reconnectNow() }
        // Rebuild the link whenever connection-relevant settings change (serialised with activation).
        scope.launch {
            settings.flow.map { listOf(it.ssid, it.password, it.bssid, it.hostOverride, it.route, it.bridgeUrl, it.bridgeToken, it.modelYearOverride, it.syncClock) }
                .distinctUntilChanged()
                .collect {
                    val wasActive = activeRoute != null
                    activation.withLock { deactivateLocked() }
                    if (wasActive) log.link("Connection settings changed — reconnecting")
                    if (isHeld) activate()
                }
        }
        scope.launch {
            settings.flow.map { it.recordPings }.distinctUntilChanged().collect { log.recordPings = it }
        }
        // Persist, alert and refresh widgets as the state changes.
        scope.launch {
            var lastSaved = 0L
            _car.collectLatest { s ->
                val s2 = settings.current
                if (s2.phoneAlerts && _link.value.connected) {
                    notify(synchronized(alerts) { alerts.prefs = s2.alerts; alerts.onState(s, System.currentTimeMillis()) }, s)
                }
                if (System.currentTimeMillis() - lastSaved > 5_000) {
                    delay(800) // coalesce the register burst after connecting
                    cache.save(s)
                    lastSaved = System.currentTimeMillis()
                    onStateSaved?.invoke()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (settings.current.phoneAlerts && _link.value.connected) {
                    notify(synchronized(alerts) { alerts.onTick(System.currentTimeMillis()) }, _car.value)
                }
            }
        }
    }

    private fun notify(list: List<Alert>, state: CarState) {
        val prefs = settings.current.alerts
        list.forEach { notifier.alert(it, state, prefs) }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String {
        val res = AppLocale.localized(context)
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }

    private fun language(): String = AppLocale.localized(context).resources.configuration.locales.toLanguageTags()

    /** Sets the connection status; [describe] is kept to rebuild the texts in another language. */
    private fun setLink(describe: () -> LinkUi) = synchronized(linkLock) {
        describeLink = describe
        linkLanguage = language()
        _link.value = describe()
    }

    /**
     * Rebuilds the status texts if the app language changed since they were made, and
     * redraws the widget and tile. Cheap when nothing changed; called when the app shows.
     */
    fun relocalize() {
        synchronized(linkLock) {
            val lang = language()
            if (lang == linkLanguage) return
            linkLanguage = lang
            _link.value = describeLink()
        }
        onStateSaved?.invoke()
    }

    // ---------------------------------------------------------------- holders

    fun hold(tag: String) {
        val first: Boolean
        synchronized(holderLock) {
            lingerJob?.cancel()
            lingerJob = null
            first = _holders.value.isEmpty()
            _holders.value = _holders.value + tag
            _backgroundWork.value = _holders.value.any { it != UI }
        }
        if (first) scope.launch { activate() }
    }

    fun release(tag: String, lingerMs: Long = 20_000) {
        synchronized(holderLock) {
            _holders.value = _holders.value - tag
            _backgroundWork.value = _holders.value.any { it != UI }
            if (_holders.value.isEmpty()) {
                lingerJob?.cancel()
                lingerJob = scope.launch {
                    delay(lingerMs)
                    activation.withLock { if (!isHeld) deactivateLocked() }
                }
            }
        }
    }

    val isHeld: Boolean get() = _holders.value.isNotEmpty()

    fun isHeldBy(tag: String): Boolean = tag in _holders.value

    /** A route (car Wi-Fi or bridge) is running, connected or trying to connect. */
    val isActive: Boolean get() = activeRoute != null

    /**
     * Turn the in-app simulated car ([DemoMode]) on or off and rebuild the link so the change takes effect.
     * Demo runs only while the app is open (it rides the UI's hold on the link) and is never persisted.
     */
    fun setDemo(on: Boolean) {
        if (DemoMode.active.value == on) return
        DemoMode.set(on)
        log.link(if (on) "Demo mode requested" else "Demo mode off — reconnecting to the real car")
        scope.launch {
            activation.withLock { deactivateLocked() }
            if (isHeld) activate()
        }
    }

    /** Try again right now (user tapped the status chip). */
    fun reconnect() {
        if (network.state.value is CarNetwork.State.Failed) network.retry()
        direct?.reconnectNow()
        if (!isHeld) {
            hold(KICK)
            release(KICK, 60_000)
        }
    }

    /** Ask the car for fresh data without any UI feedback (used by the live-refresh loop while the screen is open). */
    suspend fun refreshQuiet() {
        if (activeRoute == null) return
        runCatching { execute(CarCommand.RequestUpdate, timeoutMs = 15_000, lingerMs = 15_000) }
    }

    // ------------------------------------------------------------- activation

    private suspend fun activate() = activation.withLock {
        if (activeRoute != null || !isHeld) return@withLock
        if (DemoMode.active.value) {
            activeRoute = Route.DIRECT
            startDemo()
            return@withLock
        }
        val s = settings.current
        if (!s.canConnect) {
            setLink { LinkUi(LinkPhase.FAILED, text = str(R.string.link_not_set_up), detail = str(R.string.link_not_set_up_detail)) }
            return@withLock
        }
        val route = chooseRoute(s)
        activeRoute = route
        when (route) {
            Route.BRIDGE -> startBridge(s)
            else -> startDirect(s)
        }
    }

    private suspend fun chooseRoute(s: AppSettings): Route {
        if (s.hostOverride.isNotBlank()) return Route.DIRECT
        return when (s.route) {
            Route.DIRECT -> Route.DIRECT
            Route.BRIDGE -> Route.BRIDGE
            Route.AUTO -> {
                if (!s.hasBridge) return Route.DIRECT
                if (!s.hasCarWifi) return Route.BRIDGE
                if (System.currentTimeMillis() < preferDirectUntil) return Route.DIRECT
                setLink { LinkUi(LinkPhase.CONNECTING, Route.BRIDGE, str(R.string.link_checking_bridge)) }
                val snap = BridgeClient(s.bridgeUrl, s.bridgeToken, log, context).fetchStatus(3_000)
                // Only trust the bridge if it is in touch with the car right now, or checked it
                // recently without errors (the car is at home). Otherwise the car is elsewhere.
                val recent = snap?.car?.updatedAtMs?.let { System.currentTimeMillis() - it < 15 * 60_000 } == true
                val useBridge = snap != null && (snap.link.isConnected || (recent && snap.link is LinkStatus.Idle))
                log.link(
                    "Route: " + when {
                        snap == null -> "bridge unreachable → car Wi-Fi"
                        useBridge -> "bridge is in touch with the car → bridge"
                        else -> "bridge has no fresh contact with the car → car Wi-Fi"
                    }
                )
                if (useBridge) Route.BRIDGE else Route.DIRECT
            }
        }
    }

    private fun startDirect(s: AppSettings) {
        val override = s.hostOverride.takeIf { it.isNotBlank() }
        val connector = if (override != null) {
            val host = override.substringBefore(':')
            val port = override.substringAfter(':', "8080").toIntOrNull() ?: 8080
            log.link("Using test address $host:$port instead of the car")
            TcpConnector(host, port)
        } else {
            network.start(CarNetwork.Target(s.ssid, s.password, s.bssid.ifBlank { null }))
            network.connector(TcpConnector.DEFAULT_HOST, TcpConnector.DEFAULT_PORT)
        }
        val conn = PhevConnection(
            connector = connector,
            log = log,
            scope = scope,
            config = ConnectionConfig(
                session = SessionConfig(
                    syncClockOnConnect = s.syncClock,
                    fallbackModelYear = s.modelYearOverride.takeIf { it != ModelYear.UNKNOWN } ?: ModelYear.MY14,
                    refreshIntervalMs = 2 * 60_000L,
                ),
                connectTimeoutMs = 40_000,
            ),
            initialState = _car.value,
        )
        direct = conn
        routeJobs += scope.launch { conn.car.collect { _car.value = it } }
        routeJobs += scope.launch {
            combine(conn.status, network.state) { a, b -> a to b }.collectLatest { (st, net) ->
                setLink { describeDirect(st, net, override != null) }
                // "Reconnecting in 8 s" and "not found" (after 20 s of searching) count: redraw every second.
                while (st is LinkStatus.Retrying || (net is CarNetwork.State.Searching && st !is LinkStatus.Connected)) {
                    delay(1_000)
                    setLink { describeDirect(st, net, override != null) }
                }
            }
        }
        conn.start()
    }

    /** Demo mode: run a simulated car in this process and connect to it over the loopback, no Wi-Fi involved. */
    private fun startDemo() {
        val fake = FakeCar(modelYear = ModelYear.MY14) { log.link("[demo] $it") }.start()
        demoCar = fake
        log.link("Demo mode on — talking to a simulated car on 127.0.0.1:${fake.port}")
        val conn = PhevConnection(
            connector = TcpConnector("127.0.0.1", fake.port),
            log = log,
            scope = scope,
            config = ConnectionConfig(
                session = SessionConfig(
                    syncClockOnConnect = false,
                    fallbackModelYear = ModelYear.MY14,
                    refreshIntervalMs = 2 * 60_000L,
                ),
                connectTimeoutMs = 40_000,
            ),
            initialState = _car.value,
        )
        direct = conn
        routeJobs += scope.launch { conn.car.collect { _car.value = it } }
        routeJobs += scope.launch {
            conn.status.collect { st -> setLink { describeDirect(st, CarNetwork.State.Idle, test = true) } }
        }
        conn.start()
    }

    private fun startBridge(s: AppSettings) {
        val client = BridgeClient(s.bridgeUrl, s.bridgeToken, log, context)
        bridge = client
        log.link("Using the bridge at ${s.bridgeUrl}")
        setLink { LinkUi(LinkPhase.CONNECTING, Route.BRIDGE, str(R.string.link_connecting_bridge)) }
        client.startEvents(scope)
        routeJobs += scope.launch {
            combine(client.snapshot, client.reachable, client.error) { a, b, c -> Triple(a, b, c) }
                .collect { (snap, reachable, err) ->
                    if (snap != null && snap.car.hasData) _car.value = snap.car
                    setLink { describeBridge(snap, reachable, err) }
                }
        }
        // Ask the bridge to wake its car link so the data is fresh while we look.
        scope.launch { client.post("connect") }
    }

    /** Tear down the current route. Never interrupted halfway, so no connection is left orphaned. */
    private suspend fun deactivateLocked() = withContext(NonCancellable) {
        val route = activeRoute ?: return@withContext
        val d = direct
        val b = bridge
        activeRoute = null
        direct = null
        bridge = null
        routeJobs.forEach { it.cancel() }
        routeJobs.clear()
        if (d != null) {
            d.stop("idle", terminal = true)
            cache.saveDiag(log.dump())
        }
        network.stop()
        demoCar?.let { runCatching { it.stop() } }
        demoCar = null
        b?.stopEvents()
        setLink { LinkUi(LinkPhase.OFFLINE, text = str(R.string.link_not_connected), detail = lastUpdatedText()) }
        cache.save(_car.value)
        onStateSaved?.invoke()
        log.link("Link closed (${route.label})")
    }

    // --------------------------------------------------------------- commands

    /** Run a command, connecting first if needed. */
    suspend fun execute(cmd: CarCommand, timeoutMs: Long = 50_000, lingerMs: Long = 45_000): CommandResult {
        val tag = "cmd-${counter.incrementAndGet()}"
        hold(tag)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            var last: CommandResult? = null
            var triedFallback = false
            while (System.currentTimeMillis() < deadline) {
                // Wait for activation (the route choice may involve a quick bridge check).
                val (d, b) = activation.withLock { direct to bridge }
                if (d == null && b == null) {
                    if (!settings.current.canConnect) return CommandResult.Failure(cmd, str(R.string.link_err_not_set_up))
                    if (activeRoute == null && isHeld) scope.launch { activate() }
                    delay(150)
                    continue
                }
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000)
                val r = if (b != null) b.execute(cmd) else d!!.execute(cmd, remaining)
                last = r
                when {
                    r is CommandResult.Success -> {
                        if (b != null) scope.launch { delay(1500); b.fetchStatus() }
                        return r
                    }
                    // The connection we used was replaced meanwhile: run it on the new one.
                    r is CommandResult.Failure && r.retryable && (direct !== d || bridge !== b) -> continue
                    // The bridge could not reach the car: in Automatic mode try the car's Wi-Fi.
                    b != null && !triedFallback && settings.current.route == Route.AUTO && settings.current.hasCarWifi -> {
                        triedFallback = true
                        log.link("Bridge could not do it (${(r as CommandResult.Failure).reason}) — trying the car's Wi-Fi")
                        preferDirectUntil = System.currentTimeMillis() + 10 * 60_000
                        activation.withLock { deactivateLocked() }
                        continue
                    }
                    else -> return r
                }
            }
            return last ?: CommandResult.Failure(cmd, _link.value.detail ?: str(R.string.link_err_timeout))
        } finally {
            release(tag, lingerMs)
        }
    }

    /** The "one tap" preheat: picks the mode from the weather when smart mode is on. */
    suspend fun quickClimate(): CarCommand.ClimateOn {
        val s = settings.current
        if (!s.quickSmart) return CarCommand.ClimateOn(s.quickMode, s.quickDuration)
        // The car's own sensor when it was read recently (OBD session), otherwise the forecast for the next minutes.
        val measured = carTemperature?.invoke()?.takeIf { System.currentTimeMillis() - it.second < CAR_TEMP_MAX_AGE_MS }
        val temp = if (measured != null) {
            log.link("Quick climate: using the car's outside temperature ${"%.1f".format(measured.first)} °C (read ${(System.currentTimeMillis() - measured.second) / 60_000} min ago)")
            measured.first
        } else {
            MetNo.temperatureNear(context, s, ZonedDateTime.now().plusMinutes(15), log)
        }
        val now = Departure("quick", LocalTime.now(), emptySet(), mode = s.quickMode, duration = s.quickDuration)
        val plan = ClimatePlanner.decide(now, temp) ?: return CarCommand.ClimateOn(s.quickMode, s.quickDuration)
        log.link("Quick climate: ${plan.reason}")
        return CarCommand.ClimateOn(plan.mode, plan.duration)
    }

    /** Climate end time if the (possibly cached) state says it is running. */
    fun climateEndsAt(state: CarState = _car.value): Long? {
        if (state.climate.state != PreAcState.RUNNING) return null
        val started = state.climate.startedAtMs ?: state.updatedAtMs ?: return null
        return started + (state.climate.duration?.minutes ?: 30) * 60_000L
    }

    /**
     * Is the car doing something worth watching? Uses time to discount stale
     * cache: a climate run that should have ended, or charging data from long ago.
     */
    fun busy(state: CarState = _car.value, now: Long = System.currentTimeMillis()): Boolean {
        val climate = climateEndsAt(state)?.let { now < it + 5 * 60_000 } == true
        val fresh = state.updatedAtMs?.let { now - it < 18 * 3600_000L } == true
        val charging = state.charging == true && fresh
        return climate || charging
    }

    fun lastUpdatedText(): String? = _car.value.updatedAtMs?.let { str(R.string.link_last_data, Format.ago(it, context = context)) }

    // ------------------------------------------------------------- describing

    private fun describeDirect(st: LinkStatus, net: CarNetwork.State, test: Boolean): LinkUi {
        val via = Route.DIRECT
        if (!test) when (net) {
            is CarNetwork.State.Failed -> return LinkUi(LinkPhase.FAILED, via, str(R.string.link_cant_join), net.reason)
            is CarNetwork.State.Searching -> if (st !is LinkStatus.Connected) {
                val secs = (System.currentTimeMillis() - net.sinceMs) / 1000
                return LinkUi(
                    LinkPhase.SEARCHING, via, str(R.string.link_searching),
                    if (secs > 20) str(R.string.link_not_found) else lastUpdatedText(),
                )
            }
            else -> Unit
        }
        return when (st) {
            is LinkStatus.Idle -> LinkUi(LinkPhase.OFFLINE, via, str(R.string.link_not_connected), st.lastError ?: lastUpdatedText())
            is LinkStatus.Connecting -> LinkUi(LinkPhase.CONNECTING, via, str(R.string.link_connecting), if (st.attempt > 1) str(R.string.link_attempt, st.attempt) else null)
            LinkStatus.Handshaking -> LinkUi(LinkPhase.HANDSHAKE, via, str(R.string.link_handshake))
            is LinkStatus.Connected -> LinkUi(
                LinkPhase.CONNECTED, via, str(R.string.link_connected),
                str(R.string.link_connected_detail, Labels.modelYear(context, st.modelYear)), st.sinceMs,
            )
            is LinkStatus.Retrying -> LinkUi(
                LinkPhase.RETRYING, via, str(R.string.link_retrying, ((st.retryAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)),
                st.lastError,
            )
        }
    }

    private fun describeBridge(snap: BridgeClient.Snapshot?, reachable: Boolean?, err: String?): LinkUi {
        val via = Route.BRIDGE
        if (reachable == false) return LinkUi(LinkPhase.FAILED, via, str(R.string.link_bridge_unreachable), err)
        if (snap == null) return LinkUi(LinkPhase.CONNECTING, via, str(R.string.link_connecting_bridge))
        return when (val l = snap.link) {
            is LinkStatus.Connected -> LinkUi(LinkPhase.CONNECTED, via, str(R.string.link_via_bridge), str(R.string.link_bridge_live), l.sinceMs)
            is LinkStatus.Idle -> LinkUi(
                LinkPhase.CONNECTED, via, str(R.string.link_bridge_online),
                str(R.string.link_bridge_idle, snap.car.updatedAtMs?.let { Format.ago(it, context = context) } ?: str(R.string.link_never)),
            )
            is LinkStatus.Retrying -> LinkUi(LinkPhase.RETRYING, via, str(R.string.link_bridge_cant_reach), l.lastError)
            else -> LinkUi(LinkPhase.CONNECTING, via, str(R.string.link_bridge_connecting_car))
        }
    }

    companion object {
        const val UI = "ui"
        private const val KICK = "kick"
        /** A car temperature reading older than this is not trusted over the forecast. */
        private const val CAR_TEMP_MAX_AGE_MS = 45 * 60_000L
    }
}
