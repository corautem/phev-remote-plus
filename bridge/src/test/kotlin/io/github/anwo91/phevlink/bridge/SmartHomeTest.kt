package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.json.Json
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The car in "Home Assistant" end to end: a simulated car, the bridge, a small broker. */
class SmartHomeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("smarthome").toFile()
    private val log = DiagLog()
    private val car = FakeCar().start()
    private val config = BridgeConfig(carHost = "127.0.0.1", listenHost = "127.0.0.1", token = "t", mode = LinkMode.PERSISTENT, stateFile = File(dir, "state.json").path)
    private val activity = ActivityLog(null, log)
    private val service = BridgeService(config, log, scope, car.port, activity).also { it.start() }
    private val shared = SharedStore(File(dir, "shared.json"), log)
    private val broker = FakeBroker("phev", "pw123456")
    private val calls = ArrayList<String>()
    private val runner = CarWifi.Runner { argv, stdin, _ ->
        calls += argv.joinToString(" ") + " <" + stdin?.trim()?.length + " chars>"
        CarWifi.Run(0, "@installing\n@mqtt-ok\n")
    }

    private fun smartHome() = SmartHome(dir, service, shared, activity, home = { true }, lanAddresses = { listOf("192.168.1.21") }, log = log, scope = scope, runner = runner)

    @After
    fun tearDown() {
        broker.close(); car.stop(); scope.cancel(); dir.deleteRecursively()
    }

    private fun connected(sh: SmartHome) {
        assertEquals(null, sh.change(mapOf("enabled" to true, "host" to "127.0.0.1", "port" to broker.port, "username" to "phev", "password" to "pw123456", "language" to "en"), "Pixel"))
        assertEquals("connected", (sh.describe()["status"] as Map<*, *>)["phase"])
    }

    @Test
    fun announcesTheCarFollowsItAndRunsCommands() {
        runBlocking { withTimeout(15_000) { service.connection.status.first { it is LinkStatus.Connected } } }
        val sh = smartHome()
        sh.start()
        connected(sh)
        val id = sh.settings.id
        assertTrue(id.matches(Regex("phev_[0-9a-f]{6}")))
        val base = "phevlink/$id"
        assertTrue(broker.waitFor { broker.retained["$base/availability"] == "online" })
        assertTrue(broker.waitFor { broker.retained.keys.count { it.startsWith("homeassistant/") && it.endsWith("/$id/battery/config") } == 1 })
        assertEquals(25, broker.retained.keys.count { it.startsWith("homeassistant/") })
        assertTrue(broker.waitFor(10_000) { broker.retained["$base/state"]?.let { Json.parseObject(it)["battery"] } != null })

        // Home Assistant presses "Flash lights": the car does it, and the activity log says who.
        broker.publish("$base/cmd/locate", "PRESS")
        assertTrue(broker.waitFor(20_000) { activity.recent(50).any { it.who == SmartHome.WHO && it.code == "cmd.locate" && it.ok } })

        // A mode change goes to the shared defaults (the phones' one-tap climate follows).
        broker.publish("$base/cmd/climate_mode", "Defrost")
        assertTrue(broker.waitFor { shared.snapshot()["quickMode"] == "WINDSCREEN" })
        assertTrue(broker.waitFor(5_000) { broker.retained["$base/state"]?.let { Json.parseObject(it)["climate_mode"] } == "Defrost" })

        // A retained command (someone published it wrongly, earlier) is never acted on when it replays on subscribing.
        broker.retained["$base/cmd/climate"] = "ON"
        broker.publish("homeassistant/status", "online") // Home Assistant restarted: announce again, subscribe again
        Thread.sleep(1_500)
        assertFalse(activity.recent(50).any { it.code == "cmd.climate" })

        // Switched off: the car leaves Home Assistant.
        assertNull(sh.change(mapOf("enabled" to false), "Pixel"))
        assertTrue(broker.waitFor { broker.retained.keys.none { it.startsWith("homeassistant/") && "/$id/" in it } })
        assertEquals("offline", broker.retained["$base/availability"])
        assertEquals("off", (sh.describe()["status"] as Map<*, *>)["phase"])
        assertTrue(File(dir, "smarthome.json").readText().contains("\"enabled\":false"))
    }

    @Test
    fun badInputAndABrokerOnTheBridge() {
        val sh = smartHome()
        assertEquals("the broker's address is missing", sh.change(mapOf("enabled" to true, "host" to ""), "Pixel"))
        assertEquals("the port must be 1-65535", sh.change(mapOf("enabled" to true, "host" to "x", "port" to 0), "Pixel"))
        // The broker on this computer: the helper gets a fresh password on stdin, then the link uses it.
        assertTrue(sh.setUpLocalBroker("Pixel"))
        assertTrue(broker.waitFor(10_000) { sh.settings.localBroker })
        assertTrue(calls.single(), calls.single().endsWith("bridgectl.sh mqtt-broker <24 chars>"))
        val d = sh.describe()
        assertEquals("127.0.0.1", d["host"])
        assertEquals("phev", (d["localLogin"] as Map<*, *>)["username"])
        assertEquals(24, (d["localLogin"] as Map<*, *>)["password"].toString().length)
        assertEquals(listOf("192.168.1.21"), d["lanAddresses"])
        // The password stays on the bridge: owner-only file.
        val perms = Files.getPosixFilePermissions(File(dir, "smarthome.json").toPath())
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms)
    }
}
