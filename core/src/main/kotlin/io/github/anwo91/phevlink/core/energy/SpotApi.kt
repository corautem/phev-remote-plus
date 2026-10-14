package io.github.anwo91.phevlink.core.energy

import io.github.anwo91.phevlink.core.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Day-ahead spot prices per price area, from two free services with the same format: hvakosterstrommen.no (Norway,
 * NOK) and elprisetjustnu.se (Sweden, SEK). One file per area and local day: a list of
 * `{"NOK_per_kWh": 0.83, "time_start": "2026-10-14T00:00:00+02:00", "time_end": "…"}` — hourly, or 15-minute slots
 * since the Nordic market moved to 15 minutes. Prices are before VAT. Tomorrow's are out around 13:00.
 */
object SpotApi {
    fun zone(area: String): ZoneId = ZoneId.of(if (area.uppercase().startsWith("SE")) "Europe/Stockholm" else "Europe/Oslo")

    fun url(area: String, day: LocalDate): String {
        val a = area.uppercase()
        val host = if (a.startsWith("SE")) "https://www.elprisetjustnu.se" else "https://www.hvakosterstrommen.no"
        return "%s/api/v1/prices/%04d/%02d-%02d_%s.json".format(host, day.year, day.monthValue, day.dayOfMonth, a)
    }

    /** The slots in [text] (price in NOK or SEK per kWh), or null when it isn't a price list. */
    fun parse(text: String): List<PriceSlot>? {
        val list = runCatching { Json.parse(text) as? List<*> }.getOrNull() ?: return null
        return list.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val price = (m["NOK_per_kWh"] ?: m["SEK_per_kWh"]) as? Number ?: return@mapNotNull null
            val start = (m["time_start"] as? String)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            val end = (m["time_end"] as? String)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?: (start + 3_600_000L)
            if (end <= start) null else PriceSlot(start, end, price.toDouble())
        }.sortedBy { it.startMs }
    }

    /** A day's list is complete when it covers the whole local day (23–25 hours with summer time). */
    fun complete(slots: List<PriceSlot>, area: String, day: LocalDate): Boolean {
        if (slots.isEmpty()) return false
        val z = zone(area)
        val from = day.atStartOfDay(z).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val covered = slots.sumOf { (minOf(it.endMs, to) - maxOf(it.startMs, from)).coerceAtLeast(0) }
        return covered >= to - from
    }
}
