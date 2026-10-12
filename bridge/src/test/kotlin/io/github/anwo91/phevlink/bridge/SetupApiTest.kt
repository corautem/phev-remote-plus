package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.json.obj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/** The endpoints the app's setup wizard uses: discovery, re-plug pairing, car Wi-Fi, leaving emulator mode. */
class SetupApiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = DiagLog()
    private val dir: File = Files.createTempDirectory("setup").toFile()
    private val settings = File(dir, "phev-bridge.properties").apply { writeText("# test\ntoken=t0k\nemulator=true\nmode=interval\n") }
    private val config = BridgeConfig(listenHost = "127.0.0.1", token = "t0k", emulator = true, stateFile = File(dir, "state.json").path)
    private val service = BridgeService(config, log, scope, 1) // never started: these endpoints don't need the car
    private var uptime = 120.0
    private val pairing = Pairing(10, dir, log, { uptime }, { "boot-1" })
    private val wifiRunner = object : CarWifi.Runner {
        val calls = ArrayList<Pair<String, String?>>()
        override fun run(argv: List<String>, stdin: String?, timeoutMs: Long): CarWifi.Run {
            val cmd = argv.joinToString(" ")
            calls += cmd to stdin
            return when {
                cmd.startsWith("nmcli -t -f DEVICE,TYPE,STATE,CONNECTION") -> CarWifi.Run(0, "eth0:ethernet:connected:w\nwlan0:wifi:disconnected:\n")
                cmd.startsWith("ip route") -> CarWifi.Run(0, "default via 192.168.1.1 dev eth0\n")
                cmd.startsWith("sudo -n /h scan") -> CarWifi.Run(0, "REMOTE46pdmp:71:WPA2\n")
                cmd.startsWith("sudo -n /h join") -> CarWifi.Run(0, "@saved\n@up-ok\n")
                else -> CarWifi.Run(10, "")
            }
        }
    }
    private val wifi = CarWifi("/h", "", log, wifiRunner, { null }, { true })
    private var restarted = false
    private val updRunner = object : CarWifi.Runner {
        val calls = ArrayList<String>()
        override fun run(argv: List<String>, stdin: String?, timeoutMs: Long): CarWifi.Run {
            calls += argv.joinToString(" ")
            return if (argv.firstOrNull() == "systemctl") CarWifi.Run(3, "inactive\n") else CarWifi.Run(0, "@started\n")
        }
    }
    private val updater = Updater("/u", dir, log, updRunner, { "1.0.99" }, { true }, "1.0.37")
    private val shared = SharedStore(File(dir, "shared.json"), log)
    private val api = HttpApi(config, service, log, pairing, wifi, settings, updater, shared) { restarted = true }
    private val port = api.start(0)

    @After
    fun tearDown() {
        api.stop(); scope.cancel(); dir.deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null, token: String? = "t0k", headers: Map<String, String> = emptyMap()): Triple<Int, String, HttpURLConnection> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return Triple(code, text, c)
    }

    @Test
    fun helloNeedsNoTokenAndSaysWhetherPairingIsOpen() {
        val (code, text) = call("GET", "/api/hello", token = null)
        assertEquals(200, code)
        val m = Json.parseObject(text)
        assertEquals("phev-bridge", m["app"])
        assertEquals(true, m.obj("pairing")?.get("open"))
        assertTrue((m["features"] as List<*>).contains("carwifi"))
        assertEquals(true, m["emulator"])
    }

    @Test
    fun pairingHandsOutTheTokenOnceAndNeedsTheHeader() {
        val (noHeader, _, c0) = call("POST", "/api/pair", "", token = null)
        assertEquals(400, noHeader)
        assertNull("no CORS on pairing answers", c0.getHeaderField("Access-Control-Allow-Origin"))

        val (code, text, c1) = call("POST", "/api/pair", "", token = null, headers = mapOf("X-PHEV-Pair" to "1", "X-PHEV-Device" to "Pixel 8"))
        assertEquals(text, 200, code)
        assertEquals("t0k", Json.parseObject(text)["token"])
        assertNull(c1.getHeaderField("Access-Control-Allow-Origin"))
        assertTrue(log.tail(50).any { it.text.contains("Paired with 127.0.0.1 (Pixel 8)") })

        val (again, againText) = call("POST", "/api/pair", "", token = null, headers = mapOf("X-PHEV-Pair" to "1"))
        assertEquals(403, again)
        assertEquals("used", Json.parseObject(againText)["reason"])
        assertEquals(false, Json.parseObject(call("GET", "/api/hello", token = null).second).obj("pairing")?.get("open"))
    }

    @Test
    fun pairingClosedAfterTheWindow() {
        uptime = 900.0
        val (code, text) = call("POST", "/api/pair", "", token = null, headers = mapOf("X-PHEV-Pair" to "1"))
        assertEquals(403, code)
        assertEquals("expired", Json.parseObject(text)["reason"])
        assertFalse(text.contains("t0k"))
    }

    @Test
    fun carWifiNeedsTheTokenAndJoins() {
        assertEquals(401, call("GET", "/api/carwifi", token = null).first)
        val st = Json.parseObject(call("GET", "/api/carwifi").second)
        assertEquals("wlan0", st["iface"])
        assertEquals("eth0", st["homeIface"])

        val scan = Json.parseObject(call("POST", "/api/carwifi/scan", """{"country":"NO"}""").second)
        assertEquals(true, scan["ok"])
        assertEquals("REMOTE46pdmp", ((scan["networks"] as List<*>).first() as Map<*, *>)["ssid"])

        val join = Json.parseObject(call("POST", "/api/carwifi/join", """{"ssid":"REMOTE46pdmp","password":"secret123","country":"NO"}""").second)
        assertEquals(true, join["ok"])
        assertEquals("secret123\n", wifiRunner.calls.single { it.first.startsWith("sudo -n /h join") }.second)
        assertFalse(log.tail(200).any { it.text.contains("secret123") })
    }

    @Test
    fun systemUpdateAndRestartNeedTheToken() {
        for ((m, path) in listOf("GET" to "/api/system", "GET" to "/api/update", "POST" to "/api/update", "POST" to "/api/restart", "POST" to "/api/reboot")) {
            assertEquals("$m $path", 401, call(m, path, if (m == "POST") "" else null, token = null).first)
        }
        val sys = Json.parseObject(call("GET", "/api/system").second)
        assertTrue(sys.containsKey("build") && sys.containsKey("java") && sys.containsKey("addresses"))

        val st = Json.parseObject(call("GET", "/api/update").second)
        assertEquals("1.0.37", st["current"])
        assertEquals("1.0.99", st["latest"])
        assertEquals(true, st["available"])

        val (code, text) = call("POST", "/api/update", "")
        assertEquals(text, 200, code)
        assertTrue(updRunner.calls.contains("sudo -n /u update-start"))

        assertEquals(200, call("POST", "/api/restart", "").first)
        assertTrue(restarted)
    }

    @Test
    fun leavingEmulatorModeSavesAndRestarts() {
        assertEquals(401, call("POST", "/api/config", """{"emulator":false}""", token = null).first)
        val (code, text) = call("POST", "/api/config", """{"emulator":false}""")
        assertEquals(text, 200, code)
        assertEquals(true, Json.parseObject(text)["restarting"])
        assertTrue(restarted)
        val lines = settings.readLines()
        assertTrue(lines.contains("emulator=false"))
        assertTrue("other lines kept", lines.contains("token=t0k") && lines.contains("# test"))
    }

    @Test
    fun sharedDataNeedsTheTokenAndKeepsWhatPhonesSend() {
        assertEquals(401, call("GET", "/api/shared", token = null).first)
        assertEquals(401, call("POST", "/api/shared", """{"carName":"x"}""", token = null).first)
        assertEquals(0L, (Json.parseObject(call("GET", "/api/shared").second)["rev"] as Number).toLong())

        val (code, text) = call("POST", "/api/shared", """{"carName":"Blue","fullRangeKm":42,"departures":[]}""", headers = mapOf("X-PHEV-Device" to "Pixel 8"))
        assertEquals(text, 200, code)
        val m = Json.parseObject(call("GET", "/api/shared").second)
        assertEquals(1L, (m["rev"] as Number).toLong())
        assertEquals("Blue", m["carName"])
        assertEquals(42L, (m["fullRangeKm"] as Number).toLong())
        assertEquals("Pixel 8", m["updatedBy"])
        val hello = Json.parseObject(call("GET", "/api/hello", token = null).second)
        assertTrue((hello["features"] as List<*>).containsAll(listOf("shared", "departures")))
    }
}
