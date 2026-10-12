package io.github.anwo91.phevlink.bridge

import java.io.File
import java.security.SecureRandom
import java.util.Properties

enum class LinkMode {
    /** Stay connected to the car all the time (instant status, uses a little 12 V battery). */
    PERSISTENT,
    /** Connect every few minutes for a status check, and on demand for commands. */
    INTERVAL,
}

data class BridgeConfig(
    val carHost: String = "192.168.8.46",
    val carPort: Int = 8080,
    val listenHost: String = "0.0.0.0",
    val listenPort: Int = 8099,
    val token: String = "",
    val mode: LinkMode = LinkMode.INTERVAL,
    val intervalMinutes: Int = 10,
    /** How long to stay connected after a command or poll in INTERVAL mode. */
    val lingerSeconds: Int = 90,
    /** e.g. https://ntfy.sh/your-secret-topic — empty disables push alerts. */
    val ntfyUrl: String = "",
    val ntfyToken: String = "",
    val stateFile: String = "phev-bridge-state.json",
    val logFile: String = "",
    /** Run a simulated car instead of talking to the real one (for trying it out). */
    val emulator: Boolean = false,
    val syncClock: Boolean = true,
    /** Wi-Fi interface that talks to the car; blank = pick automatically (a radio not carrying the home network). */
    val carIface: String = "",
    /** Minutes after power-on during which the app may pair (fetch the token) once; 0 disables pairing. */
    val pairingMinutes: Int = 10,
    /** Root-owned helper the bridge may run through sudo to manage the car's Wi-Fi profile. */
    val wifiHelper: String = "/opt/phev-bridge/carwifi.sh",
) {
    companion object {
        fun load(file: File?): BridgeConfig {
            val p = Properties()
            if (file != null && file.exists()) file.inputStream().use { p.load(it) }
            fun s(k: String, d: String) = (System.getenv("PHEV_" + k.uppercase().replace('.', '_')) ?: p.getProperty(k) ?: d).trim()
            return BridgeConfig(
                carHost = s("car.host", "192.168.8.46"),
                carPort = s("car.port", "8080").toInt(),
                listenHost = s("listen.host", "0.0.0.0"),
                listenPort = s("listen.port", "8099").toInt(),
                token = s("token", ""),
                mode = LinkMode.valueOf(s("mode", "interval").uppercase()),
                intervalMinutes = s("interval.minutes", "10").toInt().coerceAtLeast(1),
                lingerSeconds = s("linger.seconds", "90").toInt().coerceAtLeast(10),
                ntfyUrl = s("ntfy.url", ""),
                ntfyToken = s("ntfy.token", ""),
                stateFile = s("state.file", "phev-bridge-state.json"),
                logFile = s("log.file", ""),
                emulator = s("emulator", "false").toBoolean(),
                syncClock = s("sync.clock", "true").toBoolean(),
                carIface = s("car.iface", ""),
                pairingMinutes = s("pairing.minutes", "10").toIntOrNull()?.coerceIn(0, 60) ?: 10,
                wifiHelper = s("wifi.helper", "/opt/phev-bridge/carwifi.sh"),
            )
        }

        /** Sets `key=value` in a properties file, keeping every other line and comment. */
        fun saveProperty(file: File, key: String, value: String): Boolean = runCatching {
            val lines = if (file.exists()) file.readLines() else SAMPLE.lines()
            var replaced = false
            val out = lines.map { line ->
                if (line.trimStart().startsWith("$key=")) {
                    replaced = true; "$key=$value"
                } else line
            }.toMutableList()
            if (!replaced) out += "$key=$value"
            file.writeText(out.joinToString("\n") + "\n")
            true
        }.getOrDefault(false)

        fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return String(CharArray(24) { alphabet[r.nextInt(alphabet.length)] })
        }

        val SAMPLE = """
            # PHEV Link bridge configuration
            # The car's Wi-Fi module (do not change unless you know why)
            car.host=192.168.8.46
            car.port=8080
            # Where the bridge API and web page listen
            listen.host=0.0.0.0
            listen.port=8099
            # API token, generated on first start. Keep it secret.
            token=
            # interval = check every few minutes (recommended), persistent = always connected
            mode=interval
            interval.minutes=10
            linger.seconds=90
            # Push alerts to your phone through ntfy (https://ntfy.sh), e.g. https://ntfy.sh/phev-<random>
            ntfy.url=
            ntfy.token=
            state.file=/var/lib/phev-bridge/state.json
            log.file=/var/log/phev-bridge.log
            # Set the car's clock from the bridge on every connection
            sync.clock=true
            # true = run against a simulated car (try it before going near the real one)
            emulator=false
            # Wi-Fi interface for the car; empty = pick a radio that isn't carrying the home network
            car.iface=
            # The app can pair (fetch the token) once within this many minutes after the bridge is powered on; 0 = off
            pairing.minutes=10
        """.trimIndent()
    }
}
