package io.github.anwo91.phevlink.link

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
 */
class BridgeClient(
    private val baseUrl: String,
    private val token: String,
    private val log: DiagLog,
) {
    data class Snapshot(val link: LinkStatus, val car: CarState, val bridgeNowMs: Long?)

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()
    private val _reachable = MutableStateFlow<Boolean?>(null)
    val reachable: StateFlow<Boolean?> = _reachable.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var events: Job? = null

    private fun url(path: String) = baseUrl.trimEnd('/') + "/api/" + path

    suspend fun fetchStatus(timeoutMs: Int = 4_000): Snapshot? = withContext(Dispatchers.IO) {
        try {
            val c = open("status", "GET", timeoutMs)
            val code = c.responseCode
            if (code == 401) {
                fail("Bridge rejected the token"); return@withContext null
            }
            if (code != 200) {
                fail("Bridge answered HTTP $code"); return@withContext null
            }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            parse(text).also { _snapshot.value = it; _reachable.value = true; _error.value = null }
        } catch (e: Exception) {
            fail("Bridge not reachable: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    suspend fun execute(cmd: CarCommand): CommandResult = withContext(Dispatchers.IO) {
        try {
            val c = open("command", "POST", 60_000)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(Json.write(Codecs.commandToMap(cmd)).toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            val m = runCatching { Json.parseObject(body) }.getOrDefault(emptyMap())
            if (code in 200..299 && m["ok"] == true) {
                CommandResult.Success(cmd, m.long("tookMs") ?: 0)
            } else {
                CommandResult.Failure(cmd, m.str("error") ?: "Bridge answered HTTP $code")
            }
        } catch (e: Exception) {
            _reachable.value = false
            CommandResult.Failure(cmd, "Bridge not reachable: ${e.message ?: e.javaClass.simpleName}", retryable = true)
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
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    _reachable.value = true
                    _error.value = null
                    backoff = 1_000
                    BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                        val data = StringBuilder()
                        while (isActive) {
                            val line = r.readLine() ?: break
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
                    fail("Bridge events: ${e.message ?: e.javaClass.simpleName}")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    fun stopEvents() {
        events?.cancel()
        events = null
    }

    private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URI(url(path)).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs.coerceAtMost(8_000)
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun fail(msg: String) {
        if (_error.value != msg) log.warn(msg)
        _error.value = msg
        _reachable.value = false
    }

    private fun parse(text: String): Snapshot {
        val m = Json.parseObject(text)
        return Snapshot(
            link = m.obj("link")?.let(Codecs::linkStatusFromMap) ?: LinkStatus.Idle(),
            car = m.obj("car")?.let(Codecs::carStateFromMap) ?: CarState(),
            bridgeNowMs = m.obj("bridge")?.long("nowMs"),
        )
    }
}
