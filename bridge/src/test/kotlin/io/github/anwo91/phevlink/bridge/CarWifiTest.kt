package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.bridge.CarWifi.Device
import io.github.anwo91.phevlink.core.diag.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWifiTest {
    private val log = DiagLog()

    /** Answers commands by prefix; records every call (with stdin). */
    private class FakeRunner(val answers: MutableMap<String, CarWifi.Run>) : CarWifi.Runner {
        val calls = ArrayList<Pair<String, String?>>()
        override fun run(argv: List<String>, stdin: String?, timeoutMs: Long): CarWifi.Run {
            val cmd = argv.joinToString(" ")
            calls += cmd to stdin
            return answers.entries.firstOrNull { cmd.startsWith(it.key) }?.value ?: CarWifi.Run(10, "no such thing")
        }
    }

    private val ethHome = mutableMapOf(
        "nmcli -t -f DEVICE,TYPE,STATE,CONNECTION device status" to CarWifi.Run(0, """
            eth0:ethernet:connected:Wired connection 1
            wlan0:wifi:disconnected:
            lo:loopback:connected (externally):lo
        """.trimIndent()),
        "ip route show default" to CarWifi.Run(0, "default via 192.168.1.1 dev eth0 proto dhcp src 192.168.1.21 metric 100\n"),
    )

    private fun wifi(runner: FakeRunner, helperExists: Boolean = true) =
        CarWifi("/opt/phev-bridge/carwifi.sh", "", log, runner, { if (it.endsWith("wlan0/address")) "b8:27:eb:12:34:56\n" else null }, { helperExists })

    @Test
    fun parsesTerseOutputWithEscapes() {
        assertEquals(listOf("My:Net", "70", "WPA2"), CarWifi.splitTerse("""My\:Net:70:WPA2"""))
        assertEquals(listOf("a\\b", ""), CarWifi.splitTerse("""a\\b:"""))
        assertEquals("eth0", CarWifi.parseDefaultDev("default via 192.168.1.1 dev eth0 proto dhcp metric 100\ndefault via 10.0.0.1 dev wlan1 metric 600"))
        assertNull(CarWifi.parseDefaultDev(""))
        val nets = CarWifi.parseScan("REMOTE46pdmp:62:WPA2\n:30:WPA2\nREMOTE46pdmp:80:WPA2\nNeighbour:90:WPA2 WPA3\n")
        assertEquals(listOf("Neighbour" to 90, "REMOTE46pdmp" to 80), nets.map { it.ssid to it.signal })
    }

    @Test
    fun picksARadioThatIsNotTheHomeLink() {
        fun d(n: String, t: String, s: String, c: String = "") = Device(n, t, s, c)
        // Ethernet home, idle Wi-Fi → Wi-Fi.
        assertEquals("wlan0" to null, CarWifi.chooseIface(listOf(d("eth0", "ethernet", "connected", "w"), d("wlan0", "wifi", "disconnected")), "eth0", ""))
        // Only Wi-Fi, and it is the home link → can't.
        assertEquals(null to CarWifi.PROBLEM_SINGLE_RADIO, CarWifi.chooseIface(listOf(d("wlan0", "wifi", "connected", "Home")), "wlan0", ""))
        // USB dongle carries home, built-in is free → built-in.
        assertEquals("wlan0" to null, CarWifi.chooseIface(listOf(d("wlan0", "wifi", "disconnected"), d("wlan1", "wifi", "connected", "Home")), "wlan1", ""))
        // Already holding the car profile wins.
        assertEquals("wlan1" to null, CarWifi.chooseIface(listOf(d("wlan0", "wifi", "disconnected"), d("wlan1", "wifi", "connected", "phev-car")), "eth0", ""))
        // Wi-Fi also on the home network, but Ethernet carries the default route → take the Wi-Fi over.
        assertEquals("wlan0" to null, CarWifi.chooseIface(listOf(d("eth0", "ethernet", "connected", "w"), d("wlan0", "wifi", "connected", "Home")), "eth0", ""))
        // No Wi-Fi at all.
        assertEquals(null to CarWifi.PROBLEM_NO_WIFI, CarWifi.chooseIface(listOf(d("eth0", "ethernet", "connected", "w")), "eth0", ""))
        // Configured interface is used as is.
        assertEquals("wlan1" to null, CarWifi.chooseIface(listOf(d("wlan1", "wifi", "disconnected")), "wlan1", "wlan1"))
    }

    @Test
    fun statusOfAFreshBridge() {
        val st = wifi(FakeRunner(ethHome)).status()
        assertEquals("wlan0", st.iface)
        assertEquals("eth0", st.homeIface)
        assertFalse(st.configured)
        assertFalse(st.connected)
        assertEquals("b8:27:eb:12:34:56", st.mac)
        assertNull(st.problem)
    }

    @Test
    fun statusWhenConnectedToTheCar() {
        val r = FakeRunner(ethHome.apply {
            put("nmcli -t -f DEVICE,TYPE,STATE,CONNECTION device status", CarWifi.Run(0, "eth0:ethernet:connected:w\nwlan0:wifi:connected:phev-car\n"))
            put("nmcli -g 802-11-wireless.ssid connection show phev-car", CarWifi.Run(0, "REMOTE46pdmp\n"))
            put("nmcli -g IP4.ADDRESS device show wlan0", CarWifi.Run(0, "192.168.8.47/24\n"))
            put("nmcli -t -f IN-USE,SIGNAL device wifi list ifname wlan0", CarWifi.Run(0, " :40\n*:71\n"))
        })
        val st = wifi(r).status()
        assertTrue(st.connected)
        assertTrue(st.configured)
        assertEquals("REMOTE46pdmp", st.ssid)
        assertEquals("192.168.8.47/24", st.ip)
        assertEquals(71, st.signal)
    }

    @Test
    fun noNetworkManagerOrNoHelper() {
        assertEquals(CarWifi.PROBLEM_NO_NM, wifi(FakeRunner(mutableMapOf())).status().problem)
        assertEquals(CarWifi.PROBLEM_HELPER_MISSING, wifi(FakeRunner(ethHome), helperExists = false).status().problem)
        val j = wifi(FakeRunner(ethHome), helperExists = false).join("REMOTE46pdmp", "secret123", "NO")
        assertFalse(j.ok)
        assertEquals(CarWifi.PROBLEM_HELPER_MISSING, j.message)
    }

    @Test
    fun joinPassesThePasswordOnStdinOnly() {
        val r = FakeRunner(ethHome.apply {
            put("sudo -n /opt/phev-bridge/carwifi.sh join", CarWifi.Run(0, "@removed REMOTE46pdmp\n@saved\n@up-ok\n"))
        })
        val j = wifi(r).join("REMOTE46pdmp", "secret123", "no")
        assertTrue(j.ok)
        assertTrue(j.saved)
        val (cmd, stdin) = r.calls.first { it.first.startsWith("sudo") }
        assertEquals("sudo -n /opt/phev-bridge/carwifi.sh join wlan0 REMOTE46pdmp NO", cmd)
        assertEquals("secret123\n", stdin)
        assertFalse("never logged", log.tail(200).any { it.text.contains("secret123") })
    }

    @Test
    fun joinSavedButCarOutOfRange() {
        val r = FakeRunner(ethHome.apply {
            put("sudo -n /opt/phev-bridge/carwifi.sh join", CarWifi.Run(0, "@saved\n@up-failed Error: No network with SSID 'REMOTE46pdmp' found.\n"))
        })
        val j = wifi(r).join("REMOTE46pdmp", "secret123", null)
        assertTrue(j.saved)
        assertFalse(j.connected)
        assertTrue(j.message, j.message.contains("No network with SSID"))
    }

    @Test
    fun joinRefusedBySudoOrBadInput() {
        val r = FakeRunner(ethHome.apply {
            put("sudo -n", CarWifi.Run(1, "sudo: a password is required\n"))
        })
        val j = wifi(r).join("REMOTE46pdmp", "secret123", null)
        assertFalse(j.ok)
        assertEquals(CarWifi.PROBLEM_HELPER_MISSING, j.message)
        assertFalse(wifi(FakeRunner(ethHome)).join("REMOTE46pdmp", "short", null).ok)
        assertFalse(wifi(FakeRunner(ethHome)).join("", "secret123", null).ok)
    }

    @Test
    fun scanReportsHelperErrors() {
        val r = FakeRunner(ethHome.apply {
            put("sudo -n /opt/phev-bridge/carwifi.sh scan", CarWifi.Run(2, "@error scan failed: Error: Wi-Fi radio is disabled\n"))
        })
        val (nets, err) = wifi(r).scan("NO")
        assertTrue(nets.isEmpty())
        assertEquals("scan failed: Error: Wi-Fi radio is disabled", err)
    }
}
