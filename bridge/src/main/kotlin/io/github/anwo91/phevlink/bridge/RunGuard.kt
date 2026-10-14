package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.diag.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tells a log reader how the last run ended and how memory is doing, so "the bridge didn't answer" can be told apart:
 * a power cut (the computer restarted), the program being killed or crashing (systemd started it again), the system
 * killing programs for lack of memory, or a normal restart/update.
 *
 * A marker file ([stateDir]/running) is written at start and removed on a clean exit (shutdown hook); finding it at
 * start means the last run didn't end cleanly.
 */
class RunGuard(
    private val stateDir: File,
    private val log: DiagLog,
    private val root: File = File("/"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val marker = File(stateDir, "running")
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * Call at start, before anything is written to [logFile]: logs how the last run ended.
     * @param logFile the bridge's log file (its last change = the last sign of life of the previous run)
     */
    fun checkLastRun(logFile: File?) {
        val prevStart = runCatching { marker.readText().trim().toLong() }.getOrNull()
        val lastAlive = listOfNotNull(prevStart, logFile?.takeIf { it.exists() }?.lastModified()?.takeIf { it > 0 }).maxOrNull()
        val bootMs = uptimeS()?.let { now() - (it * 1000).toLong() }
        val oomKills = oomKills()
        when {
            prevStart == null -> log.link("Start: the last run ended normally")
            bootMs != null && lastAlive != null && bootMs > lastAlive - 5_000 ->
                log.warn("Start: the computer itself restarted (power cut, unplugged or a system crash) — last sign of the bridge ${at(lastAlive)}, computer up since ${at(bootMs)}")
            else -> log.warn(
                "Start: the last run ended abruptly ${lastAlive?.let { "around ${at(it)}" } ?: ""} — the program was killed or crashed and was started again" +
                    (oomKills?.takeIf { it > 0 }?.let { "; the system has stopped $it program(s) for lack of memory since ${bootMs?.let(::at) ?: "boot"}" } ?: ""),
            )
        }
        log.link("Start: ${memoryLine(null)}")
    }

    fun markRunning() {
        runCatching {
            stateDir.mkdirs()
            marker.writeText(now().toString())
        }
    }

    /** From the shutdown hook: this run ends cleanly. */
    fun markStopped() {
        runCatching { marker.delete() }
    }

    /**
     * Every [periodMs]: warns when the computer runs low on memory (under [lowMb] MB available, at most every 30 min)
     * or the system stopped a program for lack of memory, naming what uses how much ([extra] adds e.g. the Matter program).
     */
    fun watchMemory(scope: CoroutineScope, extra: () -> String? = { null }, periodMs: Long = 5 * 60_000L, lowMb: Long = 100) {
        scope.launch {
            var kills = oomKills()
            var lastWarn = 0L
            while (isActive) {
                delay(periodMs)
                val k = oomKills()
                if (k != null && kills != null && k > kills) {
                    log.warn("Memory: the system just stopped ${k - kills} program(s) for lack of memory — ${memoryLine(extra())}")
                    lastWarn = now()
                } else {
                    val avail = meminfo("MemAvailable")
                    if (avail != null && avail < lowMb && now() - lastWarn > 30 * 60_000L) {
                        log.warn("Memory: running low — ${memoryLine(extra())}")
                        lastWarn = now()
                    }
                }
                kills = k ?: kills
            }
        }
    }

    /** "312 MB free of 922 MB; bridge 71 MB (heap up to 96 MB)" (+ [extra]). */
    fun memoryLine(extra: String?): String = buildString {
        val avail = meminfo("MemAvailable")
        val total = meminfo("MemTotal")
        append(if (avail != null && total != null) "$avail MB free of $total MB" else "memory unknown")
        selfRssMb()?.let { append("; bridge $it MB") }
        append(" (heap up to ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB)")
        extra?.let { append("; ").append(it) }
    }

    private fun at(ms: Long): String = AT.withZone(zone).format(Instant.ofEpochMilli(ms))

    private fun text(path: String): String? = runCatching { File(root, path).readText() }.getOrNull()

    private fun uptimeS(): Double? = text("proc/uptime")?.substringBefore(' ')?.toDoubleOrNull()

    private fun meminfo(key: String): Long? =
        text("proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.div(1024)

    /** Programs the kernel stopped for lack of memory since boot (Linux 4.13+), or null. */
    private fun oomKills(): Long? =
        text("proc/vmstat")?.lineSequence()?.firstOrNull { it.startsWith("oom_kill ") }?.substringAfter(' ')?.trim()?.toLongOrNull()

    private fun selfRssMb(): Long? = rssMb(root, "self")

    companion object {
        private val AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Resident memory of process [pid] ("self" for this one) in MB, from /proc/<pid>/status. */
        fun rssMb(root: File, pid: String): Long? =
            runCatching { File(root, "proc/$pid/status").readLines() }.getOrNull()
                ?.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.div(1024)
    }
}

/**
 * The bridge's log file: one line per entry, a date line whenever the day changes (entries only carry the time),
 * and at most [maxBytes] per file — the previous file is kept as `<name>.1`, so an SD card never fills up.
 */
class LogFileWriter(private val file: File, private val maxBytes: Long = 4L * 1024 * 1024) {
    private var out: Writer? = null
    private var size = 0L
    private var day: LocalDate? = null

    @Synchronized
    fun write(e: LogEntry, zone: ZoneId = ZoneId.systemDefault()) {
        runCatching {
            val w = out ?: open()
            val d = Instant.ofEpochMilli(e.timeMs).atZone(zone).toLocalDate()
            val sb = StringBuilder()
            if (d != day) {
                sb.append("---- ").append(d).append(" ----\n")
                day = d
            }
            sb.append(e.format(zone)).append('\n')
            val s = sb.toString()
            w.write(s)
            w.flush()
            size += s.toByteArray(Charsets.UTF_8).size
            if (size > maxBytes) rotate()
        }
    }

    @Synchronized
    fun close() {
        runCatching { out?.close() }
        out = null
    }

    private fun open(): Writer {
        file.absoluteFile.parentFile?.mkdirs()
        if (file.length() > maxBytes) rotateFile()
        size = file.length()
        return OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).also { out = it }
    }

    private fun rotate() {
        close()
        rotateFile()
        day = null
    }

    private fun rotateFile() {
        val old = File(file.path + ".1")
        old.delete()
        file.renameTo(old)
        size = 0
    }

    companion object {
        /** The last [n] lines of [file], earlier runs included (continues into `<name>.1` when needed). */
        fun tail(file: File, n: Int): List<String> {
            val cur = tailOf(file, n)
            if (cur.size >= n) return cur
            return tailOf(File(file.path + ".1"), n - cur.size) + cur
        }

        private fun tailOf(f: File, n: Int): List<String> {
            if (n <= 0 || !f.isFile) return emptyList()
            return runCatching {
                java.io.RandomAccessFile(f, "r").use { raf ->
                    val len = raf.length()
                    val start = maxOf(0L, len - n * 400L) // reads only the end, not the whole file
                    raf.seek(start)
                    val buf = ByteArray((len - start).toInt())
                    raf.readFully(buf)
                    var lines = String(buf, Charsets.UTF_8).split('\n')
                    if (start > 0) lines = lines.drop(1) // cut in the middle of a line
                    lines.filter { it.isNotEmpty() }.takeLast(n)
                }
            }.getOrDefault(emptyList())
        }
    }
}
