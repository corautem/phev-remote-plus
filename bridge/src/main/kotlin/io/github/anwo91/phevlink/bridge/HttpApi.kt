package io.github.anwo91.phevlink.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CommandResult
import kotlinx.coroutines.runBlocking
import java.io.File
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
 *   GET  /api/carwifi      the bridge's own link to the car's Wi-Fi (interface, connected, IP, MAC, signal, problem)
 *   POST /api/carwifi/scan {"country":"NO"} -> networks the car radio sees
 *   POST /api/carwifi/join {"ssid":"REMOTExxxxxx","password":"…","country":"NO"} -> save + connect the car network
 *   POST /api/carwifi/forget
 *   POST /api/config       {"emulator":false} -> saved; the bridge restarts to apply it
 *
 * Every /api call needs the token ("Authorization: Bearer <token>" or ?token=<token>), except:
 *   GET  /api/hello        who am I (for the app's discovery) + whether pairing is open
 *   POST /api/pair         with header "X-PHEV-Pair: 1": the token, once, within [Pairing]'s window after power-on.
 *                          The custom header makes browsers preflight (which this server refuses), and the answer has
 *                          no CORS header, so a web page can't pair behind the user's back.
 */
class HttpApi(
    private val config: BridgeConfig,
    private val service: BridgeService,
    private val log: DiagLog,
    private val pairing: Pairing? = null,
    private val carWifi: CarWifi? = null,
    private val settingsFile: File? = null,
    private val restart: () -> Unit = {},
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
        val path = ex.requestURI.path.removePrefix("/api/")
        if (path == "hello" && ex.requestMethod == "GET") return hello(ex)
        if (path == "pair" && ex.requestMethod == "POST") return pair(ex)
        if (!authorized(ex)) return send(ex, 401, "application/json", """{"ok":false,"error":"Missing or wrong token"}""")
        when {
            path == "carwifi" && ex.requestMethod == "GET" -> withWifi(ex) { json(ex, 200, it.status().toMap()) }
            path == "carwifi/scan" && ex.requestMethod == "POST" -> withWifi(ex) { wifi ->
                val (nets, error) = wifi.scan(body(ex)["country"]?.toString())
                json(ex, 200, linkedMapOf(
                    "ok" to (error == null), "error" to error,
                    "networks" to nets.map { mapOf("ssid" to it.ssid, "signal" to it.signal, "security" to it.security) },
                ))
            }
            path == "carwifi/join" && ex.requestMethod == "POST" -> withWifi(ex) { wifi ->
                val b = body(ex)
                val ssid = b["ssid"]?.toString().orEmpty()
                val password = b["password"]?.toString().orEmpty()
                log.link("API car Wi-Fi join from ${ex.remoteAddress.address.hostAddress} for \"$ssid\"")
                val r = wifi.join(ssid, password, b["country"]?.toString())
                json(ex, 200, linkedMapOf(
                    "ok" to r.ok, "saved" to r.saved, "connected" to r.connected, "message" to r.message,
                    "status" to r.status.toMap(),
                ))
            }
            path == "carwifi/forget" && ex.requestMethod == "POST" -> withWifi(ex) { json(ex, 200, mapOf("ok" to it.forget())) }
            path == "config" && ex.requestMethod == "POST" -> configChange(ex)
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

    private fun hello(ex: HttpExchange) {
        val w = pairing?.window()
        json(ex, 200, linkedMapOf(
            "app" to "phev-bridge",
            "version" to BridgeService.VERSION,
            "name" to hostName(),
            "features" to BridgeService.FEATURES,
            "emulator" to config.emulator,
            "pairing" to linkedMapOf("open" to (w?.open == true), "secondsLeft" to (w?.secondsLeft ?: 0), "reason" to (w?.reason ?: "disabled")),
        ))
    }

    private fun pair(ex: HttpExchange) {
        val who = ex.remoteAddress.address.hostAddress +
            (ex.requestHeaders.getFirst("X-PHEV-Device")?.take(60)?.let { " ($it)" } ?: "")
        if (ex.requestHeaders.getFirst("X-PHEV-Pair") != "1") {
            log.warn("Pairing request from $who without the pairing header — refused")
            return send(ex, 400, "application/json", Json.write(mapOf("ok" to false, "error" to "Missing pairing header")), cors = false)
        }
        val p = pairing
        if (p == null || !p.claim(who)) {
            val reason = p?.window()?.reason ?: "disabled"
            return send(ex, 403, "application/json", Json.write(mapOf("ok" to false, "error" to "Pairing is closed", "reason" to reason)), cors = false)
        }
        send(ex, 200, "application/json", Json.write(mapOf("ok" to true, "token" to config.token, "name" to hostName())), cors = false)
    }

    private fun configChange(ex: HttpExchange) {
        val b = body(ex)
        val emulator = b["emulator"] as? Boolean ?: return json(ex, 400, mapOf("ok" to false, "error" to "Nothing to change"))
        if (emulator == config.emulator) return json(ex, 200, mapOf("ok" to true, "restarting" to false))
        val file = settingsFile ?: return json(ex, 409, mapOf("ok" to false, "error" to "No config file to save to"))
        if (!BridgeConfig.saveProperty(file, "emulator", emulator.toString())) {
            return json(ex, 500, mapOf("ok" to false, "error" to "Could not write ${file.path}"))
        }
        log.link("API: emulator=${emulator} saved by ${ex.remoteAddress.address.hostAddress} — restarting to apply it")
        json(ex, 200, mapOf("ok" to true, "restarting" to true))
        restart()
    }

    private inline fun withWifi(ex: HttpExchange, block: (CarWifi) -> Unit) {
        val w = carWifi ?: return json(ex, 503, mapOf("ok" to false, "error" to "Car Wi-Fi control is not available here"))
        block(w)
    }

    private fun body(ex: HttpExchange): Map<String, Any?> {
        val text = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        return if (text.isBlank()) emptyMap() else runCatching { Json.parseObject(text) }.getOrDefault(emptyMap())
    }

    private fun hostName(): String =
        runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.ifEmpty { null } ?: "phev-bridge"

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

    private fun send(ex: HttpExchange, code: Int, type: String, body: String, cors: Boolean = true) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        if (cors) {
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type")
        }
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
