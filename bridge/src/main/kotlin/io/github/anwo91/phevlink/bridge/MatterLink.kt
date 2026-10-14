package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The car in Google Home (and Apple Home, Alexa, SmartThings…) through Matter, without Home Assistant or a cloud: the
 * bridge runs a small Matter program (bridge/matter, Node.js + matter.js) and talks to it over stdin/stdout. The
 * program shows up as a Matter bridge with the car's climate (a plug), and optionally its headlights and parking
 * lights; you add it in Google Home by scanning the code the app shows.
 *
 * Commands from a controller run like a phone's and go into the activity log as "Google Home" (or whichever controller
 * did it); the car's state goes back so the switches follow the car. Logged as "Matter: …".
 */
class MatterLink(
    stateDir: File,
    private val service: BridgeService,
    private val activity: ActivityLog,
    /** Is the bridge on the car's Wi-Fi (the car is home)? Null = not known. */
    private val home: () -> Boolean?,
    /** The bridge's home network interface (Matter only talks there, not on the car's Wi-Fi). */
    private val homeIface: () -> String?,
    /** "Climate on": the shared one-tap choice, from the weather when that is on. */
    private val climateOn: () -> CarCommand.ClimateOn,
    /** The car's name (shared by the phones), for the bridge's name in Google Home. */
    private val carName: () -> String?,
    private val log: DiagLog,
    private val scope: CoroutineScope,
    /** How to start the Matter program: node and the script. */
    private val program: () -> List<String>? = { defaultProgram(File("/opt/phev-bridge")) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Settings(
        val enabled: Boolean = false,
        val passcode: Int = 0,
        val discriminator: Int = 0,
        val climate: Boolean = true,
        val headlights: Boolean = false,
        val parking: Boolean = false,
        val language: String = "en",
        val serial: String = "",
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "enabled" to enabled, "passcode" to passcode, "discriminator" to discriminator, "climate" to climate,
            "headlights" to headlights, "parking" to parking, "language" to language, "serial" to serial,
        )

        companion object {
            fun from(m: Map<String, Any?>) = Settings(
                enabled = m["enabled"] == true,
                passcode = (m["passcode"] as? Number)?.toInt() ?: 0,
                discriminator = (m["discriminator"] as? Number)?.toInt() ?: 0,
                climate = m["climate"] != false,
                headlights = m["headlights"] == true,
                parking = m["parking"] == true,
                language = m["language"]?.toString() ?: "en",
                serial = m["serial"]?.toString().orEmpty(),
            )
        }
    }

    private val file = File(stateDir, "matter.json")
    private val storage = File(stateDir, "matter")
    @Volatile var settings: Settings = load()
        private set

    @Volatile private var proc: Process? = null
    @Volatile private var input: BufferedWriter? = null
    @Volatile private var phase = "off"
    @Volatile private var error: String? = null
    @Volatile private var errorCode: String? = null
    @Volatile private var qr: String? = null
    @Volatile private var manual: String? = null
    @Volatile private var qrRows: List<String> = emptyList()
    @Volatile private var commissioned = false
    @Volatile private var fabrics: List<Map<String, Any?>> = emptyList()
    @Volatile private var generation = 0
    @Volatile private var lastState: String? = null
    @Volatile private var dirty = true
    @Volatile private var restarts = 0
    /** The Matter program's memory as last seen (sampled every 30 s), for the line when it stops. */
    @Volatile private var lastRssMb: Long? = null
    @Volatile private var lastExit: String? = null
    @Volatile private var startedAt = 0L
    private val writeLock = Any()
    /** node + script, looked up when starting (running `node --version` on every status request would be slow on a Pi). */
    @Volatile private var programCache: List<String>? = null

    init {
        ensureCodes()
    }

    fun start() {
        programCache = runCatching { program() }.getOrNull()
        scope.launch { combine(service.connection.car, service.connection.status) { _, _ -> Unit }.collect { dirty = true } }
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                tick++
                if (proc != null && (dirty || tick % 30 == 0)) pushState(force = false)
                if (tick % 30 == 0) programMb()?.let { lastRssMb = it }
            }
        }
        if (settings.enabled) launchProgram("starting") else log.link("Matter: off")
    }

    // ------------------------------------------------------------------ the app

    fun describe(): Map<String, Any?> {
        val s = settings
        return linkedMapOf(
            "enabled" to s.enabled,
            "available" to (programCache != null),
            "phase" to phase,
            "error" to error,
            "errorCode" to errorCode,
            "qr" to qr,
            "manual" to manual,
            "qrRows" to qrRows,
            "commissioned" to commissioned,
            "fabrics" to fabrics,
            "devices" to linkedMapOf("climate" to s.climate, "headlights" to s.headlights, "parking" to s.parking),
            "programMb" to programMb(),
            "restarts" to restarts,
            "lastExit" to lastExit,
        )
    }

    /** Resident memory of the running Matter program in MB, or null. */
    fun programMb(): Long? = proc?.takeIf { it.isAlive }?.let { RunGuard.rssMb(File("/"), it.pid().toString()) }

    /** "Matter program 84 MB" for the bridge's memory lines, or null when it isn't running. */
    fun memoryNote(): String? = programMb()?.let { "Matter program $it MB" }

    /** Applies [changes] from the app (enabled; devices: climate/headlights/parking; language) and (re)starts. */
    @Synchronized
    fun change(changes: Map<String, Any?>, by: String) {
        val cur = settings
        val devices = changes["devices"] as? Map<*, *>
        val next = cur.copy(
            enabled = changes["enabled"] as? Boolean ?: cur.enabled,
            climate = devices?.get("climate") as? Boolean ?: cur.climate,
            headlights = devices?.get("headlights") as? Boolean ?: cur.headlights,
            parking = devices?.get("parking") as? Boolean ?: cur.parking,
            language = (changes["language"] as? String)?.take(10) ?: cur.language,
        )
        if (next == cur && (proc != null || !next.enabled)) return
        settings = next
        save(next)
        log.link(
            "Matter: settings saved by $by — " + if (next.enabled) "on, sharing " + listOfNotNull(
                "climate".takeIf { next.climate }, "headlights".takeIf { next.headlights }, "parking lights".takeIf { next.parking },
            ).joinToString().ifEmpty { "nothing" } else "off"
        )
        stopProgram("settings changed")
        if (next.enabled) launchProgram("settings changed") else setPhase("off")
    }

    /** Forgets every pairing (Google Home and the rest) and starts again with a new code. */
    @Synchronized
    fun reset(by: String) {
        log.link("Matter: removing every pairing, asked by $by")
        val p = proc
        if (p != null && p.isAlive) {
            send(mapOf("type" to "reset"))
            p.waitFor(15, TimeUnit.SECONDS)
        }
        stopProgram("reset")
        storage.deleteRecursively()
        settings = settings.copy(passcode = 0, discriminator = 0)
        ensureCodes()
        commissioned = false
        fabrics = emptyList()
        if (settings.enabled) launchProgram("reset")
    }

    // ------------------------------------------------------------------ the program

    @Synchronized
    private fun launchProgram(why: String) {
        val cmd = runCatching { program() }.getOrNull()
        programCache = cmd
        if (cmd == null) {
            setPhase("error", "the Matter program isn't installed on the bridge (update the bridge)", "missing")
            log.warn("Matter: can't start — Node.js 18+ or the Matter program is missing (update the bridge from the app)")
            return
        }
        val gen = ++generation
        storage.mkdirs()
        val s = settings
        val config = linkedMapOf<String, Any?>(
            "type" to "config",
            "name" to (runCatching { carName() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Outlander PHEV"),
            "storage" to storage.absolutePath,
            "passcode" to s.passcode,
            "discriminator" to s.discriminator,
            "port" to PORT,
            "interface" to runCatching { homeIface() }.getOrNull(),
            "serial" to s.serial,
            "vendorId" to VENDOR_ID,
            "productId" to PRODUCT_ID,
            "devices" to linkedMapOf(
                "climate" to label("climate", s.language).takeIf { s.climate },
                "headlights" to label("headlights", s.language).takeIf { s.headlights },
                "parking" to label("parking", s.language).takeIf { s.parking },
            ),
        )
        setPhase("starting")
        log.link("Matter: starting ($why): ${cmd.joinToString(" ")}")
        val p = try {
            ProcessBuilder(cmd).start()
        } catch (e: Exception) {
            setPhase("error", "couldn't start Node.js: ${e.message}", "no-node")
            log.warn("Matter: couldn't start ${cmd.first()}: ${e.message}")
            return
        }
        proc = p
        startedAt = System.currentTimeMillis()
        input = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
        lastState = null
        Thread({ readOut(p, gen) }, "matter-out").apply { isDaemon = true; start() }
        Thread({ readErr(p) }, "matter-err").apply { isDaemon = true; start() }
        Thread({ watch(p, gen) }, "matter-exit").apply { isDaemon = true; start() }
        send(config)
    }

    @Synchronized
    private fun stopProgram(why: String) {
        val p = proc ?: return
        generation++
        send(mapOf("type" to "stop"))
        proc = null
        input = null
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        log.link("Matter: stopped ($why)")
    }

    private fun watch(p: Process, gen: Int) {
        val code = p.waitFor()
        val rssBefore = lastRssMb
        lastRssMb = null
        if (gen != generation) return
        proc = null
        if (!settings.enabled) return
        restarts++
        lastExit = "$code" + (exitMeaning(code)?.let { " ($it)" } ?: "")
        val waitS = if (errorCode == "no-ipv6") 600L else longArrayOf(5, 30, 120, 300)[(restarts - 1).coerceIn(0, 3)]
        if (phase != "error") setPhase("error", "the Matter program stopped (exit $lastExit)")
        log.warn(
            "Matter: the program stopped (exit $lastExit) after ${(System.currentTimeMillis() - startedAt) / 1000} s" +
                (rssBefore?.let { " (last seen using $it MB)" } ?: "") + " — starting it again in $waitS s (restart $restarts)",
        )
        scope.launch {
            delay(waitS * 1000)
            synchronized(this@MatterLink) { if (gen == generation && proc == null && settings.enabled) launchProgram("restart after exit $code") }
        }
    }

    private fun readOut(p: Process, gen: Int) {
        p.inputStream.bufferedReader().forEachLine { line ->
            if (gen != generation) return@forEachLine
            val m = runCatching { Json.parseObject(line) }.getOrNull()
            if (m == null) {
                log.link("Matter: ${line.take(200)}")
                return@forEachLine
            }
            runCatching { onMessage(m) }.onFailure { log.warn("Matter: couldn't handle ${line.take(120)}: ${it.message}") }
        }
    }

    private fun readErr(p: Process) {
        p.errorStream.bufferedReader().forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("at ")) return@forEachLine
            // matter.js' own log: warnings and worse into the bridge log, routine notices as plain lines.
            when {
                Regex("""\b(WARN|ERROR|FATAL)\b""").containsMatchIn(t) -> log.warn("Matter: ${t.take(300)}")
                Regex("""\bNOTICE\b""").containsMatchIn(t) -> log.link("Matter: ${t.take(300)}")
            }
        }
    }

    private fun onMessage(m: Map<String, Any?>) {
        when (m["type"]) {
            "ready" -> {
                qr = m["qr"]?.toString()
                manual = m["manual"]?.toString()
                qrRows = (m["qrRows"] as? List<*>).orEmpty().map { it.toString() }.filter { r -> r.all { it == '0' || it == '1' } }
                updateFabrics(m)
                setPhase(if (commissioned) "paired" else "waiting")
                log.link(
                    "Matter: running after ${(System.currentTimeMillis() - startedAt) / 1000} s — " +
                        (if (commissioned) "paired with ${fabricNames()}" else "waiting to be added (code ${manual?.let { formatManual(it) }})") +
                        (programMb()?.let { ", using $it MB" } ?: ""),
                )
                pushState(force = true)
                // How much it settles at (on a 1 GB Raspberry Pi memory can get tight); a stable run resets the backoff.
                val gen = generation
                scope.launch {
                    delay(120_000)
                    if (gen != generation || proc == null) return@launch
                    restarts = 0
                    log.link("Matter: still running after 2 min, using ${programMb() ?: "?"} MB")
                }
            }
            "fabrics" -> {
                val was = fabricNames()
                updateFabrics(m)
                setPhase(if (commissioned) "paired" else "waiting")
                log.link("Matter: pairings now ${fabricNames().ifEmpty { "none" }} (was ${was.ifEmpty { "none" }})")
            }
            "command" -> onCommand(m)
            "log" -> log.link("Matter: ${m["text"]}")
            "error" -> {
                val code = m["code"]?.toString()
                setPhase("error", m["text"]?.toString(), code)
                log.warn("Matter: ${m["text"]}")
            }
        }
    }

    private fun onCommand(m: Map<String, Any?>) {
        val what = m["what"]?.toString() ?: return
        val on = m["on"] == true
        val who = controllerName((m["vendorId"] as? Number)?.toInt(), m["by"]?.toString())
        val cmd: CarCommand = when (what) {
            "climate" -> if (on) climateOn() else CarCommand.ClimateOff
            "headlights" -> CarCommand.Headlights(on)
            "parking" -> CarCommand.ParkingLights(on)
            else -> {
                log.warn("Matter: unknown switch \"$what\" from $who")
                return
            }
        }
        log.link("Matter: $who switched $what ${if (on) "on" else "off"}")
        scope.launch {
            val t0 = now()
            val r = service.execute(cmd)
            val ok = r is CommandResult.Success
            val detail = (r as? CommandResult.Failure)?.reason
            log.link("Matter: $what ${if (on) "on" else "off"} — " + if (ok) "done in ${(now() - t0) / 1000} s" else "failed: $detail")
            val map = Codecs.commandToMap(cmd)
            activity.add(Activity(t0, who, "cmd.${map["action"]}", map - "action", ok = ok, detail = detail, via = "matter"))
            // A failed switch flips back in the controller; a working one follows the car.
            pushState(force = true)
        }
    }

    private fun pushState(force: Boolean) {
        dirty = false
        val car = service.connection.car.value
        val state = linkedMapOf<String, Any?>(
            "type" to "state",
            "climate" to car.climate.isRunning,
            "headlights" to (car.headlights == true),
            "parking" to (car.parkingLights == true),
            "reachable" to (runCatching { home() }.getOrNull() != false),
        )
        val json = Json.write(state)
        if (!force && json == lastState) return
        if (send(state)) lastState = json
    }

    private fun send(m: Map<String, Any?>): Boolean {
        val w = input ?: return false
        return try {
            synchronized(writeLock) {
                w.write(Json.write(m))
                w.newLine()
                w.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun updateFabrics(m: Map<String, Any?>) {
        commissioned = m["commissioned"] == true
        fabrics = (m["fabrics"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { f ->
            val vid = (f["vendorId"] as? Number)?.toInt()
            linkedMapOf<String, Any?>("name" to controllerName(vid, f["label"]?.toString()), "label" to f["label"], "vendorId" to vid)
        }
    }

    private fun fabricNames() = fabrics.joinToString { it["name"].toString() }

    private fun setPhase(p: String, err: String? = null, code: String? = null) {
        phase = p
        error = err
        errorCode = code
    }

    // ------------------------------------------------------------------ storage

    private fun ensureCodes() {
        var s = settings
        if (s.passcode !in 1..99_999_998 || s.passcode in INVALID_PASSCODES) s = s.copy(passcode = newPasscode())
        if (s.discriminator !in 0..4095 || s.discriminator == 0) s = s.copy(discriminator = 1 + RANDOM.nextInt(4095))
        if (s.serial.isBlank()) s = s.copy(serial = "phev-" + (1..8).map { "0123456789abcdef"[RANDOM.nextInt(16)] }.joinToString(""))
        if (s != settings) {
            settings = s
            save(s)
        }
    }

    private fun load(): Settings = runCatching {
        if (file.exists()) Settings.from(Json.parseObject(file.readText())) else Settings()
    }.getOrElse { Settings() }

    private fun save(s: Settings) {
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(s.toMap()))
            // The pairing code lets anyone on the home network add the car while unpaired: owner-only.
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            tmp.renameTo(file)
        }.onFailure { log.warn("Matter: couldn't save the settings: ${it.message}") }
    }

    companion object {
        const val PORT = 5540
        /** Matter test vendor / product id (not certified): Google Home needs them registered once in its developer console. */
        const val VENDOR_ID = 0xFFF1
        const val PRODUCT_ID = 0x8000
        private val RANDOM = SecureRandom()
        private val INVALID_PASSCODES = setOf(
            11111111, 22222222, 33333333, 44444444, 55555555, 66666666, 77777777, 88888888, 12345678, 87654321,
        )

        fun newPasscode(): Int {
            while (true) {
                val p = 1 + RANDOM.nextInt(99_999_998)
                if (p !in INVALID_PASSCODES) return p
            }
        }

        /** "34970112332" → "3497-011-2332" (as Google Home shows it). */
        /** What a Node.js exit code usually means. */
        fun exitMeaning(code: Int): String? = when (code) {
            0 -> "ended by itself"
            1 -> "error — see the Matter lines above"
            2 -> "couldn't start"
            134 -> "aborted — often out of memory"
            137 -> "killed — usually the system running out of memory"
            139 -> "crashed"
            143 -> "stopped from outside"
            else -> null
        }

        fun formatManual(code: String): String = if (code.length == 11) "${code.take(4)}-${code.substring(4, 7)}-${code.drop(7)}" else code

        /** Who switched something, from the controller's vendor id (or the label it gave itself). */
        fun controllerName(vendorId: Int?, label: String?): String = when (vendorId) {
            0x6006 -> "Google Home"
            0x1349, 0x1384 -> "Apple Home"
            0x1217 -> "Alexa"
            0x110A -> "SmartThings"
            0x134B -> "Home Assistant"
            else -> label?.takeIf { it.isNotBlank() } ?: "Smart home (Matter)"
        }

        /** The names the switches get in Google Home (people can rename them there). */
        fun label(key: String, language: String): String {
            val i = listOf("en", "nb", "da", "de", "sv").indexOf(language.lowercase().take(2).let { if (it == "no" || it == "nn") "nb" else it }).coerceAtLeast(0)
            return when (key) {
                "climate" -> listOf("Car climate", "Bilklima", "Bilklima", "Autoklima", "Bilklimat")[i]
                "headlights" -> listOf("Car headlights", "Billys", "Billygter", "Autoscheinwerfer", "Billyktor")[i]
                else -> listOf("Car parking lights", "Parkeringslys bil", "Parkeringslys bil", "Standlicht Auto", "Parkeringsljus bil")[i]
            }
        }

        /**
         * node (the bridge's own copy, else the system's) and the bundled program; null when either is missing or
         * Node.js is older than 18.
         */
        fun defaultProgram(base: File): List<String>? {
            val script = File(base, "matter/phev-matter.mjs").takeIf { it.isFile } ?: return null
            val node = File(base, "node/bin/node").takeIf { it.canExecute() }?.path ?: "node"
            val version = runCatching {
                val p = ProcessBuilder(node, "--version").redirectErrorStream(true).start()
                p.waitFor(10, TimeUnit.SECONDS)
                p.inputStream.bufferedReader().readText().trim()
            }.getOrNull() ?: return null
            val major = Regex("""^v(\d+)""").find(version)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (major < 18) return null
            return listOf(node, script.path)
        }
    }
}
