package io.github.anwo91.phevlink.link

import android.content.Context
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
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.weather.MetNo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

enum class LinkPhase { OFFLINE, SEARCHING, CONNECTING, HANDSHAKE, CONNECTED, RETRYING, FAILED }

/** Connection status as shown to people. */
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

    private val _link = MutableStateFlow(LinkUi(LinkPhase.OFFLINE, text = "Not connected"))
    val link: StateFlow<LinkUi> = _link.asStateFlow()

    private val _holders = MutableStateFlow<Set<String>>(emptySet())
    /** True while something other than the open app needs the car (drives the foreground service). */
    val backgroundWork: StateFlow<Boolean> get() = _backgroundWork
    private val _backgroundWork = MutableStateFlow(false)

    private val activation = Mutex()
    @Volatile private var direct: PhevConnection? = null
    @Volatile private var bridge: BridgeClient? = null
    @Volatile private var activeRoute: Route? = null
    private var routeJobs = mutableListOf<Job>()
    private var lingerJob: Job? = null
    private val alerts = AlertEngine()
    private val counter = AtomicInteger()

    /** Hook for the widget/tile to refresh when the state changes. */
    var onStateSaved: (() -> Unit)? = null

    init {
        log.recordPings = settings.current.recordPings
        network.onAvailable = { direct?.reconnectNow() }
        // Rebuild the link when connection-relevant settings change.
        scope.launch {
            settings.flow.map { listOf(it.ssid, it.password, it.bssid, it.hostOverride, it.route, it.bridgeUrl, it.bridgeToken, it.modelYearOverride, it.syncClock) }
                .distinctUntilChanged()
                .collect {
                    if (activeRoute != null) {
                        log.link("Connection settings changed — reconnecting")
                        activation.withLock { deactivateLocked() }
                        if (_holders.value.isNotEmpty()) activate()
                    } else if (_holders.value.isNotEmpty()) {
                        activate()
                    }
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
                alerts.prefs = s2.alerts
                if (s2.phoneAlerts && _link.value.connected) {
                    for (a in alerts.onState(s, System.currentTimeMillis())) notifier.alert(a)
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
                    for (a in alerts.onTick(System.currentTimeMillis())) notifier.alert(a)
                }
            }
        }
    }

    // ---------------------------------------------------------------- holders

    fun hold(tag: String) {
        lingerJob?.cancel()
        lingerJob = null
        val before = _holders.value
        _holders.value = before + tag
        _backgroundWork.value = _holders.value.any { it != UI }
        if (before.isEmpty()) scope.launch { activate() }
    }

    fun release(tag: String, lingerMs: Long = 20_000) {
        _holders.value = _holders.value - tag
        _backgroundWork.value = _holders.value.any { it != UI }
        if (_holders.value.isEmpty()) {
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(lingerMs)
                if (_holders.value.isEmpty()) activation.withLock { deactivateLocked() }
            }
        }
    }

    val isHeld: Boolean get() = _holders.value.isNotEmpty()

    /** Try again right now (user tapped the status chip). */
    fun reconnect() {
        if (network.state.value is CarNetwork.State.Failed) network.retry()
        direct?.reconnectNow()
        if (_holders.value.isEmpty()) {
            hold(KICK)
            release(KICK, 60_000)
        }
    }

    // ------------------------------------------------------------- activation

    private suspend fun activate() = activation.withLock {
        if (activeRoute != null) return@withLock
        val s = settings.current
        if (!s.canConnect) {
            _link.value = LinkUi(LinkPhase.FAILED, text = "Not set up", detail = "Enter the car's Wi-Fi details in Settings")
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
                _link.value = LinkUi(LinkPhase.CONNECTING, Route.BRIDGE, "Checking bridge…")
                val snap = BridgeClient(s.bridgeUrl, s.bridgeToken, log).fetchStatus(3_000)
                val fresh = snap != null && (snap.link.isConnected ||
                    (snap.car.updatedAtMs?.let { System.currentTimeMillis() - it < 30 * 60_000 } == true))
                // The bridge knows the car (it is at home): use it. Otherwise the car is
                // somewhere else — try its Wi-Fi directly.
                if (fresh) Route.BRIDGE else Route.DIRECT
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
            kotlinx.coroutines.flow.combine(conn.status, network.state) { a, b -> a to b }.collect { (st, net) ->
                _link.value = describeDirect(st, net, override != null)
            }
        }
        conn.start()
    }

    private fun startBridge(s: AppSettings) {
        val client = BridgeClient(s.bridgeUrl, s.bridgeToken, log)
        bridge = client
        log.link("Using the bridge at ${s.bridgeUrl}")
        _link.value = LinkUi(LinkPhase.CONNECTING, Route.BRIDGE, "Connecting to bridge…")
        client.startEvents(scope)
        routeJobs += scope.launch {
            kotlinx.coroutines.flow.combine(client.snapshot, client.reachable, client.error) { a, b, c -> Triple(a, b, c) }
                .collect { (snap, reachable, err) ->
                    if (snap != null) _car.value = mergeBridgeState(snap.car)
                    _link.value = describeBridge(snap, reachable, err)
                }
        }
        // Ask the bridge to wake the car link so the data is fresh while we look.
        scope.launch { client.post("connect") }
    }

    private fun mergeBridgeState(car: CarState): CarState = if (car.hasData) car else _car.value

    private suspend fun deactivateLocked() {
        val route = activeRoute ?: return
        activeRoute = null
        routeJobs.forEach { it.cancel() }
        routeJobs.clear()
        direct?.let { d ->
            d.stop("idle")
            cache.saveDiag(log.dump())
        }
        direct = null
        network.stop()
        bridge?.stopEvents()
        bridge = null
        _link.value = LinkUi(LinkPhase.OFFLINE, text = "Not connected", detail = lastUpdatedText())
        cache.save(_car.value)
        onStateSaved?.invoke()
        log.link("Link closed (${route.label})")
    }

    // --------------------------------------------------------------- commands

    /** Run a command, connecting first if needed. */
    suspend fun execute(cmd: CarCommand, timeoutMs: Long = 50_000, lingerMs: Long = 45_000): CommandResult {
        val tag = "cmd-${counter.incrementAndGet()}"
        hold(tag)
        try {
            // Activation runs asynchronously from hold(); wait for it (route choice may
            // involve a quick bridge check).
            var waited = 0
            while (direct == null && bridge == null && settings.current.canConnect && waited < 8_000) {
                delay(100); waited += 100
            }
            val b = bridge
            val d = direct
            return when {
                b != null -> {
                    val r = b.execute(cmd)
                    if (r is CommandResult.Success) scope.launch { delay(1500); b.fetchStatus() }
                    r
                }
                d != null -> d.execute(cmd, timeoutMs)
                else -> CommandResult.Failure(cmd, _link.value.detail ?: "Not set up")
            }
        } finally {
            release(tag, lingerMs)
        }
    }

    /** The "one tap" preheat: picks the mode from the weather when smart mode is on. */
    suspend fun quickClimate(): CarCommand.ClimateOn {
        val s = settings.current
        if (!s.quickSmart) return CarCommand.ClimateOn(s.quickMode, s.quickDuration)
        val temp = MetNo.temperatureNear(context, s, ZonedDateTime.now().plusMinutes(15), log)
        val now = Departure("quick", LocalTime.now(), emptySet(), mode = s.quickMode, duration = s.quickDuration)
        val plan = ClimatePlanner.decide(now, temp) ?: return CarCommand.ClimateOn(s.quickMode, s.quickDuration)
        log.link("Quick climate: ${plan.reason}")
        return CarCommand.ClimateOn(plan.mode, plan.duration)
    }

    /** Is the car doing something worth watching in the background? */
    fun busy(state: CarState = _car.value): Boolean =
        state.charging == true || state.climate.state == PreAcState.RUNNING

    fun lastUpdatedText(): String? = _car.value.updatedAtMs?.let { "Last data ${Format.ago(it)}" }

    // ------------------------------------------------------------- describing

    private fun describeDirect(st: LinkStatus, net: CarNetwork.State, test: Boolean): LinkUi {
        val via = Route.DIRECT
        if (!test) when (net) {
            is CarNetwork.State.Failed -> return LinkUi(LinkPhase.FAILED, via, "Can't join car Wi-Fi", net.reason)
            is CarNetwork.State.Searching -> if (st !is LinkStatus.Connected) {
                val secs = (System.currentTimeMillis() - net.sinceMs) / 1000
                return LinkUi(
                    LinkPhase.SEARCHING, via, "Looking for the car…",
                    if (secs > 20) "Not found yet — is the car within Wi-Fi range? Its Wi-Fi sleeps after a few days parked." else lastUpdatedText(),
                )
            }
            else -> Unit
        }
        return when (st) {
            is LinkStatus.Idle -> LinkUi(LinkPhase.OFFLINE, via, "Not connected", st.lastError ?: lastUpdatedText())
            is LinkStatus.Connecting -> LinkUi(LinkPhase.CONNECTING, via, "Connecting…", if (st.attempt > 1) "Attempt ${st.attempt}" else null)
            LinkStatus.Handshaking -> LinkUi(LinkPhase.HANDSHAKE, via, "Saying hello to the car…")
            is LinkStatus.Connected -> LinkUi(LinkPhase.CONNECTED, via, "Connected", "Car Wi-Fi · ${st.modelYear.label} protocol", st.sinceMs)
            is LinkStatus.Retrying -> LinkUi(
                LinkPhase.RETRYING, via, "Reconnecting in ${((st.retryAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} s",
                st.lastError,
            )
        }
    }

    private fun describeBridge(snap: BridgeClient.Snapshot?, reachable: Boolean?, err: String?): LinkUi {
        val via = Route.BRIDGE
        if (reachable == false) return LinkUi(LinkPhase.FAILED, via, "Bridge unreachable", err)
        if (snap == null) return LinkUi(LinkPhase.CONNECTING, via, "Connecting to bridge…")
        return when (val l = snap.link) {
            is LinkStatus.Connected -> LinkUi(LinkPhase.CONNECTED, via, "Connected via bridge", "Car link live at the bridge", l.sinceMs)
            is LinkStatus.Idle -> LinkUi(LinkPhase.CONNECTED, via, "Bridge online", "Car checked ${snap.car.updatedAtMs?.let { Format.ago(it) } ?: "never"}")
            is LinkStatus.Retrying -> LinkUi(LinkPhase.RETRYING, via, "Bridge can't reach the car", l.lastError)
            else -> LinkUi(LinkPhase.CONNECTING, via, "Bridge is connecting to the car…")
        }
    }

    companion object {
        const val UI = "ui"
        private const val KICK = "kick"
    }
}
