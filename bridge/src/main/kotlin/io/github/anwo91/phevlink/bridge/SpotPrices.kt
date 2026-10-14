package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.energy.PriceSlot
import io.github.anwo91.phevlink.core.energy.SpotApi
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Spot prices per area and day ([SpotApi]): complete days are kept in [dir] (they never change), so each day is
 * fetched once; a day that isn't out yet (tomorrow before about 13:00) is asked for again at most every 30 minutes.
 * Only the area and the date leave the bridge.
 */
class SpotPrices(
    private val dir: File,
    private val log: DiagLog,
    private val fetch: (String) -> String? = ::httpGet,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val memory = ConcurrentHashMap<String, List<PriceSlot>>()
    private val lastTry = ConcurrentHashMap<String, Long>()

    /** The slots of [area] covering [fromMs]..[toMs] (fewer when some days aren't available). Blocks for the web. */
    fun slots(area: String, fromMs: Long, toMs: Long): List<PriceSlot> {
        val z = SpotApi.zone(area)
        val first = Instant.ofEpochMilli(fromMs).atZone(z).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(toMs, fromMs)).atZone(z).toLocalDate()
        val out = ArrayList<PriceSlot>()
        var d = first
        while (!d.isAfter(last)) {
            day(area, d)?.let { day -> out += day.filter { it.endMs > fromMs && it.startMs < toMs } }
            d = d.plusDays(1)
        }
        return out
    }

    /** One day's prices, or null when not available (yet). */
    fun day(area: String, day: LocalDate): List<PriceSlot>? {
        val a = area.uppercase()
        val key = "${a}_$day"
        memory[key]?.let { return it }
        val file = File(dir, "$key.json")
        if (file.exists()) {
            SpotApi.parse(file.readText())?.takeIf { SpotApi.complete(it, a, day) }?.let { memory[key] = it; return it }
        }
        val t = now()
        if (t - (lastTry[key] ?: 0L) < RETRY_MS) return null
        lastTry[key] = t
        val url = SpotApi.url(a, day)
        val text = runCatching { fetch(url) }.getOrNull()
        val slots = text?.let { SpotApi.parse(it) }
        if (slots == null || slots.isEmpty()) {
            log.link("Prices: $a $day not available (${if (text == null) "no answer from ${URI(url).host}" else "no prices in the answer"})")
            return null
        }
        val complete = SpotApi.complete(slots, a, day)
        log.link("Prices: $a $day — ${slots.size} slots, %.2f–%.2f per kWh before VAT%s".format(
            slots.minOf { it.perKwh }, slots.maxOf { it.perKwh }, if (complete) "" else " (not the whole day)",
        ))
        if (complete) {
            runCatching {
                dir.mkdirs()
                file.writeText(text)
            }.onFailure { log.warn("Prices: couldn't keep ${file.path}: ${it.message}") }
            memory[key] = slots
        }
        return slots
    }

    companion object {
        private const val RETRY_MS = 30 * 60_000L

        fun httpGet(url: String): String? {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "PHEVLink-bridge/${BridgeService.BUILD} github.com/Anwo91/phev-remote-plus")
            return try {
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
    }
}
