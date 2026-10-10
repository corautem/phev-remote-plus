package io.github.anwo91.phevlink.link

import android.content.Context
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
class CarNetwork(context: Context, private val log: DiagLog) {

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
    private val candidates = LinkedHashMap<Network, LinkProperties>()
    private var validated = HashSet<Network>()
    /** Called whenever a usable car network appears (to skip connection backoff). */
    var onAvailable: (() -> Unit)? = null

    val isActive: Boolean @Synchronized get() = target != null

    @Synchronized
    fun start(t: Target) {
        if (target == t && _state.value !is State.Failed) return
        stopInternal()
        target = t
        _state.value = State.Searching(System.currentTimeMillis(), requested = false)
        log.link("Looking for car Wi-Fi \"${t.ssid}\"" + if (!t.bssid.isNullOrBlank()) " (paired ${t.bssid})" else "")
        registerWatch()
        // If the phone is already on the car's Wi-Fi, the watch callback reports it within
        // a moment. Only ask Android to connect when that does not happen.
        main.postDelayed({ requestIfNeeded() }, 1_200)
    }

    @Synchronized
    fun stop() {
        if (target == null) return
        stopInternal()
        log.link("Released car Wi-Fi")
    }

    private fun stopInternal() {
        main.removeCallbacksAndMessages(null)
        watchCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        requestCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        watchCb = null
        requestCb = null
        requested = null
        candidates.clear()
        validated.clear()
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
                else -> "Car Wi-Fi not found — out of range, or the car's Wi-Fi is asleep"
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
                    if (s is State.Ready && s.network == network) {
                        log.link("Car Wi-Fi lost")
                        _state.value = State.Searching(System.currentTimeMillis(), requested = requestCb != null)
                    }
                    evaluate()
                }
            }
        }
        watchCb = cb
        runCatching { cm.registerNetworkCallback(req, cb, main) }
            .onFailure { log.warn("Could not watch Wi-Fi networks: ${it.message}") }
    }

    /** The car hands out 192.168.8.x addresses and offers no internet. */
    private fun looksLikeCar(lp: LinkProperties): Boolean =
        lp.linkAddresses.any { la ->
            val a = la.address
            a is Inet4Address && a.hostAddress?.startsWith("192.168.8.") == true
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
                log.link("Car Wi-Fi ready ($how)")
                _state.value = State.Ready(pick, how)
                onAvailable?.invoke()
            }
        }
    }

    // ------------------------------------------------------------------- request

    @Synchronized
    private fun requestIfNeeded() {
        val t = target ?: return
        if (_state.value is State.Ready || requestCb != null) return
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(t.ssid)
            .setWpa2Passphrase(t.password)
            .apply {
                val b = t.bssid?.takeIf { it.isNotBlank() }
                if (b != null) runCatching { setBssid(MacAddress.fromString(b)) }
            }
            .build()
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
                    log.warn("Android could not join the car Wi-Fi (declined, wrong password, or not in range)")
                    _state.value = State.Failed("Could not join \"${t.ssid}\" — wrong password, declined, or not in range")
                }
            }
        }
        requestCb = cb
        _state.value = State.Searching(System.currentTimeMillis(), requested = true)
        log.link("Asking Android to join \"${t.ssid}\"")
        runCatching { cm.requestNetwork(req, cb, main) }.onFailure {
            requestCb = null
            log.error("Network request refused: ${it.message}")
            _state.value = State.Failed("Android refused the Wi-Fi request: ${it.message}")
        }
    }

    /** After a failure (e.g. user declined), allow a fresh attempt. */
    @Synchronized
    fun retry() {
        val t = target ?: return
        target = null
        start(t)
    }
}
