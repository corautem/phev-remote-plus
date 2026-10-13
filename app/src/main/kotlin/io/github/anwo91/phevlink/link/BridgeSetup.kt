package io.github.anwo91.phevlink.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.json.obj
import io.github.anwo91.phevlink.core.json.str
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.data.Route
import io.github.anwo91.phevlink.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** A PHEV Link bridge answering on the home network. [needsUpdate]: an older bridge without the setup features. */
data class FoundBridge(
    val url: String,
    val name: String,
    val version: String,
    val features: List<String>,
    val emulator: Boolean,
    val pairingOpen: Boolean,
    val pairingReason: String?,
    val needsUpdate: Boolean,
    /** Release the bridge was built in ("1.0.40"); null for bridges from before in-app updates. */
    val build: String? = null,
) {
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: url
}

/** The bridge's own link to the car's Wi-Fi, as reported by GET /api/carwifi. */
data class BridgeWifi(
    val problem: String?,
    val iface: String?,
    val homeIface: String?,
    val configured: Boolean,
    val ssid: String?,
    val connected: Boolean,
    val ip: String?,
    val mac: String?,
    val signal: Int?,
    /** Until when the bridge stays off the car's Wi-Fi so a phone can use it; null/0 = not paused (bridge 1.2+). */
    val pausedUntilMs: Long? = null,
) {
    val paused: Boolean get() = (pausedUntilMs ?: 0L) > System.currentTimeMillis()

    companion object {
        fun from(m: Map<String, Any?>) = BridgeWifi(
            problem = m.str("problem"), iface = m.str("iface"), homeIface = m.str("homeIface"),
            configured = m["configured"] == true, ssid = m.str("ssid"), connected = m["connected"] == true,
            ip = m.str("ip"), mac = m.str("mac"), signal = (m["signal"] as? Number)?.toInt(),
            pausedUntilMs = (m["pausedUntilMs"] as? Number)?.toLong()?.takeIf { it > 0 },
        )
    }
}

/** Plain HTTP calls to a bridge for setup (no token needed for hello/pair). */
object BridgeSetupApi {
    const val PORT = 8099

    /** This phone's name in the bridge's activity log (set by the app: the name from Settings, else the model). */
    @Volatile var deviceName: () -> String = { Build.MODEL ?: "Phone" }

    /** [deviceName] for the X-PHEV-Device header (URL-encoded: names may have æøå). */
    fun deviceHeader(): String = java.net.URLEncoder.encode(deviceName().take(40), "UTF-8")

    data class Reply(val code: Int, val body: Map<String, Any?>)

    /** Who answers at [url]; null when it isn't a PHEV Link bridge (or nobody answers). */
    suspend fun hello(url: String, connectMs: Int = 2_500, readMs: Int = 4_000, network: Network? = null): FoundBridge? = withContext(Dispatchers.IO) {
        val r = request(url, "hello", "GET", null, null, connectMs, readMs, network = network) ?: return@withContext null
        when {
            r.code == 200 && r.body["app"] == "phev-bridge" -> {
                val p = r.body.obj("pairing")
                FoundBridge(
                    url = url,
                    name = r.body.str("name") ?: "phev-bridge",
                    version = r.body.str("version") ?: "?",
                    features = (r.body["features"] as? List<*>)?.map { it.toString() }.orEmpty(),
                    emulator = r.body["emulator"] == true,
                    pairingOpen = p?.get("open") == true,
                    pairingReason = p?.str("reason"),
                    needsUpdate = false,
                    build = r.body.str("build"),
                )
            }
            // A bridge from before the setup features: every /api call wants the token.
            r.code == 401 && r.body.str("error")?.contains("token", ignoreCase = true) == true ->
                FoundBridge(url, url.substringAfter("://").substringBefore(':'), "1.0", emptyList(), false, false, null, needsUpdate = true)
            else -> null
        }
    }

    /** Re-plug pairing: the token, or null with the bridge's reason ("used", "expired", …) in the second value. */
    suspend fun pair(url: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val r = request(url, "pair", "POST", null, emptyMap(), 4_000, 8_000, mapOf("X-PHEV-Pair" to "1"))
            ?: return@withContext null to "unreachable"
        if (r.code == 200) (r.body.str("token")?.takeIf { it.isNotBlank() } to null)
        else null to (r.body.str("reason") ?: r.body.str("error") ?: "HTTP ${r.code}")
    }

    suspend fun carWifi(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "carwifi", "GET", token, null, 4_000, 15_000) }

    suspend fun scan(url: String, token: String, country: String?): Reply? =
        withContext(Dispatchers.IO) { request(url, "carwifi/scan", "POST", token, mapOf("country" to country), 4_000, 60_000) }

    suspend fun join(url: String, token: String, ssid: String, password: String, country: String?): Reply? =
        withContext(Dispatchers.IO) {
            request(url, "carwifi/join", "POST", token, mapOf("ssid" to ssid, "password" to password, "country" to country), 4_000, 120_000)
        }

    suspend fun leaveTestMode(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "config", "POST", token, mapOf("emulator" to false), 4_000, 10_000) }

    // ---- bridge page (status, log, actions, update) ----

    /** The computer: model, OS, uptime, temperature, memory, disk, addresses, bridge build (bridge 1.2+). */
    suspend fun system(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "system", "GET", token, null, 4_000, 8_000) }

    /** Installed/newest version, a running update and its log; [check] asks GitHub now instead of the cached answer. */
    suspend fun updateStatus(url: String, token: String, check: Boolean = false): Reply? =
        withContext(Dispatchers.IO) { request(url, "update" + if (check) "?check=1" else "", "GET", token, null, 4_000, 20_000) }

    suspend fun startUpdate(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "update", "POST", token, emptyMap(), 4_000, 40_000) }

    suspend fun restart(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "restart", "POST", token, emptyMap(), 4_000, 8_000) }

    suspend fun reboot(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "reboot", "POST", token, emptyMap(), 4_000, 20_000) }

    /** Bridge leaves the car's Wi-Fi for [minutes] so a phone can use it (the car takes one device at a time). */
    suspend fun pauseCarWifi(url: String, token: String, minutes: Int): Reply? =
        withContext(Dispatchers.IO) { request(url, "carwifi/pause", "POST", token, mapOf("minutes" to minutes), 4_000, 30_000) }

    suspend fun resumeCarWifi(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "carwifi/resume", "POST", token, emptyMap(), 4_000, 50_000) }

    /**
     * Bridge off the car's Wi-Fi: ask it to join again now, before this phone takes the car's Wi-Fi itself (bridge with
     * "carwifi-rejoin"; 404 from older ones). Reply: connected, tried, message, inSight, signal.
     */
    suspend fun rejoinCarWifi(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "carwifi/rejoin", "POST", token, emptyMap(), 4_000, 50_000) }

    /** The smart-home link (Home Assistant over MQTT): settings, status (bridge with "smarthome"). */
    suspend fun mqtt(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "mqtt", "GET", token, null, 4_000, 8_000) }

    /** Saves smart-home settings; the bridge answers once it is connected or has failed (a few seconds). */
    suspend fun putMqtt(url: String, token: String, changes: Map<String, Any?>): Reply? =
        withContext(Dispatchers.IO) { request(url, "mqtt", "POST", token, changes, 4_000, 25_000) }

    /** MQTT brokers on the bridge's home network (a few seconds). */
    suspend fun findMqtt(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "mqtt/find", "POST", token, emptyMap(), 4_000, 45_000) }

    /** Sets up an MQTT broker on the bridge computer (in the background; follow it with [mqtt]). */
    suspend fun localMqtt(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "mqtt/local", "POST", token, emptyMap(), 4_000, 10_000) }

    /** "Add another phone": the bridge opens pairing for one more phone for [minutes] (bridge with "pair-invite"). */
    suspend fun openPairing(url: String, token: String, minutes: Int): Reply? =
        withContext(Dispatchers.IO) { request(url, "pair/open", "POST", token, mapOf("minutes" to minutes), 4_000, 8_000) }

    /** What all phones share (departures, car name, …) with "rev" and "lastDeparture" (bridge with "shared"). */
    suspend fun shared(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "shared", "GET", token, null, 4_000, 8_000) }

    /** Saves [changes] (only those keys) on the bridge; the reply's "shared" is the result. */
    suspend fun putShared(url: String, token: String, changes: Map<String, Any?>): Reply? =
        withContext(Dispatchers.IO) {
            request(url, "shared", "POST", token, changes, 4_000, 8_000)
        }

    /** The bridge's activity log, newest first (bridge with "activity"). */
    suspend fun activity(url: String, token: String, limit: Int = 300, beforeMs: Long? = null): Reply? =
        withContext(Dispatchers.IO) {
            request(url, "activity?limit=$limit" + (beforeMs?.let { "&before=$it" } ?: ""), "GET", token, null, 4_000, 10_000)
        }

    /** Hands the bridge what this phone did straight over the car's Wi-Fi. */
    suspend fun postActivity(url: String, token: String, entries: List<Map<String, Any?>>): Reply? =
        withContext(Dispatchers.IO) { request(url, "activity", "POST", token, mapOf("entries" to entries), 4_000, 10_000) }

    suspend fun connect(url: String, token: String): Reply? =
        withContext(Dispatchers.IO) { request(url, "connect", "POST", token, emptyMap(), 4_000, 10_000) }

    /** The bridge's own log, last [n] lines (plain text), or null when it doesn't answer. */
    suspend fun log(url: String, token: String, n: Int = 200): String? = withContext(Dispatchers.IO) {
        try {
            val c = URI(url.trimEnd('/') + "/api/log?n=$n").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 4_000
            c.readTimeout = 8_000
            c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            val text = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            c.disconnect()
            text
        } catch (e: Exception) {
            null
        }
    }

    private fun request(
        url: String, path: String, method: String, token: String?, body: Map<String, Any?>?,
        connectMs: Int, readMs: Int, headers: Map<String, String> = emptyMap(), network: Network? = null,
    ): Reply? = try {
        val u = URI(url.trimEnd('/') + "/api/" + path).toURL()
        // Over a given network (discovery: the home Wi-Fi even when a VPN is the default), else normal routing.
        val c = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.setRequestProperty("X-PHEV-Device", deviceHeader())
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(Json.write(body).toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        Reply(code, runCatching { Json.parseObject(text) }.getOrDefault(emptyMap()))
    } catch (e: Exception) {
        null
    }
}

/**
 * Finds bridges on the phone's home network: `phev-bridge.local`, the bridge address already saved, and every address
 * of the phone's own /24 on port [BridgeSetupApi.PORT] (a few seconds; nothing to install on the bridge for it).
 */
class BridgeDiscovery(private val context: Context, private val log: DiagLog) {
    /** [from]: this phone's LAN address the sweep was based on; [addresses] tried; [tookMs] how long it took. */
    data class Result(val bridges: List<FoundBridge>, val onLan: Boolean, val from: String?, val addresses: Int, val tookMs: Long)

    suspend fun find(extraUrls: List<String>): Result = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm?.let { lanNetwork(it) }
        val onLan = net != null
        val candidates = LinkedHashSet<String>()
        extraUrls.filter { it.isNotBlank() }.forEach { candidates += it.trim().trimEnd('/') }
        val mdns = async {
            withTimeoutOrNull(2_500) {
                runCatching { InetAddress.getAllByName("phev-bridge.local").firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()
            }
        }
        var own: String? = null
        if (onLan) {
            val v4 = cm?.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            own = v4?.address?.hostAddress
            val parts = own?.split('.')
            if (parts != null && parts.size == 4) {
                val prefix = parts.take(3).joinToString(".")
                for (i in 1..254) {
                    val ip = "$prefix.$i"
                    if (ip != own) candidates += "http://$ip:${BridgeSetupApi.PORT}"
                }
            }
        }
        val gate = Semaphore(48)
        val found = coroutineScope {
            val sweep = candidates.map { url -> async { gate.withPermit { BridgeSetupApi.hello(url, connectMs = 700, readMs = 2_000, network = net) } } }
            // phev-bridge.local only helps when the sweep can't reach it (another subnet); never wait long for it.
            val viaName = withTimeoutOrNull(3_000) { mdns.await() }
                ?.let { "http://$it:${BridgeSetupApi.PORT}" }
                ?.takeIf { it !in candidates }
                ?.let { BridgeSetupApi.hello(it, connectMs = 1_500, readMs = 3_000) }
            sweep.awaitAll() + viaName
        }.filterNotNull().distinctBy { it.host }
        log.link(
            "Bridge search: ${candidates.size} address(es) from ${own ?: "no LAN address"} in ${System.currentTimeMillis() - t0} ms — " +
                if (found.isEmpty()) "none found" else found.joinToString { "${it.name} at ${it.url} v${it.version}" + if (it.needsUpdate) " (needs update)" else "" },
        )
        Result(found, onLan, own, candidates.size, System.currentTimeMillis() - t0)
    }

    /**
     * The home network to search: the default network when it is Wi-Fi/Ethernet, else (a VPN such as Tailscale is the
     * default) the validated Wi-Fi/Ethernet network underneath. Never a VPN, and never the car's own Wi-Fi (no
     * internet, so not validated) unless nothing else exists.
     */
    private fun lanNetwork(cm: ConnectivityManager): Network? {
        fun lan(n: Network): Boolean {
            val c = cm.getNetworkCapabilities(n) ?: return false
            return !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        fun validated(n: Network) = cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        val chosen = active?.takeIf { lan(it) } ?: allNetworks(cm).filter { lan(it) }.sortedByDescending { validated(it) }.firstOrNull()
        val defaultKind = when {
            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "a VPN"
            active != null && lan(active) -> "the home LAN"
            else -> "not Wi-Fi/Ethernet"
        }
        val target = when {
            chosen == null -> "nothing"
            chosen == active -> "it"
            validated(chosen) -> "the Wi-Fi/Ethernet network underneath"
            else -> "the Wi-Fi/Ethernet network underneath (not validated)"
        }
        log.link("Bridge search: default network is $defaultKind — searching $target")
        return chosen
    }

    /**
     * The phone's home network, if it is on one: Wi-Fi or Ethernet with internet, not a VPN. The car's own Wi-Fi never
     * counts (the app joins it as a local-only network without internet). Quiet: no log line.
     */
    fun homeNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return allNetworks(cm).firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    @Suppress("DEPRECATION") // getAllNetworks: still the simple way to list networks; nothing to register for.
    private fun allNetworks(cm: ConnectivityManager): List<Network> = cm.allNetworks.toList()
}

/**
 * The "set up bridge" wizard's brain, app-wide so it survives rotation and keeps waiting while the user is away
 * unplugging the bridge. Steps: find → pair (re-plug) → car Wi-Fi (leave test mode, scan, join) → register → verify.
 * Every step logs to the diagnostic log.
 */
class BridgeSetupController(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: DiagLog,
    private val scope: CoroutineScope,
) {
    enum class Step { FIND, PAIR, CAR_WIFI, REGISTER, VERIFY }
    enum class PairState { IDLE, TRYING, NEED_REPLUG, BRIDGE_OFF }
    enum class WifiState { IDLE, CHECKING, NEED_CREDENTIALS, LEAVING_TEST_MODE, SEARCHING, JOINING, CONNECTED, CAR_NOT_SEEN, SAVED_NOT_CONNECTED, PROBLEM, FAILED }
    enum class RegState { INSTRUCTIONS, NO_BEEP, MEMORY_FULL, CLEARED, READY, REGISTERING, REGISTERED, FAILED }
    enum class VerifyState { WAITING, OK, SLOW }

    data class State(
        val step: Step = Step.FIND,
        val searching: Boolean = false,
        val searched: Boolean = false,
        val onLan: Boolean = true,
        val found: List<FoundBridge> = emptyList(),
        /** What the last search tried, shown when it found nothing (first-run setup has no Log tab). */
        val searchFrom: String? = null,
        val searchCount: Int = 0,
        val searchSeconds: Int = 0,
        val bridge: FoundBridge? = null,
        val pair: PairState = PairState.IDLE,
        val pairReason: String? = null,
        val wifi: WifiState = WifiState.IDLE,
        val wifiInfo: BridgeWifi? = null,
        val carSignal: Int? = null,
        val wifiDetail: String? = null,
        val reg: RegState = RegState.INSTRUCTIONS,
        val regDetail: String? = null,
        val verify: VerifyState = VerifyState.WAITING,
        val battery: Int? = null,
        val vin: String? = null,
        val registrations: Int? = null,
        /** Waiting for the phone to get back on the home network (e.g. right after leaving the car's Wi-Fi). */
        val waitingForHome: Boolean = false,
        /** After the car's memory was cleared: is the bridge back on the car's Wi-Fi? null = not known yet. */
        val bridgeOnCar: Boolean? = null,
        /** Registering is waiting for the bridge to be on the car's Wi-Fi first. */
        val regWaitingWifi: Boolean = false,
        /** The car's memory was cleared in this setup, so the phone has to be registered again afterwards. */
        val memoryCleared: Boolean = false,
        /** The bridge already talks to the car (registered before, e.g. from another phone): nothing left to do. */
        val alreadyWorking: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private val discovery = BridgeDiscovery(context, log)

    private val token: String get() = settings.current.bridgeToken
    private val url: String get() = _state.value.bridge?.url ?: settings.current.bridgeUrl

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    /**
     * The bridge is only reachable from the home network. Right after the app let go of the car's Wi-Fi the phone needs
     * a few seconds to get back on it, so wait up to [timeoutMs]. Logs only when it actually has to wait.
     */
    private suspend fun awaitHomeNetwork(what: String, timeoutMs: Long = 30_000): Boolean {
        if (discovery.homeNetwork() != null) return true
        log.link("Bridge setup: this phone is not on the home network ($what) — waiting up to ${timeoutMs / 1000} s for it")
        _state.update { it.copy(waitingForHome = true) }
        val t0 = System.currentTimeMillis()
        var ok = false
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            delay(1_000)
            if (discovery.homeNetwork() != null) { ok = true; break }
        }
        _state.update { it.copy(waitingForHome = false) }
        log.link(
            if (ok) "Bridge setup: back on the home network after ${(System.currentTimeMillis() - t0) / 1000} s"
            else "Bridge setup: still not on the home network after ${timeoutMs / 1000} s",
        )
        return ok
    }

    /** The bridge's car Wi-Fi status, asking again every 3 s for up to [timeoutMs] (it may be busy joining). */
    private suspend fun bridgeWifiStatus(timeoutMs: Long): BridgeWifi? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val r = BridgeSetupApi.carWifi(url, token)
            if (r != null && r.code == 200) return BridgeWifi.from(r.body)
            delay(3_000)
        }
        return null
    }

    /** Opens the wizard: with a working saved bridge it goes straight to the car Wi-Fi step, else it searches. */
    fun start() = launch {
        _state.value = State(searching = true)
        log.link("Bridge setup: started (car Wi-Fi ${if (settings.current.hasCarWifi) "\"${settings.current.ssid}\"" else "not set"}, bridge ${settings.current.bridgeUrl.ifBlank { "none saved" }})")
        // The app just let go of the car's Wi-Fi (setup pauses it): give the phone a moment to get home again.
        awaitHomeNetwork("opening setup", 20_000)
        val s = settings.current
        if (s.bridgeUrl.isNotBlank() && s.bridgeToken.isNotBlank()) {
            val hello = BridgeSetupApi.hello(s.bridgeUrl.trimEnd('/'))
            if (hello != null && !hello.needsUpdate && BridgeSetupApi.carWifi(hello.url, s.bridgeToken)?.code == 200) {
                log.link("Bridge setup: the saved bridge ${hello.url} answers with our token — continuing at the car Wi-Fi step")
                _state.update { it.copy(searching = false, bridge = hello, step = Step.CAR_WIFI) }
                checkWifiNow()
                return@launch
            }
        }
        searchNow()
    }

    fun search() = launch { searchNow() }

    private suspend fun searchNow() {
        _state.update { it.copy(step = Step.FIND, searching = true) }
        val r = discovery.find(listOf(settings.current.bridgeUrl))
        _state.update {
            it.copy(
                searching = false, searched = true, found = r.bridges, onLan = r.onLan,
                searchFrom = r.from, searchCount = r.addresses, searchSeconds = ((r.tookMs + 999) / 1000).toInt(),
            )
        }
        val usable = r.bridges.filterNot { it.needsUpdate }
        if (usable.size == 1 && r.bridges.size == 1) chooseNow(usable.first())
    }

    /** A bridge typed in by hand ("192.168.1.21" or a full URL). */
    fun useAddress(text: String) = launch {
        val t = text.trim()
        if (t.isEmpty()) return@launch
        val u = if (t.startsWith("http")) t.trimEnd('/') else "http://$t" + if (t.contains(':')) "" else ":${BridgeSetupApi.PORT}"
        _state.update { it.copy(searching = true) }
        val b = BridgeSetupApi.hello(u)
        log.link("Bridge setup: address $u → ${b?.let { "${it.name} v${it.version}" } ?: "no bridge there"}")
        _state.update { it.copy(searching = false, searched = true, found = listOfNotNull(b)) }
        if (b != null && !b.needsUpdate) chooseNow(b)
    }

    fun choose(b: FoundBridge) = launch { chooseNow(b) }

    private suspend fun chooseNow(b: FoundBridge) {
        _state.update { it.copy(bridge = b) }
        if (b.needsUpdate) return
        val s = settings.current
        if (s.bridgeToken.isNotBlank() && BridgeSetupApi.carWifi(b.url, s.bridgeToken)?.code == 200) {
            log.link("Bridge setup: ${b.url} already accepts our token")
            if (s.bridgeUrl != b.url) settings.update { it.copy(bridgeUrl = b.url) }
            _state.update { it.copy(step = Step.CAR_WIFI) }
            checkWifiNow()
            return
        }
        _state.update { it.copy(step = Step.PAIR, pair = PairState.TRYING, pairReason = null) }
        pairLoop(b)
    }

    /** Pairs as soon as the bridge's window is open; otherwise waits (polling) for the user to re-plug it. */
    private suspend fun pairLoop(start: FoundBridge) {
        var bridge = start
        var offSince = 0L
        while (true) {
            val hello = BridgeSetupApi.hello(bridge.url)
            when {
                hello == null -> {
                    if (offSince == 0L) {
                        offSince = System.currentTimeMillis()
                        log.link("Bridge setup: ${bridge.url} not answering — waiting for it to come back")
                    }
                    _state.update { it.copy(pair = PairState.BRIDGE_OFF) }
                    // Back after a re-plug but on a new address? Look for it by name once in a while.
                    if (System.currentTimeMillis() - offSince > 60_000) {
                        val again = discovery.find(emptyList()).bridges.firstOrNull { it.name == bridge.name && !it.needsUpdate }
                        if (again != null && again.url != bridge.url) {
                            log.link("Bridge setup: ${bridge.name} is back at ${again.url}")
                            bridge = again
                            _state.update { it.copy(bridge = again) }
                        }
                        offSince = System.currentTimeMillis()
                    }
                }
                hello.pairingOpen -> {
                    offSince = 0L
                    _state.update { it.copy(pair = PairState.TRYING, bridge = hello) }
                    val (tok, reason) = BridgeSetupApi.pair(bridge.url)
                    if (tok != null) {
                        val carWifi = settings.current.hasCarWifi
                        settings.update { it.copy(bridgeUrl = bridge.url, bridgeToken = tok, route = if (carWifi) Route.AUTO else Route.BRIDGE) }
                        log.link("Bridge setup: paired with ${hello.name} at ${bridge.url}; route ${if (carWifi) "automatic" else "bridge only"}")
                        _state.update { it.copy(step = Step.CAR_WIFI, bridge = hello) }
                        checkWifiNow()
                        return
                    }
                    log.warn("Bridge setup: pairing with ${bridge.url} refused ($reason)")
                    _state.update { it.copy(pair = PairState.NEED_REPLUG, pairReason = reason) }
                }
                else -> {
                    offSince = 0L
                    if (_state.value.pair != PairState.NEED_REPLUG) log.link("Bridge setup: pairing closed on ${bridge.url} (${hello.pairingReason}) — asking to re-plug")
                    _state.update { it.copy(pair = PairState.NEED_REPLUG, pairReason = hello.pairingReason) }
                }
            }
            delay(2_500)
        }
    }

    fun checkWifi() = launch { checkWifiNow() }

    private suspend fun checkWifiNow() {
        _state.update { it.copy(step = Step.CAR_WIFI, wifi = WifiState.CHECKING, wifiDetail = null) }
        val s = settings.current
        if (!s.hasCarWifi) {
            _state.update { it.copy(wifi = WifiState.NEED_CREDENTIALS) }
            return
        }
        if (!awaitHomeNetwork("checking the bridge's Wi-Fi")) return wifiFailed(NOT_HOME)
        val bridge = BridgeSetupApi.hello(url) ?: return wifiFailed("The bridge doesn't answer at $url")
        if (bridge.emulator) {
            _state.update { it.copy(wifi = WifiState.LEAVING_TEST_MODE) }
            log.link("Bridge setup: the bridge is in test mode (simulated car) — switching it to the real car")
            val r = BridgeSetupApi.leaveTestMode(url, token)
            if (r?.code != 200) return wifiFailed("Could not switch off test mode: ${r?.body?.str("error") ?: "no answer"}")
            delay(3_000)
            val back = withTimeoutOrNull(120_000) {
                var h: FoundBridge? = null
                while (h == null || h.emulator) {
                    delay(2_000)
                    h = BridgeSetupApi.hello(url)
                }
                h
            } ?: return wifiFailed("The bridge did not come back after leaving test mode")
            _state.update { it.copy(bridge = back) }
            log.link("Bridge setup: bridge back, now talking to the real car")
        }
        val st = BridgeSetupApi.carWifi(url, token)
        if (st == null || st.code != 200) return wifiFailed("Car Wi-Fi status: ${st?.body?.str("error") ?: "no answer"}")
        val info = BridgeWifi.from(st.body)
        _state.update { it.copy(wifiInfo = info) }
        log.link("Bridge setup: bridge Wi-Fi iface=${info.iface} home=${info.homeIface} profile=${info.ssid} connected=${info.connected} problem=${info.problem}")
        if (info.problem != null) {
            _state.update { it.copy(wifi = WifiState.PROBLEM, wifiDetail = info.problem) }
            return
        }
        if (info.connected && info.ssid == s.ssid) {
            _state.update { it.copy(wifi = WifiState.CONNECTED, carSignal = info.signal) }
            checkAlreadyWorking()
            return
        }
        _state.update { it.copy(wifi = WifiState.SEARCHING) }
        val country = Locale.getDefault().country.takeIf { it.length == 2 }
        val scan = BridgeSetupApi.scan(url, token, country)
        if (scan == null || scan.body["ok"] != true) {
            val why = scan?.body?.str("error") ?: "no answer"
            _state.update { it.copy(wifi = WifiState.PROBLEM, wifiDetail = why) }
            log.warn("Bridge setup: scan failed: $why")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val nets = (scan.body["networks"] as? List<Map<String, Any?>>).orEmpty()
        val car = nets.firstOrNull { it["ssid"] == s.ssid }
        log.link("Bridge setup: scan saw ${nets.size} network(s); car ${s.ssid} ${car?.let { "at ${it["signal"]}%" } ?: "not seen"}")
        if (car == null) {
            _state.update { it.copy(wifi = WifiState.CAR_NOT_SEEN) }
            return
        }
        joinNow((car["signal"] as? Number)?.toInt())
    }

    /**
     * Is the bridge already in touch with the car (registered before — e.g. when setting up another phone)? Then the
     * car doesn't need to register it again and setup can finish right here.
     */
    private suspend fun checkAlreadyWorking() {
        val snap = BridgeClient(url, token, log, context).fetchStatus(6_000) ?: return
        val fresh = snap.car.updatedAtMs?.let { System.currentTimeMillis() - it < 15 * 60_000 } == true
        val working = snap.car.vin != null && (snap.link.isConnected || fresh)
        if (working) {
            log.link("Bridge setup: the bridge already talks to the car (VIN ${snap.car.vin}, battery ${snap.car.batteryPercent}%) — no registration needed")
            _state.update { it.copy(alreadyWorking = true, battery = snap.car.batteryPercent, vin = snap.car.vin) }
        }
    }

    /** The car's Wi-Fi name and password typed in the wizard (a fresh install has none yet); saved, then on we go. */
    fun useCarWifi(ssid: String, password: String) = launch {
        settings.update { it.copy(ssid = ssid.trim(), password = password) }
        log.link("Bridge setup: car Wi-Fi name saved (${ssid.trim()})")
        checkWifiNow()
    }

    /** Save the car's network on the bridge even though it isn't in range now (it joins when the car is home). */
    fun saveWithoutCar() = launch { joinNow(null) }

    private suspend fun joinNow(signal: Int?) {
        val s = settings.current
        _state.update { it.copy(wifi = WifiState.JOINING, carSignal = signal) }
        val country = Locale.getDefault().country.takeIf { it.length == 2 }
        if (!awaitHomeNetwork("before joining")) return wifiFailed(NOT_HOME)
        val r = BridgeSetupApi.join(url, token, s.ssid, s.password, country)
        if (r == null) {
            // The answer got lost (the phone left the home network, or the bridge took long) — the bridge may well
            // have joined. Ask it what happened instead of giving up.
            log.warn("Bridge setup: no answer from the bridge while it joined the car's Wi-Fi — asking it what happened")
            if (!awaitHomeNetwork("after joining")) return wifiFailed(NOT_HOME)
            val after = bridgeWifiStatus(60_000) ?: return wifiFailed("The bridge did not answer while joining, nor in the minute after")
            log.link("Bridge setup: bridge now reports profile=${after.ssid} connected=${after.connected} signal=${after.signal} ip=${after.ip}")
            _state.update {
                when {
                    after.connected && after.ssid == s.ssid -> it.copy(wifi = WifiState.CONNECTED, wifiInfo = after, carSignal = after.signal ?: signal)
                    after.ssid == s.ssid -> it.copy(wifi = WifiState.SAVED_NOT_CONNECTED, wifiInfo = after, wifiDetail = null)
                    else -> it.copy(wifi = WifiState.FAILED, wifiInfo = after, wifiDetail = "The bridge has no saved network for ${s.ssid}")
                }
            }
            return
        }
        val info = r.body.obj("status")?.let(BridgeWifi::from)
        val message = r.body.str("message")
        _state.update { it.copy(wifiInfo = info ?: it.wifiInfo) }
        log.link("Bridge setup: join → saved=${r.body["saved"]} connected=${r.body["connected"]} mac=${info?.mac} ip=${info?.ip}: $message")
        _state.update {
            when {
                r.body["connected"] == true -> it.copy(wifi = WifiState.CONNECTED, carSignal = info?.signal ?: signal, alreadyWorking = false)
                r.body["saved"] == true -> it.copy(wifi = WifiState.SAVED_NOT_CONNECTED, wifiDetail = message)
                else -> it.copy(wifi = WifiState.FAILED, wifiDetail = message)
            }
        }
    }

    /**
     * The car isn't home: keep the car's network saved on the bridge (it joins by itself when the car is back), switch
     * the app to the bridge, and leave — registration waits until the car is home (Settings → Set up bridge again).
     * Returns at once: [onDone] runs on the main thread as soon as the app is switched over; the car's network is
     * saved on the bridge in the background, and [BridgeHomeJob] notifies when the car is home.
     */
    fun finishWithoutCar(onDone: () -> Unit) {
        job?.cancel()
        val needSave = _state.value.wifi != WifiState.SAVED_NOT_CONNECTED && _state.value.wifi != WifiState.CONNECTED
        val bridgeUrl = url
        scope.launch {
            val carWifi = settings.current.hasCarWifi
            settings.update { it.copy(route = if (carWifi) Route.AUTO else Route.BRIDGE, bridgeSetupPending = true) }
            BridgeHomeJob.schedule(context)
            log.link("Bridge setup: finished without the car — will notify when the bridge sees the car, then register it")
            withContext(Dispatchers.Main) { onDone() }
            // Save the car's network on the bridge in the background: it tries to connect for up to 45 s (the car is
            // away, so that fails), but the profile is stored first and the bridge joins by itself when the car is back.
            if (needSave) {
                val s = settings.current
                val r = BridgeSetupApi.join(bridgeUrl, s.bridgeToken, s.ssid, s.password, Locale.getDefault().country.takeIf { it.length == 2 })
                log.link("Bridge setup: car network saved on the bridge in the background — saved=${r?.body?.get("saved")}, connected=${r?.body?.get("connected")}: ${r?.body?.str("message") ?: "no answer"}")
            }
        }
    }

    private fun wifiFailed(why: String) {
        log.warn("Bridge setup: " + if (why == NOT_HOME) "this phone isn't on the home network, so it can't reach the bridge" else why)
        _state.update { it.copy(wifi = WifiState.FAILED, wifiDetail = why) }
    }

    fun toRegister() {
        job?.cancel()
        _state.update { it.copy(step = Step.REGISTER, reg = RegState.INSTRUCTIONS, regDetail = null) }
    }

    /** What the car did after the LOCK/UNLOCK sequence: 0 = nothing, 1–2 = room for the bridge, 3 = memory full. */
    fun beeps(n: Int) {
        log.link("Bridge setup: car beeped $n time(s)")
        _state.update { it.copy(reg = when (n) { 0 -> RegState.NO_BEEP; 3 -> RegState.MEMORY_FULL; else -> RegState.READY }, regDetail = null) }
    }

    fun backToInstructions() {
        job?.cancel()
        _state.update { it.copy(reg = RegState.INSTRUCTIONS, regDetail = null) }
    }

    /**
     * The car confirmed clearing its memory (one beep). That also restarts its Wi-Fi (name and password back to the
     * card), so the bridge loses it for a while: follow the bridge until it is back on the car's Wi-Fi before the user
     * enters registration mode again.
     */
    fun memoryCleared() = launch {
        log.link("Bridge setup: the car's memory was cleared — waiting for the bridge to get back on the car's Wi-Fi")
        _state.update { it.copy(reg = RegState.CLEARED, memoryCleared = true, bridgeOnCar = null) }
        val t0 = System.currentTimeMillis()
        var was: Boolean? = null
        while (System.currentTimeMillis() - t0 < 10 * 60_000L) {
            val on = BridgeSetupApi.carWifi(url, token)?.takeIf { it.code == 200 }?.let { BridgeWifi.from(it.body).connected }
            if (on != null && on != was) {
                log.link("Bridge setup: bridge ${if (on) "is back on" else "is not on"} the car's Wi-Fi (${(System.currentTimeMillis() - t0) / 1000} s after clearing)")
                was = on
            }
            _state.update { it.copy(bridgeOnCar = on ?: it.bridgeOnCar) }
            delay(5_000)
        }
    }

    fun register() = launch {
        _state.update { it.copy(reg = RegState.REGISTERING, regDetail = null) }
        if (!awaitHomeNetwork("registering")) {
            _state.update { it.copy(reg = RegState.FAILED, regDetail = NOT_HOME) }
            return@launch
        }
        // The bridge can only reach the car over the car's Wi-Fi: make sure it's on it (it may be rejoining).
        if (bridgeWifiStatus(10_000)?.connected != true) {
            log.link("Bridge setup: the bridge isn't on the car's Wi-Fi yet — waiting up to 90 s before registering")
            _state.update { it.copy(regWaitingWifi = true) }
            val t0 = System.currentTimeMillis()
            var on = false
            while (System.currentTimeMillis() - t0 < 90_000) {
                delay(4_000)
                if (bridgeWifiStatus(6_000)?.connected == true) { on = true; break }
            }
            _state.update { it.copy(regWaitingWifi = false) }
            if (!on) {
                log.warn("Bridge setup: the bridge didn't get on the car's Wi-Fi within 90 s — not registering")
                _state.update { it.copy(reg = RegState.FAILED, regDetail = NO_CAR_WIFI) }
                return@launch
            }
            log.link("Bridge setup: bridge is on the car's Wi-Fi after ${(System.currentTimeMillis() - t0) / 1000} s")
        }
        val client = BridgeClient(url, token, log, context)
        var r = client.execute(CarCommand.RegisterPhone)
        // The bridge may have been waiting out a retry pause; the car stays in registration mode for 10 minutes.
        if (r is CommandResult.Failure) {
            log.warn("Bridge setup: register failed (${r.reason}) — trying once more")
            delay(2_000)
            r = client.execute(CarCommand.RegisterPhone)
        }
        when (r) {
            is CommandResult.Success -> {
                log.link("Bridge setup: the car registered the bridge")
                _state.update { it.copy(reg = RegState.REGISTERED) }
                if (settings.current.bridgeSetupPending) {
                    settings.update { it.copy(bridgeSetupPending = false) }
                    BridgeHomeJob.cancel(context)
                    log.link("Bridge setup: nothing pending any more — background check stopped")
                }
            }
            is CommandResult.Failure -> {
                log.warn("Bridge setup: register failed: ${r.reason}")
                _state.update { it.copy(reg = RegState.FAILED, regDetail = r.reason) }
            }
        }
    }

    /** After the power is off: wait for the bridge to show the real car's data. */
    fun verify() = launch {
        _state.update { it.copy(step = Step.VERIFY, verify = VerifyState.WAITING) }
        awaitHomeNetwork("checking the bridge reaches the car")
        val client = BridgeClient(url, token, log, context)
        val since = System.currentTimeMillis()
        client.post("connect")
        while (System.currentTimeMillis() - since < 150_000) {
            val snap = client.fetchStatus(6_000)
            val car = snap?.car
            val fresh = car?.updatedAtMs?.let { it > since - 60_000 } == true
            if (snap != null && (snap.link.isConnected || fresh) && car?.hasData == true) {
                log.link("Bridge setup: verified — bridge sees the car (battery ${car.batteryPercent}%, phones registered ${car.registrations ?: "?"})")
                _state.update { it.copy(verify = VerifyState.OK, battery = car.batteryPercent, vin = car.vin, registrations = car.registrations) }
                return@launch
            }
            delay(4_000)
        }
        log.warn("Bridge setup: no car data from the bridge within 150 s — it will keep trying on its own")
        _state.update { it.copy(verify = VerifyState.SLOW) }
    }

    /** Leaves the wizard: routes through the bridge (automatic when the car's Wi-Fi is also set up). */
    fun finish() {
        job?.cancel()
        scope.launch {
            val carWifi = settings.current.hasCarWifi
            settings.update { it.copy(route = if (carWifi) Route.AUTO else Route.BRIDGE, bridgeSetupPending = false) }
            BridgeHomeJob.cancel(context)
            log.link("Bridge setup: finished (route ${if (carWifi) "automatic" else "bridge only"})")
        }
    }

    fun cancel() {
        job?.cancel()
        log.link("Bridge setup: closed at step ${_state.value.step}")
    }

    companion object {
        /** Detail code: the phone isn't on the home network, so the bridge can't be reached (the screen explains). */
        const val NOT_HOME = "not-home"
        /** Detail code: the bridge isn't on the car's Wi-Fi, so it can't reach the car to register. */
        const val NO_CAR_WIFI = "no-car-wifi"
        /** Tag for [CarRepository.pauseCarWifi] while the wizard is open. */
        const val PAUSE_TAG = "bridge setup"
    }
}
