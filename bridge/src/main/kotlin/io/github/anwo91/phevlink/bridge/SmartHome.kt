package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.planner.ClimatePlanner
import io.github.anwo91.phevlink.core.planner.Departure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The bridge's link to a smart home (Home Assistant, over MQTT — see [HomeAssistant] for the topics): settings saved
 * in the bridge's state folder, the MQTT session ([MqttLink]), the car's state sent as it changes, activity and
 * reminders sent as events, and commands from the smart home run like a phone's (and written in the activity log as
 * "Home Assistant"). Also finds MQTT brokers on the home network and can set one up on the bridge computer.
 */
class SmartHome(
    stateDir: File,
    private val service: BridgeService,
    private val shared: SharedStore,
    private val activity: ActivityLog,
    /** Is the bridge on the car's Wi-Fi (the car is home)? Null = not known. */
    private val home: () -> Boolean?,
    /** The bridge's own addresses on the home network (not its car Wi-Fi radio). */
    private val lanAddresses: () -> List<String>,
    private val log: DiagLog,
    private val scope: CoroutineScope,
    private val link: MqttLink = MqttLink(log),
    private val runner: CarWifi.Runner = ProcessRunner,
    private val bridgectl: String = "/opt/phev-bridge/bridgectl.sh",
    private val temperature: (Double, Double, ZonedDateTime) -> Double? = DepartureRunner::metNoTemperature,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Settings(
        val enabled: Boolean = false,
        val host: String = "",
        val port: Int = 1883,
        val username: String = "",
        val password: String = "",
        /** Stable id of the car in the smart home (topic and entity ids). */
        val id: String = "",
        /** The app's language when last saved: names of the entities. */
        val language: String = "en",
        /** The broker is the one set up on this bridge computer. */
        val localBroker: Boolean = false,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "enabled" to enabled, "host" to host, "port" to port, "username" to username, "password" to password,
            "id" to id, "language" to language, "localBroker" to localBroker,
        )

        companion object {
            fun from(m: Map<String, Any?>) = Settings(
                enabled = m["enabled"] == true,
                host = m["host"]?.toString().orEmpty(),
                port = (m["port"] as? Number)?.toInt() ?: 1883,
                username = m["username"]?.toString().orEmpty(),
                password = m["password"]?.toString().orEmpty(),
                id = m["id"]?.toString().orEmpty(),
                language = m["language"]?.toString() ?: "en",
                localBroker = m["localBroker"] == true,
            )
        }
    }

    private val file = File(stateDir, "smarthome.json")
    @Volatile var settings: Settings = load()
        private set
    @Volatile private var ha = HomeAssistant(ensureId(), settings.language)
    @Volatile private var lastState: String? = null
    @Volatile private var dirty = true
    @Volatile private var lastCommand: Map<String, Any?>? = null
    private val recent = HashMap<String, Long>()
    @Volatile private var localPhase = "idle"
    @Volatile private var localMessage: String? = null

    init {
        link.onConnected = { announce("connected") }
        link.onMessage = { t, p, r -> onMessage(t, p, r) }
    }

    /** Starts the link if it is switched on, and follows the car. */
    fun start() {
        activity.addListener { a -> onActivity(a) }
        service.addAlertListener { a -> if (link.connected) ha.event(a)?.let { link.publish(ha.eventTopic, it, false) } }
        scope.launch {
            combine(service.connection.car, service.connection.status) { _, _ -> Unit }.collect { dirty = true }
        }
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                tick++
                // Changes within a second go out together; once a minute anyway (car home or not, shared defaults).
                if (link.connected && (dirty || tick % 60 == 0)) publishState(force = false)
            }
        }
        if (settings.enabled && settings.host.isNotBlank()) {
            log.link("Smart home: on — broker ${settings.host}:${settings.port}" + (if (settings.localBroker) " (on this bridge)" else "") + ", car id ${settings.id}")
            link.start(options(settings))
        } else {
            log.link("Smart home: off")
        }
    }

    // ------------------------------------------------------------------ settings (the app)

    /** For GET /api/mqtt. The password itself only for the broker on this bridge (you type it into Home Assistant). */
    fun describe(): Map<String, Any?> {
        val s = settings
        return linkedMapOf(
            "enabled" to s.enabled, "host" to s.host, "port" to s.port, "username" to s.username,
            "hasPassword" to s.password.isNotEmpty(), "id" to s.id, "language" to s.language,
            "localBroker" to s.localBroker,
            "localLogin" to if (s.localBroker) mapOf("username" to s.username, "password" to s.password, "port" to s.port) else null,
            "local" to linkedMapOf("phase" to localPhase, "message" to localMessage),
            "lanAddresses" to runCatching { lanAddresses() }.getOrDefault(emptyList()),
            "status" to link.status.toMap(),
            "baseTopic" to ha.base,
            "lastCommand" to lastCommand,
        )
    }

    /**
     * Applies settings from the app ([changes]: enabled, host, port, username, password — absent = keep, language),
     * saves them and (re)connects; waits up to a few seconds so the app can say whether it worked. Null = fine, else
     * what is wrong with the input.
     */
    @Synchronized
    fun change(changes: Map<String, Any?>, by: String): String? {
        val cur = settings
        val host = (changes["host"] as? String)?.trim() ?: cur.host
        val port = (changes["port"] as? Number)?.toInt() ?: cur.port
        val user = (changes["username"] as? String)?.trim() ?: cur.username
        val pass = (changes["password"] as? String) ?: cur.password
        val enabled = changes["enabled"] as? Boolean ?: cur.enabled
        val lang = (changes["language"] as? String)?.take(10) ?: cur.language
        val local = if (changes.containsKey("host") && host != cur.host) false else cur.localBroker
        when {
            enabled && host.isBlank() -> return "the broker's address is missing"
            host.length > 253 || host.any { it.isWhitespace() || it == '/' } -> return "that isn't an address"
            port !in 1..65_535 -> return "the port must be 1-65535"
            user.length > 100 || pass.length > 200 -> return "the user name or password is too long"
        }
        val next = cur.copy(enabled = enabled, host = host, port = port, username = user, password = pass, language = lang, localBroker = local)
        apply(next, by)
        return null
    }

    private fun apply(next: Settings, by: String) {
        val before = settings
        if (before.enabled && link.connected && (!next.enabled || next.host != before.host || next.port != before.port)) {
            // Leaving this broker: take the car out of Home Assistant there, cleanly.
            if (!next.enabled) unannounce()
            link.publish(ha.availabilityTopic, "offline", true)
        }
        settings = next
        save(next)
        ha = HomeAssistant(next.id, next.language)
        lastState = null
        log.link(
            "Smart home: settings saved by $by — " + if (next.enabled) "on, broker ${next.host}:${next.port}" +
                (if (next.username.isNotEmpty()) " as ${next.username}" else " without login") + ", names in ${next.language}" else "off"
        )
        if (next.enabled) {
            link.start(options(next))
            link.awaitOutcome(8_000)
        } else {
            link.stop("switched off")
        }
    }

    private fun options(s: Settings) = MqttClient.Options(
        host = s.host, port = s.port, clientId = "phev-link-${s.id}",
        username = s.username.ifEmpty { null }, password = s.password.ifEmpty { null },
        keepAliveSec = 30, will = MqttClient.Will(ha.availabilityTopic, "offline", retain = true),
    )

    // ------------------------------------------------------------------ talking to the smart home

    private fun announce(why: String) {
        val car = service.connection.car.value
        val device = HomeAssistant.Device(shared.snapshot()["carName"]?.toString(), car.modelYear, car.vin, BridgeService.BUILD)
        link.publish(ha.availabilityTopic, "online", true)
        val configs = ha.discovery(device)
        configs.forEach { (t, p) -> link.publish(t, p, true) }
        lastState = null
        publishState(force = true)
        link.subscribe(listOf(ha.commandTopics, ha.statusTopic))
        log.link("Smart home: announced the car to Home Assistant ($why): ${configs.size} entities, topics ${ha.base}/…")
    }

    private fun unannounce() {
        ha.discoveryTopics().forEach { link.publish(it, "", true) }
        link.publish(ha.stateTopic, "", true)
        log.link("Smart home: took the car out of Home Assistant (switched off)")
    }

    private fun publishState(force: Boolean) {
        dirty = false
        val car = service.connection.car.value
        val sh = shared.snapshot()
        val x = HomeAssistant.Extra(
            home = runCatching { home() }.getOrNull(),
            linked = service.connection.status.value.isConnected,
            fullRangeKm = (sh["fullRangeKm"] as? Number)?.toInt() ?: 45, // the app's default until a phone shares its own
            quickMode = quickMode(sh),
            quickDuration = quickDuration(sh),
            zone = shared.zone(),
        )
        val json = ha.state(car, x, now())
        if (!force && json == lastState) return
        if (link.publish(ha.stateTopic, json, true)) lastState = json
    }

    private fun onActivity(a: Activity) {
        if (!link.connected) return
        link.publish(ha.activityTopic, Json.write(a.toMap() + ("text" to ActivityText.english(a))), false)
        ha.event(a)?.let { link.publish(ha.eventTopic, it, false) }
        // A shared default changed (climate mode or time from a phone): the selects follow.
        if (a.code == "shared.changed") dirty = true
    }

    private fun onMessage(topic: String, payload: String, retained: Boolean) {
        if (topic == ha.statusTopic) {
            if (payload.trim().equals("online", true)) {
                log.link("Smart home: Home Assistant (re)started — announcing the car again")
                scope.launch(Dispatchers.IO) { announce("Home Assistant started") }
            }
            return
        }
        val key = "$topic=$payload"
        synchronized(recent) {
            val t = now()
            recent.entries.removeIf { t - it.value > 3_000 }
            if (recent.containsKey(key)) {
                log.link("Smart home: ignored a repeat of ${topic.substringAfterLast('/')} $payload within 3 s")
                return
            }
            recent[key] = t
        }
        when (val a = ha.command(topic, payload, retained)) {
            is HomeAssistant.Action.Ignore -> log.warn("Smart home: ignored $topic \"${payload.take(40)}\" — ${a.why}")
            is HomeAssistant.Action.Shared -> {
                log.link("Smart home: ${a.label}, asked by Home Assistant")
                shared.update(a.changes, WHO)
                activity.add(Activity(now(), WHO, "shared.changed", mapOf("keys" to a.changes.keys.joinToString())))
                dirty = true
            }
            HomeAssistant.Action.ClimateOn -> scope.launch { run(climateFor(), "climate on") }
            is HomeAssistant.Action.Car -> scope.launch { run(a.command, a.label) }
        }
    }

    private suspend fun run(cmd: CarCommand, label: String) {
        log.link("Smart home: $label, asked by Home Assistant")
        val t0 = now()
        if (cmd == CarCommand.RequestUpdate) service.connectNow()
        val r = service.execute(cmd)
        val ok = r is CommandResult.Success
        val detail = (r as? CommandResult.Failure)?.reason
        log.link("Smart home: $label — " + if (ok) "done in ${(now() - t0) / 1000} s" else "failed: $detail")
        lastCommand = linkedMapOf("atMs" to t0, "what" to label, "ok" to ok, "detail" to detail)
        val m = Codecs.commandToMap(cmd)
        val action = m["action"]?.toString()
        if (action != null && action !in ActivityText.QUIET_COMMANDS) {
            activity.add(Activity(t0, WHO, "cmd.$action", m - "action", ok = ok, detail = detail, via = "smart home"))
        }
        dirty = true
    }

    /** "Climate on" from a smart home: the shared one-tap choice (mode, time), from the weather when that is on. */
    fun climateFor(): CarCommand.ClimateOn {
        val sh = shared.snapshot()
        val mode = quickMode(sh)
        val duration = quickDuration(sh)
        if (sh["quickSmart"] != true) return CarCommand.ClimateOn(mode, duration)
        val zone = shared.zone()
        val temp = shared.location()?.let { (la, lo) -> runCatching { temperature(la, lo, ZonedDateTime.now(zone).plusMinutes(15)) }.getOrNull() }
        val plan = ClimatePlanner.decide(Departure("smart-home", LocalTime.now(zone), emptySet(), mode = mode, duration = duration), temp)
            ?: return CarCommand.ClimateOn(mode, duration)
        log.link("Smart home: climate from the weather — ${plan.reason}")
        return CarCommand.ClimateOn(plan.mode, plan.duration)
    }

    private fun quickMode(sh: Map<String, Any?>) =
        (sh["quickMode"] as? String)?.let { runCatching { ClimateMode.valueOf(it) }.getOrNull() } ?: ClimateMode.HEAT

    private fun quickDuration(sh: Map<String, Any?>) =
        ClimateDuration.fromMinutes((sh["quickDuration"] as? Number)?.toInt() ?: 20)

    // ------------------------------------------------------------------ finding a broker

    /**
     * MQTT brokers on the home network: port 1883 on every address of the bridge's home subnet(s), this computer, and
     * "homeassistant.local". Says which of them also run Home Assistant (port 8123). A few seconds.
     */
    suspend fun findBrokers(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val t0 = now()
        val own = runCatching { lanAddresses() }.getOrDefault(emptyList())
        val candidates = LinkedHashSet<String>()
        candidates += "127.0.0.1"
        for (ip in own) {
            val p = ip.split('.')
            if (p.size == 4) for (i in 1..254) candidates += "${p[0]}.${p[1]}.${p[2]}.$i"
        }
        val named = listOf("homeassistant.local", "homeassistant").mapNotNull { n ->
            runCatching { InetAddress.getByName(n).hostAddress }.getOrNull()?.let { n to it }
        }
        named.forEach { candidates += it.second }
        val pool = Executors.newFixedThreadPool(64)
        try {
            val hits = pool.invokeAll(candidates.map { host -> Callable { if (open(host, 1883, 500)) host else null } }, 30, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
            val found = hits.map { host ->
                val local = host == "127.0.0.1" || host in own
                linkedMapOf<String, Any?>(
                    "host" to host,
                    "port" to 1883,
                    "name" to named.firstOrNull { it.second == host }?.first,
                    "homeAssistant" to (!local && open(host, 8123, 800)),
                    "thisBridge" to local,
                )
            }.distinctBy { if (it["thisBridge"] == true) "local" else it["host"] }
            log.link("Smart home: broker search on ${own.joinToString().ifEmpty { "no home address" }} (${candidates.size} addresses, ${(now() - t0) / 1000} s): " +
                if (found.isEmpty()) "none found" else found.joinToString { "${it["host"]}" + (if (it["homeAssistant"] == true) " (Home Assistant)" else "") + (if (it["thisBridge"] == true) " (this bridge)" else "") })
            found
        } finally {
            pool.shutdownNow()
        }
    }

    private fun open(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ a broker on this computer

    /**
     * Sets up Mosquitto on the bridge computer (installs it if needed, login required) through the maintenance helper,
     * then switches the link on to it. Runs in the background; [describe] shows how far it got.
     */
    fun setUpLocalBroker(by: String): Boolean {
        if (localPhase == "installing") return false
        localPhase = "installing"
        localMessage = null
        log.link("Smart home: setting up an MQTT broker on this computer, asked by $by")
        Thread({
            val pw = newPassword()
            val r = runner.run(listOf("sudo", "-n", bridgectl, "mqtt-broker"), pw + "\n", 10 * 60_000L)
            val ok = r.out.lineSequence().any { it.trim() == "@mqtt-ok" }
            if (ok) {
                localPhase = "ok"
                log.link("Smart home: the broker on this computer is ready (Mosquitto, user phev)")
                synchronized(this) {
                    apply(settings.copy(enabled = true, host = "127.0.0.1", port = 1883, username = "phev", password = pw, localBroker = true), "the broker setup")
                }
            } else {
                val why = r.out.lineSequence().firstOrNull { it.startsWith("@error") }?.removePrefix("@error")?.trim()
                    ?: when {
                        r.code == -1 -> "took too long"
                        r.out.contains("password is required") || r.out.contains("not allowed") -> "the bridge isn't allowed to (update the bridge from the app first)"
                        else -> "failed (${r.code}): ${r.out.trim().take(200)}"
                    }
                localPhase = "failed"
                localMessage = why
                log.warn("Smart home: setting up the broker on this computer failed — $why")
            }
        }, "mqtt-broker-setup").apply { isDaemon = true; start() }
        return true
    }

    // ------------------------------------------------------------------ storage

    private fun ensureId(): String {
        if (settings.id.isNotBlank()) return settings.id
        val id = "phev_" + (1..6).map { "0123456789abcdef"[RANDOM.nextInt(16)] }.joinToString("")
        settings = settings.copy(id = id)
        save(settings)
        return id
    }

    private fun load(): Settings = runCatching {
        if (file.exists()) Settings.from(Json.parseObject(file.readText())) else Settings()
    }.getOrElse {
        log.warn("Smart home: couldn't read ${file.name}: ${it.message}")
        Settings()
    }

    private fun save(s: Settings) {
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(s.toMap()))
            // Holds the broker password: only the bridge user may read it.
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            tmp.renameTo(file)
        }.onFailure { log.warn("Smart home: couldn't save the settings: ${it.message}") }
    }

    companion object {
        /** Who smart-home commands are from, in the activity log. */
        const val WHO = "Home Assistant"
        private val RANDOM = SecureRandom()
        private const val PW_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        fun newPassword(): String = (1..24).map { PW_CHARS[RANDOM.nextInt(PW_CHARS.length)] }.joinToString("")
    }
}
