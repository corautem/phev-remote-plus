package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Updating and restarting the bridge from the app. The work is done by the root helper [helper]
 * (deploy/bridgectl.sh, allowed through sudo): it downloads the newest release from GitHub in its own systemd unit,
 * checks the SHA-256 and runs the bundled installer, which restarts the bridge on the new version. Progress lands in
 * `update.log` in [stateDir]; this class reads it back for the app. Everything is logged.
 */
class Updater(
    private val helper: String,
    private val stateDir: File,
    private val log: DiagLog,
    private val runner: CarWifi.Runner = ProcessRunner,
    private val fetchLatest: () -> String? = { fetchLatestRelease() },
    private val helperExists: () -> Boolean = { File(helper).exists() },
    private val current: String = BridgeService.BUILD,
) {
    private val logFile get() = File(stateDir, "update.log")
    @Volatile private var latestCache: Pair<Long, String?>? = null

    /** The newest released version ("1.0.40"), cached for 10 minutes; null when GitHub can't be reached. */
    fun latest(force: Boolean = false): String? {
        val c = latestCache
        if (!force && c != null && System.currentTimeMillis() - c.first < CACHE_MS) return c.second
        val v = runCatching { fetchLatest() }.getOrNull()
        latestCache = System.currentTimeMillis() to v
        log.link("Update check: installed $current, newest ${v ?: "unknown (no answer from GitHub)"}")
        return v
    }

    /** What the app shows: installed/newest version, whether an update runs, how the last one ended, its log. */
    fun status(force: Boolean = false): Map<String, Any?> {
        val lines = runCatching { logFile.readLines() }.getOrDefault(emptyList())
        val last = lines.lastOrNull { it.startsWith("@") }
        val running = runner.run(listOf("systemctl", "is-active", UNIT), null, 5_000).out.trim() == "active"
        val latest = latest(force)
        return linkedMapOf(
            "current" to current,
            "latest" to latest,
            "available" to (latest != null && isNewer(latest, current)),
            "running" to running,
            "result" to last?.substringBefore(' ')?.removePrefix("@"),
            "detail" to last?.substringAfter(' ', "")?.ifBlank { null },
            "log" to lines.filterNot { it.startsWith("@") }.takeLast(40),
            "canUpdate" to helperExists(),
        )
    }

    /** Starts an update in the background. Returns null when it started (or one already runs), else why not. */
    fun start(who: String): String? {
        if (!helperExists()) return HELPER_MISSING.also { log.warn("Update requested by $who, but $helper is missing — update the bridge once by hand") }
        log.link("Update requested by $who (installed $current, newest ${latestCache?.second ?: "not checked"})")
        val r = runner.run(listOf("sudo", "-n", helper, "update-start"), null, 30_000)
        val word = r.out.lines().map { it.trim() }.lastOrNull { it.startsWith("@") }
        log.link("Update: helper answered ${word ?: r.out.trim().take(200).ifEmpty { "nothing" }} (exit ${r.code})")
        return when {
            word == "@started" || word == "@busy" -> null
            word != null -> word.substringAfter(' ', word)
            sudoRefused(r.out) -> HELPER_MISSING
            else -> r.out.trim().take(200).ifEmpty { "exit ${r.code}" }
        }
    }

    /** Restarts the computer. Null when the restart was started, else why not. */
    fun reboot(who: String): String? {
        if (!helperExists()) return HELPER_MISSING
        log.link("Restart of the computer requested by $who")
        val r = runner.run(listOf("sudo", "-n", helper, "reboot"), null, 15_000)
        val word = r.out.lines().map { it.trim() }.lastOrNull { it.startsWith("@") }
        return when {
            word == "@rebooting" -> null
            sudoRefused(r.out) -> HELPER_MISSING
            else -> (word ?: r.out.trim().take(200)).ifEmpty { "exit ${r.code}" }
        }.also { if (it != null) log.warn("Restart of the computer failed: $it") }
    }

    private fun sudoRefused(out: String) = out.contains("password is required") || out.contains("not allowed") || out.contains("may not run")

    companion object {
        const val REPO = "Anwo91/phev-remote-plus"
        const val UNIT = "phev-bridge-update"
        const val HELPER_MISSING = "helper-missing"
        private const val CACHE_MS = 10 * 60_000L

        /** True when [candidate] ("1.0.40") is a newer release than [installed] ("1.0.37"; "dev" counts as oldest). */
        fun isNewer(candidate: String, installed: String): Boolean {
            val a = parts(candidate) ?: return false
            val b = parts(installed) ?: return true
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(v: String): List<Int>? =
            v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

        /** The newest release's version from GitHub ("v1.0.40" → "1.0.40"), or null. */
        fun fetchLatestRelease(): String? {
            val c = URI("https://api.github.com/repos/$REPO/releases/latest").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "phev-bridge/${BridgeService.BUILD}")
            return try {
                if (c.responseCode != 200) null
                else (Json.parseObject(c.inputStream.bufferedReader().use { it.readText() })["tag_name"] as? String)?.removePrefix("v")
            } finally {
                c.disconnect()
            }
        }
    }
}
