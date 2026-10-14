package io.github.anwo91.phevlink.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import io.github.anwo91.phevlink.core.emulator.FakeCar

/** [AWAY]: the car isn't within reach — normal, not an error (grey). */
enum class LinkPhase { OFFLINE, SEARCHING, CONNECTING, HANDSHAKE, CONNECTED, RETRYING, FAILED, AWAY }

/** Why the car can't be reached right now; Home shows a banner for it (see [LinkUi.outOfReach]). */
enum class OutOfReach {
    /** The bridge hasn't seen the car's Wi-Fi for a while (away, or just out of its reach). */
    NOT_SEEN,
    /** This phone searched for the car's Wi-Fi and didn't find it. */
    NOT_NEARBY,
    /** On the home network, but the bridge doesn't answer. */
    BRIDGE_DOWN,
    /** Away from home and the bridge can't be reached (no VPN?). */
    HOME_UNREACHABLE,
}

/** Connection status as shown to people (texts in the app language). */
data class LinkUi(
    val phase: LinkPhase,
    val via: Route? = null,
    val text: String,
    val detail: String? = null,
    val sinceMs: Long? = null,
    /** Set when the car can't be reached: Home shows a banner, marks the car data as old and commands answer at once. */
    val outOfReach: OutOfReach? = null,
    /** When the car was last seen (bridge on its Wi-Fi, or last data), for "not seen since 14:32". */
    val lastSeenMs: Long? = null,
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
    /** What the bridge said about reaching the car when the route was last chosen (null = no bridge answer). */
    @Volatile private var lastBridgeReach: BridgeReach? = null
    /** Kept across process restarts: background alarms usually start a fresh process. */
    private val linkPrefs = context.getSharedPreferences("link_state", Context.MODE_PRIVATE)
    /**
     * The car accepted this phone's link again and again without starting a session: the phone isn't registered (or
     * the car's Wi-Fi module is stuck). Until then, while the car is near the bridge, keep off the car's Wi-Fi — every
     * join takes the car's only Wi-Fi slot from the bridge. Field log: an unregistered phone joined the car's Wi-Fi from
     * background checks all night, the bridge stayed off it, and the morning departure failed.
     */
    private var directBlockedUntil: Long
        get() = linkPrefs.getLong(KEY_BLOCKED_UNTIL, 0L)
        set(v) = linkPrefs.edit().putLong(KEY_BLOCKED_UNTIL, v).apply()
    /** How many blocks in a row (each one longer); back to 0 once the car starts a session for this phone. */
    private var noSessionStrikes: Int
        get() = linkPrefs.getInt(KEY_STRIKES, 0)
        set(v) = linkPrefs.edit().putInt(KEY_STRIKES, v).apply()
    private val directBlocked: Boolean get() = System.currentTimeMillis() < directBlockedUntil
    /** Who needs the phone to stay on the home network right now (the bridge setup): no joining the car's Wi-Fi. */
    private val _wifiPause = MutableStateFlow<Set<String>>(emptySet())

    /** Hook for the widget/tile to refresh when the state changes. */
    var onStateSaved: (() -> Unit)? = null

    /** Outside temperature measured by the car (°C, wall-clock ms of the reading), if one is known. */
    var carTemperature: (() -> Pair<Double, Long>?)? = null

    init {
        log.recordPings = settings.current.recordPings
        network.onAvailable = { direct?.reconnectNow() }
        // Rebuild the link whenever connection-relevant settings change (serialised with activation).
        scope.launch {
            settings.flow.map { listOf(it.ssid, it.password, it.bssid, it.testAddress, it.route, it.bridgeUrl, it.bridgeToken, it.modelYearOverride, it.syncClock) }
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
                if (s2.phoneAlerts && _link.value.connected && !bridgeGivesAlerts()) {
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
                if (settings.current.phoneAlerts && _link.value.connected && !bridgeGivesAlerts()) {
                    notify(synchronized(alerts) { alerts.onTick(System.currentTimeMillis()) }, _car.value)
                }
            }
        }
    }

    /** On the bridge route with "alerts from the bridge": the bridge's alerts are shown instead of this phone's own. */
    private fun bridgeGivesAlerts(): Boolean = activeRoute == Route.BRIDGE && settings.current.alertsFromBridge

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

    /**
     * Use the car's Wi-Fi rather than the bridge until [untilMs] (0 = back to normal) and rebuild the link now — while
     * the bridge has stepped off the car's Wi-Fi so this phone can use it (e.g. to register the phone).
     */
    fun preferCarWifi(untilMs: Long, why: String) {
        preferDirectUntil = untilMs
        log.link(if (untilMs > 0) "Route: using the car's Wi-Fi directly until ${Format.clock(untilMs)} ($why)" else "Route: back to normal ($why)")
        scope.launch {
            activation.withLock { deactivateLocked() }
            if (isHeld) activate()
        }
    }

    /** True while something (the bridge setup) needs the phone on the home network, so the app keeps off the car's Wi-Fi. */
    val carWifiPaused: Boolean get() = _wifiPause.value.isNotEmpty()

    /**
     * Keep the app off the car's own Wi-Fi until [resumeCarWifi] with the same [tag]. Joining the car's Wi-Fi takes the
     * phone off the home network on most phones, so e.g. the bridge setup could no longer reach the bridge — and the
     * phone would also occupy the car while the bridge tries to connect to it. A running car-Wi-Fi link is let go at once.
     */
    fun pauseCarWifi(tag: String, why: String) {
        val first = synchronized(holderLock) {
            val wasEmpty = _wifiPause.value.isEmpty()
            _wifiPause.value = _wifiPause.value + tag
            wasEmpty
        }
        if (!first) return
        scope.launch {
            activation.withLock {
                val onCarWifi = activeRoute == Route.DIRECT && demoCar == null && settings.current.testAddress.isBlank()
                log.link("Car Wi-Fi paused: $why" + if (onCarWifi) " — leaving the car's Wi-Fi so the phone returns to the home network" else "")
                if (onCarWifi) deactivateLocked()
            }
            if (isHeld) activate()
        }
    }

    fun resumeCarWifi(tag: String) {
        val last = synchronized(holderLock) {
            if (tag !in _wifiPause.value) return
            _wifiPause.value = _wifiPause.value - tag
            _wifiPause.value.isEmpty()
        }
        if (!last) return
        log.link("Car Wi-Fi allowed again")
        scope.launch { if (isHeld) activate() }
    }

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
        if (directBlocked) {
            // The person asked: give the car's Wi-Fi another chance now (a refusal again blocks it for longer).
            directBlockedUntil = 0L
            log.link("Route: the car's Wi-Fi is allowed again (reconnect tapped)")
            if (activeRoute == Route.BRIDGE) scope.launch {
                activation.withLock { deactivateLocked() }
                if (isHeld) activate()
            }
        }
        if (network.state.value is CarNetwork.State.Failed) network.retry()
        direct?.reconnectNow()
        // Through the bridge: ask it to look for the car now (a fresh scan, and joining if it's there) and wake its link.
        bridge?.let { b ->
            val s = settings.current
            scope.launch {
                log.link("Try again: asking the bridge to look for the car now")
                val r = BridgeSetupApi.rejoinCarWifi(s.bridgeUrl, s.bridgeToken)
                log.link("Try again: bridge says " + (r?.let { "${it.code}: connected=${it.body["connected"]}, in sight=${it.body["inSight"]}" } ?: "nothing"))
                b.post("connect")
            }
        }
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
        if (route == Route.DIRECT && s.testAddress.isBlank() && carWifiPaused) {
            log.link("Route: not joining the car's Wi-Fi while ${_wifiPause.value.joinToString()} needs the home network")
            setLink { LinkUi(LinkPhase.OFFLINE, text = str(R.string.link_paused_setup), detail = lastUpdatedText()) }
            return@withLock
        }
        activeRoute = route
        when (route) {
            Route.BRIDGE -> startBridge(s)
            else -> startDirect(s)
        }
    }

    private suspend fun chooseRoute(s: AppSettings): Route {
        if (s.testAddress.isNotBlank()) {
            log.link("Route: test address ${s.testAddress} (developer options on) — direct to the emulator")
            return Route.DIRECT
        }
        if (s.testAddressIgnored) {
            log.link("Route: ignoring the saved test address ${s.hostOverride.trim()} — developer options are off")
        }
        return when (s.route) {
            Route.DIRECT -> Route.DIRECT
            Route.BRIDGE -> Route.BRIDGE
            Route.AUTO -> {
                if (!s.hasBridge) return Route.DIRECT
                if (!s.hasCarWifi) return Route.BRIDGE
                val now = System.currentTimeMillis()
                if (now < preferDirectUntil) return Route.DIRECT
                val blocked = directBlocked
                setLink { LinkUi(LinkPhase.CONNECTING, Route.BRIDGE, str(R.string.link_checking_bridge)) }
                val snap = BridgeClient(s.bridgeUrl, s.bridgeToken, log, context).fetchStatus(3_000)
                // On the bridge's own network the phone never joins the car's Wi-Fi by itself: the bridge is there for
                // that, and the car's Wi-Fi takes one device at a time (asked for). Away from home it may.
                val atHome = onBridgeNetwork(s, refresh = snap != null)
                fun carWifiUnlessHome(why: String): Route {
                    if (atHome != null) {
                        log.link("Route: $why — but this phone is on the bridge's network ($atHome), so it stays off the car's Wi-Fi → bridge")
                        return Route.BRIDGE
                    }
                    log.link("Route: $why → car Wi-Fi")
                    return Route.DIRECT
                }
                if (snap == null) {
                    lastBridgeReach = null
                    return carWifiUnlessHome("bridge unreachable" + if (blocked) " (the car refused this phone a session lately, but the bridge can't help from here)" else "")
                }
                val reach = snap.reach
                lastBridgeReach = reach
                // Only trust the bridge if it is in touch with the car right now, is on the car's Wi-Fi (bridge with
                // "reach"), or (older bridges) checked it recently without errors. Otherwise the car is elsewhere.
                val recent = snap.car.updatedAtMs?.let { now - it < 15 * 60_000 } == true
                if (snap.link.isConnected || reach?.inReach == true || (reach == null && recent && snap.link is LinkStatus.Idle)) {
                    log.link("Route: bridge is in touch with the car" + (reach?.let { " (reach $it)" } ?: "") + " → bridge")
                    return Route.BRIDGE
                }
                // The bridge looked for the car's Wi-Fi a moment ago and didn't see it: no need to ask it to rejoin.
                val lookedAgo = reach?.checkedAtMs?.let { (snap.bridgeNowMs ?: now) - it }
                if (reach != null && reach.notSeen && lookedAgo != null && lookedAgo < 6 * 60_000) {
                    return carWifiUnlessHome(
                        "the bridge hasn't seen the car since ${reach.lastSeenMs?.let { Format.clockOrDay(it, locale = java.util.Locale.ENGLISH) } ?: "?"} " +
                            "(looked ${lookedAgo / 1000} s ago — away, or just out of its reach)",
                    )
                }
                val wifi = BridgeSetupApi.carWifi(s.bridgeUrl, s.bridgeToken)?.takeIf { it.code == 200 }?.let { BridgeWifi.from(it.body) }
                // The car's Wi-Fi takes one device at a time: when the bridge is on it (the car is home), the phone
                // can't join it anyway — go through the bridge even before it has fresh data.
                if (wifi?.connected == true) {
                    log.link("Route: bridge is on the car's Wi-Fi (the car takes one device at a time) but has no fresh data yet → bridge")
                    return Route.BRIDGE
                }
                if (wifi?.paused == true) {
                    return carWifiUnlessHome("the bridge has stepped off the car's Wi-Fi for a phone until ${Format.clock(wifi.pausedUntilMs ?: 0L)}")
                }
                // The bridge is off the car's Wi-Fi (NetworkManager gives up after failed joins): get it back on before
                // this phone takes the car's only Wi-Fi slot itself.
                var inSight: Boolean? = null
                if (wifi != null && wifi.configured && wifi.iface != null) {
                    setLink { LinkUi(LinkPhase.CONNECTING, Route.BRIDGE, str(R.string.link_bridge_rejoining)) }
                    val t0 = System.currentTimeMillis()
                    val r = BridgeSetupApi.rejoinCarWifi(s.bridgeUrl, s.bridgeToken)
                    val took = (System.currentTimeMillis() - t0) / 1000
                    when {
                        r == null -> log.link("Route: asked the bridge to rejoin the car's Wi-Fi — no answer after $took s")
                        r.code == 404 -> log.link("Route: this bridge can't be asked to rejoin the car's Wi-Fi yet (update the bridge)")
                        r.code != 200 -> log.link("Route: bridge rejoin refused (${r.code}: ${r.body["error"] ?: "?"})")
                        else -> {
                            inSight = r.body["inSight"] == true
                            if (r.body["connected"] == true) {
                                log.link("Route: the bridge got back on the car's Wi-Fi ($took s) → bridge")
                                return Route.BRIDGE
                            }
                            log.link(
                                "Route: the bridge couldn't get back on the car's Wi-Fi in $took s (${r.body["message"] ?: "?"}); the car's network is " +
                                    if (inSight == true) "in its sight at ${r.body["signal"]} %" else "not in its sight"
                            )
                        }
                    }
                }
                if (blocked && inSight != false) {
                    log.link("Route: the car refused this phone a session lately (not registered?) and may be next to the bridge → bridge until ${Format.clock(directBlockedUntil)}, keeping off the car's Wi-Fi")
                    return Route.BRIDGE
                }
                carWifiUnlessHome("bridge has no contact with the car" + if (inSight == false) " and can't see it (the car is away)" else "")
            }
        }
    }

    private fun startDirect(s: AppSettings) {
        val override = s.testAddress.takeIf { it.isNotBlank() }
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
            combine(conn.status, network.state, conn.noSessions) { a, b, c -> Triple(a, b, c) }.collectLatest { (st, net, ns) ->
                setLink { describeDirect(st, net, override != null, ns) }
                // "Reconnecting in 8 s" and "not found" (after 20 s of searching) count: redraw every second.
                while (st is LinkStatus.Retrying || (net is CarNetwork.State.Searching && st !is LinkStatus.Connected)) {
                    delay(1_000)
                    setLink { describeDirect(st, net, override != null, ns) }
                }
            }
        }
        if (override == null) {
            routeJobs += scope.launch {
                val n = conn.noSessions.first { it >= NO_SESSION_LIMIT }
                onNoSessions(conn, n)
            }
            routeJobs += scope.launch {
                conn.status.first { it is LinkStatus.Connected }
                if (noSessionStrikes > 0 || directBlockedUntil > 0) {
                    noSessionStrikes = 0
                    directBlockedUntil = 0L
                    log.link("Car Wi-Fi: the car started a session for this phone — no longer keeping off the car's Wi-Fi")
                }
            }
        }
        conn.start()
    }

    /**
     * The car accepted this phone's link [n] times in a row without starting a session. With a bridge (Automatic), leave
     * the car's Wi-Fi to the bridge for an hour, then 2, 4, 8 hours if it happens again.
     */
    private fun onNoSessions(conn: PhevConnection, n: Int) {
        val s = settings.current
        if (DemoMode.active.value) return
        log.warn("Car Wi-Fi: the car accepted the link $n times in a row without starting a session — this phone may not be registered with the car (Settings → Phone registration)")
        if (!s.hasBridge || s.route != Route.AUTO) return
        val strikes = noSessionStrikes + 1
        val minutes = (60L shl (strikes - 1).coerceAtMost(3))
        noSessionStrikes = strikes
        directBlockedUntil = System.currentTimeMillis() + minutes * 60_000
        log.link(
            "Route: leaving the car's Wi-Fi to the bridge while the car is near it, until ${Format.clock(directBlockedUntil)} " +
                "(${minutes / 60} h, time $strikes) — every join takes the car's only Wi-Fi slot from the bridge"
        )
        scope.launch {
            activation.withLock { if (direct === conn) deactivateLocked() }
            if (isHeld) activate()
        }
    }

    /** Demo mode: run a simulated car in this process and connect to it over the loopback, no Wi-Fi involved. */
    private fun startDemo() {
        val fake = FakeCar(modelYear = ModelYear.MY14) { log.link("[demo] $it") }.apply {
            // The demo story: parked at home, plugged in and charging, slowly enough to watch it climb
            // (one charge minute every 4 s: 64 % → full in about 8 minutes).
            minutesPerSecond = DEMO_MINUTES_PER_SECOND
            plugIn(charge = true)
        }.start()
        demoCar = fake
        log.link("Demo mode on — talking to a simulated car on 127.0.0.1:${fake.port} (plugged in, charging from ${fake.batteryPercent} %)")
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
            var lastReach: String? = null
            combine(client.snapshot, client.reachable, client.error) { a, b, c -> Triple(a, b, c) }
                .collect { (snap, reachable, err) ->
                    if (snap != null && snap.car.hasData) _car.value = snap.car
                    snap?.reach?.let { r ->
                        if (r.state != lastReach) log.link("Reach (from the bridge): ${lastReach ?: "–"} → $r")
                        lastReach = r.state
                    }
                    if (snap != null) {
                        lastBridgeReach = snap.reach
                        // New alerts from the bridge show at once while the app is open.
                        if (snap.alerts.isNotEmpty()) BridgeAlerts.deliver(context, snap)
                    }
                    // Only matters when the bridge doesn't answer: "bridge down" at home vs. "can't reach home" away.
                    val atHome = if (reachable == false) onBridgeNetwork(s, refresh = false) != null else null
                    setLink { describeBridge(snap, reachable, err, atHome) }
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
    /** Told about every command this phone ran straight over the car's Wi-Fi (the bridge logs its own). */
    @Volatile var onDirectCommand: ((CarCommand, CommandResult) -> Unit)? = null

    /**
     * @param nearbyMs over the car's Wi-Fi: give up after this long if the car hasn't been found ("not nearby") instead
     *   of waiting out [timeoutMs]; null = be patient (a scheduled departure, where the car's Wi-Fi may need waking).
     */
    suspend fun execute(cmd: CarCommand, timeoutMs: Long = 50_000, lingerMs: Long = 45_000, nearbyMs: Long? = NEARBY_COMMAND_MS): CommandResult {
        var direct = false
        val r = executeRouted(cmd, timeoutMs, lingerMs, nearbyMs) { direct = it }
        if (direct) runCatching { onDirectCommand?.invoke(cmd, r) }
        return r
    }

    private suspend fun executeRouted(cmd: CarCommand, timeoutMs: Long, lingerMs: Long, nearbyMs: Long?, usedDirect: (Boolean) -> Unit): CommandResult {
        val tag = "cmd-${counter.incrementAndGet()}"
        hold(tag)
        var deadline = System.currentTimeMillis() + timeoutMs
        // Choosing the route may take a while (getting the bridge back on the car's Wi-Fi: up to ~35 s); that time
        // doesn't count against the command, up to a limit.
        var routeGrace = ROUTE_GRACE_MS
        try {
            var last: CommandResult? = null
            var triedFallback = false
            while (System.currentTimeMillis() < deadline) {
                // Wait for activation (the route choice may involve a quick bridge check).
                val t0 = System.currentTimeMillis()
                val (d, b) = activation.withLock { direct to bridge }
                val waited = System.currentTimeMillis() - t0
                if (waited > 1_000 && routeGrace > 0) {
                    val extra = minOf(waited, routeGrace)
                    deadline += extra
                    routeGrace -= extra
                }
                if (d == null && b == null) {
                    if (!settings.current.canConnect) return CommandResult.Failure(cmd, str(R.string.link_err_not_set_up))
                    if (activeRoute == null && isHeld) scope.launch { activate() }
                    delay(150)
                    continue
                }
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000)
                usedDirect(b == null)
                val giveUpAfter = nearbyMs?.takeIf { settings.current.testAddress.isBlank() && !DemoMode.active.value }
                val r = if (b != null) b.execute(cmd) else d!!.execute(
                    cmd, remaining, connectWithinMs = giveUpAfter,
                    // Only "not nearby" while the phone hasn't even found the car's Wi-Fi.
                    stillLooking = { network.state.value.let { it is CarNetwork.State.Searching || (it is CarNetwork.State.Failed && it.notFound) } },
                )
                last = r
                when {
                    r is CommandResult.Success -> {
                        if (b != null) scope.launch { delay(1500); b.fetchStatus() }
                        return r
                    }
                    // The connection we used was replaced meanwhile: run it on the new one.
                    r is CommandResult.Failure && r.retryable && (direct !== d || bridge !== b) -> continue
                    // The bridge could not reach the car: in Automatic mode try the car's Wi-Fi (not while the car keeps
                    // refusing this phone a session — that would only take the car's Wi-Fi from the bridge).
                    b != null && !triedFallback && settings.current.route == Route.AUTO && settings.current.hasCarWifi && !directBlocked &&
                        onBridgeNetwork(settings.current, refresh = false).also { home ->
                            if (home != null) log.link("Bridge could not do it (${(r as CommandResult.Failure).reason}) — not trying the car's Wi-Fi: this phone is on the bridge's network ($home)")
                        } == null -> {
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

    /**
     * Car data from the bridge's background check (the app may be closed): taken when newer than what's shown, and
     * saved so the widget and tile show it. A live link of this phone's own has fresher data and wins.
     */
    fun takeBridgeSnapshot(snap: BridgeClient.Snapshot) {
        lastBridgeReach = snap.reach
        if (!snap.car.hasData || activeRoute == Route.DIRECT) return
        if ((snap.car.updatedAtMs ?: 0L) <= (_car.value.updatedAtMs ?: 0L)) return
        _car.value = snap.car
        cache.save(snap.car)
        onStateSaved?.invoke()
    }

    /**
     * For a departure that's due: the bridge hasn't seen the car for a while and this phone has nothing else to try
     * (it's on the bridge's network, or the bridge is the only route) — the bridge's reach, else null (go ahead).
     */
    suspend fun notWithinReach(): BridgeReach? {
        val s = settings.current
        if (!s.hasBridge || s.route == Route.DIRECT || DemoMode.active.value) return null
        val snap = BridgeClient(s.bridgeUrl, s.bridgeToken, log, context).fetchStatus(4_000) ?: return null
        lastBridgeReach = snap.reach
        val reach = snap.reach?.takeIf { it.notSeen } ?: return null
        if (s.route == Route.AUTO && s.hasCarWifi && onBridgeNetwork(s, refresh = false) == null) {
            log.link("Departure: the bridge hasn't seen the car ($reach), but this phone is away from home and may be near it — trying")
            return null
        }
        // Before giving up on a departure, have the bridge look once more right now (a fresh scan; joins if the car is there).
        val r = BridgeSetupApi.rejoinCarWifi(s.bridgeUrl, s.bridgeToken)
        val body = r?.takeIf { it.code == 200 }?.body
        if (body != null && (body["connected"] == true || body["inSight"] == true)) {
            log.link("Departure: the bridge looked again and " + if (body["connected"] == true) "is back on the car's Wi-Fi — going ahead" else "sees the car — going ahead")
            lastBridgeReach = null
            return null
        }
        log.link("Departure: the bridge looked again — " + (if (r != null) "still not in sight" else "no answer to the fresh look"))
        return reach
    }

    /** The last word from the bridge was that it doesn't see the car (for telling a failure from "not within reach"). */
    val bridgeSaysNotSeen: Boolean get() = lastBridgeReach?.notSeen == true

    fun lastUpdatedText(): String? = _car.value.updatedAtMs?.let { str(R.string.link_last_data, Format.ago(it, context = context)) }

    /** "Not seen since 14:32. It may be away, or just out of the bridge's reach — it reconnects by itself." */
    private fun notSeenLine(seenMs: Long?): String =
        if (seenMs != null) str(R.string.reach_not_seen_line, Format.clockOrDay(seenMs)) else str(R.string.reach_not_seen_line_never)

    // ------------------------------------------------------------ home network

    /**
     * Is this phone on the bridge's own network right now (home Wi-Fi or Ethernet, not a VPN, not the car's Wi-Fi)?
     * Then the app never joins the car's Wi-Fi by itself. Compares the phone's network with the bridge's own addresses
     * (asked from the bridge now and then and remembered, so it also works while the bridge is down; the bridge's car
     * Wi-Fi radio left out). Null = not at home, or not known; else a short description for the log.
     */
    private suspend fun onBridgeNetwork(s: AppSettings, refresh: Boolean): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // getAllNetworks: the simple way to list networks; nothing to register for.
        val mine = cm.allNetworks.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lan = !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // the car's Wi-Fi has no internet
            if (!lan) null else cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        }
        if (mine.isEmpty()) return null
        val bridgeIps = bridgeLanAddresses(s, refresh)
        for (la in mine) for (ip in bridgeIps) {
            if (sameSubnet(la, ip)) return "${la.address.hostAddress}/${la.prefixLength}, bridge at $ip"
        }
        return null
    }

    /** The bridge's own IPv4 addresses except its car Wi-Fi radio (remembered; asked again every few hours). */
    private suspend fun bridgeLanAddresses(s: AppSettings, refresh: Boolean): List<String> {
        val urlHost = runCatching { java.net.URI(s.bridgeUrl).host }.getOrNull()?.takeIf { IPV4.matches(it) }
        val cachedFor = linkPrefs.getString(KEY_LAN_URL, null)
        var cached = if (cachedFor == s.bridgeUrl) linkPrefs.getString(KEY_LAN_IPS, null)?.split(',')?.filter { it.isNotBlank() } else null
        val age = System.currentTimeMillis() - linkPrefs.getLong(KEY_LAN_AT, 0L)
        if (refresh && (cached == null || age > LAN_REFRESH_MS)) {
            val sys = BridgeSetupApi.system(s.bridgeUrl, s.bridgeToken)?.takeIf { it.code == 200 }?.body
            val carIface = BridgeSetupApi.carWifi(s.bridgeUrl, s.bridgeToken)?.takeIf { it.code == 200 }?.body?.get("iface")?.toString()
            @Suppress("UNCHECKED_CAST")
            val all = sys?.get("addresses") as? Map<String, Any?>
            if (all != null) {
                val lan = all.filterKeys { it != carIface }.values.mapNotNull { v -> v?.toString()?.takeIf { IPV4.matches(it) } }
                linkPrefs.edit().putString(KEY_LAN_URL, s.bridgeUrl).putString(KEY_LAN_IPS, lan.joinToString(","))
                    .putLong(KEY_LAN_AT, System.currentTimeMillis()).apply()
                if (lan != cached) log.link("Bridge addresses on its networks: ${lan.joinToString().ifEmpty { "none" }}" + (carIface?.let { " (car Wi-Fi on $it left out)" } ?: ""))
                cached = lan
            }
        }
        return (listOfNotNull(urlHost) + cached.orEmpty()).distinct()
    }

    private fun sameSubnet(la: LinkAddress, ip: String): Boolean {
        val mine = (la.address as? Inet4Address)?.address?.map { it.toInt() and 0xff } ?: return false
        val other = ip.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return false
        val bits = la.prefixLength.coerceIn(8, 32)
        fun v(b: List<Int>) = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (v(mine) and mask) == (v(other) and mask)
    }

    // ------------------------------------------------------------- describing

    private fun describeDirect(st: LinkStatus, net: CarNetwork.State, test: Boolean, noSessions: Int = 0): LinkUi {
        val via = Route.DIRECT
        val notRegistered = !test && noSessions >= NO_SESSION_LIMIT
        if (!test) when (net) {
            // Android gave up joining while the bridge doesn't see the car either: it's not nearby, not a wrong password.
            is CarNetwork.State.Failed -> return if (net.notFound && lastBridgeReach?.notSeen == true) {
                LinkUi(LinkPhase.AWAY, via, str(R.string.reach_not_nearby_chip), str(R.string.reach_not_nearby_line), outOfReach = OutOfReach.NOT_NEARBY, lastSeenMs = lastBridgeReach?.lastSeenMs)
            } else {
                LinkUi(LinkPhase.FAILED, via, str(R.string.link_cant_join), net.reason)
            }
            is CarNetwork.State.Searching -> if (st !is LinkStatus.Connected) {
                val searched = System.currentTimeMillis() - net.sinceMs
                // Searched long enough: the car isn't nearby (it keeps looking while the app needs the car).
                if (searched > NEARBY_SEARCH_MS) {
                    val seen = _car.value.updatedAtMs
                    return LinkUi(
                        LinkPhase.AWAY, via, str(R.string.reach_not_nearby_chip),
                        str(R.string.reach_not_nearby_line), outOfReach = OutOfReach.NOT_NEARBY, lastSeenMs = seen,
                    )
                }
                return LinkUi(LinkPhase.SEARCHING, via, str(R.string.link_searching), lastUpdatedText())
            }
            else -> Unit
        }
        return when (st) {
            is LinkStatus.Idle -> LinkUi(
                LinkPhase.OFFLINE, via, str(R.string.link_not_connected),
                if (notRegistered) str(R.string.link_no_session_hint) else st.lastError ?: lastUpdatedText(),
            )
            is LinkStatus.Connecting -> LinkUi(LinkPhase.CONNECTING, via, str(R.string.link_connecting), if (st.attempt > 1) str(R.string.link_attempt, st.attempt) else null)
            LinkStatus.Handshaking -> LinkUi(LinkPhase.HANDSHAKE, via, str(R.string.link_handshake))
            is LinkStatus.Connected -> LinkUi(
                LinkPhase.CONNECTED, via, str(R.string.link_connected),
                str(R.string.link_connected_detail, Labels.modelYear(context, st.modelYear)), st.sinceMs,
            )
            is LinkStatus.Retrying -> LinkUi(
                LinkPhase.RETRYING, via, str(R.string.link_retrying, ((st.retryAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)),
                if (notRegistered) str(R.string.link_no_session_hint) else st.lastError,
            )
        }
    }

    private fun describeBridge(snap: BridgeClient.Snapshot?, reachable: Boolean?, err: String?, atHome: Boolean? = null): LinkUi {
        val via = Route.BRIDGE
        if (reachable == false) {
            return if (atHome == true) {
                LinkUi(LinkPhase.FAILED, via, str(R.string.reach_bridge_down_chip), str(R.string.reach_bridge_down_line), outOfReach = OutOfReach.BRIDGE_DOWN)
            } else {
                LinkUi(LinkPhase.FAILED, via, str(R.string.reach_home_unreachable_chip), str(R.string.reach_home_unreachable_line), outOfReach = OutOfReach.HOME_UNREACHABLE)
            }
        }
        if (snap == null) return LinkUi(LinkPhase.CONNECTING, via, str(R.string.link_connecting_bridge))
        val reach = snap.reach
        // The bridge hasn't seen the car for a while: say that plainly instead of "can't reach" or a green "online".
        if (reach != null && reach.notSeen && !snap.link.isConnected) {
            val seen = reach.lastSeenMs ?: snap.car.updatedAtMs
            return LinkUi(LinkPhase.AWAY, via, str(R.string.reach_not_seen_chip), notSeenLine(seen), outOfReach = OutOfReach.NOT_SEEN, lastSeenMs = seen)
        }
        // Kept off the car's Wi-Fi (the car refused this phone a session): say so while the bridge can't reach the car.
        if (directBlocked && snap.link is LinkStatus.Retrying && settings.current.route == Route.AUTO) {
            return LinkUi(LinkPhase.RETRYING, via, str(R.string.link_bridge_cant_reach), str(R.string.link_bridge_only))
        }
        return when (val l = snap.link) {
            is LinkStatus.Connected -> LinkUi(LinkPhase.CONNECTED, via, str(R.string.link_via_bridge), str(R.string.link_bridge_live), l.sinceMs)
            is LinkStatus.Idle -> {
                val checked = str(R.string.link_bridge_idle, snap.car.updatedAtMs?.let { Format.ago(it, context = context) } ?: str(R.string.link_never))
                val recent = snap.car.updatedAtMs?.let { System.currentTimeMillis() - it < 15 * 60_000 } == true
                when {
                    // Between its checks the bridge lets the car link rest: only green when the car is known to be in reach.
                    reach?.inReach == true || (reach == null && recent) -> LinkUi(LinkPhase.CONNECTED, via, str(R.string.link_bridge_online), checked)
                    reach?.state == BridgeReach.CHECKING &&
                        reach?.sinceMs?.let { (snap.bridgeNowMs ?: System.currentTimeMillis()) - it < CHECKING_SHOWN_MS } != false ->
                        LinkUi(LinkPhase.CONNECTING, via, str(R.string.reach_checking_chip), checked)
                    else -> LinkUi(LinkPhase.OFFLINE, via, str(R.string.link_bridge_online), checked)
                }
            }
            // Off the car's Wi-Fi for a moment (it may be back any second): checking, not a fault.
            is LinkStatus.Retrying -> if (reach?.state == BridgeReach.CHECKING && l.lastError == io.github.anwo91.phevlink.core.session.Unreachable.OFF_CAR_WIFI &&
                reach?.sinceMs?.let { (snap.bridgeNowMs ?: System.currentTimeMillis()) - it < CHECKING_SHOWN_MS } != false
            ) {
                LinkUi(LinkPhase.CONNECTING, via, str(R.string.reach_checking_chip), str(R.string.reach_checking_line))
            } else {
                LinkUi(LinkPhase.RETRYING, via, str(R.string.link_bridge_cant_reach), Labels.failureReason(context, l.lastError))
            }
            else -> LinkUi(LinkPhase.CONNECTING, via, str(R.string.link_bridge_connecting_car))
        }
    }

    companion object {
        const val UI = "ui"
        private const val KICK = "kick"
        /** A car temperature reading older than this is not trusted over the forecast. */
        private const val CAR_TEMP_MAX_AGE_MS = 45 * 60_000L
        /** Demo car's simulated minutes per real second (charging and climate countdowns). */
        private const val DEMO_MINUTES_PER_SECOND = 0.25
        /** This many link closes in a row without a session: the phone is probably not registered with the car. */
        private const val NO_SESSION_LIMIT = 2
        /** This long searching for the car's Wi-Fi without finding it = the car isn't nearby (said on screen; a tapped command gives up). */
        const val NEARBY_SEARCH_MS = 25_000L
        /** "Checking the car…" is shown this long at most; after that the bridge's actual trouble is shown. */
        private const val CHECKING_SHOWN_MS = 5 * 60_000L
        /** A tapped command over the car's Wi-Fi gives up after this long when the car isn't found. */
        const val NEARBY_COMMAND_MS = 30_000L
        /** Time spent choosing the route that doesn't count against a command's timeout. */
        private const val ROUTE_GRACE_MS = 45_000L
        private const val KEY_BLOCKED_UNTIL = "direct_blocked_until"
        private const val KEY_STRIKES = "no_session_strikes"
        private const val KEY_LAN_URL = "bridge_lan_url"
        private const val KEY_LAN_IPS = "bridge_lan_ips"
        private const val KEY_LAN_AT = "bridge_lan_at"
        private const val LAN_REFRESH_MS = 6 * 3600_000L
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
