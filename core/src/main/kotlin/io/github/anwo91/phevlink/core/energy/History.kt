package io.github.anwo91.phevlink.core.energy

import kotlin.math.roundToInt

/** One charge at home, as the bridge saw it: battery levels, energy from the wall and (when known) what it cost. */
data class ChargeRecord(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val fromPct: Int,
    val toPct: Int,
    val kwh: Double,
    val cost: Double?,
    val perKwh: Double?,
    val currency: String,
    val model: String,
    val area: String?,
) {
    val minutes: Int get() = ((endMs - startMs) / 60_000).toInt()

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "startMs" to startMs, "endMs" to endMs, "fromPct" to fromPct, "toPct" to toPct, "kwh" to kwh,
        "cost" to cost, "perKwh" to perKwh, "currency" to currency, "model" to model, "area" to area,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): ChargeRecord? = runCatching {
            ChargeRecord(
                id = m["id"] as String,
                startMs = (m["startMs"] as Number).toLong(),
                endMs = (m["endMs"] as Number).toLong(),
                fromPct = (m["fromPct"] as Number).toInt(),
                toPct = (m["toPct"] as Number).toInt(),
                kwh = (m["kwh"] as Number).toDouble(),
                cost = (m["cost"] as? Number)?.toDouble(),
                perKwh = (m["perKwh"] as? Number)?.toDouble(),
                currency = m["currency"] as? String ?: "NOK",
                model = m["model"] as? String ?: "spot",
                area = m["area"] as? String,
            )
        }.getOrNull()
    }
}

/**
 * One time the car was away: from when the bridge last had it ([leftMs]) until it was back ([backMs]), with the battery
 * level before and after (null when unknown). [usedPct] is negative when the car came back fuller (charged away).
 */
data class TripRecord(val id: String, val leftMs: Long, val backMs: Long, val fromPct: Int?, val toPct: Int?) {
    val minutes: Int get() = ((backMs - leftMs) / 60_000).toInt()
    val usedPct: Int? get() = if (fromPct != null && toPct != null) fromPct - toPct else null

    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "leftMs" to leftMs, "backMs" to backMs, "fromPct" to fromPct, "toPct" to toPct)

    companion object {
        fun fromMap(m: Map<String, Any?>): TripRecord? = runCatching {
            TripRecord(
                id = m["id"] as String,
                leftMs = (m["leftMs"] as Number).toLong(),
                backMs = (m["backMs"] as Number).toLong(),
                fromPct = (m["fromPct"] as? Number)?.toInt(),
                toPct = (m["toPct"] as? Number)?.toInt(),
            )
        }.getOrNull()
    }
}

/**
 * Turns "the car left / is back" (the bridge's reach) and the car's battery into trips. A trip opens when the car is
 * declared not seen (it left at the bridge's last sight of it, with the battery level known then) and closes when the
 * car is back and has reported its battery again — or after [waitForDataMs] without that. Shorter than [minAwayMs]:
 * not a trip (a Wi-Fi hiccup, the car moved in the driveway).
 */
class TripTracker(
    private val minAwayMs: Long = 10 * 60_000L,
    private val waitForDataMs: Long = 30 * 60_000L,
) {
    /** The open trip (kept by the owner across restarts): left at, battery then, back at (once back). */
    data class Open(val leftMs: Long, val fromPct: Int?, val backMs: Long? = null)

    var open: Open? = null

    /** The car is not seen: it left at [leftMs] with [pct] % (the last known level). */
    fun left(leftMs: Long, pct: Int?) {
        if (open == null) open = Open(leftMs, pct)
    }

    /** The car is within reach again at [nowMs]; a trip shorter than the minimum is dropped. */
    fun back(nowMs: Long) {
        val o = open ?: return
        if (o.backMs != null) return
        open = if (nowMs - o.leftMs < minAwayMs) null else o.copy(backMs = nowMs)
    }

    /**
     * A car state: [updatedAtMs] when the car reported it, [pct] its battery. Returns the finished trip once the car
     * has reported after coming back (or waited too long for that).
     */
    fun onCar(updatedAtMs: Long?, pct: Int?, nowMs: Long, newId: (Long) -> String): TripRecord? {
        val o = open ?: return null
        val back = o.backMs ?: return null
        val fresh = updatedAtMs != null && updatedAtMs >= back && pct != null
        if (!fresh && nowMs - back < waitForDataMs) return null
        open = null
        return TripRecord(newId(o.leftMs), o.leftMs, back, o.fromPct, if (fresh) pct else null)
    }
}

/** A week (or any span) at a glance: charging and trips. */
data class Summary(
    val charges: Int,
    val kwh: Double,
    /** Sum of the known costs; [costComplete] false when some charges' costs aren't known (yet). */
    val cost: Double?,
    val costComplete: Boolean,
    val currency: String?,
    val trips: Int,
    val awayMinutes: Int,
    /** Battery used on trips (only trips that used some), % of a full battery. */
    val usedPct: Int,
    /** Electric km that is about (from the full range), null without a range. */
    val electricKm: Int?,
) {
    val empty: Boolean get() = charges == 0 && trips == 0

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "charges" to charges, "kwh" to kwh, "cost" to cost, "costComplete" to costComplete, "currency" to currency,
        "trips" to trips, "awayMinutes" to awayMinutes, "usedPct" to usedPct, "electricKm" to electricKm,
    )

    companion object {
        fun of(charges: List<ChargeRecord>, trips: List<TripRecord>, fromMs: Long, toMs: Long, fullRangeKm: Int?): Summary {
            val c = charges.filter { it.endMs in fromMs until toMs }
            val t = trips.filter { it.backMs in fromMs until toMs }
            val costs = c.mapNotNull { it.cost }
            val used = t.mapNotNull { it.usedPct }.filter { it > 0 }.sum()
            return Summary(
                charges = c.size,
                kwh = (c.sumOf { it.kwh } * 10).roundToInt() / 10.0,
                cost = if (costs.isEmpty()) null else (costs.sum() * 100).roundToInt() / 100.0,
                costComplete = costs.size == c.size,
                currency = c.lastOrNull()?.currency,
                trips = t.size,
                awayMinutes = t.sumOf { it.minutes },
                usedPct = used,
                electricKm = fullRangeKm?.takeIf { it > 0 }?.let { (used * it) / 100 },
            )
        }
    }
}
