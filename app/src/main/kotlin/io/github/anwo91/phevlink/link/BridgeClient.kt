package io.github.anwo91.phevlink.link

import android.content.Context
import androidx.annotation.StringRes
import io.github.anwo91.phevlink.PhevApp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.json.obj
import io.github.anwo91.phevlink.core.json.str
import io.github.anwo91.phevlink.core.json.long
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.session.LinkStatus
import io.github.anwo91.phevlink.util.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Talks to the PHEV Link bridge (a small always-on computer near the car).
 * Live state arrives over server-sent events; commands are plain POSTs.
 * Error texts are in the app language ([context] is only used for that).
 */
class BridgeClient(
    private val baseUrl: String,
    private val token: String,
    private val log: DiagLog,
    private val context: Context = PhevApp.instance,
) {
    /** One reminder the bridge raised (left unlocked, charge complete …), in the bridge's clock. */
    /** [params]: numbers to put into words here (the weekly summary). */
    data class BridgeAlert(val type: String, val message: String, val timeMs: Long, val params: Map<String, Any?> = emptyMap())

    data class Snapshot(
        val link: LinkStatus,
        val car: CarState,
        val bridgeNowMs: Long?,
        val reach: BridgeReach? = null,
        /** The bridge's latest alerts (newest last), for phones that show the bridge's alerts. */
        val alerts: List<BridgeAlert> = emptyList(),
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()
    private val _reachable = MutableStateFlow<Boolean?>(null)
    val reachable: StateFlow<Boolean?> = _reachable.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var events: Job? = null
    @Volatile private var eventConn: HttpURLConnection? = null

    private fun url(path: String) = baseUrl.trimEnd('/') + "/api/" + path

    private fun errorText(@StringRes id: Int, vararg args: Any): String {
        val res = AppLocale.localized(context)
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }

    suspend fun fetchStatus(timeoutMs: Int = 4_000): Snapshot? = withContext(Dispatchers.IO) {
        try {
            val c = open("status", "GET", timeoutMs)
            val code = c.responseCode
            if (code == 401) {
                fail(errorText(R.string.bridge_err_token), hard = true); return@withContext null
            }
            if (code != 200) {
                fail(errorText(R.string.bridge_err_http, code), hard = true); return@withContext null
            }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            parse(text).also { _snapshot.value = it; answered("status") }
        } catch (e: Exception) {
            fail(errorText(R.string.bridge_err_unreachable, e.message ?: e.javaClass.simpleName))
            null
        }
    }

    suspend fun execute(cmd: CarCommand): CommandResult = withContext(Dispatchers.IO) {
        try {
            // The bridge waits for the car to confirm some commands (climate running) on top of reaching it.
            val c = open("command", "POST", (60_000 + cmd.confirmMs).toInt())
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(Json.write(Codecs.commandToMap(cmd)).toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code != 401) answered("command")
            val m = runCatching { Json.parseObject(body) }.getOrDefault(emptyMap())
            if (code in 200..299 && m["ok"] == true) {
                CommandResult.Success(cmd, m.long("tookMs") ?: 0)
            } else {
                CommandResult.Failure(cmd, m.str("error") ?: errorText(R.string.bridge_err_http, code))
            }
        } catch (e: Exception) {
            val why = errorText(R.string.bridge_err_unreachable, e.message ?: e.javaClass.simpleName)
            fail(why)
            CommandResult.Failure(cmd, why, retryable = true)
        }
    }

    suspend fun post(path: String) = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(path, "POST", 8_000)
            c.doOutput = true
            c.outputStream.use { }
            c.responseCode
            c.disconnect()
        }
    }

    /** Follow live updates until [stopEvents]. Reconnects on its own. */
    fun startEvents(scope: CoroutineScope) {
        if (events?.isActive == true) return
        events = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive) {
                try {
                    val c = URI(url("events") + "?token=" + URLEncoder.encode(token, "UTF-8")).toURL()
                        .openConnection() as HttpURLConnection
                    c.connectTimeout = 6_000
                    c.readTimeout = 40_000 // the bridge sends a keepalive every 15 s
                    c.setRequestProperty("Accept", "text/event-stream")
                    eventConn = c
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    answered("live updates")
                    backoff = 1_000
                    BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                        val data = StringBuilder()
                        while (isActive) {
                            val line = r.readLine() ?: break
                            // Any line (data or the 15 s keepalive) proves the bridge is there — also after a
                            // command or status request failed meanwhile while this stream kept running.
                            if (_reachable.value != true || failingSince != null) answered("live updates")
                            when {
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                line.isEmpty() && data.isNotEmpty() -> {
                                    runCatching { _snapshot.value = parse(data.toString()) }
                                    data.clear()
                                }
                            }
                        }
                    }
                    c.disconnect()
                } catch (e: Exception) {
                    if (!isActive) break
                    fail(errorText(R.string.bridge_err_events, e.message ?: e.javaClass.simpleName))
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    fun stopEvents() {
        events?.cancel()
        events = null
        // Unblock the reader right away instead of waiting for the next keepalive.
        eventConn?.let { c -> Thread { runCatching { c.disconnect() } }.start() }
        eventConn = null
    }

    private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URI(url(path)).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs.coerceAtMost(8_000)
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer $token")
        // Who is asking, for the bridge's activity log.
        c.setRequestProperty("X-PHEV-Device", BridgeSetupApi.deviceHeader())
        return c
    }

    /** When talking to the bridge started failing (null = it answers); see [fail]. */
    @Volatile private var failingSince: Long? = null

    /** The bridge answered (an HTTP reply, or a line on the live stream). */
    private fun answered(what: String) {
        val since = failingSince
        if (_reachable.value == false || since != null) {
            log.link("Bridge answers again ($what)" + (since?.let { " after ${(System.currentTimeMillis() - it) / 1000} s" } ?: ""))
        }
        failingSince = null
        _reachable.value = true
        _error.value = null
    }

    /**
     * Talking to the bridge failed. A single failed request or a dropped live stream (the phone changing networks, a
     * busy bridge) isn't an outage: "unreachable" is shown only once failures have lasted [UNREACHABLE_AFTER_MS] with
     * nothing answering in between, or right away when it was never reached yet or for a [hard] answer (wrong token,
     * HTTP error).
     */
    private fun fail(msg: String, hard: Boolean = false) {
        val now = System.currentTimeMillis()
        val since = failingSince ?: now.also { failingSince = it }
        val report = hard || _reachable.value != true || now - since >= UNREACHABLE_AFTER_MS
        if (_error.value != msg || (report && _reachable.value == true)) {
            log.warn(msg + if (report) "" else " — trying again; not shown as unreachable yet")
        }
        _error.value = msg
        if (report) _reachable.value = false
    }

    private companion object {
        /** How long failures must last (with nothing answering) before the app says the bridge is unreachable. */
        const val UNREACHABLE_AFTER_MS = 20_000L
    }

    private fun parse(text: String): Snapshot {
        val m = Json.parseObject(text)
        return Snapshot(
            link = m.obj("link")?.let(Codecs::linkStatusFromMap) ?: LinkStatus.Idle(),
            car = m.obj("car")?.let(Codecs::carStateFromMap) ?: CarState(),
            bridgeNowMs = m.obj("bridge")?.long("nowMs"),
            reach = BridgeReach.from(m.obj("reach")),
            alerts = (m.obj("bridge")?.get("alerts") as? List<*>).orEmpty().mapNotNull { a ->
                val am = a as? Map<*, *> ?: return@mapNotNull null
                val type = am["type"] as? String ?: return@mapNotNull null
                val time = (am["timeMs"] as? Number)?.toLong() ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val params = (am["params"] as? Map<String, Any?>).orEmpty()
                BridgeAlert(type, am["message"] as? String ?: "", time, params)
            },
        )
    }
}

/**
 * Whether the bridge can reach the car (bridge with "reach"): `in_reach` (on the car's Wi-Fi), `checking`,
 * `not_seen` (off the car's Wi-Fi for a while and not seen in a real scan: away, or just out of reach),
 * `stepped_aside` (made room for a phone) or `unknown`.
 */
data class BridgeReach(
    val state: String,
    val sinceMs: Long?,
    /** Last time the bridge was on the car's Wi-Fi, saw it, or heard from it. */
    val lastSeenMs: Long?,
    /** When the bridge last looked for the car's Wi-Fi. */
    val checkedAtMs: Long?,
    val inSight: Boolean?,
) {
    val notSeen: Boolean get() = state == NOT_SEEN
    val inReach: Boolean get() = state == IN_REACH

    override fun toString(): String = state + (lastSeenMs?.let { " (last seen ${io.github.anwo91.phevlink.util.Format.clockOrDay(it)})" } ?: "")

    companion object {
        const val IN_REACH = "in_reach"
        const val CHECKING = "checking"
        const val NOT_SEEN = "not_seen"
        const val STEPPED_ASIDE = "stepped_aside"

        /** Null when the bridge has nothing to say ("unknown": no car Wi-Fi to watch) — then the older rules apply. */
        fun from(m: Map<String, Any?>?): BridgeReach? {
            val state = m?.str("state")?.takeIf { it != "unknown" } ?: return null
            return BridgeReach(state, m.long("sinceMs"), m.long("lastSeenMs"), m.long("checkedAtMs"), m["inSight"] as? Boolean)
        }
    }
}
