package io.github.anwo91.phevlink.bridge

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

class BridgeApiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val car = FakeCar().start()
    private val log = DiagLog()
    private val stateFile = File.createTempFile("bridge", ".json").apply { delete() }
    private val config = BridgeConfig(
        carHost = "127.0.0.1", listenHost = "127.0.0.1", token = "t0k",
        mode = LinkMode.PERSISTENT, stateFile = stateFile.path,
    )
    private val service = BridgeService(config, log, scope, car.port).also { it.start() }
    private val api = HttpApi(config, service, log)
    private val port = api.start(0)

    @After
    fun tearDown() {
        api.stop(); car.stop(); scope.cancel(); stateFile.delete()
    }

    private fun call(method: String, path: String, body: String? = null, token: String? = "t0k"): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    @Test
    fun statusCommandsAndAuth() {
        assertEquals(401, call("GET", "/api/status", token = null).first)
        assertEquals(401, call("GET", "/api/status", token = "wrong").first)
        assertEquals(200, call("GET", "/", token = null).first)

        // wait for the link
        val deadline = System.currentTimeMillis() + 10_000
        var phase = ""
        while (System.currentTimeMillis() < deadline) {
            val m = Json.parseObject(call("GET", "/api/status").second)
            phase = m.obj("link")?.get("phase").toString()
            if (phase == "connected" && m.obj("car")?.get("batteryPercent") != null) break
            Thread.sleep(200)
        }
        assertEquals("connected", phase)

        val (code, text) = call("POST", "/api/command", """{"action":"climate","mode":"COOL","minutes":10}""")
        assertEquals(text, 200, code)
        assertTrue(text.contains("\"ok\":true"))

        val bad = call("POST", "/api/command", """{"action":"teleport"}""")
        assertEquals(400, bad.first)

        Thread.sleep(500)
        val car = Json.parseObject(call("GET", "/api/status").second).obj("car")!!
        assertEquals("RUNNING", car.obj("climate")?.get("state"))
        assertTrue(call("GET", "/api/log?n=20").second.contains("API command"))
    }

    @Test
    fun vehicleSettingsViaApi() {
        // Wait for the settings dump: the status carries pre-labelled groups for the web page.
        val deadline = System.currentTimeMillis() + 10_000
        var groups: List<*> = emptyList<Any>()
        while (System.currentTimeMillis() < deadline) {
            groups = Json.parseObject(call("GET", "/api/status").second)["vehicleSettings"] as? List<*> ?: emptyList<Any>()
            if (groups.size == 8) break
            Thread.sleep(200)
        }
        assertEquals(8, groups.size)
        @Suppress("UNCHECKED_CAST")
        val lights = groups.map { it as Map<String, Any?> }.first { it["title"] == "Lights" }
        @Suppress("UNCHECKED_CAST")
        val lid = (lights["items"] as List<Map<String, Any?>>).first { it["id"] == 7L }
        assertEquals("1 minute", lid["valueLabel"])

        val (code, text) = call("POST", "/api/command", """{"action":"setting","id":7,"value":3}""")
        assertEquals(text, 200, code)
        assertEquals(31 to 3, car.setting(7))
        val settings = Json.parseObject(call("GET", "/api/status").second).obj("car")!!.obj("settings")!!
        assertEquals(3L, settings.obj("7")?.get("value"))

        assertEquals(400, call("POST", "/api/command", """{"action":"setting","id":99,"value":0}""").first)
    }
}
