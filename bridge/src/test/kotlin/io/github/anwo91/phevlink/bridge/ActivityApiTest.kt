package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.json.obj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files

/** The activity log on the bridge: commands with the phone's name, what the car did, phones reporting direct use. */
class ActivityApiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val car = FakeCar().start()
    private val log = DiagLog()
    private val dir: File = Files.createTempDirectory("activity").toFile()
    private val config = BridgeConfig(
        carHost = "127.0.0.1", listenHost = "127.0.0.1", token = "t0k",
        mode = LinkMode.PERSISTENT, stateFile = File(dir, "state.json").path,
    )
    private val activity = ActivityLog(File(dir, "activity.jsonl"), log)
    private val service = BridgeService(config, log, scope, car.port, activity).also { it.start() }
    private val api = HttpApi(config, service, log, activity = activity)
    private val port = api.start(0)

    @After
    fun tearDown() {
        api.stop(); car.stop(); scope.cancel(); dir.deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null, device: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer t0k")
        device?.let { c.setRequestProperty("X-PHEV-Device", URLEncoder.encode(it, "UTF-8")) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    private fun entries(): List<Map<*, *>> =
        (Json.parseObject(call("GET", "/api/activity").second)["entries"] as List<*>).map { it as Map<*, *> }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for $what\n${log.dump()}")
    }

    @Test
    fun commandsCarEventsAndDirectUse() {
        waitFor("link") { Json.parseObject(call("GET", "/api/status").second).obj("car")?.get("batteryPercent") != null }

        // A phone with a non-ASCII name starts the heat through the bridge.
        val (code, text) = call("POST", "/api/command", """{"action":"climate","mode":"HEAT","minutes":20}""", device = "Åse sin Pixel")
        assertEquals(text, 200, code)
        waitFor("command entry") { entries().any { it["code"] == "cmd.climate" } }
        val cmd = entries().first { it["code"] == "cmd.climate" }
        assertEquals("Åse sin Pixel", cmd["who"])
        assertEquals("bridge", cmd["via"])
        assertEquals("Åse sin Pixel started heating for 20 min", cmd["text"])

        // The car's own change shows up too.
        waitFor("climate on (car)") { entries().any { it["code"] == "car.climate_on" } }
        car.plugIn(charge = true)
        waitFor("charging started") { entries().any { it["code"] == "car.charge_started" } }
        assertTrue(log.tail(200).any { it.text.startsWith("Activity: Charging started") })

        // Refresh is not worth a line.
        call("POST", "/api/command", """{"action":"refresh"}""", device = "Kari")
        Thread.sleep(500)
        assertTrue(entries().none { it["code"] == "cmd.refresh" })

        // A phone reports what it did next to the car; only its own actions are accepted.
        val direct = Activity(System.currentTimeMillis() - 60_000, "Kari", "cmd.headlights", mapOf("on" to true))
        val fake = Activity(System.currentTimeMillis(), "Kari", "car.alarm")
        val body = Json.write(mapOf("entries" to listOf(direct.toMap(), fake.toMap(), direct.toMap())))
        val (pc, pt) = call("POST", "/api/activity", body, device = "Kari")
        assertEquals(pt, 200, pc)
        assertEquals(1L, (Json.parseObject(pt)["added"] as Number).toLong())
        val reported = entries().first { it["code"] == "cmd.headlights" }
        assertEquals("direct", reported["via"])
        assertTrue(entries().none { it["code"] == "car.alarm" })

        // Survives a restart of the bridge.
        val again = ActivityLog(File(dir, "activity.jsonl"), DiagLog())
        assertEquals(activity.size, again.size)
        assertEquals(activity.recent(5).map { it.id }, again.recent(5).map { it.id })
    }
}
