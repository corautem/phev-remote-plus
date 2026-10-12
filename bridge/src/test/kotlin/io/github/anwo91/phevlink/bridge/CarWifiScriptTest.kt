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
        p.outputStream.use { it.write(stdin.toByteArray()) }
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
    fun scanListsNetworks() {
        val (code, out) = run("scan", "wlan0")
        assertEquals(out, 0, code)
        assertEquals(listOf("REMOTE46pdmp" to 71, "Neighbour" to 40), CarWifi.parseScan(out).map { it.ssid to it.signal })
    }
}
