package io.github.anwo91.phevlink.core.energy

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One spot-price interval (15 minutes or an hour): price per kWh before VAT, in the area's currency. */
data class PriceSlot(val startMs: Long, val endMs: Long, val perKwh: Double)

/** How the owner pays for electricity at home. */
enum class PriceModel {
    /** Hourly/15-minute spot price of the area (Norway, Sweden), plus VAT, minus the Norwegian electricity support. */
    SPOT,

    /** Norgespris: a fixed 40 øre/kWh before VAT (50 øre with VAT; 40 øre in NO4, which has no VAT). */
    NORGESPRIS,

    /** A fixed price per kWh the owner enters (VAT included). */
    FIXED,
    ;

    companion object {
        fun of(s: String?): PriceModel = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: SPOT
    }
}

/**
 * The owner's electricity price, as kept on the bridge for all phones. [area] null = guessed from the home location.
 * [gridFeePerKwh]: what the bill adds per kWh on top of the energy (grid fee's energy part, supplier markup), VAT
 * included. [fullChargeKwh]: energy from the wall for a full charge from empty (charging losses included).
 */
data class PricePlan(
    val model: PriceModel = PriceModel.SPOT,
    val area: String? = null,
    val fixedPerKwh: Double? = null,
    val gridFeePerKwh: Double = 0.0,
    val support: Boolean = true,
    val fullChargeKwh: Double = DEFAULT_FULL_CHARGE_KWH,
    /** The fixed price's currency (ISO code, e.g. "EUR"); spot prices are in the area's own currency. */
    val currency: String? = null,
) {
    /** The currency costs are in, for [area]. */
    fun currencyFor(area: String?): String = if (model == PriceModel.FIXED && currency != null) currency else PriceAreas.currency(area)

    companion object {
        /** 2014–2018 cars: about 10 kWh usable, about 12 kWh from the wall with the charger's losses. */
        const val DEFAULT_FULL_CHARGE_KWH = 12.0
    }
}

/** Price areas: Norway NO1–NO5 and Sweden SE1–SE4, a rough guess from coordinates, currency and VAT. */
object PriceAreas {
    val NORWAY = listOf("NO1", "NO2", "NO3", "NO4", "NO5")
    val SWEDEN = listOf("SE1", "SE2", "SE3", "SE4")
    val ALL = NORWAY + SWEDEN

    fun valid(a: String?): Boolean = a != null && a.uppercase() in ALL

    fun currency(area: String?): String = if (area?.uppercase()?.startsWith("SE") == true) "SEK" else "NOK"

    /** VAT on electricity: 25 %, except Northern Norway (NO4), which has none for households. */
    fun vat(area: String?): Double = if (area?.uppercase() == "NO4") 0.0 else 0.25

    /** Norwegian electricity support (strømstøtte) only exists in Norway. */
    fun hasSupport(area: String?): Boolean = area?.uppercase()?.startsWith("NO") == true

    /**
     * The price area for a home location — a rough guess (area borders follow grid regions, not lines on a map), shown
     * as a guess the owner can change. Null outside Norway and Sweden.
     */
    fun guess(lat: Double, lon: Double): String? {
        if (lat < 55.0 || lat > 71.5 || lon < 4.0 || lon > 31.5) return null
        return if (inSweden(lat, lon)) {
            when {
                lat >= 65.5 -> "SE1"
                lat >= 61.0 -> "SE2"
                lat >= 56.9 -> "SE3"
                else -> "SE4"
            }
        } else {
            if (lat < 57.8 || (lon > 24.0 && lat < 68.5)) return null // Denmark, Finland, the Baltic
            when {
                lat >= 65.0 -> "NO4"
                lat >= 62.3 -> "NO3"
                lat > 60.5 && lon < 8.0 -> "NO5"
                lat > 59.6 && lon < 7.0 -> "NO5"
                lat < 60.2 && lon < 9.85 -> "NO2"
                else -> "NO1"
            }
        }
    }

    // The Norway–Sweden border as (latitude, longitude) points, south to north: east of it is Sweden.
    private val BORDER = listOf(
        58.9 to 11.0, 59.1 to 11.45, 59.3 to 11.75, 59.8 to 12.1, 60.2 to 12.45, 61.0 to 12.4, 61.5 to 12.6, 62.0 to 12.25,
        62.6 to 12.15, 63.0 to 12.1, 63.5 to 12.2, 64.0 to 13.7, 64.5 to 14.1, 65.0 to 14.4, 66.0 to 15.2, 67.0 to 16.3,
        68.0 to 17.9, 68.5 to 19.0, 69.05 to 20.6,
    )

    private fun inSweden(lat: Double, lon: Double): Boolean {
        if (lat > 69.05) return false
        // South of the border's start: Skåne and the west coast (Denmark's Zealand ends just west of 12.66° E).
        if (lat < 58.9) return lon < 19.5 && lat > 55.0 && lon > (if (lat < 56.1) 12.66 else 11.0)
        val i = BORDER.indexOfLast { it.first <= lat }.coerceIn(0, BORDER.size - 2)
        val (la0, lo0) = BORDER[i]
        val (la1, lo1) = BORDER[i + 1]
        val border = lo0 + (lo1 - lo0) * ((lat - la0) / (la1 - la0)).coerceIn(0.0, 1.0)
        return lon > border && lon < 24.2
    }
}

/** What a charge cost: [cost] null while the spot prices for its hours aren't known yet. */
data class ChargeCost(val kwh: Double, val cost: Double?, val perKwh: Double?, val currency: String)

/** Energy and cost of a charge from the battery levels and the price plan. */
object ChargeCosts {
    /** Norwegian electricity support 2026: 90 % of the hourly spot price above 77 øre/kWh (before VAT). */
    const val SUPPORT_THRESHOLD_NOK = 0.77
    const val SUPPORT_SHARE = 0.90
    const val NORGESPRIS_BEFORE_VAT = 0.40

    /** Energy from the wall for a charge from [fromPct] to [toPct] %. */
    fun gridKwh(fromPct: Int, toPct: Int, fullChargeKwh: Double): Double =
        ((toPct - fromPct).coerceAtLeast(0) / 100.0 * fullChargeKwh * 10).roundToInt() / 10.0

    /**
     * The cost of [kwh] drawn evenly from [startMs] to [endMs] under [plan] in [area] (the plan's own area, or the
     * guessed one). [slots]: spot prices covering the charge (only needed for [PriceModel.SPOT]); when they don't cover
     * it, [ChargeCost.cost] is null.
     */
    fun cost(kwh: Double, startMs: Long, endMs: Long, plan: PricePlan, area: String?, slots: List<PriceSlot>): ChargeCost {
        val currency = plan.currencyFor(area)
        val vat = PriceAreas.vat(area)
        val perKwh: Double? = when (plan.model) {
            PriceModel.FIXED -> plan.fixedPerKwh
            PriceModel.NORGESPRIS -> NORGESPRIS_BEFORE_VAT * (1 + vat)
            PriceModel.SPOT -> spotAverage(startMs, endMs, slots, plan.support && PriceAreas.hasSupport(area))?.let { it * (1 + vat) }
        }?.let { it + plan.gridFeePerKwh }
        val cost = perKwh?.let { round2(it * kwh) }
        return ChargeCost(kwh, cost, perKwh?.let(::round2), currency)
    }

    /**
     * The spot price paid per kWh over [startMs]..[endMs] (time-weighted, so energy drawn evenly), after the support:
     * 90 % of each hour's average above the threshold. Null when the slots leave more than 5 % of the time uncovered.
     */
    fun spotAverage(startMs: Long, endMs: Long, slots: List<PriceSlot>, support: Boolean): Double? {
        val end = max(endMs, startMs + 60_000)
        val sorted = slots.sortedBy { it.startMs }
        val hourAvg = if (support) hourlyAverages(sorted) else emptyMap()
        var covered = 0L
        var sum = 0.0
        for (s in sorted) {
            val a = max(s.startMs, startMs)
            val b = min(s.endMs, end)
            if (b <= a) continue
            val hour = s.startMs / HOUR
            val sub = hourAvg[hour]?.let { SUPPORT_SHARE * max(0.0, it - SUPPORT_THRESHOLD_NOK) } ?: 0.0
            sum += (s.perKwh - sub) * (b - a)
            covered += b - a
        }
        val span = end - startMs
        if (covered < span * 0.95 || covered == 0L) return null
        return sum / covered
    }

    /** Average spot price per clock hour (UTC hour index) — the support is worked out per hour. */
    private fun hourlyAverages(slots: List<PriceSlot>): Map<Long, Double> =
        slots.groupBy { it.startMs / HOUR }.mapValues { (_, l) ->
            val total = l.sumOf { it.endMs - it.startMs }.toDouble()
            if (total <= 0) l.map { it.perKwh }.average() else l.sumOf { it.perKwh * (it.endMs - it.startMs) } / total
        }

    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
    private const val HOUR = 3_600_000L
}
