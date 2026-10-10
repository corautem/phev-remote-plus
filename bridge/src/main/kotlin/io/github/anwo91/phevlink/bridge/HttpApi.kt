package io.github.anwo91.phevlink.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CommandResult
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Small HTTP API on the JDK's built-in server (no dependencies):
 *
 *   GET  /                 control page (asks for the token once)
 *   GET  /api/status       current state as JSON
 *   GET  /api/events       server-sent events, one "status" event per change
 *   POST /api/command      {"action":"climate","mode":"HEAT","minutes":20} -> {"ok":true,...}
 *                          vehicle settings: {"action":"setting","id":7,"value":2}, {"action":"reset_settings"}
 *   POST /api/connect      connect now
 *   POST /api/disconnect   disconnect now
 *   GET  /api/log?n=300    diagnostic log (text)
 *
 * Every /api call needs the token: "Authorization: Bearer <token>" or ?token=<token>.
 */
class HttpApi(
    private val config: BridgeConfig,
    private val service: BridgeService,
    private val log: DiagLog,
) {
    private lateinit var server: HttpServer

    fun start(port: Int = config.listenPort): Int {
        server = HttpServer.create(InetSocketAddress(config.listenHost, port), 32)
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "http").apply { isDaemon = true } }
        server.createContext("/") { ex -> safe(ex) { root(ex) } }
        server.createContext("/api/") { ex -> safe(ex) { api(ex) } }
        server.start()
        return server.address.port
    }

    fun stop() = server.stop(0)

    private fun root(ex: HttpExchange) {
        if (ex.requestURI.path != "/" && ex.requestURI.path != "/index.html") return send(ex, 404, "text/plain", "Not found")
        send(ex, 200, "text/html; charset=utf-8", WebUi.HTML)
    }

    private fun api(ex: HttpExchange) {
        if (ex.requestMethod == "OPTIONS") return send(ex, 204, "text/plain", "")
        if (!authorized(ex)) return send(ex, 401, "application/json", """{"ok":false,"error":"Missing or wrong token"}""")
        val path = ex.requestURI.path.removePrefix("/api/")
        when {
            path == "status" && ex.requestMethod == "GET" -> json(ex, 200, service.snapshot())
            path == "events" && ex.requestMethod == "GET" -> events(ex)
            path == "command" && ex.requestMethod == "POST" -> command(ex)
            path == "connect" && ex.requestMethod == "POST" -> {
                runBlocking { service.connectNow() }
                json(ex, 200, mapOf("ok" to true))
            }
            path == "disconnect" && ex.requestMethod == "POST" -> {
                runBlocking { service.disconnectNow() }
                json(ex, 200, mapOf("ok" to true))
            }
            path == "log" && ex.requestMethod == "GET" -> {
                val n = query(ex)["n"]?.toIntOrNull() ?: 300
                send(ex, 200, "text/plain; charset=utf-8", log.tail(n).joinToString("\n") { it.format(ZoneId.systemDefault()) })
            }
            else -> json(ex, 404, mapOf("ok" to false, "error" to "Unknown endpoint"))
        }
    }

    private fun command(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val params: Map<String, Any?> = if (body.isNotBlank()) Json.parseObject(body) else query(ex)
        val cmd = try {
            Codecs.commandFromMap(params)
        } catch (e: Exception) {
            return json(ex, 400, mapOf("ok" to false, "error" to (e.message ?: "Bad command")))
        }
        log.add(io.github.anwo91.phevlink.core.diag.LogKind.CMD, "API command from ${ex.remoteAddress.address.hostAddress}: ${cmd.label}")
        val r = runBlocking { service.execute(cmd) }
        when (r) {
            is CommandResult.Success -> json(ex, 200, mapOf("ok" to true, "message" to "${cmd.label}: done", "tookMs" to r.tookMs))
            is CommandResult.Failure -> json(ex, 502, mapOf("ok" to false, "error" to r.reason))
        }
    }

    private fun events(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, 0)
        val queue = LinkedBlockingQueue<Map<String, Any?>>()
        val listener: (Map<String, Any?>) -> Unit = { queue.offer(it) }
        service.addListener(listener)
        val out = ex.responseBody
        try {
            out.write(sse(service.snapshot()))
            out.flush()
            while (true) {
                var snap = queue.poll(15, TimeUnit.SECONDS)
                if (snap == null) {
                    out.write(": keepalive\n\n".toByteArray())
                } else {
                    // Coalesce bursts (the car streams many registers at once).
                    Thread.sleep(150)
                    while (true) snap = queue.poll() ?: break
                    out.write(sse(snap!!))
                }
                out.flush()
            }
        } catch (_: Exception) {
            // client went away
        } finally {
            service.removeListener(listener)
            runCatching { ex.close() }
        }
    }

    private fun sse(snapshot: Map<String, Any?>) = "event: status\ndata: ${Json.write(snapshot)}\n\n".toByteArray()

    private fun authorized(ex: HttpExchange): Boolean {
        if (config.token.isBlank()) return true
        val header = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
        val given = header ?: query(ex)["token"] ?: return false
        return MessageDigest.isEqual(given.toByteArray(), config.token.toByteArray())
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery?.split('&')?.mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }?.toMap() ?: emptyMap()

    private fun json(ex: HttpExchange, code: Int, body: Any?) = send(ex, code, "application/json", Json.write(body))

    private fun send(ex: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    private inline fun safe(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("HTTP ${ex.requestURI.path} failed: ${e.message}")
            runCatching { send(ex, 500, "application/json", Json.write(mapOf("ok" to false, "error" to (e.message ?: "error")))) }
        }
    }
}
