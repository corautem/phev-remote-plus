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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Smart charging (opt-in, shared setting): when the car is plugged in at home, the bridge works out how long it needs
 * to get full and moves tonight's charging to the cheapest hours before the car is needed (the next departure, or
 * 07:00) — with a spot price the cheapest stretch, with a flat price the hours just before (night grid fees). It does
 * that with the car's own charge timer (2014–2017 cars): tonight's day gets its own window with "full charge", so the
 * car never stops short; the owner's timer is kept and put back once the car is full, unplugged or the morning is
 * over. If a phone changes the timer meanwhile, the bridge steps back. Every decision is logged.
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
    /** What the bridge has done this plug-in (kept across restarts, so the owner's timer is never lost). */
    private data class State(
        /** The owner's timer before the bridge changed it (null = had none / not changed). */
        val owner: ChargeTimerProgram? = null,
        /** What the bridge wrote; non-null while the car carries the bridge's window. */
        val written: ChargeTimerProgram? = null,
        val plan: ChargePlan? = null,
        /** When this plug-in was first seen; null while unplugged. */
        val pluggedMs: Long? = null,
        /** This plug-in is decided (planned, or nothing to do): [note] says what. */
        val decided: Boolean = false,
        val note: String = "waiting",
        val tries: Int = 0,
        val rewrites: Int = 0,
        val plannedAtMs: Long = 0,
    )

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
        val area = store.priceArea()
        val plan = s.plan
        val saving = plan?.let { savingFor(it, car().batteryPercent ?: 0) }
        return linkedMapOf(
            "enabled" to cfg.enabled, "readyAt" to cfg.readyAt.toString(), "useDepartures" to cfg.useDepartures,
            "status" to if (s.written != null) "planned" else s.note,
            "plan" to plan?.toMap(), "saving" to saving, "currency" to store.pricePlan().currencyFor(area),
        )
    }

    /** One look (every minute). */
    suspend fun tick() {
        val cfg = store.smartCharging()
        val c = car()
        val t = now()
        val home = carHome() == true
        val s = st

        // The bridge's window is on the car: put the owner's timer back when it has done its job.
        if (s.written != null) {
            val nowProgram = ChargeTimerProgram.read(c)?.normalized()
            val why = when {
                !cfg.enabled -> "smart charging was switched off"
                c.plug == PlugState.UNPLUGGED -> "the car was unplugged"
                (c.batteryPercent ?: 0) >= 99 && !c.isCharging -> "the battery is full"
                s.plan != null && t > s.plan.readyByMs + 3 * HOUR -> "the morning is over"
                else -> null
            }
            if (why == null && nowProgram != null && nowProgram != s.written && t - s.plannedAtMs > 3 * 60_000L) {
                log.link("Smart charging: the car's timer was changed by someone else (${nowProgram.summary()}) — stepping back; it stays as they set it")
                record("smart.done", "reason" to "changed")
                update(State(pluggedMs = s.pluggedMs, decided = true, note = "changed"))
                return
            }
            if (why == null) {
                if (c.isCharging && s.plan != null && t < s.plan.startMs - 5 * 60_000L && !keptChargingLogged) {
                    keptChargingLogged = true
                    log.link("Smart charging: the car is charging already although its window starts at ${clock(s.plan.startMs)} — it didn't pause for the new timer")
                }
                replan(c, cfg, t)
                return
            }
            if (!home) return // can't reach the car: put it back once it's home
            restore(why)
            return
        }

        // Unplugged: forget this plug-in.
        if (c.plug != PlugState.PLUGGED) {
            if (s.pluggedMs != null || s.decided) update(State(note = if (cfg.enabled) "waiting" else "off"))
            return
        }
        if (!cfg.enabled) {
            if (s.note != "off") update(s.copy(note = "off"))
            return
        }
        if (!home) return
        if (s.pluggedMs == null) {
            log.link("Smart charging: plugged in at ${c.batteryPercent ?: "?"} % — planning")
            update(s.copy(pluggedMs = t, decided = false, tries = 0))
        }
        if (st.decided) return
        decide(c, cfg, t)
    }

    private suspend fun decide(c: CarState, cfg: SharedStore.SmartCharging, t: Long) {
        val pct = c.batteryPercent ?: return
        if (c.modelYear == ModelYear.MY18 || c.modelYear == ModelYear.MY24) return skip("my18", "only 2014–2017 cars let the bridge set the charge timer")
        if (pct >= 95) return skip("full", "the battery is at $pct % already")
        val since = st.pluggedMs ?: t
        if (activity.recent(30).any { it.code == "cmd.charge_now" && it.atMs >= since - 10 * 60_000L }) return skip("charge_now", "\"Charge now\" was pressed — leaving it")
        val owner = ChargeTimerProgram.read(c)
        if (owner == null) {
            if (st.note != "no_timer_data") {
                log.link("Smart charging: waiting for the car to report its charge timer (needed to put it back afterwards)")
                update(st.copy(note = "no_timer_data"))
            }
            return
        }
        val plan = makePlan(pct, cfg, t)
        if (plan == null) {
            val model = store.pricePlan().model
            val area = store.priceArea()
            // Spot price before the next day's prices are out (about 13:00): try again later rather than give up.
            if (model == PriceModel.SPOT && area != null && ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), store.zone()).hour < 14) {
                if (st.note != "waiting_prices") {
                    log.link("Smart charging: tomorrow's prices aren't out yet — planning when they are")
                    update(st.copy(note = "waiting_prices"))
                }
                return
            }
            return skip("no_time", "no window fits before ${readyByText(cfg, t)} — charging as usual")
        }
        val avg = plan.avgPrice
        val rightAway = plan.nowPrice
        if (avg != null && rightAway != null && rightAway - avg < MIN_SAVING) {
            return skip("no_saving", "charging right away is about as cheap (%.2f vs %.2f) — charging as usual".format(rightAway, avg))
        }
        write(owner, plan, pct, first = true)
    }

    /** The plan for [pct] %: the cheapest (spot) or latest (flat price) window before the car is needed. */
    private fun makePlan(pct: Int, cfg: SharedStore.SmartCharging, t: Long): ChargePlan? {
        val zone = store.zone()
        val readyBy = SmartCharge.readyBy(ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), zone), cfg.readyAt, store.departures(), cfg.useDepartures)
            .toInstant().toEpochMilli()
        val minutes = SmartCharge.minutesNeeded(pct, 100, SmartCharge.pctPerHour(history.recentCharges()))
        val earliest = t + 5 * 60_000L
        val plan = store.pricePlan()
        val area = store.priceArea()
        return if (plan.model == PriceModel.SPOT && area != null) {
            SmartCharge.cheapest(earliest, readyBy, minutes, prices.slots(area, earliest, readyBy))
        } else {
            SmartCharge.latest(earliest, readyBy, minutes)
        }
    }

    private suspend fun write(owner: ChargeTimerProgram?, plan: ChargePlan, pct: Int, first: Boolean) {
        val program = SmartCharge.program(owner, plan, store.zone()) ?: return skip("no_time", "the window is too long for the car's timer")
        val saving = savingFor(plan, pct)
        log.link(
            "Smart charging: ${if (first) "charging" else "moving to"} ${clock(plan.startMs)}–${clock(plan.endMs)} for ${pct} → 100 %" +
                (plan.avgPrice?.let { " at %.2f (right away %.2f)".format(it, plan.nowPrice ?: 0.0) } ?: " (flat price: just before ${clock(plan.readyByMs)})") +
                (saving?.let { ", saves ≈ %.2f".format(it) } ?: "") + " — setting the car's timer: ${program.summary()}",
        )
        when (val r = execute(CarCommand.SetChargeTimer(program))) {
            is CommandResult.Success -> {
                keptChargingLogged = false
                update(st.copy(owner = if (first) owner else st.owner, written = program, plan = plan, decided = true, note = "planned", plannedAtMs = now(), rewrites = if (first) 0 else st.rewrites + 1))
                record("smart.planned", "start" to clock(plan.startMs), "end" to clock(plan.endMs), "saving" to saving, "currency" to store.pricePlan().currencyFor(store.priceArea()))
            }
            is CommandResult.Failure -> {
                val tries = st.tries + 1
                log.warn("Smart charging: the car didn't take the timer (${r.reason})" + if (tries >= 3) " — giving up for tonight, charging as usual" else " — trying again in a minute")
                update(st.copy(tries = tries, decided = tries >= 3, note = if (tries >= 3) "failed" else st.note))
                if (tries >= 3) record("smart.failed", "detail" to r.reason)
            }
        }
    }

    /** Tomorrow's prices came out (or got cheaper): move the window if it hasn't started and the move is worth it. */
    private suspend fun replan(c: CarState, cfg: SharedStore.SmartCharging, t: Long) {
        val s = st
        val plan = s.plan ?: return
        val current = plan.avgPrice ?: return
        if (s.rewrites >= 2 || t - s.plannedAtMs < 30 * 60_000L || t > plan.startMs - 15 * 60_000L || c.isCharging) return
        val pct = c.batteryPercent ?: return
        val better = makePlan(pct, cfg, t) ?: return
        val newPrice = better.avgPrice
        if (newPrice == null || current - newPrice < MIN_SAVING / 2 || better.startMs == plan.startMs) {
            update(s.copy(plannedAtMs = t)) // looked; look again in half an hour
            return
        }
        write(s.owner, better, pct, first = false)
    }

    private suspend fun restore(why: String) {
        val s = st
        val target = s.owner ?: ChargeTimerProgram.EMPTY
        log.link("Smart charging: $why — putting the owner's timer back (${target.summary()})")
        when (val r = execute(CarCommand.SetChargeTimer(target))) {
            is CommandResult.Success -> {
                record("smart.done", "reason" to why)
                update(State(pluggedMs = s.pluggedMs.takeIf { car().plug == PlugState.PLUGGED }, decided = true, note = "done"))
            }
            is CommandResult.Failure -> {
                val tries = s.tries + 1
                log.warn("Smart charging: couldn't put the timer back (${r.reason}) — trying again" + if (tries >= 5) "; still the bridge's window: ${s.written?.summary()}" else "")
                update(s.copy(tries = tries))
            }
        }
    }

    private fun skip(code: String, why: String) {
        log.link("Smart charging: $why")
        update(st.copy(decided = true, note = code))
    }

    /** What moving saves, with VAT, for the energy from [pct] to full (spot only). */
    private fun savingFor(plan: ChargePlan, pct: Int): Double? {
        val now = plan.nowPrice ?: return null
        val avg = plan.avgPrice ?: return null
        val kwh = ChargeCosts.gridKwh(pct, 100, store.pricePlan().fullChargeKwh)
        return ((now - avg) * kwh * (1 + PriceAreas.vat(store.priceArea())) * 100).toLong() / 100.0
    }

    private fun record(code: String, vararg params: Pair<String, Any?>) {
        activity.add(Activity(now(), Activity.BRIDGE, code, linkedMapOf(*params)))
    }

    private fun readyByText(cfg: SharedStore.SmartCharging, t: Long): String =
        clock(SmartCharge.readyBy(ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), store.zone()), cfg.readyAt, store.departures(), cfg.useDepartures).toInstant().toEpochMilli())

    private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(store.zone()).format(HHMM)

    // ------------------------------------------------------------------ state file

    private fun update(next: State) {
        st = next
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(
                Json.write(
                    linkedMapOf(
                        "owner" to next.owner?.let { Codecs.commandToMap(CarCommand.SetChargeTimer(it)) },
                        "written" to next.written?.let { Codecs.commandToMap(CarCommand.SetChargeTimer(it)) },
                        "plan" to next.plan?.toMap(), "pluggedMs" to next.pluggedMs, "decided" to next.decided, "note" to next.note,
                        "tries" to next.tries, "rewrites" to next.rewrites, "plannedAtMs" to next.plannedAtMs,
                    ),
                ),
            )
        }.onFailure { log.warn("Smart charging: couldn't save its state: ${it.message}") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(): State = runCatching {
        if (!file.exists()) return State()
        val m = Json.parseObject(file.readText())
        fun program(k: String) = (m[k] as? Map<String, Any?>)?.let { (Codecs.commandFromMap(it) as? CarCommand.SetChargeTimer)?.program }
        State(
            owner = program("owner"), written = program("written"), plan = ChargePlan.fromMap(m["plan"] as? Map<String, Any?>),
            pluggedMs = (m["pluggedMs"] as? Number)?.toLong(), decided = m["decided"] as? Boolean ?: false, note = m["note"] as? String ?: "waiting",
            tries = (m["tries"] as? Number)?.toInt() ?: 0, rewrites = (m["rewrites"] as? Number)?.toInt() ?: 0,
            plannedAtMs = (m["plannedAtMs"] as? Number)?.toLong() ?: 0,
        ).also { s ->
            if (s.written != null) log.link("Smart charging: the car carries the bridge's window (${s.written.summary()}); the owner's timer (${(s.owner ?: ChargeTimerProgram.EMPTY).summary()}) goes back afterwards")
        }
    }.getOrElse {
        log.warn("Smart charging: couldn't read ${file.path}: ${it.message}")
        State()
    }

    companion object {
        private const val HOUR = 3_600_000L
        /** Per kWh (before VAT): below this, moving the charge isn't worth touching the car's timer. */
        private const val MIN_SAVING = 0.05
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
