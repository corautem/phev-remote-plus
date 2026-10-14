package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.energy.ChargeCosts
import io.github.anwo91.phevlink.core.energy.ChargeRecord
import io.github.anwo91.phevlink.core.energy.PriceAreas
import io.github.anwo91.phevlink.core.energy.PriceModel
import io.github.anwo91.phevlink.core.energy.Summary
import io.github.anwo91.phevlink.core.energy.TripRecord
import io.github.anwo91.phevlink.core.energy.TripTracker
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Charging and trips at home, kept on the bridge for every phone:
 * - each charge the bridge sees ("charge.session" in the activity log) with its energy from the wall and — from the
 *   shared price plan and the area's spot prices — what it cost (filled in later when the prices weren't out yet);
 * - each trip: the car not seen by the bridge, then back, with the battery level before and after;
 * - on Sunday at 19:00 the week's summary, raised as an alert so every phone shows it in its own language.
 * Files: charges.jsonl and trips.jsonl (one JSON object per line), trip_open.json while the car is away.
 */
class EnergyHistory(
    private val dir: File,
    private val store: SharedStore,
    private val prices: SpotPrices,
    private val activity: ActivityLog,
    private val log: DiagLog,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val chargesFile = File(dir, "charges.jsonl")
    private val tripsFile = File(dir, "trips.jsonl")
    private val openFile = File(dir, "trip_open.json")
    private val weeklyFile = File(dir, "weekly_sent")
    private val charges = ArrayList<ChargeRecord>()
    private val trips = ArrayList<TripRecord>()
    private val tracker = TripTracker()

    init {
        load()
    }

    /** Starts listening for charges and watching for trips ([reach]: the bridge's reach map, [car]: the last state). */
    fun start(reach: (() -> Map<String, Any?>?)?, car: () -> CarState): Job {
        activity.addListener { a -> if (a.code == "charge.session") scope.launch(Dispatchers.IO) { record(a) } }
        return scope.launch(Dispatchers.IO) {
            var lastFill = 0L
            while (isActive) {
                val fill = now() - lastFill > 30 * 60_000L
                if (fill) lastFill = now()
                tick(runCatching { reach?.invoke() }.getOrNull(), car(), tripsOn = reach != null, fill = fill)
                delay(30_000)
            }
        }
    }

    /** One look: the trip, the weekly summary and (with [fill]) costs still missing. */
    internal fun tick(reach: Map<String, Any?>?, car: CarState, tripsOn: Boolean = true, fill: Boolean = false) {
        if (tripsOn) runCatching { watchTrip(reach, car) }.onFailure { log.warn("Trips: ${it.message}") }
        runCatching { weekly() }.onFailure { log.warn("Weekly summary: ${it.message}") }
        if (fill) runCatching { fillCosts() }.onFailure { log.warn("Charging costs: ${it.message}") }
    }

    // ------------------------------------------------------------------ charges

    /** A charge just ended: energy, cost (if the prices are known), kept and logged. */
    fun record(a: Activity) {
        val from = a.int("fromPct") ?: return
        val to = a.int("toPct") ?: return
        val minutes = a.int("minutes") ?: 0
        if (to <= from) {
            log.link("Charging: ${from} → $to % — nothing charged, not kept")
            return
        }
        val end = a.atMs
        val start = end - minutes * 60_000L
        val r = price(ChargeRecord(a.id, start, end, from, to, 0.0, null, null, "NOK", "spot", null))
        synchronized(this) {
            charges += r
            append(chargesFile, r.toMap())
        }
        log.link("Charging: $from → $to % in ${ActivityText.hm(minutes)} ≈ ${r.kwh} kWh from the wall" + costText(r))
    }

    /** [r] with energy and cost worked out from the current plan (and the area's prices for its hours). */
    private fun price(r: ChargeRecord): ChargeRecord {
        val plan = store.pricePlan()
        val area = store.priceArea()
        val kwh = ChargeCosts.gridKwh(r.fromPct, r.toPct, plan.fullChargeKwh)
        val slots = if (plan.model == PriceModel.SPOT && area != null) prices.slots(area, r.startMs, maxOf(r.endMs, r.startMs + 60_000)) else emptyList()
        val c = ChargeCosts.cost(kwh, r.startMs, r.endMs, plan, area, slots)
        return r.copy(kwh = kwh, cost = c.cost, perKwh = c.perKwh, currency = c.currency, model = plan.model.name.lowercase(), area = area)
    }

    private fun costText(r: ChargeRecord): String = when {
        r.cost != null -> ", ≈ %.2f %s (%.2f/kWh, %s%s)".format(r.cost, r.currency, r.perKwh ?: 0.0, r.model, r.area?.let { " $it" } ?: "")
        r.model == "spot" && r.area == null -> " — no cost: no price area (set the home location or the area)"
        r.model == "fixed" -> " — no cost: no fixed price set"
        else -> " — cost later, when the ${r.area} prices for those hours are in"
    }

    /** Charges of the last week without a cost (prices weren't out, or no area then): priced again. */
    internal fun fillCosts() {
        val since = now() - 7 * 24 * 3600_000L
        val todo = synchronized(this) { charges.filter { it.cost == null && it.endMs > since } }
        if (todo.isEmpty()) return
        var filled = 0
        for (r in todo) {
            val p = price(r)
            if (p.cost == null) continue
            synchronized(this) {
                val i = charges.indexOfFirst { it.id == r.id }
                if (i >= 0) charges[i] = p
            }
            filled++
            log.link("Charging: cost for ${Instant.ofEpochMilli(r.endMs).atZone(store.zone()).toLocalDate()} filled in" + costText(p))
        }
        if (filled > 0) synchronized(this) { rewrite(chargesFile, charges.map { it.toMap() }) }
    }

    // ------------------------------------------------------------------ trips

    private fun watchTrip(reach: Map<String, Any?>?, car: CarState) {
        val state = reach?.get("state") as? String ?: return
        val t = now()
        when (state) {
            CarWifiWatch.REACH_NOT_SEEN -> if (tracker.open == null) {
                val left = (reach["lastSeenMs"] as? Number)?.toLong() ?: (reach["sinceMs"] as? Number)?.toLong() ?: t
                tracker.left(left, car.batteryPercent)
                log.link("Trips: the car left at ${clock(left)} with ${car.batteryPercent ?: "?"} %")
                saveOpen()
            }
            CarWifiWatch.REACH_IN_REACH -> {
                val o = tracker.open
                if (o != null && o.backMs == null) {
                    tracker.back(t)
                    log.link(if (tracker.open == null) "Trips: back after ${(t - o.leftMs) / 60_000} min — too short to count" else "Trips: the car is back at ${clock(t)} — waiting for its battery level")
                    saveOpen()
                }
            }
        }
        val trip = tracker.onCar(car.updatedAtMs, car.batteryPercent, t) { Activity.newId(it) } ?: return
        saveOpen()
        synchronized(this) {
            trips += trip
            append(tripsFile, trip.toMap())
        }
        log.link("Trips: away ${ActivityText.hm(trip.minutes)}, battery ${trip.fromPct ?: "?"} → ${trip.toPct ?: "?"} %")
        activity.add(Activity(trip.backMs, Activity.CAR, "car.trip", linkedMapOf("minutes" to trip.minutes, "fromPct" to trip.fromPct, "toPct" to trip.toPct)))
    }

    private fun saveOpen() {
        val o = tracker.open
        runCatching {
            if (o == null) openFile.delete()
            else openFile.writeText(Json.write(mapOf("leftMs" to o.leftMs, "fromPct" to o.fromPct, "backMs" to o.backMs)))
        }
    }

    // ------------------------------------------------------------------ weekly summary

    /** Sunday 19:00–19:30 (local), once a week: the last seven days, when there was anything. */
    private fun weekly() {
        val z = store.zone()
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now()), z)
        if (t.dayOfWeek != DayOfWeek.SUNDAY) return
        val due = t.toLocalDate().atTime(WEEKLY_AT).atZone(z)
        if (t.isBefore(due) || !t.isBefore(due.plusMinutes(30))) return
        val week = t.toLocalDate().toString()
        if (runCatching { weeklyFile.readText().trim() }.getOrNull() == week) return
        runCatching { weeklyFile.writeText(week) }
        if (!store.weeklySummary()) {
            log.link("Weekly summary: off")
            return
        }
        val s = summary(now() - 7 * 24 * 3600_000L, now())
        if (s.empty) {
            log.link("Weekly summary: no charging or trips this week — nothing to send")
            return
        }
        val text = buildString {
            append("${s.charges} charge(s), ${s.kwh} kWh")
            s.cost?.let { append(", ≈ %.0f %s".format(it, s.currency ?: "")) }
            if (s.trips > 0) append("; ${s.trips} trip(s), ${s.usedPct} % battery" + (s.electricKm?.let { " (≈ $it km electric)" } ?: ""))
        }
        log.link("Weekly summary: $text → the phones")
        raise?.invoke(Alert(AlertType.WEEKLY_SUMMARY, text, now(), s.toMap()))
    }

    /** How the summary reaches the phones (the bridge's alerts). */
    @Volatile var raise: ((Alert) -> Unit)? = null

    // ------------------------------------------------------------------ reading

    fun summary(fromMs: Long, toMs: Long): Summary = synchronized(this) {
        Summary.of(charges.toList(), trips.toList(), fromMs, toMs, (store.snapshot()["fullRangeKm"] as? Number)?.toInt())
    }

    /** For GET /api/history: newest first, the plan, this week and this month. */
    fun describe(limit: Int): Map<String, Any?> {
        val z = store.zone()
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now()), z)
        val monthStart = t.toLocalDate().with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay(z).toInstant().toEpochMilli()
        val plan = store.pricePlan()
        val area = store.priceArea()
        val (c, tr) = synchronized(this) { charges.takeLast(limit).asReversed().map { it.toMap() } to trips.takeLast(limit).asReversed().map { it.toMap() } }
        return linkedMapOf(
            "ok" to true,
            "charges" to c,
            "trips" to tr,
            "week" to summary(now() - 7 * 24 * 3600_000L, now() + 1).toMap(),
            "month" to summary(monthStart, now() + 1).toMap(),
            "plan" to linkedMapOf(
                "model" to plan.model.name.lowercase(), "area" to plan.area, "areaUsed" to area,
                "areaGuess" to store.location()?.let { (la, lo) -> PriceAreas.guess(la, lo) },
                "currency" to plan.currencyFor(area), "fixedPerKwh" to plan.fixedPerKwh, "gridFeePerKwh" to plan.gridFeePerKwh,
                "support" to plan.support, "fullChargeKwh" to plan.fullChargeKwh,
            ),
        )
    }

    // ------------------------------------------------------------------ files

    private fun load() {
        readLines(chargesFile).mapNotNullTo(charges) { ChargeRecord.fromMap(it) }
        readLines(tripsFile).mapNotNullTo(trips) { TripRecord.fromMap(it) }
        runCatching {
            if (openFile.exists()) {
                val m = Json.parseObject(openFile.readText())
                tracker.open = TripTracker.Open(
                    (m["leftMs"] as Number).toLong(), (m["fromPct"] as? Number)?.toInt(), (m["backMs"] as? Number)?.toLong(),
                )
            }
        }
        if (charges.isNotEmpty() || trips.isNotEmpty() || tracker.open != null) {
            log.link("History: ${charges.size} charge(s), ${trips.size} trip(s)" + (tracker.open?.let { " — the car is away since ${clock(it.leftMs)}" } ?: ""))
        }
    }

    private fun readLines(f: File): List<Map<String, Any?>> = runCatching {
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { l -> runCatching { Json.parseObject(l) }.getOrNull() }
    }.getOrElse {
        log.warn("History: couldn't read ${f.path}: ${it.message}")
        emptyList()
    }

    private fun append(f: File, m: Map<String, Any?>) = runCatching {
        dir.mkdirs()
        f.appendText(Json.write(m) + "\n")
    }.onFailure { log.warn("History: couldn't save to ${f.path}: ${it.message}") }

    private fun rewrite(f: File, list: List<Map<String, Any?>>) = runCatching {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(list.joinToString("") { Json.write(it) + "\n" })
        tmp.renameTo(f)
    }.onFailure { log.warn("History: couldn't save ${f.path}: ${it.message}") }

    private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(store.zone()).toLocalTime().withNano(0).toString().take(5)

    companion object {
        val WEEKLY_AT: LocalTime = LocalTime.of(19, 0)
    }
}
