package io.github.anwo91.phevlink.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs deploy/carwifi.sh against a stub `nmcli` that records its arguments. */
class CarWifiScriptTest {
    private val dir: File = Files.createTempDirectory("carwifi").toFile()
    private val calls = File(dir, "calls.txt")

    private val script: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "deploy/carwifi.sh"), File(it, "bridge/deploy/carwifi.sh")) }
        .first { it.exists() }

    init {
        // Two Wi-Fi profiles: the home network wlan0 was set up with (active on wlan0), and an old manual car profile.
        stub("nmcli", """
            echo "$*" >> "${calls.path}"
            case "$*" in
              "radio wifi on") exit 0 ;;
              "-t -f UUID,TYPE connection show") printf 'u-home:802-11-wireless\nu-old:802-11-wireless\nu-eth:802-3-ethernet\n' ;;
              "-g connection.id connection show uuid u-home") echo "HomeWifi" ;;
              "-g 802-11-wireless.ssid connection show uuid u-home") echo "MyHome" ;;
              "-g connection.id connection show uuid u-old") echo "REMOTE46pdmp" ;;
              "-g 802-11-wireless.ssid connection show uuid u-old") echo "REMOTE46pdmp" ;;
              "-g GENERAL.CONNECTION device show wlan0") echo "HomeWifi" ;;
              "connection delete"*|"connection modify"*|"connection add"*) exit 0 ;;
              "--wait 45 connection up phev-car ifname wlan0") [ -f "${dir.path}/up-fails" ] && { echo "Error: No network with SSID found."; exit 10; }; exit 0 ;;
              "-t -f SSID,SIGNAL,SECURITY device wifi list ifname wlan0 --rescan yes") printf 'REMOTE46pdmp:71:WPA2\nNeighbour:40:WPA2\n' ;;
              "device disconnect wlan0"|"device set wlan0 autoconnect no"|"device set wlan0 autoconnect yes") exit 0 ;;
              "--wait 30 connection up phev-car ifname wlan0") exit 0 ;;
              "-g GENERAL.TYPE device show wlan1") echo "wifi" ;;
              "-g GENERAL.TYPE device show eth0") echo "ethernet" ;;
              "-g connection.id connection show phev-car") echo "phev-car" ;;
              "-g connection.interface-name connection show phev-car") echo "wlan0" ;;
              "device disconnect wlan0"|"device set wlan1 autoconnect yes") exit 0 ;;
              "--wait 45 connection up phev-car ifname wlan1") exit 0 ;;
              *) echo "unexpected: $*" >&2; exit 99 ;;
            esac
        """)
        stub("rfkill", "exit 0")
    }

    private fun stub(name: String, body: String) {
        File(dir, name).apply { writeText("#!/bin/bash\n" + body.trimIndent() + "\n"); setExecutable(true) }
    }

    private fun run(vararg args: String, stdin: String = ""): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("bash", script.path) + args).redirectErrorStream(true)
        pb.environment()["PATH"] = dir.path + ":/usr/bin:/bin"
        val p = pb.start()
        // The script may exit before reading stdin (bad input is refused at once): a broken pipe is fine here.
        runCatching { p.outputStream.use { it.write(stdin.toByteArray()) } }
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun recorded(): List<String> = if (calls.exists()) calls.readLines() else emptyList()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun joinReplacesOldProfilesAndPinsTheMac() {
        val (code, out) = run("join", "wlan0", "REMOTE46pdmp", stdin = "secret123\n")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("@removed REMOTE46pdmp"))
        assertTrue("home profile must not grab the car radio back: $out", out.contains("@disabled HomeWifi"))
        assertTrue(out, out.contains("@saved"))
        assertTrue(out, out.contains("@up-ok"))
        val add = recorded().single { it.startsWith("connection add") }
        for (part in listOf(
            "ifname wlan0", "con-name phev-car", "ssid REMOTE46pdmp", "wifi-sec.psk secret123",
            "ipv4.never-default yes", "connection.autoconnect-retries 0",
            "802-11-wireless.cloned-mac-address permanent", "802-11-wireless.powersave 2",
        )) assertTrue("$part in: $add", add.contains(part))
        assertTrue(recorded().contains("connection delete uuid u-old"))
        assertFalse("the home profile is kept, only its autoconnect is switched off", recorded().contains("connection delete uuid u-home"))
    }

    @Test
    fun joinSavedEvenWhenTheCarIsAway() {
        File(dir, "up-fails").writeText("")
        val (code, out) = run("join", "wlan0", "REMOTE46pdmp", stdin = "secret123\n")
        assertEquals(0, code)
        assertTrue(out.contains("@saved"))
        assertTrue(out, out.contains("@up-failed Error: No network with SSID found."))
    }

    @Test
    fun rejectsBadInputBeforeTouchingAnything() {
        assertEquals(2, run("join", "wlan0", "REMOTE46pdmp", stdin = "short\n").first)
        assertEquals(2, run("join", "wlan0;reboot", "REMOTE46pdmp", stdin = "secret123\n").first)
        assertEquals(2, run("join", "wlan0", "", stdin = "secret123\n").first)
        assertEquals(2, run("join", "wlan0", "REMOTE46pdmp", "norway", stdin = "secret123\n").first)
        assertEquals(2, run("reboot").first)
        assertFalse(recorded().any { it.startsWith("connection add") || it.startsWith("connection delete") })
    }

    @Test
    fun pauseLeavesTheCarWifiAndResumeJoinsAgain() {
        val (code, out) = run("pause", "wlan0")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("@paused"))
        assertTrue(recorded().containsAll(listOf("device disconnect wlan0", "device set wlan0 autoconnect no")))
        assertFalse("never touches the saved profile", recorded().any { it.startsWith("connection modify") || it.startsWith("connection delete") })

        val (code2, out2) = run("resume", "wlan0")
        assertEquals(out2, 0, code2)
        assertTrue(out2, out2.contains("@resumed") && out2.contains("@up-ok"))
        assertTrue(recorded().contains("device set wlan0 autoconnect yes"))
        assertEquals(2, run("pause", "wlan0;reboot").first)
    }

    @Test
    fun moveTakesTheCarsNetworkToAnotherRadio() {
        val (code, out) = run("move", "wlan1")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("@moved wlan0 wlan1") && out.contains("@up-ok"))
        assertTrue(recorded().contains("connection modify phev-car connection.interface-name wlan1"))
        assertTrue("the old radio is let go", recorded().contains("device disconnect wlan0"))
        // Only Wi-Fi radios, and only valid names.
        assertEquals(2, run("move", "eth0").first)
        assertEquals(2, run("move", "wlan1;reboot").first)
    }

    @Test
    fun scanListsNetworks() {
        val (code, out) = run("scan", "wlan0")
        assertEquals(out, 0, code)
        assertEquals(listOf("REMOTE46pdmp" to 71, "Neighbour" to 40), CarWifi.parseScan(out).map { it.ssid to it.signal })
    }

    @Test
    fun whyShowsNetworkManagersWordsAboutTheCarRadioOnly() {
        stub("journalctl", """
            echo "$*" > "${dir.path}/journal-args.txt"
            echo "2026-10-13T01:06:10+0200 pi NetworkManager[512]: <info>  [1.2] device (wlan1): state change: activated -> disconnected"
            echo "2026-10-13T01:06:12+0200 pi NetworkManager[512]: <warn>  [1.3] device (wlan0): Activation: (wifi) association took too long"
            echo "2026-10-13T01:06:13+0200 pi NetworkManager[512]: <info>  [1.4] device (wlan0): dhcp4 (wlan0): state changed"
            echo "2026-10-13T01:06:40+0200 pi NetworkManager[512]: <warn>  [1.5] device (wlan0): state change: need-auth -> failed (reason 'no-secrets')"
            echo "2026-10-13T01:06:41+0200 pi wpa_supplicant[400]: wlan0: CTRL-EVENT-SSID-TEMP-DISABLED id=0 ssid=\"REMOTE46pdmp\" auth_failures=1"
        """)
        val (code, out) = run("why", "wlan0")
        assertEquals(out, 0, code)
        val lines = out.lines().filter { it.startsWith("@nm ") }
        assertEquals(out, 3, lines.size)
        assertTrue(lines.any { it.contains("no-secrets") })
        assertFalse("other radios and DHCP chatter are left out", out.contains("wlan1") || out.contains("dhcp4"))
        assertTrue(out.trim().endsWith("@done"))
        assertTrue(File(dir, "journal-args.txt").readText().contains("-u NetworkManager"))
        assertEquals(2, run("why", "wlan0;reboot").first)
    }
}
