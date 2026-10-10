package io.github.anwo91.phevlink.obd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ObdLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * ELM327 over TCP, for Wi-Fi dongles (usually 192.168.0.10:35000 on the dongle's own access point).
 *
 * The dongle's Wi-Fi has no internet, so Android may keep routing traffic over mobile data. [open] therefore looks
 * for the network the phone has on the dongle's subnet (an IPv4 address in the same /24 as the dongle, or within
 * that network's own prefix) and creates the socket with that network's socket factory, which binds just this
 * socket to it. If there is no such network it falls back to a plain socket (works when the phone routes there
 * anyway, e.g. an adapter on the home LAN).
 */
class WifiTcpLink private constructor(
    private val socket: Socket,
    override val description: String,
    log: DiagLog,
) : ObdLink {

    private val pipe = StreamPipe("Wi-Fi", socket.getInputStream(), socket.getOutputStream(), { socket.close() }, log)

    init {
        pipe.start()
    }

    override suspend fun send(bytes: ByteArray) {
        pipe.send(bytes)
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? = pipe.receive(timeoutMs)

    override fun close() {
        pipe.close()
    }

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** Splits "host:port" (port optional, default [DEFAULT_PORT]). Returns null when malformed. */
        fun parseAddress(address: String): Pair<String, Int>? {
            val a = address.trim()
            if (a.isEmpty()) return null
            val colon = a.lastIndexOf(':')
            if (colon < 0) return a to DEFAULT_PORT
            val host = a.substring(0, colon).trim()
            val port = a.substring(colon + 1).trim().toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return host to port
        }

        /**
         * Opens a TCP connection to [address] ("host:port").
         * @throws ObdConnectException [ObdConnectException.Reason.WIFI_UNREACHABLE] or [ObdConnectException.Reason.BAD_ADDRESS]
         */
        suspend fun open(context: Context, address: String, name: String, log: DiagLog): WifiTcpLink {
            val (host, port) = parseAddress(address)
                ?: throw ObdConnectException(ObdConnectException.Reason.BAD_ADDRESS, "Invalid address '$address' (expected host:port)")
            val target: InetAddress = withContext(Dispatchers.IO) {
                try {
                    InetAddress.getByName(host)
                } catch (e: UnknownHostException) {
                    throw ObdConnectException(ObdConnectException.Reason.BAD_ADDRESS, "Unknown host '$host'", e)
                } catch (e: SecurityException) {
                    throw ObdConnectException(ObdConnectException.Reason.BAD_ADDRESS, "Cannot resolve '$host': ${e.message}", e)
                }
            }
            val network = findNetwork(context, target, log)
            if (network == null) {
                log.warn("OBD: Wi-Fi: the phone has no network on the subnet of ${target.hostAddress} — is it joined to the adapter's Wi-Fi? Trying the default route.")
            }
            val t0 = SystemClock.elapsedRealtime()
            log.link("OBD: Wi-Fi: connecting to ${target.hostAddress}:$port" + if (network != null) " (bound to network $network)" else "")
            val socket: Socket = try {
                network?.socketFactory?.createSocket() ?: Socket()
            } catch (e: IOException) {
                throw ObdConnectException(ObdConnectException.Reason.WIFI_UNREACHABLE, "Could not create a socket: ${e.message}", e)
            }
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                connectBlocking(CONNECT_TIMEOUT_MS + 1_000L, abort = { socket.close() }) {
                    socket.connect(InetSocketAddress(target, port), CONNECT_TIMEOUT_MS)
                }
                val link = WifiTcpLink(socket, "Wi-Fi $name (${target.hostAddress}:$port)", log)
                log.link("OBD: socket connected (Wi-Fi) in ${SystemClock.elapsedRealtime() - t0} ms")
                return link
            } catch (e: CancellationException) {
                runCatching { socket.close() }
                throw e
            } catch (e: IOException) {
                runCatching { socket.close() }
                log.warn("OBD: Wi-Fi: connection to ${target.hostAddress}:$port failed after ${SystemClock.elapsedRealtime() - t0} ms: ${e.javaClass.simpleName}: ${e.message}")
                throw ObdConnectException(
                    ObdConnectException.Reason.WIFI_UNREACHABLE,
                    "No TCP connection to ${target.hostAddress}:$port (${e.message})",
                    e,
                )
            } catch (e: RuntimeException) {
                runCatching { socket.close() }
                throw ObdConnectException(
                    ObdConnectException.Reason.WIFI_UNREACHABLE,
                    "No TCP connection to ${target.hostAddress}:$port (${e.javaClass.simpleName}: ${e.message})",
                    e,
                )
            }
        }

        /** The network whose IPv4 address is on [target]'s subnet, or null. */
        @Suppress("DEPRECATION") // allNetworks: still the simplest correct snapshot; no callback needed here.
        private fun findNetwork(context: Context, target: InetAddress, log: DiagLog): Network? {
            if (target !is Inet4Address) return null
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val networks: Array<Network> = try {
                cm.allNetworks
            } catch (e: SecurityException) {
                log.warn("OBD: Wi-Fi: cannot list networks (${e.message})")
                return null
            }
            for (n in networks) {
                val lp = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address as? Inet4Address ?: continue
                    val prefix = la.prefixLength
                    if (sameSubnet(a, target, 24) || (prefix in 8..32 && sameSubnet(a, target, prefix))) {
                        log.link("OBD: Wi-Fi: using network ${lp.interfaceName ?: n.toString()} (phone address ${a.hostAddress}/$prefix)")
                        return n
                    }
                }
            }
            return null
        }

        private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
            val x = toInt(a)
            val y = toInt(b)
            val mask = if (prefix <= 0) 0 else -1 shl (32 - prefix.coerceAtMost(32))
            return (x and mask) == (y and mask)
        }

        private fun toInt(a: Inet4Address): Int {
            val b = a.address
            return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        }
    }
}
