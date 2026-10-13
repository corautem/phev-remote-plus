package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertEngine
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.session.ConnectionConfig
import io.github.anwo91.phevlink.core.session.LinkStatus
import io.github.anwo91.phevlink.core.session.PhevConnection
import io.github.anwo91.phevlink.core.session.SessionConfig
import io.github.anwo91.phevlink.core.session.SocketConnector
import io.github.anwo91.phevlink.core.session.TcpConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps the bridge's connection to the car according to [BridgeConfig.mode],
 * runs commands, raises alerts and remembers the last known state on disk.
 */
class BridgeService(
    private val config: BridgeConfig,
    private val log: DiagLog,
    private val scope: CoroutineScope,
    carPort: Int = config.carPort,
    /** Where what the car does (charging, climate, doors…) is written down. */
    private val activity: ActivityLog? = null,
    /**
     * Why the car can't be reached right now without trying, or null: the bridge is off the car's Wi-Fi (the car is
     * away). Saves 5-second timeouts and gives the app a reason people understand instead of "timed out".
     */
    private val unreachableBecause: () -> String? = { null },
) {
    private val tracker = io.github.anwo91.phevlink.core.activity.CarEventTracker()
    val startedAtMs = System.currentTimeMillis()
    private val alerts = AlertEngine()
    private val notifier = Ntfy(config.ntfyUrl, config.ntfyToken, log)
    private val listeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()
    private val holdLock = Mutex()
    private var holds = 0
    private var lingerJob: Job? = null
    val recentAlerts = ArrayDeque<Alert>()
    private val tcp = TcpConnector(config.carHost, carPort)

    val connection = PhevConnection(
        connector = SocketConnector {
            withContext(Dispatchers.IO) { runCatching { unreachableBecause() }.getOrNull() }?.let { throw IOException(it) }
            tcp.connect()
        },
        log = log,
        scope = scope,
        config = ConnectionConfig(
            session = SessionConfig(syncClockOnConnect = config.syncClock, refreshIntervalMs = 5 * 60_000L),
        ),
        initialState = loadState(),
    )

    fun start() {
        // Push every change to SSE listeners and the alert engine.
        scope.launch {
            combine(connection.status, connection.car) { l, c -> l to c }.collect { (link, car) ->
                val snapshot = snapshot(link, car)
                listeners.forEach { runCatching { it(snapshot) } }
                for (a in synchronized(alerts) { alerts.onState(car, System.currentTimeMillis()) }) raise(a)
                activity?.let { log -> synchronized(tracker) { tracker.onState(car) }.forEach { log.add(it) } }
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                for (a in synchronized(alerts) { alerts.onTick(System.currentTimeMillis()) }) raise(a)
                saveState(connection.car.value)
            }
        }
        when (config.mode) {
            LinkMode.PERSISTENT -> connection.start()
            LinkMode.INTERVAL -> scope.launch {
                while (isActive) {
                    poll()
                    delay(config.intervalMinutes * 60_000L)
                }
            }
        }
        log.link("Bridge running in ${config.mode.name.lowercase()} mode")
    }

    /** Connect briefly to refresh the state (INTERVAL mode). */
    suspend fun poll() {
        hold()
        try {
            withTimeoutOrNull(45_000) { connection.status.first { it is LinkStatus.Connected } } ?: return
            // Let the car stream its registers, then ask for a refresh to be sure.
            delay(4_000)
            connection.execute(CarCommand.RequestUpdate, 15_000)
            delay(4_000)
            saveState(connection.car.value)
        } finally {
            release()
        }
    }

    suspend fun execute(cmd: CarCommand): CommandResult {
        // The car is away (the bridge is off its Wi-Fi): say so now rather than after 45 seconds of trying.
        withContext(Dispatchers.IO) { runCatching { unreachableBecause() }.getOrNull() }?.let {
            log.link("Command ${Codecs.commandToMap(cmd)["action"]}: not tried — $it")
            return CommandResult.Failure(cmd, it)
        }
        hold()
        try {
            return connection.execute(cmd, 45_000)
        } finally {
            release()
        }
    }

    private suspend fun hold() = holdLock.withLock {
        holds++
        lingerJob?.cancel()
        lingerJob = null
        connection.start()
    }

    private suspend fun release() = holdLock.withLock {
        holds--
        if (holds <= 0 && config.mode == LinkMode.INTERVAL) {
            holds = 0
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(config.lingerSeconds * 1000L)
                holdLock.withLock { if (holds == 0) connection.stop("idle") }
            }
        }
    }

    suspend fun connectNow() = hold().also { scope.launch { delay(1000); release() } }

    suspend fun disconnectNow() = holdLock.withLock {
        holds = 0
        lingerJob?.cancel()
        connection.stop("requested")
    }

    fun addListener(l: (Map<String, Any?>) -> Unit) = listeners.add(l)

    private val alertListeners = CopyOnWriteArrayList<(Alert) -> Unit>()

    /** Told about every reminder the bridge raises (left unlocked, door open, …), e.g. the smart-home link. */
    fun addAlertListener(l: (Alert) -> Unit) = alertListeners.add(l)
    fun removeListener(l: (Map<String, Any?>) -> Unit) = listeners.remove(l)

    fun snapshot(
        link: LinkStatus = connection.status.value,
        car: CarState = connection.car.value,
    ): Map<String, Any?> = linkedMapOf(
        "link" to Codecs.linkStatusToMap(link),
        "car" to Codecs.carStateToMap(car),
        // Vehicle settings with names/labels resolved, for the web page (it has no catalogue).
        "vehicleSettings" to Codecs.visibleSettingsToList(car.settings),
        "bridge" to linkedMapOf(
            "mode" to config.mode.name.lowercase(),
            "intervalMinutes" to config.intervalMinutes,
            "startedAtMs" to startedAtMs,
            "nowMs" to System.currentTimeMillis(),
            "version" to VERSION,
            "build" to BUILD,
            "features" to FEATURES,
            "emulator" to config.emulator,
            "alerts" to synchronized(recentAlerts) {
                recentAlerts.map { mapOf("type" to it.type.name, "title" to it.title, "message" to it.message, "timeMs" to it.timeMs) }
            },
        ),
    )

    private fun raise(a: Alert) {
        log.link("ALERT ${a.title}: ${a.message}")
        synchronized(recentAlerts) {
            recentAlerts.addLast(a)
            while (recentAlerts.size > 20) recentAlerts.removeFirst()
        }
        scope.launch { notifier.send(a.title, a.message, a.type.name) }
        alertListeners.forEach { l -> runCatching { l(a) } }
    }

    private fun loadState(): CarState = runCatching {
        val f = File(config.stateFile)
        if (!f.exists()) CarState() else Codecs.carStateFromMap(Json.parseObject(f.readText()))
    }.getOrElse { CarState() }

    @Synchronized
    private fun saveState(s: CarState) {
        if (!s.hasData) return
        runCatching {
            val f = File(config.stateFile)
            f.absoluteFile.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Json.write(Codecs.carStateToMap(s)))
            tmp.renameTo(f)
        }.onFailure { log.warn("Could not save state: ${it.message}") }
    }

    companion object {
        /** API version (what the app can ask for). */
        const val VERSION = "1.2"

        /** Release this jar was built in ("1.0.40", from the jar's manifest; "dev" for a local build). */
        val BUILD: String = BridgeService::class.java.`package`?.implementationVersion ?: "dev"

        /** What this bridge can do beyond the car link (the app checks before offering the setup wizard or updates). */
        val FEATURES = listOf("pair", "carwifi", "config", "system", "update", "restart", "carwifi-pause", "pair-invite", "shared", "departures", "charge-timer", "activity", "carwifi-rejoin", "smarthome", "matter")
    }
}
