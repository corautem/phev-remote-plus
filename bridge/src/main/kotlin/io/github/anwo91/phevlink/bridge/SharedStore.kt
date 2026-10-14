package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.energy.PriceAreas
import io.github.anwo91.phevlink.core.energy.PriceModel
import io.github.anwo91.phevlink.core.energy.PricePlan
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.planner.Departure
import java.io.File
import java.time.ZoneId

/**
 * What all phones share through the bridge: departures, the car's name, range, quick-climate defaults and the
 * weather location (plus the phones' time zone, so departures run on local time even when the bridge's clock is UTC).
 * Kept in one JSON file; every write bumps [rev] so phones can tell what's new. Last writer wins per key.
 */
class SharedStore(private val file: File, private val log: DiagLog, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var data: Map<String, Any?> = load()

    val rev: Long get() = (data["rev"] as? Number)?.toLong() ?: 0L

    fun snapshot(): Map<String, Any?> = data

    /** Applies the [KEYS] in [changes] (others ignored), from [by]. Returns the new snapshot. */
    @Synchronized
    fun update(changes: Map<String, Any?>, by: String): Map<String, Any?> {
        val accepted = changes.filter { (k, v) -> k in KEYS && valid(k, v) }
        (changes.keys - accepted.keys).takeIf { it.isNotEmpty() }?.let { log.warn("Shared data: ignored ${it.joinToString()} from $by") }
        if (accepted.isEmpty()) return data
        val next = LinkedHashMap(data)
        next.putAll(accepted)
        next["rev"] = rev + 1
        next["updatedAtMs"] = now()
        next["updatedBy"] = by
        save(next)
        log.link("Shared data: ${accepted.keys.joinToString()} saved by $by (rev ${next["rev"]})")
        if ("departures" in accepted) {
            val list = departures()
            log.link("Shared data: ${list.size} departure(s)" + list.joinToString("") { d ->
                "; ${d.time} ${d.days.sortedBy { it.value }.joinToString("") { it.name.take(2) }}" + (if (d.enabled) "" else " (off)") + (if (d.smart) " smart" else " ${d.mode} ${d.duration.minutes} min")
            })
        }
        return next
    }

    /** How the last departure the bridge handled went (shown to phones; not writable by them). */
    @Synchronized
    fun recordDeparture(result: Map<String, Any?>) {
        val next = LinkedHashMap(data)
        next["lastDeparture"] = result
        save(next)
    }

    fun departures(): List<Departure> = Codecs.departuresFromJson(data["departures"]?.let { Json.write(it) })

    fun zone(): ZoneId = (data["timeZone"] as? String)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    fun location(): Pair<Double, Double>? {
        val lat = (data["homeLat"] as? Number)?.toDouble() ?: return null
        val lon = (data["homeLon"] as? Number)?.toDouble() ?: return null
        return lat to lon
    }

    private fun valid(key: String, v: Any?): Boolean = when (key) {
        "departures" -> v is List<*>
        "carName", "quickMode", "timeZone", "carColor" -> v is String && v.length <= 80
        "fullRangeKm", "quickDuration" -> v is Number
        "homeLat", "homeLon" -> v == null || v is Number
        "quickSmart", "plugReminder" -> v is Boolean
        "plugReminderAt" -> v is String && HHMM.matches(v)
        "plugReminderBelow" -> v is Number && v.toInt() in 10..100
        "priceModel" -> v is String && v.lowercase() in setOf("spot", "norgespris", "fixed")
        "priceArea" -> v == null || (v is String && (v.isEmpty() || PriceAreas.valid(v)))
        "priceFixed" -> v == null || (v is Number && v.toDouble() in 0.0..20.0)
        "priceGridFee" -> v is Number && v.toDouble() in 0.0..10.0
        "priceSupport", "weeklySummary" -> v is Boolean
        "fullChargeKwh" -> v is Number && v.toDouble() in 4.0..30.0
        "priceCurrency" -> v is String && Regex("^[A-Z]{3}$").matches(v)
        "smartCharge", "smartUseDepartures" -> v is Boolean
        "smartReadyAt" -> v is String && HHMM.matches(v)
        else -> false
    }

    /** What electricity costs at home (shared by all phones): spot/Norgespris/fixed, area, fees, charger losses. */
    fun pricePlan(): PricePlan = PricePlan(
        model = PriceModel.of(data["priceModel"] as? String),
        area = (data["priceArea"] as? String)?.takeIf { PriceAreas.valid(it) }?.uppercase(),
        fixedPerKwh = (data["priceFixed"] as? Number)?.toDouble(),
        gridFeePerKwh = (data["priceGridFee"] as? Number)?.toDouble() ?: 0.0,
        support = data["priceSupport"] as? Boolean ?: true,
        fullChargeKwh = (data["fullChargeKwh"] as? Number)?.toDouble() ?: PricePlan.DEFAULT_FULL_CHARGE_KWH,
        currency = data["priceCurrency"] as? String,
    )

    /** The price area: chosen, or guessed from the home location (null when neither). */
    fun priceArea(): String? = pricePlan().area ?: location()?.let { (la, lo) -> PriceAreas.guess(la, lo) }

    /** Smart charging: off by default (it changes the car's charge timer); ready by 07:00 or the next departure. */
    data class SmartCharging(val enabled: Boolean, val readyAt: java.time.LocalTime, val useDepartures: Boolean)

    fun smartCharging(): SmartCharging = SmartCharging(
        enabled = data["smartCharge"] as? Boolean ?: false,
        readyAt = (data["smartReadyAt"] as? String)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: java.time.LocalTime.of(7, 0),
        useDepartures = data["smartUseDepartures"] as? Boolean ?: true,
    )

    /** The Sunday-evening summary of the week (on by default). */
    fun weeklySummary(): Boolean = data["weeklySummary"] as? Boolean ?: true

    /** The evening plug-in reminder: on (default), at what local time ("21:00"), below what battery %. */
    data class PlugReminder(val enabled: Boolean, val at: java.time.LocalTime, val belowPercent: Int)

    fun plugReminder(): PlugReminder = PlugReminder(
        enabled = data["plugReminder"] as? Boolean ?: true,
        at = (data["plugReminderAt"] as? String)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: java.time.LocalTime.of(21, 0),
        belowPercent = (data["plugReminderBelow"] as? Number)?.toInt() ?: 80,
    )

    private fun load(): Map<String, Any?> =
        runCatching { if (file.exists()) Json.parseObject(file.readText()) else emptyMap() }.getOrElse {
            log.warn("Shared data: couldn't read ${file.path} (${it.message}) — starting empty")
            emptyMap()
        }

    private fun save(m: Map<String, Any?>) {
        data = m
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(m))
            tmp.renameTo(file)
        }.onFailure { log.warn("Shared data: couldn't save ${file.path}: ${it.message}") }
    }

    companion object {
        /** What phones may write. */
        val KEYS = setOf(
            "departures", "carName", "fullRangeKm", "quickMode", "quickDuration", "quickSmart", "homeLat", "homeLon", "timeZone", "carColor",
            "plugReminder", "plugReminderAt", "plugReminderBelow",
            "priceModel", "priceArea", "priceFixed", "priceGridFee", "priceSupport", "fullChargeKwh", "weeklySummary", "priceCurrency",
            "smartCharge", "smartReadyAt", "smartUseDepartures",
        )
        /** Keys that change what charges cost. */
        val PRICE_KEYS = setOf("priceModel", "priceArea", "priceFixed", "priceGridFee", "priceSupport", "fullChargeKwh", "priceCurrency")
        private val HHMM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    }
}
