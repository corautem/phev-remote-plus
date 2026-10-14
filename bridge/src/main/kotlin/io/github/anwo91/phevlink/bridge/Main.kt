package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.diag.LogEntry
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.session.Unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
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
    val logFile = config.logFile.takeIf { it.isNotBlank() }?.let(::File)
    val logWriter = logFile?.let { LogFileWriter(it) }
    log.addListener { e: LogEntry ->
        println(e.format(ZoneId.systemDefault()))
        logWriter?.write(e)
    }
    // How the last run ended (power cut, killed, normal) and how memory is doing — before anything else is logged.
    val runGuard = RunGuard(File(config.stateFile).absoluteFile.parentFile, log)
    runGuard.checkLastRun(logFile)
    runGuard.markRunning()
    Thread.setDefaultUncaughtExceptionHandler { t, e -> log.error("Crash in ${t.name}: $e") }

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
    val carWifi = CarWifi(config.wifiHelper, config.carIface, log)
    // Off the car's Wi-Fi (the car is away): say so at once instead of timing out on the car's address.
    val unreachableBecause: () -> String? = {
        if (config.emulator) null else carWifi.status().takeIf { it.configured && it.iface != null && !it.connected }?.let {
            if (carWifi.paused) Unreachable.STEPPED_ASIDE else Unreachable.OFF_CAR_WIFI
        }
    }
    val service = BridgeService(config, log, scope, carPort, activity, unreachableBecause)
    service.start()
    val pairing = Pairing(config.pairingMinutes, stateDir, log)
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
    // Keeps the bridge on the car's Wi-Fi: NetworkManager gives up after failed joins (e.g. while a phone held the car's Wi-Fi).
    val watch = CarWifiWatch(
        carWifi, log, onActivity = { activity.add(it) }, onBack = { scope.launch { service.connectNow() } },
        lastContact = { service.connection.car.value.updatedAtMs },
    )
    if (!config.emulator) {
        // The phones show whether the car can be reached at all (in reach / checking / not seen since …), pushed on change.
        service.reach = { watch.reach() }
        watch.onReachChange = { service.pushNow() }
        watch.start(scope)
    }
    // Home Assistant (and other smart homes) over MQTT: off until switched on from the app.
    val lanAddresses: () -> List<String> = {
        val carIface = runCatching { carWifi.status().iface }.getOrNull()
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.name != carIface && !it.name.startsWith("docker") && !it.name.startsWith("tailscale") }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress } }
    }
    val smartHome = SmartHome(
        stateDir, service, shared, activity,
        home = { if (config.emulator) service.connection.status.value.isConnected else watch.onCarWifi },
        lanAddresses = lanAddresses, log = log, scope = scope,
        bridgectl = File(File(config.wifiHelper).absoluteFile.parentFile, "bridgectl.sh").path,
    )
    smartHome.start()
    // Google Home, Apple Home, Alexa … through Matter (a small Node.js program next to the jar): off until switched on.
    val matter = MatterLink(
        stateDir, service, activity,
        home = { if (config.emulator) service.connection.status.value.isConnected else watch.onCarWifi },
        homeIface = { runCatching { carWifi.status().homeIface }.getOrNull() },
        climateOn = { smartHome.climateFor() },
        carName = { shared.snapshot()["carName"]?.toString() },
        log = log, scope = scope,
        program = { MatterLink.defaultProgram(File(config.wifiHelper).absoluteFile.parentFile) },
    )
    matter.start() // the program stops by itself when the bridge exits (its stdin closes)
    runGuard.watchMemory(scope, extra = { matter.memoryNote() })
    // Evening plug-in reminder (shared settings), raised like the other alerts so every phone gets it.
    Reminders(
        shared, car = { service.connection.car.value },
        carHome = { if (config.emulator) service.connection.status.value.isConnected else watch.onCarWifi },
        raise = { service.raiseAlert(it) }, log = log,
    ).start(scope)
    val api = HttpApi(config, service, log, pairing, carWifi, configFile, updater, shared, activity, watch, smartHome, matter, restart)
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
        log.link("Stopping")
        runGuard.markStopped()
        logWriter?.close()
    })
    Thread.currentThread().join()
}

