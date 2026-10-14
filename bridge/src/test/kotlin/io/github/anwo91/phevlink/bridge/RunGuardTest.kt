package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.diag.LogEntry
import io.github.anwo91.phevlink.core.diag.LogKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RunGuardTest {
    private val now = 1_760_000_000_000L

    private fun fakeRoot(uptimeS: Long, oomKills: Long = 0): File {
        val root = Files.createTempDirectory("root").toFile()
        File(root, "proc").mkdirs()
        File(root, "proc/uptime").writeText("$uptimeS.12 1000.00\n")
        File(root, "proc/meminfo").writeText("MemTotal:         944000 kB\nMemFree:  100 kB\nMemAvailable:     320000 kB\n")
        File(root, "proc/vmstat").writeText("nr_free_pages 100\noom_kill $oomKills\n")
        return root
    }

    private fun texts(log: DiagLog) = log.snapshot().joinToString("\n") { it.text }

    @Test
    fun cleanStopIsNormal() {
        val dir = Files.createTempDirectory("state").toFile()
        val log = DiagLog()
        val g = RunGuard(dir, log, fakeRoot(3600), now = { now })
        g.markRunning()
        g.markStopped()
        g.checkLastRun(null)
        assertTrue(texts(log), texts(log).contains("ended normally"))
        assertTrue(texts(log), texts(log).contains("312 MB free of 921 MB"))
    }

    @Test
    fun powerCutIsTheComputerRestarting() {
        val dir = Files.createTempDirectory("state").toFile()
        val logFile = File(dir, "bridge.log").apply { writeText("x\n"); setLastModified(now - 3_600_000) }
        RunGuard(dir, DiagLog(), fakeRoot(100_000), now = { now - 7_200_000 }).markRunning()
        val log = DiagLog()
        RunGuard(dir, log, fakeRoot(120), now = { now }).checkLastRun(logFile) // booted 2 min ago, last line 1 h ago
        assertTrue(texts(log), texts(log).contains("the computer itself restarted"))
    }

    @Test
    fun killedProgramWithMemoryShortage() {
        val dir = Files.createTempDirectory("state").toFile()
        val logFile = File(dir, "bridge.log").apply { writeText("x\n"); setLastModified(now - 60_000) }
        RunGuard(dir, DiagLog(), fakeRoot(100_000), now = { now - 7_200_000 }).markRunning()
        val log = DiagLog()
        RunGuard(dir, log, fakeRoot(86_400, oomKills = 2), now = { now }).checkLastRun(logFile) // up for a day
        val t = texts(log)
        assertTrue(t, t.contains("ended abruptly"))
        assertTrue(t, t.contains("stopped 2 program(s) for lack of memory"))
        assertFalse(t, t.contains("computer itself"))
    }

    @Test
    fun logFileGetsDayLinesAndRotates() {
        val dir = Files.createTempDirectory("logs").toFile()
        val f = File(dir, "bridge.log")
        val w = LogFileWriter(f, maxBytes = 2_000)
        repeat(100) { w.write(LogEntry(now + it * 1000L, LogKind.LINK, "line $it " + "x".repeat(40)), java.time.ZoneOffset.UTC) }
        w.close()
        val old = File(dir, "bridge.log.1")
        assertTrue(old.exists())
        assertTrue(f.length() <= 2_200)
        assertTrue(old.readText().startsWith("---- 2025-10-09 ----"))
        assertTrue(f.readText().startsWith("---- 2025-10-09 ----"))
        assertEquals(1, f.readText().lines().count { it.startsWith("----") })
        // the tail spans both files, newest last
        val tail = LogFileWriter.tail(f, 40)
        assertEquals(40, tail.size)
        assertTrue(tail.last(), tail.last().contains("line 99 "))
        assertTrue(tail.any { it.startsWith("----") }) // reached into the older file
        assertEquals(tail.filter { it.contains("line ") }.map { it.substringAfter("line ").substringBefore(' ').toInt() }.sorted(),
            tail.filter { it.contains("line ") }.map { it.substringAfter("line ").substringBefore(' ').toInt() })
    }
}
