package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.activity.Activity
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
 *   GET  /api/log?n=300    diagnostic log (text); &file=1: from the log file, earlier runs included
 *   GET  /api/carwifi      the bridge's own link to the car's Wi-Fi (interface, connected, IP, MAC, signal, problem)
 *   POST /api/carwifi/scan {"country":"NO"} -> networks the car radio sees
 *   POST /api/carwifi/join {"ssid":"REMOTExxxxxx","password":"…","country":"NO"} -> save + connect the car network
 *   POST /api/carwifi/forget
 *   POST /api/carwifi/pause {"minutes":10} -> leave the car's Wi-Fi so a phone can use it (comes back by itself)
 *   POST /api/carwifi/resume -> back on the car's Wi-Fi now
 *   GET  /api/mqtt         the smart-home link (Home Assistant over MQTT): settings (no password), status, last command
 *   POST /api/mqtt         {"enabled":true,"host":"192.168.1.10","port":1883,"username":"…","password":"…","language":"nb"}
 *                          -> saved, (re)connected; answers once connected or failed (a few seconds)
 *   POST /api/mqtt/find    MQTT brokers on the home network (and which run Home Assistant)
 *   POST /api/mqtt/local   set up an MQTT broker (Mosquitto) on the bridge computer and use it (runs in the background)
 *   GET  /api/matter       the car in Google Home / Apple Home / Alexa through Matter: on/off, pairing code (QR and
 *                          manual), paired controllers, what is shared
 *   POST /api/matter       {"enabled":true,"devices":{"climate":true,"headlights":false,"parking":false},"language":"nb"}
 *   POST /api/matter/reset remove every pairing; a new code
 *   POST /api/carwifi/rejoin -> if the bridge is off the car's Wi-Fi (and not paused), ask NetworkManager to join it now;
 *                          {"connected":…,"tried":…,"message":…,"inSight":…,"signal":…} (takes up to ~35 s)
 *   POST /api/pair/open    {"minutes":10} -> "Add another phone": pairing open for one more phone (needs the token)
 *   POST /api/config       {"emulator":false} -> saved; the bridge restarts to apply it
 *   GET  /api/system       the computer: model, OS, uptime, temperature, memory, disk, addresses, bridge build
 *   GET  /api/update       installed/newest version, whether an update runs, its log (?check=1 asks GitHub now)
 *   POST /api/update       start an update (download the newest release, check it, install, restart)
 *   POST /api/restart      restart the bridge program (systemd starts it again)
 *   POST /api/reboot       restart the computer
 *   GET  /api/shared       what all phones share: departures, car name, range, climate defaults, weather place, time zone
 *                          (+ "rev", bumped on every change, and "lastDeparture": how the last bridge-run departure went)
 *   POST /api/shared       {"departures":[…],"carName":"…"} -> saved (only the keys sent; last writer wins per key)
 *   GET  /api/activity     ?limit=200&before=<ms>: who did what and what the car did, newest first (+ English "text")
 *   POST /api/activity     {"entries":[…]}: a phone reports what it did straight over the car's Wi-Fi ("cmd.…" only)
 *   GET  /api/history      ?limit=300: charges (energy, cost) and trips, newest first, this week, this month, the price plan
 *
 * Phones name themselves in the "X-PHEV-Device" header (URL-encoded); that name goes into the activity log.
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
    private val updater: Updater? = null,
    private val shared: SharedStore? = null,
    private val activity: ActivityLog? = null,
    private val watch: CarWifiWatch? = null,
    private val smartHome: SmartHome? = null,
    private val matter: MatterLink? = null,
    private val history: EnergyHistory? = null,
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
            path == "carwifi" && ex.requestMethod == "GET" -> withWifi(ex) { json(ex, 200, it.status().toMap() + ("pausedUntilMs" to it.pausedUntilMs)) }
            path == "carwifi/pause" && ex.requestMethod == "POST" -> withWifi(ex) { wifi ->
                val minutes = (body(ex)["minutes"] as? Number)?.toInt() ?: 10
                log.link("API: leave the car's Wi-Fi for $minutes min, asked by ${ex.remoteAddress.address.hostAddress}")
                runBlocking { service.disconnectNow() }
                val why = wifi.pause(minutes) { runBlocking { service.connectNow() } }
                if (why != null) runBlocking { service.connectNow() }
                record(ex, "bridge.carwifi_paused", mapOf("minutes" to minutes), why)
                json(ex, if (why == null) 200 else 500, mapOf("ok" to (why == null), "error" to why, "pausedUntilMs" to wifi.pausedUntilMs))
            }
            path == "carwifi/resume" && ex.requestMethod == "POST" -> withWifi(ex) { wifi ->
                val why = wifi.resume("asked by ${who(ex)}")
                runBlocking { service.connectNow() }
                record(ex, "bridge.carwifi_resumed", emptyMap(), why)
                json(ex, if (why == null) 200 else 500, mapOf("ok" to (why == null), "error" to why))
            }
            path == "carwifi/rejoin" && ex.requestMethod == "POST" -> withWifi(ex) { wifi ->
                // A phone is about to fall back to the car's own Wi-Fi: get the bridge back on it first if it can be.
                val w = watch ?: return@withWifi json(ex, 503, mapOf("ok" to false, "error" to "The car Wi-Fi watch is not running here"))
                val r = w.check(rejoinNow = true, who = who(ex))
                log.link("API: rejoin the car's Wi-Fi, asked by ${who(ex)} — ${r.message}")
                json(ex, 200, linkedMapOf<String, Any?>("ok" to true, "pausedUntilMs" to wifi.pausedUntilMs) + r.toMap())
            }
            path == "matter" && ex.requestMethod == "GET" -> withMatter(ex) { json(ex, 200, it.describe()) }
            path == "matter" && ex.requestMethod == "POST" -> withMatter(ex) { m ->
                val wasOn = m.settings.enabled
                m.change(body(ex), who(ex))
                if (m.settings.enabled != wasOn) record(ex, if (m.settings.enabled) "bridge.matter_on" else "bridge.matter_off", emptyMap(), null)
                // Starting takes a moment: answer with the code once it is there (or after a few seconds).
                val until = System.currentTimeMillis() + 8_000
                while (m.settings.enabled && System.currentTimeMillis() < until && m.describe()["phase"] == "starting") Thread.sleep(200)
                json(ex, 200, linkedMapOf<String, Any?>("ok" to true) + m.describe())
            }
            path == "matter/reset" && ex.requestMethod == "POST" -> withMatter(ex) { m ->
                m.reset(who(ex))
                record(ex, "bridge.matter_reset", emptyMap(), null)
                json(ex, 200, linkedMapOf<String, Any?>("ok" to true) + m.describe())
            }
            path == "mqtt" && ex.requestMethod == "GET" -> withSmartHome(ex) { json(ex, 200, it.describe()) }
            path == "mqtt" && ex.requestMethod == "POST" -> withSmartHome(ex) { sh ->
                val b = body(ex)
                val wasOn = sh.settings.enabled
                val bad = sh.change(b, who(ex))
                if (bad != null) return@withSmartHome json(ex, 400, mapOf("ok" to false, "error" to bad))
                val on = sh.settings.enabled
                if (on != wasOn || (on && b.containsKey("host"))) {
                    record(ex, if (on) "bridge.smarthome_on" else "bridge.smarthome_off", if (on) mapOf("broker" to sh.settings.host) else emptyMap(), null)
                }
                json(ex, 200, linkedMapOf<String, Any?>("ok" to true) + sh.describe())
            }
            path == "mqtt/find" && ex.requestMethod == "POST" -> withSmartHome(ex) { sh ->
                json(ex, 200, mapOf("ok" to true, "brokers" to runBlocking { sh.findBrokers() }))
            }
            path == "mqtt/local" && ex.requestMethod == "POST" -> withSmartHome(ex) { sh ->
                val started = sh.setUpLocalBroker(who(ex))
                json(ex, 200, linkedMapOf<String, Any?>("ok" to true, "started" to started) + sh.describe())
            }
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
            path == "pair/open" && ex.requestMethod == "POST" -> {
                val p = pairing ?: return json(ex, 503, mapOf("ok" to false, "error" to "Pairing is not available here"))
                val minutes = (body(ex)["minutes"] as? Number)?.toInt() ?: 10
                val seconds = p.invite(minutes, who(ex))
                record(ex, "bridge.invite", mapOf("minutes" to minutes), null)
                json(ex, 200, mapOf("ok" to true, "secondsLeft" to seconds))
            }
            path == "system" && ex.requestMethod == "GET" -> json(ex, 200, linkedMapOf(
                "build" to BridgeService.BUILD, "version" to BridgeService.VERSION,
                "startedAtMs" to service.startedAtMs, "nowMs" to System.currentTimeMillis(),
            ) + SystemInfo.read())
            path == "update" && ex.requestMethod == "GET" -> withUpdater(ex) { json(ex, 200, it.status(force = query(ex)["check"] == "1")) }
            path == "update" && ex.requestMethod == "POST" -> withUpdater(ex) { u ->
                val why = u.start(who(ex))
                record(ex, "bridge.update", emptyMap(), why)
                json(ex, if (why == null) 200 else 500, mapOf("ok" to (why == null), "error" to why))
            }
            path == "restart" && ex.requestMethod == "POST" -> {
                log.link("API: restart requested by ${who(ex)}")
                record(ex, "bridge.restart", emptyMap(), null)
                json(ex, 200, mapOf("ok" to true))
                restart()
            }
            path == "reboot" && ex.requestMethod == "POST" -> withUpdater(ex) { u ->
                val why = u.reboot(who(ex))
                record(ex, "bridge.reboot", emptyMap(), why)
                json(ex, if (why == null) 200 else 500, mapOf("ok" to (why == null), "error" to why))
            }
            path == "shared" && ex.requestMethod == "GET" -> withShared(ex) { json(ex, 200, it.snapshot() + ("rev" to it.rev)) }
            path == "shared" && ex.requestMethod == "POST" -> withShared(ex) { s ->
                val by = who(ex)
                val before = s.rev
                val changes = body(ex)
                val next = s.update(changes, by)
                if (s.rev != before) record(ex, "shared.changed", mapOf("keys" to changes.keys.filter { it in SharedStore.KEYS }.joinToString()), null)
                if (s.rev != before && changes.keys.any { it in SharedStore.PRICE_KEYS }) history?.planChanged()
                // The car's clock (set by the bridge) follows the phones' time zone.
                if (s.rev != before && "timeZone" in changes && s.zone() != java.time.ZoneId.systemDefault()) {
                    log.link("Time zone: now ${s.zone()} (from $by)")
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(s.zone()))
                }
                json(ex, 200, mapOf("ok" to true, "shared" to next))
            }
            path == "activity" && ex.requestMethod == "GET" -> {
                val a = activity ?: return json(ex, 503, mapOf("ok" to false, "error" to "No activity log here"))
                val q = query(ex)
                val list = a.recent(q["limit"]?.toIntOrNull() ?: 200, q["before"]?.toLongOrNull())
                json(ex, 200, mapOf("ok" to true, "size" to a.size, "entries" to list.map { it.toMap() + ("text" to ActivityText.english(it)) }))
            }
            path == "activity" && ex.requestMethod == "POST" -> {
                val a = activity ?: return json(ex, 503, mapOf("ok" to false, "error" to "No activity log here"))
                val by = who(ex)
                @Suppress("UNCHECKED_CAST")
                val list = (body(ex)["entries"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(Activity::fromMap) }
                // Phones only report their own actions; what the car did comes from the bridge itself.
                val added = list.filter { it.code.startsWith("cmd.") }.take(500)
                    .count { a.add(it.copy(who = it.who.ifBlank { by }, via = it.via ?: "direct")) }
                json(ex, 200, mapOf("ok" to true, "added" to added))
            }
            path == "history" && ex.requestMethod == "GET" -> {
                val h = history ?: return json(ex, 503, mapOf("ok" to false, "error" to "No history here"))
                json(ex, 200, h.describe(query(ex)["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 300))
            }
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
                val q = query(ex)
                val n = (q["n"]?.toIntOrNull() ?: 300).coerceIn(1, 5000)
                // file=1: from the log file, so lines from before the last restart are included.
                val fromFile = config.logFile.takeIf { q["file"] == "1" && it.isNotBlank() }
                    ?.let { LogFileWriter.tail(File(it), n) }?.takeIf { it.isNotEmpty() }
                send(ex, 200, "text/plain; charset=utf-8", fromFile?.joinToString("\n") ?: log.tail(n).joinToString("\n") { it.format(ZoneId.systemDefault()) })
            }
            else -> json(ex, 404, mapOf("ok" to false, "error" to "Unknown endpoint"))
        }
    }

    private fun hello(ex: HttpExchange) {
        val w = pairing?.window()
        json(ex, 200, linkedMapOf(
            "app" to "phev-bridge",
            "version" to BridgeService.VERSION,
            "build" to BridgeService.BUILD,
            "name" to hostName(),
            "features" to BridgeService.FEATURES,
            "emulator" to config.emulator,
            "pairing" to linkedMapOf("open" to (w?.open == true), "secondsLeft" to (w?.secondsLeft ?: 0), "reason" to (w?.reason ?: "disabled")),
        ))
    }

    private fun pair(ex: HttpExchange) {
        val who = ex.remoteAddress.address.hostAddress + (deviceName(ex)?.let { " ($it)" } ?: "")
        if (ex.requestHeaders.getFirst("X-PHEV-Pair") != "1") {
            log.warn("Pairing request from $who without the pairing header — refused")
            return send(ex, 400, "application/json", Json.write(mapOf("ok" to false, "error" to "Missing pairing header")), cors = false)
        }
        val p = pairing
        if (p == null || !p.claim(who)) {
            if (p != null) log.link("Pairing refused for $who")
            val reason = p?.window()?.reason ?: "disabled"
            return send(ex, 403, "application/json", Json.write(mapOf("ok" to false, "error" to "Pairing is closed", "reason" to reason)), cors = false)
        }
        activity?.add(Activity(System.currentTimeMillis(), deviceName(ex) ?: who, "bridge.paired"))
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

    private inline fun withUpdater(ex: HttpExchange, block: (Updater) -> Unit) {
        val u = updater ?: return json(ex, 503, mapOf("ok" to false, "error" to "Updates are not available here"))
        block(u)
    }

    /** The phone's own name (X-PHEV-Device, URL-encoded), if it sent one. */
    private fun deviceName(ex: HttpExchange): String? = ex.requestHeaders.getFirst("X-PHEV-Device")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }?.trim()?.take(40)?.ifEmpty { null }

    /** Who is asking: the phone's name, else its address. */
    private fun who(ex: HttpExchange): String = deviceName(ex) ?: ex.remoteAddress.address.hostAddress

    private fun record(ex: HttpExchange, code: String, params: Map<String, Any?>, failure: String?) {
        activity?.add(Activity(System.currentTimeMillis(), who(ex), code, params, ok = failure == null, detail = failure))
    }

    private inline fun withMatter(ex: HttpExchange, block: (MatterLink) -> Unit) {
        val m = matter ?: return json(ex, 503, mapOf("ok" to false, "error" to "Matter is not available here"))
        block(m)
    }

    private inline fun withSmartHome(ex: HttpExchange, block: (SmartHome) -> Unit) {
        val s = smartHome ?: return json(ex, 503, mapOf("ok" to false, "error" to "The smart-home link is not available here"))
        block(s)
    }

    private inline fun withShared(ex: HttpExchange, block: (SharedStore) -> Unit) {
        val s = shared ?: return json(ex, 503, mapOf("ok" to false, "error" to "Shared data is not available here"))
        block(s)
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
        log.add(io.github.anwo91.phevlink.core.diag.LogKind.CMD, "API command from ${who(ex)}: ${cmd.label}")
        val r = runBlocking { service.execute(cmd) }
        val action = params["action"]?.toString()?.lowercase()
        if (action != null && action !in ActivityText.QUIET_COMMANDS) {
            val details = if (cmd is CarCommand.SetChargeTimer) mapOf("summary" to cmd.program.summary()) else Codecs.commandToMap(cmd) - "action"
            activity?.add(Activity(System.currentTimeMillis(), who(ex), "cmd.$action", details, ok = r is CommandResult.Success, detail = (r as? CommandResult.Failure)?.reason, via = "bridge"))
        }
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
