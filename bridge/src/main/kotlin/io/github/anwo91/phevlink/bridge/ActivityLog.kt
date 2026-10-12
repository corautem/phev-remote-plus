package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Json
import java.io.File

/**
 * The bridge's activity log: who did what (phones through the bridge, phones that report what they did straight over
 * the car's Wi-Fi, the bridge's own departures) and what the car did (charging, climate, doors, locks…), newest last.
 * Kept in memory ([capacity] entries) and appended to [file] as JSON lines, so it survives restarts. Every entry is
 * also written to the diagnostic log as one plain English line.
 */
class ActivityLog(private val file: File?, private val log: DiagLog, private val capacity: Int = 3000) {
    private val entries = ArrayDeque<Activity>()
    private val ids = HashSet<String>()
    private var linesInFile = 0

    init {
        load()
    }

    @Synchronized
    fun add(a: Activity): Boolean {
        if (!ids.add(a.id)) return false
        // Phones may report late: keep the list in time order.
        if (entries.isEmpty() || entries.last().atMs <= a.atMs) entries.addLast(a)
        else {
            val i = entries.indexOfFirst { it.atMs > a.atMs }
            entries.add(if (i < 0) entries.size else i, a)
        }
        while (entries.size > capacity) ids.remove(entries.removeFirst().id)
        log.link("Activity: " + ActivityText.english(a) + (a.via?.let { " [$it]" } ?: ""))
        append(a)
        return true
    }

    /** Newest first, at most [limit], older than [beforeMs] when given. */
    @Synchronized
    fun recent(limit: Int = 200, beforeMs: Long? = null): List<Activity> =
        entries.asReversed().asSequence().filter { beforeMs == null || it.atMs < beforeMs }.take(limit.coerceIn(1, 1000)).toList()

    val size: Int @Synchronized get() = entries.size

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val lines = f.readLines()
            linesInFile = lines.size
            lines.takeLast(capacity).forEach { line ->
                runCatching { Activity.fromMap(Json.parseObject(line)) }.getOrNull()?.let {
                    if (ids.add(it.id)) entries.addLast(it)
                }
            }
            entries.sortBy { it.atMs }
        }.onFailure { log.warn("Activity: couldn't read ${f.path}: ${it.message}") }
    }

    private fun append(a: Activity) {
        val f = file ?: return
        runCatching {
            f.absoluteFile.parentFile?.mkdirs()
            f.appendText(Json.write(a.toMap()) + "\n")
            linesInFile++
            // Keep the file about as long as the list: rewrite it now and then.
            if (linesInFile > capacity * 2) {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(entries.joinToString("") { Json.write(it.toMap()) + "\n" })
                tmp.renameTo(f)
                linesInFile = entries.size
            }
        }.onFailure { log.warn("Activity: couldn't save to ${f.path}: ${it.message}") }
    }
}
