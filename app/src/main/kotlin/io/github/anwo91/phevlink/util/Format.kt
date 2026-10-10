package io.github.anwo91.phevlink.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Format {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val dayClock = DateTimeFormatter.ofPattern("EEE HH:mm")

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ms) / 1000
        return when {
            s < 45 -> "just now"
            s < 90 -> "1 min ago"
            s < 3600 -> "${s / 60} min ago"
            s < 7200 -> "1 h ago"
            s < 86400 -> "${s / 3600} h ago"
            s < 2 * 86400 -> "yesterday"
            else -> "${s / 86400} days ago"
        }
    }

    fun clock(ms: Long): String = clock.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    /** "21:40", or "Sat 07:30" when not today. */
    fun clockOrDay(ms: Long, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (d == today) clock(ms) else dayClock.withZone(zone).format(Instant.ofEpochMilli(ms))
    }

    fun minutes(m: Int): String = when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}
