package io.github.anwo91.phevlink.core.obd.dtc

import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n.Arg
import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n.Key
import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n.Lit
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity.CHECK_NOW
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity.CHECK_SOON
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity.STOP
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.BODY
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.CHASSIS
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.COMPUTER_OUTPUTS
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.EMISSIONS
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.FUEL_AIR
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.HYBRID_EV
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.IGNITION_MISFIRE
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.NETWORK
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.POWERTRAIN_ENGINE
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.SPEED_IDLE
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.TRANSMISSION
import java.util.Locale

/**
 * Which text keys a curated entry uses. Keys point into strings_<lang>.txt:
 * [sub] and [sum] are single strings, [causes] and [checks] are numbered list bases (`...c1`, `...c2`),
 * [advice] a single string. [args] fill `{slots}` such as `{cyl}` or `{module}`.
 */
internal class CuratedDef(
    val system: DtcSystem,
    val severity: DtcSeverity,
    val sub: String,
    val sum: String,
    val causes: String,
    val checks: String,
    /** Advice key, or null for `cur.<family>.adv` when defined, else the generic advice for [severity]. */
    val advice: String?,
    val family: String,
    val args: Map<String, Arg>,
) {
    fun adviceKey(): String = advice
        ?: "cur.$family.adv".takeIf { DtcI18n.has(it) }
        ?: "txt.advice_${severity.name.lowercase(Locale.ROOT)}"
}

/**
 * Hand-written explanations for the codes an Outlander PHEV (2014–2017) owner is most likely to see.
 * The text lives under `cur.*` in strings_<lang>.txt (original to this project; general guidance,
 * not a substitute for a professional diagnosis). Every code here must exist in codes.tsv.
 */
internal object CuratedDtcs {

    private val defs = LinkedHashMap<String, CuratedDef>()

    operator fun get(code: String): CuratedDef? = defs[code]

    val codes: Set<String> get() = defs.keys

    fun explain(code: String, def: CuratedDef, lang: String, title: String?): DtcExplanation {
        val sub = DtcI18n.text(lang, def.sub, def.args)
        return DtcExplanation(
            code = code,
            title = title ?: sub,
            system = def.system,
            subsystem = sub,
            severity = def.severity,
            summary = DtcI18n.text(lang, def.sum, def.args),
            causes = DtcI18n.list(lang, def.causes, def.args),
            checks = DtcI18n.list(lang, def.checks, def.args),
            driveAdvice = DtcI18n.text(lang, def.adviceKey(), def.args),
            manufacturerSpecific = DtcRules.isManufacturerRange(code),
            curated = true,
            known = true,
        )
    }

    /**
     * Registers [code]. [key] is the text family (`cur.<key>.sub|sum|c|k|adv`); individual parts can
     * point elsewhere. [advice] is a family name or a full key ("txt.advice_hv"); by default
     * `cur.<key>.adv`, or the generic advice for the severity.
     */
    private fun add(
        code: String,
        system: DtcSystem,
        severity: DtcSeverity,
        key: String = code,
        sub: String = key,
        sum: String = key,
        causes: String = key,
        checks: String = key,
        advice: String? = null,
        args: Map<String, Arg> = emptyMap(),
    ) {
        require(code !in defs) { "Duplicate curated DTC $code" }
        val adviceKey = advice?.let { if ('.' in it) it else "cur.$it.adv" }
        defs[code] = CuratedDef(
            system, severity, "cur.$sub.sub", "cur.$sum.sum", "cur.$causes.c", "cur.$checks.k", adviceKey, key, args,
        )
    }

    private fun k(key: String) = Key(key)
    private fun l(text: String) = Lit(text)

    init {
        // ---- engine timing, crank/cam sensors, engine start
        add("P0011", POWERTRAIN_ENGINE, CHECK_NOW, key = "vvt", args = mapOf("cam" to l("A"), "camname" to k("cur.w.intake_cam")))
        add("P0014", POWERTRAIN_ENGINE, CHECK_NOW, key = "vvt", args = mapOf("cam" to l("B"), "camname" to k("cur.w.exhaust_cam")))
        add("P0016", POWERTRAIN_ENGINE, CHECK_NOW, key = "corr", args = mapOf("cam" to l("A"), "camname" to k("cur.w.intake_cam")))
        add("P0017", POWERTRAIN_ENGINE, CHECK_NOW, key = "corr", args = mapOf("cam" to l("B"), "camname" to k("cur.w.exhaust_cam")))
        add("P0335", POWERTRAIN_ENGINE, CHECK_NOW)
        add("P0336", POWERTRAIN_ENGINE, CHECK_NOW, sub = "P0335", advice = "P0335")
        add("P0340", POWERTRAIN_ENGINE, CHECK_NOW, advice = "P0335")
        add("P0A0F", POWERTRAIN_ENGINE, CHECK_NOW)

        // ---- cooling, thermostat, oil pressure
        add("P0116", POWERTRAIN_ENGINE, CHECK_SOON)
        add("P0117", POWERTRAIN_ENGINE, CHECK_SOON, sub = "P0116")
        add("P0118", POWERTRAIN_ENGINE, CHECK_SOON, sub = "P0116")
        add("P0125", POWERTRAIN_ENGINE, CHECK_SOON)
        add("P0128", POWERTRAIN_ENGINE, CHECK_SOON)
        add("P0217", POWERTRAIN_ENGINE, STOP)
        add("P0480", POWERTRAIN_ENGINE, CHECK_NOW)
        add("P0524", POWERTRAIN_ENGINE, STOP, advice = "txt.advice_oil_low")

        // ---- air metering
        add("P0100", FUEL_AIR, CHECK_SOON)
        add("P0101", FUEL_AIR, CHECK_SOON, sub = "P0100", advice = "P0100")
        add("P0102", FUEL_AIR, CHECK_SOON, sub = "P0100", advice = "P0100")
        add("P0103", FUEL_AIR, CHECK_SOON, sub = "P0100", advice = "P0100")
        add("P0112", FUEL_AIR, CHECK_SOON)
        add("P0113", FUEL_AIR, CHECK_SOON, sub = "P0112", advice = "P0112")

        // ---- electronic throttle and accelerator pedal
        add("P0121", FUEL_AIR, CHECK_NOW)
        add("P0122", FUEL_AIR, CHECK_NOW, sub = "P0121", checks = "P0121", advice = "P0121")
        add("P0123", FUEL_AIR, CHECK_NOW, sub = "P0121", checks = "P0121", advice = "P0121")
        add("P2101", FUEL_AIR, CHECK_NOW, advice = "P0121")
        add("P2135", FUEL_AIR, CHECK_NOW, checks = "P0121", advice = "P0121")
        add("P2138", FUEL_AIR, CHECK_NOW)

        // ---- fuel mixture, injectors, pump
        add("P0171", FUEL_AIR, CHECK_SOON)
        add("P0172", FUEL_AIR, CHECK_SOON, sub = "P0171")
        for (cyl in 1..4) add("P020$cyl", FUEL_AIR, CHECK_NOW, key = "inj", args = mapOf("cyl" to l("$cyl")))
        add("P0230", FUEL_AIR, CHECK_NOW)

        // ---- misfire and ignition
        add("P0300", IGNITION_MISFIRE, CHECK_NOW, advice = "txt.advice_misfire")
        for (cyl in 1..4) {
            add("P030$cyl", IGNITION_MISFIRE, CHECK_NOW, key = "mis", advice = "txt.advice_misfire", args = mapOf("cyl" to l("$cyl")))
        }
        add("P0325", IGNITION_MISFIRE, CHECK_SOON)
        add("P0327", IGNITION_MISFIRE, CHECK_SOON, sub = "P0325", checks = "P0325", advice = "P0325")
        add("P0328", IGNITION_MISFIRE, CHECK_SOON, sub = "P0325", advice = "P0325")
        for ((i, letter) in listOf("A", "B", "C", "D").withIndex()) {
            add(
                "P035${i + 1}", IGNITION_MISFIRE, CHECK_NOW, key = "coil", advice = "txt.advice_misfire",
                args = mapOf("letter" to l(letter), "cyl" to l("${i + 1}")),
            )
        }

        // ---- oxygen sensors (bank 1; the four-cylinder engine has only bank 1)
        fun o2Args(sensor: Int) = mapOf(
            "n" to l("$sensor"),
            "side" to k(if (sensor == 1) "cur.w.front" else "cur.w.rear"),
            "where" to k(if (sensor == 1) "cur.w.before_cat" else "cur.w.after_cat"),
            "role" to k(if (sensor == 1) "cur.o2.role_front" else "cur.o2.role_rear"),
        )
        fun heater(code: String, sensor: Int, kind: String) =
            add(code, FUEL_AIR, CHECK_SOON, key = "o2h", sum = "o2h_$kind", args = o2Args(sensor))
        heater("P0031", 1, "low")
        heater("P0032", 1, "high")
        heater("P0135", 1, "circ")
        heater("P0037", 2, "low")
        heater("P0038", 2, "high")
        heater("P0141", 2, "circ")
        fun o2(code: String, sensor: Int, problem: String, sumKey: String = problem) = add(
            code, FUEL_AIR, CHECK_SOON, key = "o2", sum = sumKey, causes = problem,
            advice = if (sensor == 1) "cur.o2f.adv" else "cur.o2r.adv", args = o2Args(sensor),
        )
        for ((sensor, codes) in listOf(1 to listOf("P0130", "P0131", "P0132", "P0133", "P0134"), 2 to listOf("P0136", "P0137", "P0138", "P0139", "P0140"))) {
            val fr = if (sensor == 1) "f" else "r"
            o2(codes[0], sensor, "o2_circ")
            o2(codes[1], sensor, "o2_low", "o2_low_$fr")
            o2(codes[2], sensor, "o2_high", "o2_high_$fr")
            o2(codes[3], sensor, "o2_slow")
            o2(codes[4], sensor, "o2_noact")
        }
        o2("P2195", 1, "P2195")
        o2("P2196", 1, "P2196")

        // ---- catalyst, evaporative system, EGR
        add("P0420", EMISSIONS, CHECK_SOON)
        add("P0440", EMISSIONS, CHECK_SOON, sub = "evap", advice = "evap")
        add("P0441", EMISSIONS, CHECK_SOON, sub = "evap", advice = "evap")
        for ((code, size) in listOf("P0442" to "small", "P0455" to "large", "P0456" to "very_small")) {
            add(
                code, EMISSIONS, CHECK_SOON, sub = "evap", sum = "evap_leak", checks = "evap_leak", advice = "evap",
                args = mapOf("size" to k("cur.w.leak_$size")),
            )
        }
        add("P0457", EMISSIONS, CHECK_SOON, sub = "evap")
        add("P0443", EMISSIONS, CHECK_SOON, sub = "evap", advice = "evap")
        add("P0446", EMISSIONS, CHECK_SOON, sub = "evap", advice = "evap")
        add("P0401", EMISSIONS, CHECK_SOON)
        add("P0403", EMISSIONS, CHECK_SOON, sub = "P0401")

        // ---- vehicle speed, idle, 12 V supply, control modules, MIL request
        add("P0500", SPEED_IDLE, CHECK_SOON)
        add("P0506", SPEED_IDLE, CHECK_SOON)
        add("P0507", SPEED_IDLE, CHECK_SOON, sub = "P0506")
        add("P0562", COMPUTER_OUTPUTS, CHECK_NOW)
        add("P0563", COMPUTER_OUTPUTS, CHECK_NOW, sub = "P0562")
        add("P0601", COMPUTER_OUTPUTS, CHECK_NOW)
        add("P0606", COMPUTER_OUTPUTS, CHECK_NOW, sub = "P0601", causes = "P0601", checks = "P0601", advice = "P0601")
        add("P0700", TRANSMISSION, CHECK_SOON)

        // ---- hybrid / EV system
        add("P0AA6", HYBRID_EV, CHECK_NOW, advice = "txt.advice_isolation")
        add("P0A80", HYBRID_EV, CHECK_NOW, sub = "batt")
        add("P0A7F", HYBRID_EV, CHECK_SOON, sub = "batt")
        add("P0A7D", HYBRID_EV, CHECK_NOW, sub = "batt")
        add("P0A7E", HYBRID_EV, CHECK_NOW, sub = "batt")
        add("P0A9C", HYBRID_EV, CHECK_NOW, advice = "txt.advice_hv")
        add("P0AFA", HYBRID_EV, CHECK_NOW, sub = "batt", advice = "txt.advice_hv")
        add("P0AFB", HYBRID_EV, CHECK_NOW, sub = "batt", advice = "txt.advice_hv")
        add("P0A94", HYBRID_EV, CHECK_NOW)
        add("P0A93", HYBRID_EV, CHECK_NOW, sub = "pec", advice = "pec")
        add("P0A05", HYBRID_EV, CHECK_NOW, sub = "pec", advice = "pec")
        add("P0A7C", HYBRID_EV, CHECK_NOW, sub = "pec", advice = "pec")
        add("P0A78", HYBRID_EV, CHECK_NOW, advice = "txt.advice_hv")
        add("P0A3F", HYBRID_EV, CHECK_NOW)
        add("P0A0A", HYBRID_EV, CHECK_NOW, sub = "hvil", causes = "hvil", checks = "hvil", advice = "topic.hv_interlock.adv")
        add("P0A0D", HYBRID_EV, CHECK_NOW, sub = "hvil", causes = "hvil", checks = "hvil", advice = "topic.hv_interlock.adv")
        add("P0A95", HYBRID_EV, CHECK_NOW, advice = "txt.advice_hv")

        // ---- network (U) codes: "lost" family with the module name in {module}
        fun lost(code: String, module: String, severity: DtcSeverity, advice: String, extraCheck: Boolean = false) = add(
            code, NETWORK, severity, key = "lost", checks = if (extraCheck) "lostx" else "lost", advice = advice,
            args = mapOf("module" to k("cur.mod.$module"), "effect" to k("cur.$code.effect"), "extra" to k("cur.$code.extra")),
        )
        lost("U0100", "ecm", CHECK_NOW, "U0100", extraCheck = true)
        lost("U0101", "tcm", CHECK_SOON, "txt.advice_network")
        lost("U0110", "mcu", CHECK_NOW, "txt.advice_hv", extraCheck = true)
        lost("U0111", "bmu", CHECK_NOW, "txt.advice_hv", extraCheck = true)
        lost("U0121", "abs", CHECK_NOW, "U0121")
        lost("U0131", "eps", CHECK_NOW, "U0131")
        lost("U0140", "bcm", CHECK_SOON, "txt.advice_network")
        lost("U0151", "srs", CHECK_NOW, "U0151", extraCheck = true)
        lost("U0155", "ipc", CHECK_SOON, "U0155")
        lost("U0164", "hvac", CHECK_SOON, "topic.b_climate.adv")
        lost("U0293", "hpcu", CHECK_NOW, "txt.advice_hv", extraCheck = true)
        add("U0001", NETWORK, CHECK_SOON, causes = "bus", checks = "bus", advice = "txt.advice_network")
        add("U0073", NETWORK, CHECK_SOON, causes = "bus", checks = "bus", advice = "txt.advice_network")
        add("U3003", COMPUTER_OUTPUTS, CHECK_SOON)

        // ---- chassis (C) and body (B)
        for ((code, wheel) in listOf("C0031" to "lf", "C0034" to "rf", "C0037" to "lr", "C003A" to "rr")) {
            add(code, CHASSIS, CHECK_NOW, key = "wheel", args = mapOf("wheel" to k("cur.w.wheel_$wheel")))
        }
        add("C0077", CHASSIS, CHECK_NOW)
        add("B0001", BODY, CHECK_NOW, key = "airbag", args = mapOf("which" to k("cur.w.airbag_driver"), "extra" to k("cur.B0001.extra")))
        add("B0010", BODY, CHECK_NOW, key = "airbag", args = mapOf("which" to k("cur.w.airbag_passenger"), "extra" to k("cur.B0010.extra")))
    }
}
