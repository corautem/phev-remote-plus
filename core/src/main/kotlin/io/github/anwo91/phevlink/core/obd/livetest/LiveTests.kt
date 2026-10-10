package io.github.anwo91.phevlink.core.obd.livetest

import io.github.anwo91.phevlink.core.obd.DtcKind
import io.github.anwo91.phevlink.core.obd.Mode01
import io.github.anwo91.phevlink.core.obd.PhevSignals
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The guided live tests. Design, thresholds and sources: see the project docs (docs/live-tests.md). Thresholds
 * marked "estimate" have no published Outlander PHEV value and are engineering estimates kept in [Limits] so they
 * can be tuned from real logs.
 */
object LiveTests {
    val all: List<LiveTest> = listOf(
        DtcFullScan, TwelveVoltHealth, DcDcOutput, HvCapacity, HvCellBalance, InspectionReadiness,
        ChargingSession, EngineWarmup, FuelTrims, WheelSpeedMatch,
    )

    fun byId(id: String): LiveTest? = all.firstOrNull { it.id == id }
}

/** All verdict limits in one place. */
object Limits {
    // 12 V battery at rest (AGM open-circuit voltage table, Discover Battery)
    const val REST_GOOD = 12.60
    const val REST_HALF = 12.30
    const val REST_LOW = 12.00
    const val REST_SURFACE = 13.0          // estimate: above this the battery is still charging/surface charge
    const val BORDERLINE = 0.10            // estimate: adapter accuracy

    // 12 V with power ON (not READY): estimates
    const val ON_GOOD = 12.20
    const val ON_SAG = 11.90
    const val ON_LOOKS_READY = 13.20       // a DC/DC is charging → user probably went to READY

    // DC/DC output in READY: estimates (common charging-system targets 13.5–14.8 V)
    const val DCDC_LOW_FAIL = 12.80
    const val DCDC_LOW_WARN = 13.20
    const val DCDC_HIGH_WARN = 15.00
    const val DCDC_HIGH_FAIL = 15.50
    const val DCDC_LOAD_GOOD = 13.0
    const val DCDC_LOAD_FAIL = 12.6
    const val DCDC_BASELINE_DROP = 0.5

    // Drive battery cell balance at rest: estimates
    const val SPREAD_GOOD_MV = 20.0
    const val SPREAD_SLIGHT_MV = 50.0
    const val SPREAD_CLEAR_MV = 100.0
    const val LOW_CELL_V = 3.40
    const val CELLS = 80
    const val CELLS_PER_MODULE = 8

    // Capacity (Mitsubishi warranty basis 38 Ah before MY2017, 40 Ah from MY2017; 70 % threshold)
    const val SOH_GOOD = 80.0
    const val SOH_WARRANTY = 70.0
    const val SOC_MISMATCH = 10.0          // estimate
    const val CAPACITY_QUICK_DROP_AH = 2.0 // estimate

    // Charging: estimate of the net power into the pack from a 10 A / 230 V cable
    const val CHARGE_NET_W = 2000.0
    const val CHARGE_MIN_MINUTES = 10.0

    // Engine warm-up: estimates (thermostat ~82 °C on comparable Mitsubishi engines)
    const val WARM_TARGET = 75.0
    const val WARM_STABLE_MIN = 80.0
    const val WARM_SLOW = 60.0
    const val HOT_WARN = 105.0
    const val HOT_FAIL = 110.0

    // Fuel trims (aa1car: within ±10 % normal; codes around ±20–25 %)
    const val TRIM_GOOD = 10.0
    const val TRIM_BAD = 20.0

    // Inspection: estimate of "codes were cleared recently"
    const val CLEARED_RECENT_KM = 200.0
    const val CLEARED_RECENT_WARMUPS = 10.0

    // Wheel speeds: estimates (1 % ≈ 3.5 mm tread difference on 225/55 R18)
    const val WHEEL_GOOD_PCT = 0.5
    const val WHEEL_WARN_PCT = 1.5
    const val WHEEL_SINGLE_PCT = 1.0
}

private fun headline(prefix: String, verdict: Verdict) = Msg("$prefix.head.${verdict.name.lowercase()}")

private fun result(
    ctx: TestContext,
    test: LiveTest,
    started: Long,
    prefix: String,
    findings: List<Finding>,
    measurements: List<Measurement>,
    next: List<Msg>,
    codes: List<String> = emptyList(),
    samples: Map<String, List<Double>> = emptyMap(),
    verdictOverride: Verdict? = null,
): TestResult {
    val verdict = verdictOverride ?: Verdict.worst(findings.map { it.verdict })
    val ordered = findings.sortedByDescending { it.verdict.severity() }
    return TestResult(
        test.id, verdict, headline(prefix, verdict), ordered, measurements, next, test.relatedTips, codes,
        started, ctx.now(), samples,
    )
}

private fun Verdict.severity(): Int = when (this) {
    Verdict.FAIL -> 5
    Verdict.WARN -> 4
    Verdict.INCONCLUSIVE -> 3
    Verdict.INFO -> 2
    Verdict.PASS -> 1
    Verdict.CANCELLED -> 0
}

private fun m(label: String, value: Double?, unit: String, decimals: Int = 1) = Measurement(Msg(label), value, unit, decimals)
private fun n(v: Double, d: Int = 1) = Num(v, d)
private fun f(v: Verdict, key: String, vararg args: Pair<String, Any>) = Finding(v, Msg(key, mapOf(*args)))

// ====================================================================================== 12 V battery

object TwelveVoltHealth : LiveTest("twelve-volt-health", needsEngine = false, minutes = 2, relatedTips = listOf("weak-12v-battery", "obd-dongle-drain")) {
    private val ecuVoltageIds = listOf(Mode01.valueId(0x42), "ecm.battery_v", "cluster.voltage", "awc.accessory_v")

    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("t12.pre"))
        val liveV: (Map<String, Double?>) -> List<Measurement> = { listOf(m("m.v12", it["v"], "V", 2)) }
        val rest = ctx.sample(Msg("t12.measure_rest"), 30_000, 2_000, requireCar = false, live = liveV) {
            mapOf("v" to ctx.client.readAdapterVoltage())
        }
        val vRest = rest.median("v") ?: throw TestAborted(Msg("abort.no_voltage"))

        ctx.prompt(Msg("t12.switch_on"))
        ctx.pause(Msg("t12.settle"), 20_000)
        val on = ctx.sample(Msg("t12.measure_on"), 30_000, 2_000, requireCar = false, live = liveV) {
            val out = HashMap<String, Double?>()
            out["v"] = ctx.client.readAdapterVoltage()
            for (id in ecuVoltageIds) out[id] = ctx.fresh(id, 10_000)?.value
            out
        }
        val vOn = on.median("v")

        val findings = ArrayList<Finding>()
        when {
            vRest > Limits.REST_SURFACE -> findings += f(Verdict.INCONCLUSIVE, "t12.rest_surface", "v" to n(vRest, 2))
            vRest >= Limits.REST_GOOD -> findings += f(Verdict.PASS, "t12.rest_good", "v" to n(vRest, 2))
            vRest >= Limits.REST_HALF -> findings += f(Verdict.WARN, "t12.rest_half", "v" to n(vRest, 2))
            vRest >= Limits.REST_LOW -> findings += f(Verdict.FAIL, "t12.rest_low", "v" to n(vRest, 2))
            else -> findings += f(Verdict.FAIL, "t12.rest_very_low", "v" to n(vRest, 2))
        }
        if (vRest <= Limits.REST_SURFACE && listOf(Limits.REST_GOOD, Limits.REST_HALF, Limits.REST_LOW).any { abs(vRest - it) < Limits.BORDERLINE }) {
            findings += f(Verdict.INFO, "t12.borderline")
        }
        if (vOn != null) {
            when {
                vOn >= Limits.ON_LOOKS_READY -> findings += f(Verdict.INFO, "t12.on_ready", "v" to n(vOn, 2))
                vOn >= Limits.ON_GOOD -> findings += f(Verdict.PASS, "t12.on_good", "v" to n(vOn, 2))
                vOn >= Limits.ON_SAG -> findings += f(Verdict.WARN, "t12.on_sag", "v" to n(vOn, 2))
                else -> findings += f(Verdict.FAIL, "t12.on_weak", "v" to n(vOn, 2))
            }
            for (id in ecuVoltageIds) {
                val ecu = on.median(id) ?: continue
                if (abs(ecu - vOn) > 0.5) {
                    findings += f(Verdict.INFO, "t12.ecu_diff", "ecu" to n(ecu, 2), "v" to n(vOn, 2))
                    break
                }
            }
        }
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = if (verdict == Verdict.WARN || verdict == Verdict.FAIL) {
            listOf(Msg("next.12v_ready_charge"), Msg("next.12v_charger"), Msg("next.12v_drain"), Msg("next.12v_replace"))
        } else {
            listOf(Msg("next.run_dcdc"))
        }
        val samples = HashMap<String, List<Double>>()
        rest.exportTo(samples, "rest.")
        on.exportTo(samples, "on.")
        return result(
            ctx, this, started, "t12", findings,
            listOf(
                m("m.v_rest", vRest, "V", 2), m("m.v_rest_min", rest.min("v"), "V", 2),
                m("m.v_on", vOn, "V", 2), m("m.v_on_min", on.min("v"), "V", 2),
            ),
            next, samples = samples,
        )
    }
}

// ====================================================================================== DC/DC

object DcDcOutput : LiveTest("dcdc-output", needsEngine = false, minutes = 3, relatedTips = listOf("weak-12v-battery", "ev-system-warning")) {
    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("dc.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        ctx.pause(Msg("dc.settle"), 20_000)
        val liveV: (Map<String, Double?>) -> List<Measurement> = { listOf(m("m.v12", it["v"], "V", 2)) }
        val ready = ctx.sample(Msg("dc.measure"), 60_000, 2_000, live = liveV) { mapOf("v" to ctx.client.readAdapterVoltage()) }
        val vReady = ready.median("v") ?: throw TestAborted(Msg("abort.no_voltage"))

        val findings = ArrayList<Finding>()
        when {
            vReady > Limits.DCDC_HIGH_FAIL -> findings += f(Verdict.FAIL, "dc.too_high", "v" to n(vReady, 2))
            vReady > Limits.DCDC_HIGH_WARN -> findings += f(Verdict.WARN, "dc.high", "v" to n(vReady, 2))
            vReady >= Limits.DCDC_LOW_WARN -> findings += f(Verdict.PASS, "dc.good", "v" to n(vReady, 2))
            vReady >= Limits.DCDC_LOW_FAIL -> findings += f(Verdict.WARN, "dc.low", "v" to n(vReady, 2))
            else -> findings += f(Verdict.FAIL, "dc.not_charging", "v" to n(vReady, 2))
        }

        var vLoad: Double? = null
        if (ctx.prompt(Msg("dc.load"), Msg("btn.continue"), Msg("btn.skip"))) {
            ctx.pause(Msg("dc.settle_load"), 10_000)
            val load = ctx.sample(Msg("dc.measure_load"), 40_000, 2_000, live = liveV) { mapOf("v" to ctx.client.readAdapterVoltage()) }
            vLoad = load.median("v")
            vLoad?.let {
                findings += when {
                    it >= Limits.DCDC_LOAD_GOOD -> f(Verdict.PASS, "dc.load_good", "v" to n(it, 2))
                    it >= Limits.DCDC_LOAD_FAIL -> f(Verdict.WARN, "dc.load_low", "v" to n(it, 2))
                    else -> f(Verdict.FAIL, "dc.load_fail", "v" to n(it, 2))
                }
            }
            ctx.prompt(Msg("dc.load_off"))
        }

        val baseline = ctx.store.get("dcdc.baseline")?.toDoubleOrNull()
        if (baseline != null && vReady < baseline - Limits.DCDC_BASELINE_DROP) {
            findings += f(Verdict.WARN, "dc.below_baseline", "v" to n(vReady, 2), "base" to n(baseline, 2))
        }
        val verdict = Verdict.worst(findings.map { it.verdict })
        if (verdict == Verdict.PASS) ctx.store.put("dcdc.baseline", vReady.toString())
        val next = if (verdict == Verdict.FAIL || verdict == Verdict.WARN) {
            listOf(Msg("next.drive_retest"), Msg("next.run_scan"), Msg("next.workshop_dcdc"))
        } else emptyList()
        return result(
            ctx, this, started, "dc", findings,
            listOf(
                m("m.v_ready", vReady, "V", 2), m("m.v_ready_min", ready.min("v"), "V", 2), m("m.v_ready_max", ready.max("v"), "V", 2),
                m("m.v_load", vLoad, "V", 2), m("m.v_baseline", baseline, "V", 2),
            ),
            next, listOf("P0A94", "P0562", "P0563"), ready.all(),
        )
    }
}

// ====================================================================================== drive battery capacity

object HvCapacity : LiveTest("hv-capacity", needsEngine = false, minutes = 1, relatedTips = listOf("drive-battery-capacity-loss", "winter-range-and-engine-heat")) {
    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("cap.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        val s = ctx.sample(Msg("cap.measure"), 15_000, 5_000, live = { batteryLive(it) }) {
            val b = ctx.client.readBattery()
            mapOf("cap" to b?.capacityAh, "rem" to b?.remainingAh, "soc" to b?.soc, "pack" to b?.packVoltage, "kwh" to b?.energyKwh)
        }
        val cap = s.median("cap") ?: throw TestAborted(Msg("abort.no_bmu"))
        val rem = s.median("rem")
        val soc = s.median("soc")
        val vin = ctx.vin ?: ctx.client.state.value.vin
        val year = PhevSignals.modelYearFromVin(vin)
        val ref = PhevSignals.referenceCapacityAh(vin)
        val soh = cap * 100.0 / ref
        val findings = ArrayList<Finding>()
        val warrantyAh = ref * Limits.SOH_WARRANTY / 100.0
        findings += when {
            soh >= Limits.SOH_GOOD -> f(Verdict.PASS, "cap.good", "soh" to n(soh, 0), "cap" to n(cap, 1), "ref" to n(ref, 0))
            soh >= Limits.SOH_WARRANTY -> f(Verdict.WARN, "cap.aged", "soh" to n(soh, 0), "cap" to n(cap, 1), "ref" to n(ref, 0))
            else -> f(Verdict.FAIL, "cap.low", "soh" to n(soh, 0), "cap" to n(cap, 1), "limit" to n(warrantyAh, 1))
        }
        if (year == null) {
            findings += f(Verdict.INFO, "cap.year_unknown", "soh40" to n(cap * 100.0 / PhevSignals.NOMINAL_CAPACITY_AH, 0))
        } else {
            findings += f(Verdict.INFO, "cap.year", "year" to year, "ref" to n(ref, 0))
        }
        if (rem != null && soc != null && cap > 0) {
            val calc = rem * 100.0 / cap
            if (abs(calc - soc) > Limits.SOC_MISMATCH) findings += f(Verdict.INFO, "cap.estimate_stale", "calc" to n(calc, 0), "soc" to n(soc, 0))
        }
        // Trend: "time:capacity;..." newest last, at most 24 entries.
        val history = ctx.store.get("cap.history").orEmpty().split(';').mapNotNull {
            val p = it.split(':')
            val t = p.getOrNull(0)?.toLongOrNull()
            val c = p.getOrNull(1)?.toDoubleOrNull()
            if (t != null && c != null) t to c else null
        }
        val now = ctx.now()
        val recent = history.lastOrNull { now - it.first <= 30L * 24 * 3600 * 1000 && now - it.first > 60_000 }
        if (recent != null && recent.second - cap > Limits.CAPACITY_QUICK_DROP_AH) {
            findings += f(Verdict.INFO, "cap.quick_change", "before" to n(recent.second, 1), "now" to n(cap, 1))
        }
        history.firstOrNull()?.let { first ->
            if (now - first.first > 7L * 24 * 3600 * 1000) {
                findings += f(Verdict.INFO, "cap.trend", "before" to n(first.second, 1), "days" to ((now - first.first) / (24L * 3600 * 1000)).toInt())
            }
        }
        ctx.store.put("cap.history", (history + (now to cap)).takeLast(24).joinToString(";") { "${it.first}:${it.second}" })
        findings += f(Verdict.INFO, "cap.context")
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = when (verdict) {
            Verdict.FAIL, Verdict.WARN -> listOf(Msg("next.cap_track"), Msg("next.cap_full_charges"), Msg("next.cap_dealer"), Msg("next.cap_warranty"))
            else -> listOf(Msg("next.cap_track"))
        }
        return result(
            ctx, this, started, "cap", findings,
            listOf(
                m("m.capacity", cap, "Ah", 1), m("m.reference", ref, "Ah", 0), m("m.soh", soh, "%", 0),
                m("m.remaining", rem, "Ah", 1), m("m.soc", soc, "%", 1), m("m.energy", s.median("kwh"), "kWh", 1),
                m("m.pack_v", s.median("pack"), "V", 1),
            ),
            next, listOf("P0A7F", "P0A80"), s.all(),
        )
    }
}

internal fun batteryLive(v: Map<String, Double?>): List<Measurement> = buildList {
    v["soc"]?.let { add(m("m.soc", it, "%", 1)) }
    v["cap"]?.let { add(m("m.capacity", it, "Ah", 1)) }
    v["spread"]?.let { add(m("m.spread", it, "mV", 0)) }
    v["pack"]?.let { add(m("m.pack_v", it, "V", 1)) }
}

// ====================================================================================== cell balance

object HvCellBalance : LiveTest("hv-cell-balance", needsEngine = false, minutes = 2, relatedTips = listOf("drive-battery-weak-cell", "drive-battery-capacity-loss")) {
    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("cell.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        ctx.pause(Msg("cell.settle"), 20_000)
        val minNos = ArrayList<Int>()
        val maxNos = ArrayList<Int>()
        val s = ctx.sample(Msg("cell.measure"), 60_000, 5_000, live = { batteryLive(it) }) {
            val b = ctx.client.readBattery()
            b?.minCellNo?.let { minNos += it }
            b?.maxCellNo?.let { maxNos += it }
            mapOf(
                "spread" to b?.spreadMv, "min" to b?.minCellV, "max" to b?.maxCellV, "pack" to b?.packVoltage, "soc" to b?.soc,
            )
        }
        val spread = s.median("spread") ?: throw TestAborted(Msg("abort.no_bmu"))
        val minV = s.median("min")
        val maxV = s.median("max")
        val pack = s.median("pack")
        val soc = s.median("soc")
        val lowNo = minNos.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val highNo = maxNos.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val findings = ArrayList<Finding>()

        val plausible = minV != null && maxV != null && pack != null &&
            pack / Limits.CELLS in (minV - 0.05)..(maxV + 0.05) && minV in 3.0..4.3 && maxV in 3.0..4.3 &&
            (lowNo == null || lowNo in 1..Limits.CELLS) && (highNo == null || highNo in 1..Limits.CELLS)
        if (!plausible) {
            findings += f(Verdict.INCONCLUSIVE, "cell.decode_unreliable")
        } else {
            findings += when {
                spread <= Limits.SPREAD_GOOD_MV -> f(Verdict.PASS, "cell.good", "mv" to n(spread, 0))
                spread <= Limits.SPREAD_SLIGHT_MV -> f(Verdict.WARN, "cell.slight", "mv" to n(spread, 0))
                spread <= Limits.SPREAD_CLEAR_MV -> f(Verdict.WARN, "cell.clear", "mv" to n(spread, 0))
                else -> f(Verdict.FAIL, "cell.large", "mv" to n(spread, 0))
            }
            if (soc != null && minV < Limits.LOW_CELL_V && soc > 30) {
                findings += f(Verdict.FAIL, "cell.low_cell", "v" to n(minV, 3))
            }
            if (lowNo != null) {
                findings += f(
                    Verdict.INFO, "cell.lowest", "cell" to lowNo,
                    "module" to ceil(lowNo / Limits.CELLS_PER_MODULE.toDouble()).toInt(), "v" to n(minV, 3),
                )
            }
            if (soc != null && (soc < 30 || soc > 95)) findings += f(Verdict.INFO, "cell.soc_range", "soc" to n(soc, 0))
            // Trend: which cell was lowest in earlier runs.
            if (lowNo != null) {
                val past = ctx.store.get("cell.lowest").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
                val all = (past + lowNo).takeLast(6)
                ctx.store.put("cell.lowest", all.joinToString(","))
                val repeats = all.count { it == lowNo }
                if (repeats >= 3) findings += f(Verdict.INFO, "cell.repeat_lowest", "cell" to lowNo, "times" to repeats)
            }
        }
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = if (verdict == Verdict.WARN || verdict == Verdict.FAIL) {
            listOf(Msg("next.cell_full_charges"), Msg("next.cell_repeat"), Msg("next.cell_workshop"))
        } else emptyList()
        return result(
            ctx, this, started, "cell", findings,
            listOf(
                m("m.spread", spread, "mV", 0), m("m.cell_max", maxV, "V", 3), m("m.cell_min", minV, "V", 3),
                Measurement(Msg("m.cell_max_no"), highNo?.toDouble(), "", 0), Measurement(Msg("m.cell_min_no"), lowNo?.toDouble(), "", 0),
                m("m.pack_v", pack, "V", 1), m("m.avg_cell", pack?.div(Limits.CELLS), "V", 3), m("m.soc", soc, "%", 1),
            ),
            next, samples = s.all(),
        )
    }
}

// ====================================================================================== charging

object ChargingSession : LiveTest("charging-session", needsEngine = false, minutes = 25, relatedTips = listOf("charging-wont-start", "charge-flap-broken")) {
    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("chg.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        ctx.working(Msg("chg.reading"))
        val before = ctx.client.readBattery() ?: throw TestAborted(Msg("abort.no_bmu"))
        val socBefore = before.soc ?: throw TestAborted(Msg("abort.no_bmu"))
        if (socBefore > 85) {
            return result(ctx, this, started, "chg", listOf(f(Verdict.INCONCLUSIVE, "chg.too_full", "soc" to n(socBefore, 0))), emptyList(), emptyList())
        }
        val t0 = ctx.now()
        ctx.prompt(Msg("chg.plug_in"), Msg("btn.done"))
        val minutes = (ctx.now() - t0) / 60_000.0
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        ctx.working(Msg("chg.reading"))
        var after = ctx.client.readBattery()
        if (after == null) after = ctx.client.readBattery()
        val socAfter = after?.soc ?: throw TestAborted(Msg("abort.no_bmu"))
        val findings = ArrayList<Finding>()
        if (minutes < Limits.CHARGE_MIN_MINUTES) {
            findings += f(Verdict.INCONCLUSIVE, "chg.too_short", "min" to n(minutes, 0))
        } else {
            val pack = before.packVoltage ?: after.packVoltage ?: 300.0
            val expectedAh = Limits.CHARGE_NET_W * (minutes / 60.0) / pack
            val gotAh = if (before.remainingAh != null && after.remainingAh != null) after.remainingAh - before.remainingAh else null
            val dSoc = socAfter - socBefore
            val cap = before.capacityAh ?: after.capacityAh
            val expectedSoc = if (cap != null && cap > 0) expectedAh * 100.0 / cap else null
            findings += when {
                gotAh != null && gotAh >= expectedAh * 0.5 -> f(Verdict.PASS, "chg.good", "ah" to n(gotAh, 1), "soc" to n(dSoc, 0))
                gotAh == null && expectedSoc != null && dSoc >= expectedSoc * 0.5 -> f(Verdict.PASS, "chg.good_soc", "soc" to n(dSoc, 0))
                (gotAh ?: 0.0) > 0.2 || dSoc >= 1.0 -> f(Verdict.WARN, "chg.slow", "ah" to n(gotAh ?: 0.0, 1), "soc" to n(dSoc, 0), "expected" to n(expectedAh, 1))
                else -> f(Verdict.FAIL, "chg.none", "min" to n(minutes, 0))
            }
            val c200 = (after.charges200V ?: 0) - (before.charges200V ?: 0)
            val c100 = (after.charges100V ?: 0) - (before.charges100V ?: 0)
            if (before.charges200V != null || before.charges100V != null) {
                findings += if (c200 > 0 || c100 > 0) f(Verdict.INFO, "chg.counter_up") else f(Verdict.INFO, "chg.counter_same")
            }
        }
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = if (verdict == Verdict.FAIL || verdict == Verdict.WARN) {
            listOf(Msg("next.chg_checklist"), Msg("next.chg_other_socket"), Msg("next.chg_workshop"))
        } else emptyList()
        return result(
            ctx, this, started, "chg", findings,
            listOf(
                m("m.minutes", minutes, "min", 0), m("m.soc_before", socBefore, "%", 1), m("m.soc_after", socAfter, "%", 1),
                m("m.rem_before", before.remainingAh, "Ah", 1), m("m.rem_after", after.remainingAh, "Ah", 1),
            ),
            next,
        )
    }
}

// ====================================================================================== engine warm-up

private const val RPM = 0x0C
private const val COOLANT = 0x05
private const val LOAD = 0x04
private const val STFT = 0x06
private const val LTFT = 0x07
private const val MAP = 0x0B
private const val MAF = 0x10
private const val SPEED = 0x0D
private const val AMBIENT = 0x46
private const val FUEL = 0x2F

private fun id(pid: Int) = Mode01.valueId(pid)

private suspend fun TestContext.pids(vararg pids: Int): Map<String, Double?> {
    val got = client.readPids(pids.toList())
    return pids.associate { id(it) to got[id(it)]?.value }
}

/** Asks the user to start the engine and waits until it runs. */
private suspend fun TestContext.ensureEngineRunning(): Boolean {
    if ((pids(RPM)[id(RPM)] ?: 0.0) > 500) return true
    prompt(Msg("eng.start"))
    var streak = 0
    return waitFor(Msg("eng.waiting"), 90_000, 2_000) {
        val rpm = pids(RPM)[id(RPM)] ?: 0.0
        streak = if (rpm > 500) streak + 1 else 0
        streak >= 3
    }
}

object EngineWarmup : LiveTest("engine-warmup", needsEngine = true, minutes = 15, relatedTips = listOf("engine-starts-unexpectedly", "engine-cold-start-campaign")) {
    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("warm.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        val first = ctx.pids(COOLANT, AMBIENT, FUEL)
        val startTemp = first[id(COOLANT)] ?: throw TestAborted(Msg("abort.no_engine_data"))
        val ambient = first[id(AMBIENT)] ?: ctx.fresh("ac.ambient_temp", 60_000)?.value ?: ctx.fresh("awc.ambient_temp", 60_000)?.value
        val findings = ArrayList<Finding>()
        first[id(FUEL)]?.let { if (it < 10) findings += f(Verdict.WARN, "warm.low_fuel", "pct" to n(it, 0)) }
        if (!ctx.ensureEngineRunning()) throw TestAborted(Msg("eng.no_start"))

        var reachedAtS: Double? = null
        var stoppedStreak = 0
        var engineStopped = false
        val periodMs = 5_000L
        var index = 0
        val s = ctx.sample(
            Msg("warm.measure"), 15 * 60_000L, periodMs,
            live = { listOfNotNull(m("m.coolant", it[id(COOLANT)], "°C", 0), m("m.rpm", it[id(RPM)], "rpm", 0)) },
            stopWhen = { sm ->
                val temps = sm.list(id(COOLANT))
                val last = temps.lastOrNull() ?: 0.0
                val stable = temps.size >= 12 && temps.takeLast(12).let { w -> w.min() >= Limits.WARM_STABLE_MIN && w.max() - w.min() <= 4.0 }
                stable || last > Limits.HOT_FAIL || engineStopped
            },
        ) {
            val v = ctx.pids(COOLANT, RPM, LOAD)
            val t = v[id(COOLANT)]
            if (reachedAtS == null && t != null && t >= Limits.WARM_TARGET) reachedAtS = index * periodMs / 1000.0
            val rpm = v[id(RPM)] ?: 0.0
            stoppedStreak = if (rpm < 100) stoppedStreak + 1 else 0
            if (stoppedStreak >= 3) engineStopped = true
            index++
            v
        }
        val temps = s.list(id(COOLANT))
        val maxT = temps.maxOrNull() ?: throw TestAborted(Msg("abort.no_engine_data"))
        val lastT = temps.last()
        val limitS = if ((ambient ?: 10.0) < 0) 15 * 60.0 else 10 * 60.0
        val ranS = index * periodMs / 1000.0
        when {
            maxT > Limits.HOT_FAIL -> findings += f(Verdict.FAIL, "warm.overheat", "t" to n(maxT, 0))
            maxT > Limits.HOT_WARN -> findings += f(Verdict.WARN, "warm.hot", "t" to n(maxT, 0))
        }
        val r = reachedAtS
        when {
            r != null && r <= limitS -> findings += f(Verdict.PASS, "warm.good", "min" to n(r / 60.0, 1), "t" to n(lastT, 0))
            engineStopped && r == null -> Unit
            r != null -> findings += f(Verdict.WARN, "warm.slow", "min" to n(r / 60.0, 1))
            maxT >= Limits.WARM_SLOW -> findings += f(Verdict.WARN, "warm.slow_max", "t" to n(maxT, 0), "min" to n(ranS / 60.0, 0))
            ranS >= 14 * 60 -> findings += f(Verdict.FAIL, "warm.cold", "t" to n(maxT, 0))
            else -> findings += f(Verdict.INCONCLUSIVE, "warm.too_short", "t" to n(maxT, 0))
        }
        // A drop of more than 5 °C after reaching temperature while still running.
        if (r != null && !engineStopped) {
            val afterReach = temps.drop((r * 1000 / periodMs).toInt())
            val peak = afterReach.maxOrNull()
            if (peak != null && afterReach.isNotEmpty() && peak - afterReach.last() > 5) findings += f(Verdict.WARN, "warm.drops")
        }
        if (engineStopped) findings += f(Verdict.WARN, "warm.engine_stopped")
        val rpms = s.list(id(RPM)).takeLast(12).filter { it > 300 }
        if (!engineStopped && rpms.size >= 6 && rpms.max() - rpms.min() > 300) {
            findings += f(Verdict.INFO, "warm.uneven_rpm", "spread" to n(rpms.max() - rpms.min(), 0))
        }
        if (findings.none { it.verdict != Verdict.INFO }) findings += f(Verdict.INCONCLUSIVE, "warm.too_short", "t" to n(maxT, 0))
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = buildList {
            add(Msg("next.switch_off_engine"))
            if (verdict == Verdict.WARN || verdict == Verdict.FAIL) {
                add(Msg("next.coolant_level"))
                add(Msg("next.thermostat"))
            }
            if (engineStopped) add(Msg("next.run_scan"))
        }
        return result(
            ctx, this, started, "warm", findings,
            listOf(
                m("m.coolant_start", startTemp, "°C", 0), m("m.coolant_max", maxT, "°C", 0), m("m.coolant_end", lastT, "°C", 0),
                m("m.ambient", ambient, "°C", 0), m("m.time_to_75", r?.div(60.0), "min", 1), m("m.run_time", ranS / 60.0, "min", 1),
            ),
            next, listOf("P0128", "P0125", "P0116", "P0117", "P0118"), s.all(),
        )
    }
}

// ====================================================================================== fuel trims

object FuelTrims : LiveTest("fuel-trims", needsEngine = true, minutes = 3, road = true, relatedTips = listOf("airflow-sensor-early-cars", "old-fuel-and-fuel-cap")) {
    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("trim.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        if (!ctx.ensureEngineRunning()) throw TestAborted(Msg("eng.no_start"))
        val warm = ctx.waitFor(Msg("trim.warming"), 10 * 60_000L, 5_000) { (ctx.pids(COOLANT)[id(COOLANT)] ?: 0.0) >= Limits.WARM_TARGET }
        if (!warm) throw TestAborted(Msg("trim.not_warm"))

        val live: (Map<String, Double?>) -> List<Measurement> = {
            listOf(m("m.stft", it[id(STFT)], "%", 1), m("m.ltft", it[id(LTFT)], "%", 1), m("m.rpm", it[id(RPM)], "rpm", 0))
        }
        val a = ctx.sample(Msg("trim.measure_a"), 60_000, 2_000, live = live) { ctx.pids(STFT, LTFT, RPM, LOAD, MAP, MAF) }
        val findings = ArrayList<Finding>()
        val totalA = phase(a, findings, "a") ?: throw TestAborted(Msg("abort.no_engine_data"))

        var totalB: Double? = null
        var b: Samples? = null
        if (ctx.prompt(Msg("trim.road"), Msg("btn.start_road"), Msg("btn.skip"))) {
            val road = ctx.sample(Msg("trim.measure_b"), 60_000, 2_000, live = live) {
                val v = ctx.pids(STFT, LTFT, RPM, SPEED)
                // Only count samples with the engine running at road speed.
                if ((v[id(RPM)] ?: 0.0) < 500 || (v[id(SPEED)] ?: 0.0) < 40) emptyMap() else v
            }
            b = road
            totalB = phase(road, findings, "b")
            if (totalB == null) findings += f(Verdict.INFO, "trim.road_no_data")
        }
        // Interpretation across phases.
        val leanA = totalA > Limits.TRIM_GOOD
        val richA = totalA < -Limits.TRIM_GOOD
        val tb = totalB
        when {
            leanA && tb != null && abs(tb) <= Limits.TRIM_GOOD -> findings += f(Verdict.INFO, "trim.hint_air_leak")
            leanA && (tb == null || tb > Limits.TRIM_GOOD) -> findings += f(Verdict.INFO, "trim.hint_lean_both")
            richA || (tb != null && tb < -Limits.TRIM_GOOD) -> findings += f(Verdict.INFO, "trim.hint_rich")
        }
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = buildList {
            add(Msg("next.switch_off_engine"))
            if (verdict == Verdict.WARN || verdict == Verdict.FAIL) {
                add(Msg("next.trim_hoses"))
                add(Msg("next.run_scan"))
            }
        }
        val samples = HashMap<String, List<Double>>()
        a.exportTo(samples, "idle.")
        b?.exportTo(samples, "road.")
        return result(
            ctx, this, started, "trim", findings,
            listOf(
                m("m.stft_a", a.median(id(STFT)), "%", 1), m("m.ltft_a", a.median(id(LTFT)), "%", 1), m("m.total_a", totalA, "%", 1),
                m("m.stft_b", b?.median(id(STFT)), "%", 1), m("m.ltft_b", b?.median(id(LTFT)), "%", 1), m("m.total_b", totalB, "%", 1),
            ),
            next, listOf("P0171", "P0172", "P0101"), samples,
        )
    }

    /** Adds the finding for one phase and returns its total trim (STFT + LTFT), or null without data. */
    private fun phase(s: Samples, findings: MutableList<Finding>, which: String): Double? {
        val st = s.median(id(STFT)) ?: return null
        val lt = s.median(id(LTFT)) ?: 0.0
        val total = st + lt
        val stRange = (s.max(id(STFT)) ?: st) - (s.min(id(STFT)) ?: st)
        if (s.count(id(STFT)) >= 5 && stRange < 0.5 && abs(st) < 0.5) findings += f(Verdict.INFO, "trim.open_loop")
        val dir = Msg(if (total >= 0) "trim.lean" else "trim.rich")
        findings += when {
            abs(total) <= Limits.TRIM_GOOD -> f(Verdict.PASS, "trim.good_$which", "total" to n(total, 1))
            abs(total) <= Limits.TRIM_BAD -> f(Verdict.WARN, "trim.high_$which", "total" to n(total, 1), "dir" to dir)
            else -> f(Verdict.FAIL, "trim.bad_$which", "total" to n(total, 1), "dir" to dir)
        }
        return total
    }
}

// ====================================================================================== inspection readiness

object InspectionReadiness : LiveTest("inspection-readiness", needsEngine = false, minutes = 1, relatedTips = listOf("prepare-for-eu-kontroll", "old-fuel-and-fuel-cap")) {
    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("insp.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        ctx.working(Msg("insp.reading"))
        val r = ctx.client.readReadiness() ?: throw TestAborted(Msg("abort.no_engine_data"))
        ctx.working(Msg("scan.scanning"))
        val scan = ctx.client.scanDtcs(includeExtended = false)
        val supported = ctx.client.state.value.supportedPids[0x7E8].orEmpty()
        val extra = listOf(0x21, 0x30, 0x31, 0x4E).filter { it in supported }
        val values = if (extra.isNotEmpty()) ctx.client.readPids(extra) else emptyMap()
        fun v(pid: Int) = values[id(pid)]?.value

        val findings = ArrayList<Finding>()
        val stored = scan.dtcs.filter { it.kind == DtcKind.STORED || it.kind == DtcKind.PERMANENT }.map { it.code }.distinct()
        val pending = scan.dtcs.filter { it.kind == DtcKind.PENDING }.map { it.code }.distinct()
        if (r.milOn == true || stored.isNotEmpty()) {
            findings += f(Verdict.FAIL, "insp.mil_or_codes", "codes" to stored.joinToString(", ").ifEmpty { "—" })
        }
        if (pending.isNotEmpty()) findings += f(Verdict.WARN, "insp.pending", "codes" to pending.joinToString(", "))
        val incomplete = r.incomplete
        if (incomplete.isNotEmpty()) {
            findings += f(Verdict.WARN, "insp.incomplete", "count" to incomplete.size)
        }
        for (mon in r.supported) {
            findings += if (mon.complete) f(Verdict.PASS, "insp.monitor_done", "name" to Msg("mon.${mon.key}"))
            else f(Verdict.INFO, "insp.monitor_open", "name" to Msg("mon.${mon.key}"))
        }
        val clearedKm = v(0x31)
        val warmups = v(0x30)
        if ((clearedKm != null && clearedKm < Limits.CLEARED_RECENT_KM) || (warmups != null && warmups < Limits.CLEARED_RECENT_WARMUPS)) {
            findings += f(Verdict.WARN, "insp.recently_cleared")
        }
        if (findings.none { it.verdict == Verdict.FAIL || it.verdict == Verdict.WARN }) findings += f(Verdict.PASS, "insp.ready")
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = buildList {
            if (incomplete.isNotEmpty()) add(Msg("next.insp_drive"))
            if (stored.isNotEmpty() || r.milOn == true) add(Msg("next.insp_fix_codes"))
            add(Msg("next.insp_no_clear"))
        }
        return result(
            ctx, this, started, "insp", findings,
            listOf(
                Measurement(Msg("m.mil"), null, "", text = Msg(if (r.milOn == true) "val.on" else "val.off")),
                Measurement(Msg("m.monitors"), null, "", text = Msg("val.n_of_m", "n" to (r.supported.size - incomplete.size), "m" to r.supported.size)),
                m("m.dist_mil", v(0x21), "km", 0), m("m.dist_cleared", clearedKm, "km", 0),
                m("m.warmups", warmups, "", 0), m("m.time_cleared", v(0x4E), "min", 0),
            ),
            next, (stored + pending).distinct(),
        )
    }
}

// ====================================================================================== full DTC scan

object DtcFullScan : LiveTest("dtc-full-scan", needsEngine = false, minutes = 1, relatedTips = listOf("ev-system-warning", "weak-12v-battery", "check-open-recalls")) {
    private val lowVoltageCodes = setOf("P0562", "P1035", "P104B", "P1000", "P1AF2")

    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("scan.pre"))
        if (!ctx.waitFor(Msg("wait.car"), 30_000) { ctx.client.state.value.carReachable }) throw TestAborted(Msg("abort.car_silent"))
        val findings = ArrayList<Finding>()
        ctx.working(Msg("scan.voltage"))
        val v12 = ctx.client.readAdapterVoltage()
        if (v12 != null && v12 < 12.2) findings += f(Verdict.WARN, "scan.low_voltage", "v" to n(v12, 2))
        val mil = ctx.client.readReadiness()?.milOn
        ctx.working(Msg("scan.scanning"))
        val scan = ctx.client.scanDtcs(includeExtended = true)
        if (!scan.success) throw TestAborted(Msg("abort.car_silent"))
        val codes = scan.dtcs.map { it.code }.distinct()
        val confirmed = scan.dtcs.filter { it.kind != DtcKind.PENDING }
        val uEcus = scan.dtcs.filter { it.code.startsWith("U") }.map { it.ecuRxId }.distinct()

        if (codes.isEmpty()) {
            findings += f(Verdict.PASS, "scan.none", "ecus" to scan.respondingEcus.size)
        } else {
            findings += f(Verdict.INFO, "scan.found", "count" to codes.size, "ecus" to scan.respondingEcus.size)
        }
        if (codes.any { it in lowVoltageCodes } || uEcus.size >= 2) findings += f(Verdict.WARN, "scan.pattern_low_voltage")
        if ("P10DC" in codes) findings += f(Verdict.WARN, "scan.p10dc")
        val hybrid = confirmed.filter { it.code.startsWith("P0A") || it.code.startsWith("P0B") || it.code.startsWith("P0C") }.map { it.code }.distinct()
        if (hybrid.isNotEmpty()) findings += f(Verdict.FAIL, "scan.hybrid", "codes" to hybrid.joinToString(", "))
        val emission = confirmed.filter { c -> listOf("P01", "P02", "P03", "P04").any { c.code.startsWith(it) } }.map { it.code }.distinct()
        if (emission.isNotEmpty()) {
            findings += if (mil == true) f(Verdict.FAIL, "scan.emission_mil", "codes" to emission.joinToString(", "))
            else f(Verdict.WARN, "scan.emission", "codes" to emission.joinToString(", "))
        }
        val chassis = codes.filter { it.startsWith("C") }
        if (chassis.isNotEmpty()) findings += f(Verdict.WARN, "scan.chassis", "codes" to chassis.joinToString(", "))
        if (codes.isNotEmpty() && codes.all { it.startsWith("U") }) findings += f(Verdict.WARN, "scan.only_u")
        val pendingOnly = scan.dtcs.isNotEmpty() && scan.dtcs.all { it.kind == DtcKind.PENDING }
        if (pendingOnly) findings += f(Verdict.WARN, "scan.pending_only")
        if (codes.isNotEmpty() && findings.none { it.verdict == Verdict.WARN || it.verdict == Verdict.FAIL }) {
            findings += f(Verdict.WARN, "scan.other_codes")
        }
        if (scan.errors.isNotEmpty() && codes.isEmpty()) findings += f(Verdict.INFO, "scan.some_silent", "count" to scan.errors.size)
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = buildList {
            if (codes.isNotEmpty()) add(Msg("next.read_codes"))
            if (findings.any { it.msg.key == "scan.pattern_low_voltage" || it.msg.key == "scan.low_voltage" }) add(Msg("next.run_12v"))
            if (hybrid.isNotEmpty()) add(Msg("next.hv_workshop"))
            if (codes.isNotEmpty()) add(Msg("next.save_before_clear"))
        }
        return result(
            ctx, this, started, "scan", findings,
            listOf(
                m("m.v12", v12, "V", 2),
                Measurement(Msg("m.codes"), codes.size.toDouble(), "", 0),
                Measurement(Msg("m.ecus"), scan.respondingEcus.size.toDouble(), "", 0),
            ),
            next, codes,
        )
    }
}

// ====================================================================================== wheel speeds

object WheelSpeedMatch : LiveTest("wheel-speed-match", needsEngine = false, minutes = 2, road = true, relatedTips = listOf("mixed-tyres", "ev-system-warning")) {
    private val wheels = listOf("abs.wheel_fl", "abs.wheel_fr", "abs.wheel_rl", "abs.wheel_rr")

    override suspend fun run(ctx: TestContext): TestResult {
        val started = ctx.now()
        ctx.prompt(Msg("wheel.pre"))
        val speedOk = ctx.waitFor(Msg("wheel.wait_speed"), 3 * 60_000L, 2_000) {
            val sp = ctx.pids(SPEED)[id(SPEED)] ?: 0.0
            sp in 45.0..85.0
        }
        if (!speedOk) throw TestAborted(Msg("wheel.no_speed"))
        val rows = ArrayList<Map<String, Double?>>()
        ctx.sample(Msg("wheel.measure"), 30_000, 1_000, live = { listOf(m("m.speed", it[id(SPEED)], "km/h", 0)) }) {
            val row = HashMap<String, Double?>()
            row.putAll(ctx.pids(SPEED))
            for (w in wheels) row[w] = ctx.fresh(w, 2_000)?.value
            row["front"] = ctx.fresh("motor.front_rpm", 3_000)?.value
            row["rear"] = ctx.fresh("motor.rear_rpm", 3_000)?.value
            rows += row
            row
        }
        // Keep only steady rows (speed change ≤ 2 km/h from the previous row, 40–90 km/h).
        val steady = rows.filterIndexed { i, r ->
            val sp = r[id(SPEED)] ?: return@filterIndexed false
            val prev = rows.getOrNull(i - 1)?.get(id(SPEED))
            sp in 40.0..90.0 && (prev == null || abs(sp - prev) <= 2.0)
        }
        val findings = ArrayList<Finding>()
        val measurements = ArrayList<Measurement>()
        val absRows = steady.filter { r -> wheels.all { r[it] != null && r[it]!! > 0 } }
        if (absRows.size >= 10) {
            val med = wheels.associateWith { w -> absRows.mapNotNull { it[w] }.median()!! }
            val front = (med.getValue("abs.wheel_fl") + med.getValue("abs.wheel_fr")) / 2
            val rear = (med.getValue("abs.wheel_rl") + med.getValue("abs.wheel_rr")) / 2
            val mean = (front + rear) / 2
            val diff = (front - rear) / mean * 100.0
            findings += classify(abs(diff), "wheel.axle", "pct" to n(abs(diff), 2))
            for (w in wheels) {
                val dev = (med.getValue(w) - mean) / mean * 100.0
                if (abs(dev) > Limits.WHEEL_SINGLE_PCT) findings += f(Verdict.WARN, "wheel.single", "wheel" to Msg("wheel.${w.substringAfter('_')}"), "pct" to n(dev, 2))
            }
            measurements += m("m.front_rear_pct", diff, "%", 2)
            for (w in wheels) measurements += Measurement(Msg("m.wheel_${w.substringAfter('_')}"), med.getValue(w), "km/h", 2)
        } else {
            val ratios = steady.mapNotNull { r ->
                val fr = r["front"]
                val re = r["rear"]
                if (fr != null && re != null && fr > 100) re / fr else null
            }
            val ratio = ratios.median()
            if (ratio == null || ratios.size < 10) {
                findings += f(Verdict.INCONCLUSIVE, "wheel.no_data")
            } else {
                measurements += m("m.motor_ratio", ratio, "", 4)
                val base = ctx.store.get("wheel.ratio_baseline")?.toDoubleOrNull()
                if (base == null) {
                    if (ctx.prompt(Msg("wheel.same_tyres"), Msg("btn.yes"), Msg("btn.no"))) {
                        ctx.store.put("wheel.ratio_baseline", ratio.toString())
                        findings += f(Verdict.INFO, "wheel.baseline_saved")
                    } else {
                        findings += f(Verdict.INFO, "wheel.no_baseline")
                    }
                    findings += f(Verdict.INCONCLUSIVE, "wheel.first_run")
                } else {
                    val change = (ratio - base) / base * 100.0
                    measurements += m("m.ratio_change", change, "%", 2)
                    findings += classify(abs(change), "wheel.ratio", "pct" to n(abs(change), 2))
                }
            }
        }
        val verdict = Verdict.worst(findings.map { it.verdict })
        val next = if (verdict == Verdict.WARN || verdict == Verdict.FAIL) listOf(Msg("next.tyre_pressures"), Msg("next.tyre_same")) else emptyList()
        return result(ctx, this, started, "wheel", findings, measurements, next)
    }

    private fun classify(pct: Double, prefix: String, vararg args: Pair<String, Any>): Finding = when {
        pct <= Limits.WHEEL_GOOD_PCT -> f(Verdict.PASS, "$prefix.good", *args)
        pct <= Limits.WHEEL_WARN_PCT -> f(Verdict.WARN, "$prefix.small", *args)
        else -> f(Verdict.FAIL, "$prefix.large", *args)
    }
}
