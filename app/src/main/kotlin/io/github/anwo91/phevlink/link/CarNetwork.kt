package io.github.anwo91.phevlink.link

import android.content.Context
import io.github.anwo91.phevlink.R
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.session.SocketConnector
import io.github.anwo91.phevlink.util.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * Gets the phone onto the car's Wi-Fi without disturbing anything else.
 *
 * What the official app gets wrong, and what this does instead:
 *  - it asks for an SSID *pattern* (always shows the picker, never auto-approved)
 *    -> we ask for the exact SSID, plus the car's BSSID when paired, which
 *       Android auto-approves after the first time (and in the background with
 *       a companion pairing);
 *  - it binds the whole process to the car network and never notices when that
 *    network dies -> we bind only the car socket, and follow onLost/onAvailable;
 *  - if the phone is already on the car's Wi-Fi (joined manually), we just use it.
 */
class CarNetwork(private val context: Context, private val log: DiagLog) {

    data class Target(val ssid: String, val password: String, val bssid: String?)

    sealed interface State {
        data object Idle : State
        data class Searching(val sinceMs: Long, val requested: Boolean) : State
        data class Ready(val network: Network, val how: String) : State
        data class Failed(val reason: String) : State
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var target: Target? = null
    private var watchCb: ConnectivityManager.NetworkCallback? = null
    private var requestCb: ConnectivityManager.NetworkCallback? = null
    private var requested: Network? = null
    private var releasePending = false
    private var releasedNetwork: Network? = null
    private val candidates = LinkedHashMap<Network, LinkProperties>()
    private var validated = HashSet<Network>()
    /** Last reported Wi-Fi signal strength (dBm) per network, for the log — tells range apart from protocol trouble. */
    private val signal = HashMap<Network, Int>()
    /** Called whenever a usable car network appears (to skip connection backoff). */
    var onAvailable: (() -> Unit)? = null

    val isActive: Boolean @Synchronized get() = target != null

    @Synchronized
    fun start(t: Target) {
        if (target == t && _state.value !is State.Failed) {
            if (releasePending) {
                releasePending = false
                main.removeCallbacksAndMessages(RELEASE_TOKEN)
                log.link("Reusing the car Wi-Fi the app still holds")
            }
            return
        }
        stopInternal()
        target = t
        _state.value = State.Searching(System.currentTimeMillis(), requested = false)
        log.link("Looking for car Wi-Fi \"${t.ssid}\"" + if (!t.bssid.isNullOrBlank()) " (paired ${t.bssid})" else "")
        registerWatch()
        // If the phone is already on the car's Wi-Fi, the watch callback reports it within
        // a moment. Only ask Android to connect when that does not happen.
        scheduleRequest()
    }

    /**
     * Lets go of the car's Wi-Fi after [graceMs]. Field log: when the app released its request and reconnected
     * soon after, the phone was still on the car's network ("phone already on car Wi-Fi") but nothing held it any
     * more, and Android dropped it 40–70 s later in the middle of a session. Holding the request a little longer
     * lets a quick restart reuse it.
     */
    @Synchronized
    fun stop(graceMs: Long = RELEASE_GRACE_MS) {
        if (target == null || releasePending) return
        if (graceMs <= 0 || requestCb == null) {
            releaseNow()
            return
        }
        releasePending = true
        main.postAtTime({ synchronized(this) { if (releasePending) releaseNow() } }, RELEASE_TOKEN, android.os.SystemClock.uptimeMillis() + graceMs)
    }

    private fun releaseNow() {
        if (target == null) return
        stopInternal()
        log.link("Released car Wi-Fi")
    }

    private fun stopInternal() {
        releasePending = false
        main.removeCallbacksAndMessages(RELEASE_TOKEN)
        // Remember what our own request had joined: if the phone is still on it later, it's our leftover, not a
        // network the user joined — and Android will drop it soon unless we ask for it again.
        requested?.let { releasedNetwork = it }
        main.removeCallbacksAndMessages(REQUEST_TOKEN)
        watchCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        requestCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        watchCb = null
        requestCb = null
        requested = null
        candidates.clear()
        validated.clear()
        signal.clear()
        target = null
        _state.value = State.Idle
    }

    /** Wait until a car network is usable. */
    suspend fun awaitNetwork(timeoutMs: Long): Network? = withTimeoutOrNull(timeoutMs) {
        state.mapNotNull { (it as? State.Ready)?.network }.first()
    }

    /** Socket factory for [io.github.anwo91.phevlink.core.session.PhevConnection]. */
    fun connector(host: String, port: Int, waitMs: Long = 30_000) = SocketConnector {
        val n = awaitNetwork(waitMs) ?: throw IOException(
            when (val s = state.value) {
                is State.Failed -> s.reason
                else -> AppLocale.localized(context).getString(R.string.net_not_found)
            }
        )
        withContext(Dispatchers.IO) {
            // Bind only this socket to the car network; the rest of the phone keeps using
            // mobile data / home Wi-Fi for internet.
            val s = n.socketFactory.createSocket()
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), 6_000)
                s
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
        }
    }

    // --------------------------------------------------------------------- watch

    private fun registerWatch() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                synchronized(this@CarNetwork) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated += network else validated -= network
                    readSignal(caps)?.let { signal[network] = it }
                    evaluate()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                synchronized(this@CarNetwork) {
                    if (looksLikeCar(lp)) candidates[network] = lp else candidates -= network
                    evaluate()
                }
            }

            override fun onLost(network: Network) {
                synchronized(this@CarNetwork) {
                    candidates -= network
                    validated -= network
                    val s = _state.value
                    if (s is State.Ready && s.network == network && network != requested) {
                        log.link("Car Wi-Fi lost" + sig(network))
                        _state.value = State.Searching(System.currentTimeMillis(), requested = requestCb != null)
                        scheduleRequest()
                    }
                    signal -= network
                    evaluate()
                }
            }
        }
        watchCb = cb
        runCatching { cm.registerNetworkCallback(req, cb, main) }
            .onFailure { log.warn("Could not watch Wi-Fi networks: ${it.message}") }
    }

    /**
     * A network the phone joined by itself counts as the car only with positive
     * evidence: a 192.168.8.x address *and* 192.168.8.46 as gateway, DNS or DHCP
     * server. (Many 4G home routers also use 192.168.8.x, with .1 as gateway.)
     */
    private fun looksLikeCar(lp: LinkProperties): Boolean {
        val onSubnet = lp.linkAddresses.any { la ->
            val a = la.address
            a is Inet4Address && a.hostAddress?.startsWith("192.168.8.") == true
        }
        if (!onSubnet) return false
        val gw = lp.routes.any { it.gateway?.hostAddress == CAR_IP }
        val dns = lp.dnsServers.any { it.hostAddress == CAR_IP }
        val dhcp = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            lp.dhcpServerAddress?.hostAddress == CAR_IP
        return gw || dns || dhcp
    }

    /** Wi-Fi signal strength in dBm from the capabilities, or null when the platform doesn't report it. */
    private fun readSignal(caps: NetworkCapabilities): Int? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            caps.signalStrength.takeIf { it != Int.MIN_VALUE }
        } else null
    }.getOrNull()

    /** " signal=-67 dBm (good)" for the log, or "" when unknown. Rough bands: >-60 strong, -70 ok, <-80 weak. */
    private fun sig(network: Network?): String {
        val s = signal[network] ?: return ""
        val band = when {
            s >= -60 -> "strong"
            s >= -70 -> "good"
            s >= -78 -> "ok"
            else -> "weak"
        }
        return " signal=$s dBm ($band)"
    }

    private fun evaluate() {
        if (target == null) return
        val current = _state.value
        val req = requested
        val pick = when {
            req != null -> req
            else -> candidates.keys.firstOrNull { it !in validated }
        }
        if (pick != null) {
            if (current !is State.Ready || current.network != pick) {
                val how = if (pick == req) "connected by the app" else "phone already on car Wi-Fi"
                val lp = candidates[pick]
                log.link("Car Wi-Fi ready ($how)" + sig(pick) + (lp?.let { " addr=${it.linkAddresses.joinToString()}" } ?: ""))
                _state.value = State.Ready(pick, how)
                onAvailable?.invoke()
                if (pick != req && pick == releasedNetwork && requestCb == null) {
                    // Our own earlier connection, no longer requested: ask for it again so Android keeps it.
                    log.link("That network was joined by the app before and nothing holds it now — asking Android to keep it")
                    main.removeCallbacksAndMessages(REQUEST_TOKEN)
                    main.post { requestIfNeeded(force = true) }
                }
            }
        } else if (current is State.Ready) {
            // The network we were using no longer qualifies (lost, or turned out to be a router).
            log.link("Car Wi-Fi no longer usable — searching again")
            _state.value = State.Searching(System.currentTimeMillis(), requested = requestCb != null)
            scheduleRequest()
        }
    }

    private fun scheduleRequest() {
        if (requestCb != null) return
        main.removeCallbacksAndMessages(REQUEST_TOKEN)
        main.postAtTime({ requestIfNeeded() }, REQUEST_TOKEN, android.os.SystemClock.uptimeMillis() + 1_200)
    }

    // ------------------------------------------------------------------- request

    @Synchronized
    private fun requestIfNeeded(force: Boolean = false) {
        val t = target ?: return
        if ((_state.value is State.Ready && !force) || requestCb != null) return
        val spec = try {
            WifiNetworkSpecifier.Builder()
                .setSsid(t.ssid)
                .setWpa2Passphrase(t.password)
                .apply {
                    val b = t.bssid?.takeIf { it.isNotBlank() }
                    if (b != null) runCatching { setBssid(MacAddress.fromString(b)) }
                }
                .build()
        } catch (e: Exception) {
            log.error("Invalid car Wi-Fi details: ${e.message}")
            _state.value = State.Failed(AppLocale.localized(context).getString(R.string.net_invalid_details, e.message.toString()))
            return
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(this@CarNetwork) {
                    if (requestCb !== this) return
                    requested = network
                    evaluate()
                }
            }

            override fun onLost(network: Network) {
                synchronized(this@CarNetwork) {
                    if (requestCb !== this) return
                    if (requested == network) {
                        requested = null
                        log.link("Car Wi-Fi connection dropped — Android will reconnect when it is back in range")
                        _state.value = State.Searching(System.currentTimeMillis(), requested = true)
                        evaluate()
                    }
                }
            }

            override fun onUnavailable() {
                synchronized(this@CarNetwork) {
                    if (requestCb !== this) return
                    requestCb = null
                    if (_state.value is State.Ready) {
                        // A re-request for the network we are already using was refused (e.g. app in the background).
                        log.warn("Android would not confirm the car Wi-Fi the phone is on — using it as long as it lasts")
                        return
                    }
                    log.warn("Android could not join the car Wi-Fi (declined, wrong password, or not in range)")
                    _state.value = State.Failed(AppLocale.localized(context).getString(R.string.net_could_not_join, t.ssid))
                }
            }
        }
        requestCb = cb
        // When re-requesting a network the phone is still on, keep using it meanwhile.
        if (!force) _state.value = State.Searching(System.currentTimeMillis(), requested = true)
        log.link("Asking Android to join \"${t.ssid}\"")
        runCatching { cm.requestNetwork(req, cb, main) }.onFailure {
            requestCb = null
            log.error("Network request refused: ${it.message}")
            _state.value = State.Failed(AppLocale.localized(context).getString(R.string.net_request_refused, it.message.toString()))
        }
    }

    companion object {
        const val CAR_IP = "192.168.8.46"
        private val REQUEST_TOKEN = Any()
        private val RELEASE_TOKEN = Any()
        /** How long the car's Wi-Fi is kept after the link stops (see [stop]). */
        const val RELEASE_GRACE_MS = 120_000L
    }

    /** After a failure (e.g. user declined), allow a fresh attempt. */
    @Synchronized
    fun retry() {
        val t = target ?: return
        target = null
        start(t)
    }
}
