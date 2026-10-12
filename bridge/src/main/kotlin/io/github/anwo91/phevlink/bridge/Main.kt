package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.diag.LogEntry
import io.github.anwo91.phevlink.core.emulator.FakeCar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * PHEV Link bridge: runs on a small always-on computer (e.g. a Raspberry Pi)
 * within Wi-Fi range of the car, keeps the car link, and exposes it over an
 * HTTP API + web page so the phone app works from anywhere (through a VPN
 * such as Tailscale — do not expose it to the internet directly).
 *
 * Usage: java -jar phev-bridge.jar [config.properties]
 *        java -jar phev-bridge.jar --sample-config > /etc/phev-bridge.properties
 */
fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    if (args.firstOrNull() == "--sample-config") {
        println(BridgeConfig.SAMPLE); return
    }
    val configFile = args.firstOrNull { !it.startsWith("--") }?.let(::File)
        ?: File("/etc/phev-bridge.properties").takeIf { it.exists() }
    var config = BridgeConfig.load(configFile)

    if (config.token.isBlank()) {
        val token = BridgeConfig.newToken()
        config = config.copy(token = token)
        val saved = configFile?.let { BridgeConfig.saveProperty(it, "token", token) } ?: false
        println("=".repeat(60))
        println("Generated API token: $token")
        println(if (saved) "Saved to ${configFile!!.path}" else "Add  token=$token  to your config file to keep it.")
        println("=".repeat(60))
    }

    val log = DiagLog(capacity = 5000)
    val logWriter = config.logFile.takeIf { it.isNotBlank() }?.let {
        runCatching {
            File(it).absoluteFile.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(it, true), Charsets.UTF_8)
        }.getOrNull()
    }
    log.addListener { e: LogEntry ->
        val line = e.format(ZoneId.systemDefault())
        println(line)
        logWriter?.apply { write(line); write("\n"); flush() }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var carPort = config.carPort
    if (config.emulator) {
        val fake = FakeCar { log.link("[emulator] $it") }.start(port = 0)
        config = config.copy(carHost = "127.0.0.1")
        carPort = fake.port
        log.warn("EMULATOR MODE — talking to a simulated car on port $carPort")
    }

    val stateDir = File(config.stateFile).absoluteFile.parentFile
    // Who did what, and what the car did (charging in detail): kept on the bridge for every phone to read.
    val activity = ActivityLog(File(stateDir, "activity.jsonl"), log)
    val service = BridgeService(config, log, scope, carPort, activity)
    service.start()
    val pairing = Pairing(config.pairingMinutes, stateDir, log)
    val carWifi = CarWifi(config.wifiHelper, config.carIface, log)
    // Settings changes from the app (leaving emulator mode) and "restart" apply on a fresh start; systemd brings us back.
    val restart = {
        Thread {
            Thread.sleep(800)
            log.link("Restarting")
            exitProcess(0)
        }.apply { isDaemon = false }.start()
    }
    val updater = Updater(File(File(config.wifiHelper).absoluteFile.parentFile, "bridgectl.sh").path, stateDir, log)
    // Departures, car name, range and climate defaults all phones share; the bridge runs the departures while the car is home.
    val shared = SharedStore(File(stateDir, "shared.json"), log)
    DepartureRunner(
        shared, log,
        carHome = { config.emulator || runCatching { carWifi.status().connected }.getOrDefault(false) },
        execute = { service.execute(it) },
        onActivity = { activity.add(it) },
    ).start(scope)
    val api = HttpApi(config, service, log, pairing, carWifi, configFile, updater, shared, activity, restart)
    activity.add(io.github.anwo91.phevlink.core.activity.Activity(System.currentTimeMillis(), "bridge", "bridge.started", mapOf("build" to BridgeService.BUILD)))
    val port = api.start()
    log.link("Bridge ${BridgeService.BUILD} (API ${BridgeService.VERSION}) on http://${config.listenHost}:$port/  (car ${config.carHost}:$carPort)")
    // How the last update from the app ended (it restarted us), so it shows in this log too.
    runCatching { File(stateDir, "update.log").readLines().lastOrNull { it.startsWith("@") } }.getOrNull()
        ?.let { log.link("Last update: ${it.removePrefix("@")}") }
    pairing.window().let { w ->
        log.link(if (w.open) "Pairing open for ${w.secondsLeft / 60} min ${w.secondsLeft % 60} s (app: Settings → Set up bridge)" else "Pairing closed (${w.reason})")
    }
    runCatching { carWifi.status() }.onSuccess { st ->
        log.link("Car Wi-Fi: iface=${st.iface ?: "-"} state=${st.ifaceState ?: "-"} profile=${st.ssid ?: "none"} connected=${st.connected} home=${st.homeIface ?: "-"}" +
            (st.problem?.let { " problem=$it" } ?: ""))
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { api.stop() }
        logWriter?.close()
    })
    Thread.currentThread().join()
}

