package io.github.anwo91.phevlink.util

import android.content.Context
import io.github.anwo91.phevlink.PhevApp
import io.github.anwo91.phevlink.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Times and durations as shown to people, in the app language. The [Context] parameters
 * default to the application (fine everywhere after `Application.onCreate`); pass one
 * explicitly from code that may run earlier.
 */
object Format {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun ago(ms: Long, now: Long = System.currentTimeMillis(), context: Context = PhevApp.instance): String {
        val r = AppLocale.localized(context).resources
        val s = (now - ms) / 1000
        return when {
            s < 45 -> r.getString(R.string.ago_just_now)
            s < 3600 -> {
                val m = if (s < 90) 1 else (s / 60).toInt()
                r.getQuantityString(R.plurals.ago_minutes, m, m)
            }
            s < 86400 -> {
                val h = if (s < 7200) 1 else (s / 3600).toInt()
                r.getQuantityString(R.plurals.ago_hours, h, h)
            }
            s < 2 * 86400 -> r.getString(R.string.ago_yesterday)
            else -> {
                val d = (s / 86400).toInt()
                r.getQuantityString(R.plurals.ago_days, d, d)
            }
        }
    }

    fun clock(ms: Long): String = clock.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    /** "21:40", or "Sat 07:30" (weekday in [locale]) when not today. */
    fun clockOrDay(
        ms: Long,
        now: Long = System.currentTimeMillis(),
        locale: Locale = AppLocale.uiLocale(PhevApp.instance),
    ): String {
        val zone = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (d == today) clock(ms)
        else DateTimeFormatter.ofPattern("EEE HH:mm", locale).withZone(zone).format(Instant.ofEpochMilli(ms))
    }

    fun minutes(m: Int, context: Context = PhevApp.instance): String {
        val r = AppLocale.localized(context)
        return when {
            m < 60 -> r.getString(R.string.dur_min, m)
            m % 60 == 0 -> r.getString(R.string.dur_h, m / 60)
            else -> r.getString(R.string.dur_h_min, m / 60, m % 60)
        }
    }
}
