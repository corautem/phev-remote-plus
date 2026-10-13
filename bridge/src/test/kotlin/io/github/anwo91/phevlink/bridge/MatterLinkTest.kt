package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.session.LinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The bridge's side of Matter, with a stand-in for the Node.js program that speaks the same lines. */
class MatterLinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("matter").toFile()
    private val log = DiagLog()
    private val car = FakeCar().start()
    private val config = BridgeConfig(carHost = "127.0.0.1", listenHost = "127.0.0.1", token = "t", mode = LinkMode.PERSISTENT, stateFile = File(dir, "state.json").path)
    private val activity = ActivityLog(null, log)
    private val service = BridgeService(config, log, scope, car.port, activity).also { it.start() }

    /** Says ready, gets paired by "Google Home" (vendor 0x6006) and switches the climate on; echoes what it is told. */
    private val fake = File(dir, "fake-matter.mjs").apply {
        writeText(
            """
            import readline from "node:readline";
            const send = (m) => process.stdout.write(JSON.stringify(m) + "\n");
            readline.createInterface({ input: process.stdin }).on("line", (l) => {
              const m = JSON.parse(l);
              if (m.type === "config") {
                send({ type: "log", text: "config " + JSON.stringify(m.devices) + " passcode " + m.passcode + " name " + m.name });
                console.error("2026-10-13 20:00:00.000 NOTICE Node phev-link going online");
                send({ type: "ready", commissioned: false, qr: "MT:TEST", manual: "34970112332", qrRows: ["101", "010", "101"], fabrics: [] });
                setTimeout(() => {
                  send({ type: "fabrics", commissioned: true, fabrics: [{ index: 1, label: "", vendorId: 24582 }] });
                  send({ type: "command", what: "climate", on: true, by: "", vendorId: 24582 });
                }, 300);
              }
              if (m.type === "state") send({ type: "log", text: "state " + JSON.stringify(m) });
              if (m.type === "stop" || m.type === "reset") process.exit(0);
            });
            """.trimIndent()
        )
    }

    private fun node(): String? = runCatching {
        val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        p.waitFor()
        if (p.exitValue() == 0) "node" else null
    }.getOrNull()

    private fun link() = MatterLink(
        dir, service, activity, home = { true }, homeIface = { "eth0" },
        climateOn = { CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_20) }, carName = { "Outlander" },
        log = log, scope = scope, program = { listOf(node()!!, fake.path) },
    )

    @After
    fun tearDown() {
        car.stop(); scope.cancel(); dir.deleteRecursively()
    }

    private fun waitFor(ms: Long = 15_000, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @Test
    fun pairsRunsCommandsAndFollowsTheCar() {
        if (node() == null) return // needs Node.js (CI and the Pi have it)
        runBlocking { withTimeout(15_000) { service.connection.status.first { it is LinkStatus.Connected } } }
        val m = link()
        m.start()
        assertEquals("off", m.describe()["phase"])
        m.change(mapOf("enabled" to true, "language" to "nb"), "Pixel")
        assertTrue(waitFor { m.describe()["phase"] == "paired" })
        val d = m.describe()
        assertEquals("MT:TEST", d["qr"])
        assertEquals(listOf("101", "010", "101"), d["qrRows"])
        assertEquals("Google Home", ((d["fabrics"] as List<*>).single() as Map<*, *>)["name"])
        val dump = { log.dump() }
        assertTrue(dump(), waitFor { "config {\"climate\":\"Bilklima\",\"headlights\":null,\"parking\":null}" in dump() })
        assertTrue(dump(), "name Outlander" in dump())
        // Google Home switched the climate on: the car did it, and the activity log says who.
        assertTrue(waitFor(20_000) { activity.recent(20).any { it.code == "cmd.climate" && it.who == "Google Home" && it.ok } })
        // The car's state went back to the program.
        assertTrue(dump(), waitFor { Regex("state \\{\"type\":\"state\",\"climate\":true").containsMatchIn(dump()) })
        assertTrue("matter.js notices land in the bridge log", "Matter: 2026-10-13 20:00:00.000 NOTICE Node phev-link going online" in dump())

        // Share the headlights too: the program starts again with them.
        m.change(mapOf("devices" to mapOf("headlights" to true)), "Pixel")
        assertTrue(dump(), waitFor { "\"headlights\":\"Billys\"" in dump() })

        // Remove every pairing: a new pairing code.
        val before = m.settings.passcode
        m.reset("Pixel")
        assertNotEquals(before, m.settings.passcode)
        m.change(mapOf("enabled" to false), "Pixel")
        assertTrue(waitFor { m.describe()["phase"] == "off" })
        assertTrue(File(dir, "matter.json").readText().contains("\"enabled\":false"))
    }

    @Test
    fun saysWhenTheProgramIsMissing() {
        val m = MatterLink(
            dir, service, activity, home = { true }, homeIface = { null },
            climateOn = { CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_20) }, carName = { null },
            log = log, scope = scope, program = { null },
        )
        m.start()
        m.change(mapOf("enabled" to true), "Pixel")
        assertEquals("error", m.describe()["phase"])
        assertEquals("missing", m.describe()["errorCode"])
        assertEquals(false, m.describe()["available"])
    }

    @Test
    fun codesAndNames() {
        assertEquals("3497-011-2332", MatterLink.formatManual("34970112332"))
        assertEquals("Google Home", MatterLink.controllerName(0x6006, ""))
        assertEquals("Apple Home", MatterLink.controllerName(0x1349, null))
        assertEquals("My hub", MatterLink.controllerName(0x1234, "My hub"))
        repeat(200) { val p = MatterLink.newPasscode(); assertTrue(p in 1..99_999_998 && p != 12345678 && p != 11111111) }
        assertEquals("Bilklima", MatterLink.label("climate", "nb"))
        assertEquals("Car climate", MatterLink.label("climate", "fr"))
    }
}
