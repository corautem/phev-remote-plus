package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The bridge's own connection to the car's Wi-Fi, so the app can set it up — no terminal needed.
 *
 * Reading the state uses plain `nmcli`/`ip` as the bridge user. Changes (scan, join, forget) go through the root-owned
 * helper [helper] (deploy/carwifi.sh), which the installer allows through sudo and which does only those jobs.
 *
 * The car gets its own Wi-Fi interface: [preferredIface] if configured, else the radio already holding the car profile,
 * else a Wi-Fi radio that does not carry the home network (default route). A Pi whose only Wi-Fi radio *is* its home
 * link can't serve the car without cutting itself off — reported as [PROBLEM_SINGLE_RADIO].
 */
class CarWifi(
    private val helper: String,
    private val preferredIface: String,
    private val log: DiagLog,
    private val runner: Runner = ProcessRunner,
    private val readFile: (String) -> String? = { p -> runCatching { File(p).readText() }.getOrNull() },
    private val helperExists: () -> Boolean = { File(helper).exists() },
) {
    /** Runs a command; [Run.code] is -1 on timeout and 127 when the program is missing. Output is stdout + stderr. */
    fun interface Runner {
        fun run(argv: List<String>, stdin: String?, timeoutMs: Long): Run
    }

    data class Run(val code: Int, val out: String)

    data class Device(val name: String, val type: String, val state: String, val connection: String)
    data class Network(val ssid: String, val signal: Int, val security: String)

    data class Status(
        val problem: String?,
        val iface: String?,
        val ifaceState: String?,
        val homeIface: String?,
        val configured: Boolean,
        val ssid: String?,
        val connected: Boolean,
        val ip: String?,
        val mac: String?,
        val signal: Int?,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "problem" to problem, "iface" to iface, "ifaceState" to ifaceState, "homeIface" to homeIface,
            "configured" to configured, "ssid" to ssid, "connected" to connected, "ip" to ip, "mac" to mac, "signal" to signal,
        )
    }

    data class JoinResult(val ok: Boolean, val saved: Boolean, val connected: Boolean, val message: String, val status: Status)

    // ------------------------------------------------------------------ reading

    fun status(): Status {
        val dev = runner.run(listOf("nmcli", "-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device", "status"), null, 8_000)
        if (dev.code != 0) {
            log.warn("Car Wi-Fi: nmcli device status failed (${dev.code}): ${dev.out.trim().take(200)}")
            return Status(PROBLEM_NO_NM, null, null, null, false, null, false, null, null, null)
        }
        val devices = parseDevices(dev.out)
        val home = parseDefaultDev(runner.run(listOf("ip", "route", "show", "default"), null, 4_000).out)
        val profileSsid = runner.run(listOf("nmcli", "-g", "802-11-wireless.ssid", "connection", "show", PROFILE), null, 4_000)
            .takeIf { it.code == 0 }?.out?.lineSequence()?.firstOrNull()?.trim()?.let(::unescape)?.ifEmpty { null }
        val (iface, problem) = chooseIface(devices, home, preferredIface)
        val d = devices.firstOrNull { it.name == iface }
        val problemNow = problem ?: if (!helperExists()) PROBLEM_HELPER_MISSING else null
        if (iface == null || d == null) {
            return Status(problemNow, null, null, home, profileSsid != null, profileSsid, false, null, null, null)
        }
        val connected = d.state == "connected" && d.connection == PROFILE
        val ip = if (connected) {
            runner.run(listOf("nmcli", "-g", "IP4.ADDRESS", "device", "show", iface), null, 4_000).out
                .lineSequence().map { it.substringBefore('|').trim() }.firstOrNull { it.isNotEmpty() }
        } else null
        val signal = if (connected) {
            runner.run(listOf("nmcli", "-t", "-f", "IN-USE,SIGNAL", "device", "wifi", "list", "ifname", iface, "--rescan", "no"), null, 6_000).out
                .lineSequence().map { splitTerse(it) }.firstOrNull { it.firstOrNull() == "*" }?.getOrNull(1)?.toIntOrNull()
        } else null
        val mac = readFile("/sys/class/net/$iface/address")?.trim()?.ifEmpty { null }
        return Status(problemNow, iface, d.state, home, profileSsid != null, profileSsid, connected, ip, mac, signal)
    }

    // ------------------------------------------------------------------ changes (through the helper)

    /** Networks the car's radio sees, strongest first (hidden networks left out). */
    fun scan(country: String?): Pair<List<Network>, String?> {
        val st = status()
        val iface = st.iface ?: return emptyList<Network>() to (st.problem ?: PROBLEM_NO_WIFI)
        if (st.problem == PROBLEM_HELPER_MISSING) return emptyList<Network>() to PROBLEM_HELPER_MISSING
        val args = listOf("scan", iface) + listOfNotNull(cleanCountry(country))
        val r = runHelper(args, null, 40_000)
        if (r.code != 0) {
            val why = helperError(r)
            log.warn("Car Wi-Fi: scan on $iface failed: $why")
            return emptyList<Network>() to why
        }
        val nets = parseScan(r.out)
        log.link("Car Wi-Fi: scan on $iface found ${nets.size} network(s): " + nets.take(8).joinToString { "${it.ssid} (${it.signal}%)" })
        return nets to null
    }

    /** Saves the car's network on the car radio and brings it up. Never logs the password. */
    fun join(ssid: String, password: String, country: String?): JoinResult {
        val before = status()
        val bad = when {
            ssid.isEmpty() || ssid.toByteArray().size > 32 || ssid.any { it.isISOControl() } -> "bad network name"
            password.length !in 8..63 || password.any { it.code !in 0x20..0x7e } -> "the password must be 8-63 plain characters"
            else -> null
        }
        if (bad != null) return JoinResult(false, false, false, bad, before)
        val iface = before.iface ?: return JoinResult(false, false, false, before.problem ?: PROBLEM_NO_WIFI, before)
        if (before.problem == PROBLEM_HELPER_MISSING) return JoinResult(false, false, false, PROBLEM_HELPER_MISSING, before)
        log.link("Car Wi-Fi: joining \"$ssid\" on $iface (home network on ${before.homeIface ?: "?"})")
        val r = runHelper(listOf("join", iface, ssid) + listOfNotNull(cleanCountry(country)), password + "\n", 90_000)
        r.out.lineSequence().filter { it.startsWith("@") && !it.startsWith("@saved") && !it.startsWith("@up-") }
            .forEach { log.link("Car Wi-Fi: ${it.removePrefix("@")}") }
        val saved = r.out.lineSequence().any { it.trim() == "@saved" }
        val upOk = r.out.lineSequence().any { it.trim() == "@up-ok" }
        val upFailed = r.out.lineSequence().firstOrNull { it.startsWith("@up-failed") }?.removePrefix("@up-failed")?.trim()
        val after = status()
        val message = when {
            !saved -> helperError(r)
            upOk || after.connected -> "connected"
            else -> "saved; not connected yet: ${upFailed ?: "unknown"}"
        }
        log.link("Car Wi-Fi: join result — saved=$saved connected=${after.connected} ip=${after.ip ?: "-"} mac=${after.mac ?: "-"}: $message")
        return JoinResult(saved, saved, after.connected, message, after)
    }

    /** Until when the bridge stays off the car's Wi-Fi so a phone can use it (0 = not paused). */
    @Volatile var pausedUntilMs: Long = 0L
        private set
    private val resumeTimer by lazy { java.util.Timer("carwifi-resume", true) }
    private var resumeTask: java.util.TimerTask? = null

    /**
     * Leave the car's Wi-Fi for [minutes] (1–30) so a phone can use it — e.g. to register the phone; the car takes one
     * device at a time. Comes back by itself afterwards (then [onResumed] runs), and after a reboot. Null = paused.
     */
    @Synchronized
    fun pause(minutes: Int, onResumed: () -> Unit = {}): String? {
        val st = status()
        val iface = st.iface ?: return st.problem ?: PROBLEM_NO_WIFI
        if (st.problem == PROBLEM_HELPER_MISSING) return PROBLEM_HELPER_MISSING
        val r = runHelper(listOf("pause", iface), null, 20_000)
        if (r.out.lineSequence().none { it.trim() == "@paused" }) {
            return helperError(r).also { log.warn("Car Wi-Fi: pause on $iface failed: $it") }
        }
        val m = minutes.coerceIn(1, 30)
        pausedUntilMs = System.currentTimeMillis() + m * 60_000L
        resumeTask?.cancel()
        val task = object : java.util.TimerTask() {
            override fun run() {
                resume("the $m-minute pause is over")
                runCatching { onResumed() }
            }
        }
        resumeTask = task
        resumeTimer.schedule(task, m * 60_000L)
        log.link("Car Wi-Fi: left the car's Wi-Fi on $iface for $m min so a phone can use it")
        return null
    }

    /** Join the car's Wi-Fi again after [pause]. Null = done (joining may still take a moment when the car is asleep). */
    @Synchronized
    fun resume(why: String): String? {
        resumeTask?.cancel()
        resumeTask = null
        pausedUntilMs = 0L
        val st = status()
        val iface = st.iface ?: return st.problem ?: PROBLEM_NO_WIFI
        val r = runHelper(listOf("resume", iface), null, 45_000)
        val ok = r.out.lineSequence().any { it.trim() == "@resumed" }
        val up = r.out.lineSequence().firstOrNull { it.startsWith("@up") }?.removePrefix("@")
        log.link("Car Wi-Fi: back on the car's Wi-Fi on $iface ($why) — ${up ?: r.out.trim().take(200)}")
        return if (ok) null else helperError(r)
    }

    fun forget(): Boolean {
        val r = runHelper(listOf("forget"), null, 20_000)
        log.link("Car Wi-Fi: forget — ${r.out.trim().ifEmpty { "code ${r.code}" }}")
        return r.code == 0
    }

    private fun runHelper(args: List<String>, stdin: String?, timeoutMs: Long): Run =
        runner.run(listOf("sudo", "-n", helper) + args, stdin, timeoutMs)

    private fun helperError(r: Run): String {
        r.out.lineSequence().firstOrNull { it.startsWith("@error") }?.let { return it.removePrefix("@error").trim() }
        return when {
            r.code == -1 -> "timed out"
            r.out.contains("password is required") || r.out.contains("not allowed") || r.out.contains("may not run") ->
                PROBLEM_HELPER_MISSING // sudo refused: the installer's sudoers rule is missing
            r.code == 127 -> PROBLEM_HELPER_MISSING
            else -> "failed (${r.code}): ${r.out.trim().take(200)}"
        }
    }

    companion object {
        const val PROFILE = "phev-car"
        const val PROBLEM_NO_NM = "no-networkmanager"
        const val PROBLEM_NO_WIFI = "no-wifi"
        const val PROBLEM_SINGLE_RADIO = "single-radio"
        const val PROBLEM_HELPER_MISSING = "helper-missing"

        private fun cleanCountry(cc: String?): String? = cc?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) }

        /** Splits one line of `nmcli -t` output on unescaped ':' and unescapes `\:` and `\\`. */
        fun splitTerse(line: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length -> { cur.append(line[i + 1]); i += 2; continue }
                    c == ':' -> { out += cur.toString(); cur.setLength(0) }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString()
            return out
        }

        fun unescape(s: String): String = splitTerse(s).joinToString(":")

        fun parseDevices(text: String): List<Device> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = splitTerse(line)
            if (f.size < 4) null else Device(f[0], f[1], f[2], f[3])
        }.toList()

        fun parseDefaultDev(text: String): String? =
            text.lineSequence().mapNotNull { Regex("""\bdev\s+(\S+)""").find(it)?.groupValues?.get(1) }.firstOrNull()

        fun parseScan(text: String): List<Network> = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("@") }
            .mapNotNull { line ->
                val f = splitTerse(line)
                val ssid = f.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Network(ssid, f.getOrNull(1)?.toIntOrNull() ?: 0, f.getOrNull(2).orEmpty())
            }
            .groupBy { it.ssid }.map { (_, same) -> same.maxBy { it.signal } } // one entry per name, strongest AP
            .sortedByDescending { it.signal }

        /**
         * Which interface serves the car, or a problem. Order: the configured one; the radio already holding the car
         * profile; a free Wi-Fi radio that isn't the home link; a Wi-Fi radio busy with something else that isn't the
         * home link (the helper switches its old profile's autoconnect off).
         */
        fun chooseIface(devices: List<Device>, homeDev: String?, preferred: String): Pair<String?, String?> {
            val wifi = devices.filter { it.type == "wifi" }
            if (preferred.isNotBlank()) {
                return if (devices.any { it.name == preferred }) preferred to null else null to PROBLEM_NO_WIFI
            }
            wifi.firstOrNull { it.connection == PROFILE }?.let { return it.name to null }
            if (wifi.isEmpty()) return null to PROBLEM_NO_WIFI
            val notHome = wifi.filter { it.name != homeDev && it.state != "unmanaged" }
            notHome.firstOrNull { it.state == "disconnected" || it.state == "unavailable" }?.let { return it.name to null }
            notHome.firstOrNull()?.let { return it.name to null }
            return null to PROBLEM_SINGLE_RADIO
        }
    }
}

/** Real processes: stdout and stderr merged, killed after the timeout. */
object ProcessRunner : CarWifi.Runner {
    override fun run(argv: List<String>, stdin: String?, timeoutMs: Long): CarWifi.Run = try {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        p.outputStream.use { os -> if (stdin != null) os.write(stdin.toByteArray()) }
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        reader.isDaemon = true
        reader.start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            reader.join(500)
            CarWifi.Run(-1, out.toString())
        } else {
            reader.join(2_000)
            CarWifi.Run(p.exitValue(), out.toString())
        }
    } catch (e: java.io.IOException) {
        CarWifi.Run(127, e.message ?: "cannot run ${argv.firstOrNull()}")
    }
}
