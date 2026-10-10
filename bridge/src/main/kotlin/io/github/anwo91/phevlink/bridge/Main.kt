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
        val saved = configFile?.let { persistToken(it, token) } ?: false
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

    val service = BridgeService(config, log, scope, carPort)
    service.start()
    val api = HttpApi(config, service, log)
    val port = api.start()
    log.link("Bridge API on http://${config.listenHost}:$port/  (car ${config.carHost}:$carPort)")

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { api.stop() }
        logWriter?.close()
    })
    Thread.currentThread().join()
}

private fun persistToken(file: File, token: String): Boolean = runCatching {
    val lines = if (file.exists()) file.readLines() else BridgeConfig.SAMPLE.lines()
    var replaced = false
    val out = lines.map { line ->
        if (line.trimStart().startsWith("token=")) {
            replaced = true; "token=$token"
        } else line
    }.toMutableList()
    if (!replaced) out += "token=$token"
    file.writeText(out.joinToString("\n") + "\n")
    true
}.getOrDefault(false)
