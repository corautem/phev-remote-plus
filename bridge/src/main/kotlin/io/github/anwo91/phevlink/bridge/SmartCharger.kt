package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.energy.ChargeCosts
import io.github.anwo91.phevlink.core.energy.ChargePlan
import io.github.anwo91.phevlink.core.energy.PriceAreas
import io.github.anwo91.phevlink.core.energy.PriceModel
import io.github.anwo91.phevlink.core.energy.SmartCharge
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ChargeTimerProgram
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.Reg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Smart charging (opt-in, shared setting; spot price only): when the car is plugged in at home, the bridge works out
 * how long it needs to get full and moves tonight's charging to the cheapest hours before the car is needed (the next
 * departure, or 07:00), using the car's own charge timer (2014–2017 cars). The owner's timer is never lost:
 * - it is read only from what the car sent during this run of the bridge (both timer registers), and saved — with
 *   the program about to be written — to smart_charge.json (atomically) *before* anything is written;
 * - every program the bridge wrote or tried tonight is remembered, so a write that failed but reached the car still
 *   counts as the bridge's and is put back;
 * - before putting it back, the bridge checks the car still carries one of its own programs: if a phone changed the
 *   timer meanwhile, that change stays;
 * - putting it back is retried (with growing pauses) until it works, and only while the car is within reach.
 * Every decision is logged.
 */
class SmartCharger(
    private val store: SharedStore,
    private val history: EnergyHistory,
    private val prices: SpotPrices,
    private val activity: ActivityLog,
    private val car: () -> CarState,
    /** True when the bridge is on the car's Wi-Fi (the car is home). */
    private val carHome: () -> Boolean?,
    private val execute: suspend (CarCommand) -> CommandResult,
    private val log: DiagLog,
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class State(
        /** The owner's timer before the bridge changed it. */
        val owner: ChargeTimerProgram? = null,
        /** The bridge's program on the car (or being written); non-null until the owner's is back. */
        val written: ChargeTimerProgram? = null,
        /** The car confirmed [written]. */
        val confirmed: Boolean = false,
        /** Every program the bridge wrote or tried tonight. */
        val ours: List<ChargeTimerProgram> = emptyList(),
        val plan: ChargePlan? = null,
        /** When this plug-in was first seen; null while unplugged. */
        val pluggedMs: Long? = null,
        /** This plug-in is decided (planned, or nothing to do): [note] says what. */
        val decided: Boolean = false,
        val note: String = "waiting",
        val writeTries: Int = 0,
        val restoreTries: Int = 0,
        val nextRestoreMs: Long = 0,
        val rewrites: Int = 0,
        /** When the last write was sent, and when it came back (the car's reports after that show its effect). */
        val lastWriteMs: Long = 0,
        val writeDoneMs: Long = 0,
        /** Last time the window was looked at again (tomorrow's prices, an earlier departure). */
        val lastLookMs: Long = 0,
    )

    /** When this run of the bridge started: the owner's timer is only taken from what the car sent since. */
    private val runStartMs = now()
    @Volatile private var st = load()
    private var keptChargingLogged = false

    fun start(scope: CoroutineScope): Job = scope.launch {
        val cfg = store.smartCharging()
        log.link("Smart charging: " + if (cfg.enabled) "on (ready by ${cfg.readyAt}${if (cfg.useDepartures) " or the next departure" else ""})" else "off")
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn("Smart charging: ${it.message}") }
            delay(60_000)
        }
    }

    /** For the phones: on/off, what's planned, why nothing is. */
    fun describe(): Map<String, Any?> {
        val s = st
        val cfg = store.smartCharging()
        val plan = s.plan
        val t = now()
        val status = when {
            s.written == null -> s.note
            !cfg.enabled || (plan != null && t > plan.readyByMs) || (!s.confirmed && s.writeTries >= 3) || s.restoreTries > 0 -> "restoring"
            !s.confirmed -> "writing"
            else -> "planned"
        }
        return linkedMapOf(
            "enabled" to cfg.enabled, "readyAt" to cfg.readyAt.toString(), "useDepartures" to cfg.useDepartures,
            "status" to status, "plan" to plan?.toMap(), "saving" to plan?.let { savingFor(it, car().batteryPercent ?: 0) },
            "currency" to store.pricePlan().currencyFor(store.priceArea()),
        )
    }

    /** One look (every minute). */
    suspend fun tick() {
        val cfg = store.smartCharging()
        val c = car()
        val t = now()
        val home = carHome() == true
        if (st.written != null) return carryingOurs(c, cfg, t, home)

        // Unplugged: forget this plug-in.
        if (c.plug != PlugState.PLUGGED) {
            if (st.pluggedMs != null || st.decided) update(State(note = if (cfg.enabled) "waiting" else "off"))
            return
        }
        if (!cfg.enabled) {
            if (st.note != "off") update(st.copy(note = "off"))
            return
        }
        if (!home) return
        if (st.pluggedMs == null) {
            log.link("Smart charging: plugged in at ${c.batteryPercent ?: "?"} % — planning")
            update(st.copy(pluggedMs = t, decided = false, writeTries = 0))
        }
        if (st.decided) return
        decide(c, cfg, t)
    }

    /** The car carries (or may carry) the bridge's window: confirm it, move it, or put the owner's timer back. */
    private suspend fun carryingOurs(c: CarState, cfg: SharedStore.SmartCharging, t: Long, home: Boolean) {
        val s = st
        val written = s.written ?: return
        // What the car reported after the last write came back (older reports can't show its effect).
        val onCar = timerSince(c, s.writeDoneMs)
        if (onCar != null && onCar !in s.ours) {
            if (onCar == ownerProgram(s)) {
                if (s.confirmed) {
                    // Tonight's timer was confirmed, and since then the car reports the owner's: a phone put it back.
                    log.link("Smart charging: the car has the owner's timer again (${onCar.summary()}) — put back by a phone; done")
                    update(State(pluggedMs = s.pluggedMs.takeIf { c.plug == PlugState.PLUGGED }, decided = true, note = "done"))
                    return
                }
                // Not confirmed: the write didn't take — tried again below (or the owner's timer is written back).
            } else {
                // Someone else changed the timer (a phone): that stays — nothing to put back.
                log.link("Smart charging: the car's timer was changed by someone else (${onCar.summary()}) — stepping back; it stays as they set it. The timer before tonight was: ${ownerProgram(s).summary()}")
                record("smart.stepped_back")
                update(State(pluggedMs = s.pluggedMs.takeIf { c.plug == PlugState.PLUGGED }, decided = true, note = "changed"))
                return
            }
        }
        if (!s.confirmed && onCar == written) {
            log.link("Smart charging: the car confirms tonight's timer (${written.summary()})")
            update(s.copy(confirmed = true, writeTries = 0, note = "planned"))
            return
        }
        val plan = s.plan
        val why = when {
            !cfg.enabled -> "smart charging was switched off"
            c.plug == PlugState.UNPLUGGED -> "the car was unplugged"
            (c.batteryPercent ?: 0) >= 99 && !c.isCharging -> "the battery is full"
            plan != null && t > plan.readyByMs + 3 * HOUR && !c.isCharging -> "the morning is over"
            !s.confirmed && s.writeTries >= 3 -> "the car didn't take tonight's timer"
            else -> null
        }
        if (why != null) {
            if (home && t >= s.nextRestoreMs) restore(why, note = if (!s.confirmed) "failed" else "done")
            return
        }
        if (!home) return
        if (!s.confirmed) {
            // The last write failed and the car doesn't show it: try the same program again.
            if (t - s.lastWriteMs >= 60_000L) write(s.owner, written, plan ?: return, c.batteryPercent ?: 0, first = false, expected = (s.ours + ownerProgram(s)).toSet())
            return
        }
        if (c.isCharging && plan != null && t < plan.startMs - 5 * 60_000L && !keptChargingLogged) {
            keptChargingLogged = true
            log.link("Smart charging: the car is charging already although its window starts at ${clock(plan.startMs)} — it didn't pause for the timer")
        }
        replan(c, cfg, t)
    }

    private suspend fun decide(c: CarState, cfg: SharedStore.SmartCharging, t: Long) {
        val pct = c.batteryPercent ?: return
        if (c.modelYear == ModelYear.MY18 || c.modelYear == ModelYear.MY24) return skip("my18", "only 2014–2017 cars let the bridge set the charge timer")
        if (pct >= 95) return skip("full", "the battery is at $pct % already")
        val since = st.pluggedMs ?: t
        if (activity.recent(30).any { it.code == "cmd.charge_now" && it.atMs >= since - 10 * 60_000L }) return skip("charge_now", "\"Charge now\" was pressed — leaving it")
        val pricePlan = store.pricePlan()
        val area = store.priceArea()
        if (pricePlan.model != PriceModel.SPOT || area == null) return skip("flat_price", "smart charging needs a spot price with a price area (the price is ${pricePlan.model.name.lowercase()}${if (area == null) ", no area" else ""})")
        // The owner's timer, as the car sent it during this run (both registers): put back exactly afterwards.
        val owner = timerSince(c, runStartMs)
        if (owner == null) {
            if (st.note != "no_timer_data") {
                log.link("Smart charging: waiting for the car to report its charge timer (needed to put it back afterwards)")
                update(st.copy(note = "no_timer_data"))
            }
            return
        }
        val plan = makePlan(pct, cfg, t, area)
        val avg = plan?.avgPrice
        val rightAway = plan?.nowPrice
        if (plan == null || avg == null || rightAway == null) {
            // Before the next day's prices are out (about 13:00): try again later rather than give up.
            if (hourOf(t) < 14) {
                if (st.note != "waiting_prices") {
                    log.link("Smart charging: not enough prices yet to plan before ${readyByText(cfg, t)} — planning when tomorrow's are out")
                    update(st.copy(note = "waiting_prices"))
                }
                return
            }
            return skip("no_time", "no window fits before ${readyByText(cfg, t)} — charging as usual")
        }
        if (rightAway - avg < MIN_SAVING) {
            return skip("no_saving", "charging right away is about as cheap (%.2f vs %.2f) — charging as usual".format(rightAway, avg))
        }
        val program = SmartCharge.program(owner, plan, t, carTime(c))
            ?: return skip("no_slot", "every timer slot is used by other days (${owner.summary()}) — leaving the timer alone")
        write(owner, program, plan, pct, first = true, expected = setOf(owner))
    }

    /** The cheapest window before the car is needed, ending half an hour early for margin. */
    private fun makePlan(pct: Int, cfg: SharedStore.SmartCharging, t: Long, area: String): ChargePlan? {
        val readyBy = readyBy(cfg, t)
        val minutes = SmartCharge.minutesNeeded(pct, 100, SmartCharge.pctPerHour(history.recentCharges()))
        val earliest = t + 5 * 60_000L
        val deadline = readyBy - MARGIN_MS
        return SmartCharge.cheapest(earliest, deadline, minutes, prices.slots(area, earliest, deadline))?.copy(readyByMs = readyBy)
    }

    /**
     * Writes [program] (tonight's window on [owner]'s timer). The state — the owner's timer and the program — is saved
     * first; if that fails nothing is written.
     */
    private suspend fun write(
        owner: ChargeTimerProgram?, program: ChargeTimerProgram, plan: ChargePlan, pct: Int, first: Boolean, expected: Set<ChargeTimerProgram>,
    ) {
        val s = st
        val t = now()
        val pending = s.copy(
            owner = if (first) owner else s.owner, written = program, confirmed = false, ours = (s.ours + program).distinct(),
            plan = plan, decided = true, note = "writing", lastWriteMs = t, writeTries = if (first) 0 else s.writeTries,
        )
        if (!update(pending)) {
            st = s
            return skip("state_failed", "couldn't save its state — not touching the car's timer")
        }
        val saving = savingFor(plan, pct)
        log.link(
            "Smart charging: ${if (first) "charging" else "writing"} ${clock(plan.startMs)}–${clock(plan.endMs)} for $pct → 100 %" +
                " at %.2f (right away %.2f)".format(plan.avgPrice ?: 0.0, plan.nowPrice ?: 0.0) + (saving?.let { ", saves ≈ %.2f".format(it) } ?: "") +
                " — car's timer now: ${program.summary()}; owner's timer (put back afterwards): ${(pending.owner ?: ChargeTimerProgram.EMPTY).summary()}",
        )
        // Compare-and-swap: the car only takes it while its timer (as reported in this connection) is one we expect.
        val r = execute(CarCommand.SetChargeTimer(program, expected = expected))
        if (r is CommandResult.Failure && (r.reason == CarCommand.SetChargeTimer.TIMER_CHANGED || r.reason == CarCommand.SetChargeTimer.TIMER_UNKNOWN)) {
            // Nothing was written. Changed: a phone set the timer — it stays. Unknown: look again next minute.
            if (r.reason == CarCommand.SetChargeTimer.TIMER_CHANGED) {
                log.link("Smart charging: ${r.reason} (${ChargeTimerProgram.read(car())?.summary() ?: "?"}) — stepping back")
                if (!first) record("smart.stepped_back")
                update(State(pluggedMs = s.pluggedMs, decided = true, note = "changed"))
            } else {
                log.link("Smart charging: ${r.reason} — trying again in a minute")
                if (first) update(s) else update(st.copy(writeDoneMs = now(), writeTries = st.writeTries + 1))
            }
            return
        }
        update(st.copy(writeDoneMs = now()))
        when (r) {
            is CommandResult.Success -> {
                keptChargingLogged = false
                update(st.copy(confirmed = true, writeTries = 0, note = "planned"))
                if (first || s.plan?.startMs != plan.startMs) {
                    record("smart.planned", "start" to clock(plan.startMs), "end" to clock(plan.endMs), "saving" to saving, "currency" to store.pricePlan().currencyFor(store.priceArea()))
                }
            }
            is CommandResult.Failure -> {
                val tries = st.writeTries + 1
                // It may have reached the car anyway: the program stays "ours", and the owner's timer goes back if it
                // never confirms.
                log.warn("Smart charging: the car didn't confirm the timer (${r.reason}) — " + if (tries >= 3) "putting the owner's timer back" else "trying again in a minute")
                update(st.copy(writeTries = tries))
                if (tries >= 3) record("smart.failed", "detail" to r.reason)
            }
        }
    }

    /** Tomorrow's prices came out, or the car is needed earlier now: move the window if it hasn't started. */
    private suspend fun replan(c: CarState, cfg: SharedStore.SmartCharging, t: Long) {
        val s = st
        val plan = s.plan ?: return
        val current = plan.avgPrice ?: return
        if (t > plan.startMs - 15 * 60_000L || c.isCharging) return
        val readyBy = readyBy(cfg, t)
        val tooLate = plan.endMs > readyBy - MARGIN_MS
        // Cheaper hours: at most every half hour, three moves a night. Needed earlier now: at once.
        if (!tooLate && (s.rewrites >= 3 || t - maxOf(s.lastLookMs, s.lastWriteMs) < 30 * 60_000L)) return
        val pct = c.batteryPercent ?: return
        val area = store.priceArea() ?: return
        val better = makePlan(pct, cfg, t, area)
        val newPrice = better?.avgPrice
        if (tooLate && (better == null || newPrice == null)) {
            restore("the car is needed by ${clock(readyBy)} now and no window fits — charging as usual", note = "no_time")
            return
        }
        if (better == null || newPrice == null || better.startMs == plan.startMs || (!tooLate && current - newPrice < MIN_SAVING / 2)) {
            update(s.copy(lastLookMs = t)) // looked; look again in half an hour
            return
        }
        val program = SmartCharge.program(s.owner, better, t, carTime(c))
        if (program == null) {
            if (tooLate) restore("the car is needed by ${clock(readyBy)} now and the timer can't take a new window — charging as usual", note = "no_time")
            else update(s.copy(lastLookMs = t))
            return
        }
        log.link("Smart charging: " + if (tooLate) "the car is needed by ${clock(readyBy)} now — moving the window" else "cheaper hours came in (%.2f → %.2f) — moving the window".format(current, newPrice))
        update(st.copy(rewrites = s.rewrites + 1, lastLookMs = t))
        write(s.owner, program, better, pct, first = false, expected = s.ours.toSet())
    }

    /**
     * Puts the owner's timer back — always written (harmless if it is there already), but only while the car's timer
     * is one of the bridge's or the owner's (compare-and-swap): a phone's change stays. Retried with growing pauses.
     */
    private suspend fun restore(why: String, note: String = "done") {
        val s = st
        val target = ownerProgram(s)
        log.link("Smart charging: $why — putting the owner's timer back (${target.summary()})" + if (s.restoreTries > 0) ", try ${s.restoreTries + 1}" else "")
        val r = execute(CarCommand.SetChargeTimer(target, expected = (s.ours + target).toSet()))
        when {
            r is CommandResult.Success -> {
                record("smart.done", "reason" to why)
                update(State(pluggedMs = s.pluggedMs.takeIf { car().plug == PlugState.PLUGGED }, decided = true, note = note))
            }
            r is CommandResult.Failure && r.reason == CarCommand.SetChargeTimer.TIMER_CHANGED -> {
                log.link("Smart charging: $why — but the timer was changed by someone else meanwhile (${ChargeTimerProgram.read(car())?.summary() ?: "?"}); leaving it")
                record("smart.stepped_back")
                update(State(pluggedMs = s.pluggedMs.takeIf { car().plug == PlugState.PLUGGED }, decided = true, note = "changed"))
            }
            r is CommandResult.Failure -> {
                val tries = s.restoreTries + 1
                val pause = RESTORE_PAUSES_MIN[minOf(tries - 1, RESTORE_PAUSES_MIN.size - 1)]
                log.warn("Smart charging: couldn't put the owner's timer back (${r.reason}) — trying again in $pause min; the car may still have: ${s.written?.summary()}")
                update(s.copy(restoreTries = tries, nextRestoreMs = now() + pause * 60_000L))
            }
        }
    }

    private fun ownerProgram(s: State): ChargeTimerProgram = (s.owner ?: ChargeTimerProgram.EMPTY).normalized()

    private fun skip(code: String, why: String) {
        log.link("Smart charging: $why")
        update(st.copy(decided = true, note = code))
    }

    /**
     * The charge timer the car reported after [sinceMs] — both the timers (0x20) and the full-charge days (0x0e) —
     * or null when either is older (or missing): older data could be from before a phone changed it.
     */
    private fun timerSince(c: CarState, sinceMs: Long): ChargeTimerProgram? {
        val presets = c.registers[Reg.READ_CHARGE_PRESETS] ?: return null
        val full = c.registers[Reg.READ_FULL_CHARGE_DAYS] ?: return null
        if (presets.updatedAtMs < sinceMs || full.updatedAtMs < sinceMs) return null
        return ChargeTimerProgram.read(c)?.normalized()
    }

    /**
     * Epoch ms → the car's own clock (its timer runs on it). From the clock the car last reported (within 2 days),
     * rounded to the quarter hour; else the shared time zone.
     */
    private fun carTime(c: CarState): (Long) -> LocalDateTime {
        val zone = store.zone()
        val clk = c.carClock
        val readAt = c.carClockReadAtMs
        val offsetMin = if (clk != null && clk.size >= 5 && readAt != null && now() - readAt < 2 * 24 * HOUR) {
            runCatching {
                val carLocal = LocalDateTime.of(clk[0], clk[1], clk[2], clk[3], clk[4])
                val utc = LocalDateTime.ofInstant(Instant.ofEpochMilli(readAt), ZoneOffset.UTC)
                (Duration.between(utc, carLocal).toMinutes() / 15.0).roundToLong() * 15
            }.getOrNull()?.takeIf { abs(it) <= 14 * 60 }
        } else null
        val zoneOffsetMin = zone.rules.getOffset(Instant.ofEpochMilli(now())).totalSeconds / 60L
        if (offsetMin != null && offsetMin != zoneOffsetMin) {
            log.link("Smart charging: the car's clock is at UTC%+d min, the home time zone at %+d — using the car's".format(offsetMin, zoneOffsetMin))
        }
        // The car's clock agrees with the home time zone: use the zone (it knows summer time). Else the car's offset.
        val target: java.time.ZoneId = if (offsetMin != null && offsetMin != zoneOffsetMin) ZoneOffset.ofTotalSeconds((offsetMin * 60).toInt()) else zone
        return { ms: Long -> LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), target) }
    }

    /** What moving saves, with VAT, for the energy from [pct] to full. */
    private fun savingFor(plan: ChargePlan, pct: Int): Double? {
        val now = plan.nowPrice ?: return null
        val avg = plan.avgPrice ?: return null
        val kwh = ChargeCosts.gridKwh(pct, 100, store.pricePlan().fullChargeKwh)
        return ((now - avg) * kwh * (1 + PriceAreas.vat(store.priceArea())) * 100).toLong() / 100.0
    }

    private fun record(code: String, vararg params: Pair<String, Any?>) {
        activity.add(Activity(now(), Activity.BRIDGE, code, linkedMapOf(*params)))
    }

    private fun readyBy(cfg: SharedStore.SmartCharging, t: Long): Long =
        SmartCharge.readyBy(ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), store.zone()), cfg.readyAt, store.departures(), cfg.useDepartures).toInstant().toEpochMilli()

    private fun readyByText(cfg: SharedStore.SmartCharging, t: Long): String = clock(readyBy(cfg, t))

    private fun hourOf(t: Long): Int = Instant.ofEpochMilli(t).atZone(store.zone()).hour

    private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(store.zone()).format(HHMM)

    // ------------------------------------------------------------------ state file

    /** Saves [next] (temp file, then rename). False when it couldn't be saved. */
    private fun update(next: State): Boolean {
        st = next
        return runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                Json.write(
                    linkedMapOf(
                        "owner" to next.owner?.let(::programMap),
                        "written" to next.written?.let(::programMap),
                        "confirmed" to next.confirmed,
                        "ours" to next.ours.map(::programMap),
                        "plan" to next.plan?.toMap(), "pluggedMs" to next.pluggedMs, "decided" to next.decided, "note" to next.note,
                        "writeTries" to next.writeTries, "restoreTries" to next.restoreTries, "nextRestoreMs" to next.nextRestoreMs,
                        "rewrites" to next.rewrites, "lastWriteMs" to next.lastWriteMs, "writeDoneMs" to next.writeDoneMs, "lastLookMs" to next.lastLookMs,
                    ),
                ),
            )
            check(tmp.renameTo(file)) { "rename failed" }
        }.onFailure { log.warn("Smart charging: couldn't save its state: ${it.message}") }.isSuccess
    }

    private fun programMap(p: ChargeTimerProgram) = Codecs.commandToMap(CarCommand.SetChargeTimer(p))

    @Suppress("UNCHECKED_CAST")
    private fun load(): State = runCatching {
        if (!file.exists()) return State()
        val m = Json.parseObject(file.readText())
        fun program(v: Any?) = (v as? Map<String, Any?>)?.let { (Codecs.commandFromMap(it) as? CarCommand.SetChargeTimer)?.program?.normalized() }
        State(
            owner = program(m["owner"]), written = program(m["written"]), confirmed = m["confirmed"] as? Boolean ?: false,
            ours = (m["ours"] as? List<*>).orEmpty().mapNotNull { program(it) },
            plan = ChargePlan.fromMap(m["plan"] as? Map<String, Any?>),
            pluggedMs = (m["pluggedMs"] as? Number)?.toLong(), decided = m["decided"] as? Boolean ?: false, note = m["note"] as? String ?: "waiting",
            writeTries = (m["writeTries"] as? Number)?.toInt() ?: 0, restoreTries = (m["restoreTries"] as? Number)?.toInt() ?: 0,
            nextRestoreMs = (m["nextRestoreMs"] as? Number)?.toLong() ?: 0, rewrites = (m["rewrites"] as? Number)?.toInt() ?: 0,
            lastWriteMs = (m["lastWriteMs"] as? Number)?.toLong() ?: 0,
            writeDoneMs = (m["writeDoneMs"] as? Number)?.toLong() ?: 0,
            lastLookMs = (m["lastLookMs"] as? Number)?.toLong() ?: 0,
        ).let { s -> if (s.written != null && s.written !in s.ours) s.copy(ours = s.ours + s.written) else s }.also { s ->
            if (s.written != null) log.link("Smart charging: the car carries the bridge's window (${s.written.summary()}); the owner's timer (${(s.owner ?: ChargeTimerProgram.EMPTY).summary()}) goes back afterwards")
        }
    }.getOrElse {
        log.warn("Smart charging: couldn't read ${file.path}: ${it.message}")
        State()
    }

    companion object {
        private const val HOUR = 3_600_000L
        /** The window ends at least this long before the car is needed. */
        private const val MARGIN_MS = 30 * 60_000L
        /** Per kWh (before VAT): below this, moving the charge isn't worth touching the car's timer. */
        private const val MIN_SAVING = 0.05
        /** Minutes between tries to put the owner's timer back. */
        private val RESTORE_PAUSES_MIN = listOf(1L, 2L, 5L, 10L, 30L)
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
