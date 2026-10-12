package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdaterTest {
    private val dir: File = Files.createTempDirectory("upd").toFile()
    private val log = DiagLog()

    private class FakeRunner(val answer: (String) -> CarWifi.Run) : CarWifi.Runner {
        val calls = ArrayList<String>()
        override fun run(argv: List<String>, stdin: String?, timeoutMs: Long): CarWifi.Run {
            val cmd = argv.joinToString(" ")
            calls += cmd
            return answer(cmd)
        }
    }

    private fun updater(runner: FakeRunner, latest: String? = "1.0.40", helper: Boolean = true, current: String = "1.0.37") =
        Updater("/h", dir, log, runner, { latest }, { helper }, current)

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Updater.isNewer("1.0.40", "1.0.37"))
        assertTrue(Updater.isNewer("1.0.100", "1.0.99"))
        assertTrue(Updater.isNewer("v1.0.40", "dev"))
        assertFalse(Updater.isNewer("1.0.37", "1.0.37"))
        assertFalse(Updater.isNewer("1.0.36", "1.0.37"))
        assertFalse(Updater.isNewer("garbage", "1.0.37"))
    }

    @Test
    fun statusReadsTheUpdateLog() {
        File(dir, "update.log").writeText("2026-10-12 22:30:00 update requested\n2026-10-12 22:30:05 checksum ok\n@update-failed checksum mismatch (download damaged)\n")
        val r = FakeRunner { if (it.startsWith("systemctl is-active")) CarWifi.Run(3, "inactive\n") else CarWifi.Run(0, "") }
        val st = updater(r).status()
        assertEquals("1.0.37", st["current"])
        assertEquals("1.0.40", st["latest"])
        assertEquals(true, st["available"])
        assertEquals(false, st["running"])
        assertEquals("update-failed", st["result"])
        assertEquals("checksum mismatch (download damaged)", st["detail"])
        assertEquals(2, (st["log"] as List<*>).size)
    }

    @Test
    fun noUpdateWhenGitHubIsUnreachable() {
        val st = updater(FakeRunner { CarWifi.Run(0, "active\n") }, latest = null).status()
        assertNull(st["latest"])
        assertEquals(false, st["available"])
        assertEquals(true, st["running"])
    }

    @Test
    fun startRunsTheHelperThroughSudo() {
        val r = FakeRunner { CarWifi.Run(0, "@started\n") }
        assertNull(updater(r).start("192.168.1.16"))
        assertEquals("sudo -n /h update-start", r.calls.single())
        assertNull(updater(FakeRunner { CarWifi.Run(0, "@busy\n") }).start("x"))
    }

    @Test
    fun startExplainsFailures() {
        assertEquals(Updater.HELPER_MISSING, updater(FakeRunner { CarWifi.Run(0, "") }, helper = false).start("x"))
        assertEquals(Updater.HELPER_MISSING, updater(FakeRunner { CarWifi.Run(1, "sudo: a password is required\n") }).start("x"))
        assertEquals("systemd-run is missing", updater(FakeRunner { CarWifi.Run(2, "@update-failed systemd-run is missing\n") }).start("x"))
    }

    @Test
    fun rebootUsesTheHelper() {
        val r = FakeRunner { CarWifi.Run(0, "@rebooting\n") }
        assertNull(updater(r).reboot("x"))
        assertEquals("sudo -n /h reboot", r.calls.single())
    }

    @Test
    fun scriptsAreValidBash() {
        for (name in listOf("bridgectl.sh", "install.sh", "carwifi.sh")) {
            val f = listOf(File("deploy/$name"), File("bridge/deploy/$name")).first { it.exists() }
            val p = ProcessBuilder("bash", "-n", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("bash -n $name: $out", 0, p.waitFor())
        }
    }

    @Test
    fun helperRefusesUnknownActions() {
        val f = listOf(File("deploy/bridgectl.sh"), File("bridge/deploy/bridgectl.sh")).first { it.exists() }
        val p = ProcessBuilder("bash", f.path, "rm -rf /").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(2, p.waitFor())
        assertTrue(out, out.contains("@error unknown action"))
    }
}
