package io.github.anwo91.phevlink.core.obd.dtc

import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n.Arg
import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n.Comp
import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n.Key
import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n.Lit
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity.CHECK_NOW
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity.CHECK_SOON
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity.INFO
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity.STOP
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.BODY
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.CHASSIS
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.COMPUTER_OUTPUTS
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.EMISSIONS
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.FUEL_AIR
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.HYBRID_EV
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.IGNITION_MISFIRE
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.MANUFACTURER
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.NETWORK
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.POWERTRAIN_ENGINE
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.SPEED_IDLE
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.TRANSMISSION
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem.UNKNOWN
import java.util.Locale

/**
 * Rule-based explanations for codes without a curated entry.
 *
 * All decisions (topic, failure type, severity, component name) are made on the English title; the
 * output text comes from string keys in `strings_<lang>.tsv`:
 *  - `topic.<id>.sub|about|note|adv` and numbered lists `topic.<id>.c1..`, `.cc1..` (extra causes for
 *    circuit faults) and `.k1..` (checks);
 *  - `fail.*` for the failure type in the title wording ("Circuit Low", "Range/Performance", ...);
 *  - slots `{component}`, `{subject}`, `{module}`, `{area}`, `{letter}` ... filled with localised values.
 */
internal object DtcRules {

    private const val MAX_CAUSES = 6
    private const val MAX_CHECKS = 7

    // ======================================================================================
    // Code number structure
    // ======================================================================================

    /** SAE J2012 manufacturer-controlled ranges: P1xxx, P3000–P33FF, and B/C/U 1xxx–2xxx. */
    fun isManufacturerRange(code: String): Boolean {
        val d1 = code[1]
        return when (code[0]) {
            'P' -> d1 == '1' || (d1 == '3' && code[2] in '0'..'3')
            else -> d1 == '1' || d1 == '2'
        }
    }

    /** The system implied by the code number alone. */
    fun systemByRange(code: String): DtcSystem {
        val d1 = code[1]
        val d2 = code[2]
        return when (code[0]) {
            'P' -> when (d1) {
                '0' -> when (d2) {
                    '0', '1', '2' -> FUEL_AIR
                    '3' -> IGNITION_MISFIRE
                    '4' -> EMISSIONS
                    '5' -> SPEED_IDLE
                    '6' -> COMPUTER_OUTPUTS
                    '7', '8', '9' -> TRANSMISSION
                    'A', 'B', 'C' -> HYBRID_EV
                    else -> UNKNOWN
                }
                '2' -> when (d2) {
                    '0', '1', '2', 'A' -> FUEL_AIR
                    '3' -> IGNITION_MISFIRE
                    '4' -> EMISSIONS
                    '5' -> SPEED_IDLE
                    '6' -> COMPUTER_OUTPUTS
                    '7', '8', '9' -> TRANSMISSION
                    else -> UNKNOWN
                }
                '1' -> MANUFACTURER
                '3' -> if (d2 in '0'..'3') MANUFACTURER else if (d2 == '4') POWERTRAIN_ENGINE else UNKNOWN
                else -> UNKNOWN
            }
            'C' -> if (d1 in '0'..'3') CHASSIS else UNKNOWN
            'B' -> if (d1 in '0'..'3') BODY else UNKNOWN
            'U' -> if (d1 in '0'..'3') NETWORK else UNKNOWN
            else -> UNKNOWN
        }
    }

    private fun isHybridRange(code: String) =
        code.startsWith("P0A") || code.startsWith("P0B") || code.startsWith("P0C")

    private val HYBRID: (String) -> Boolean = { isHybridRange(it) }

    // ======================================================================================
    // Entry point
    // ======================================================================================

    /**
     * @param title English title (drives all rules), or null when the code is not in the list.
     * @param displayTitle title shown to the user (localised), or null to use [title].
     */
    fun explain(code: String, title: String?, lang: String, displayTitle: String? = null): DtcExplanation {
        val r = Render(lang)
        return when {
            code[1] !in '0'..'3' -> r.impossibleNumber(code)
            title == null -> r.untitled(code)
            else -> r.fromTitle(code, title, displayTitle ?: title)
        }
    }

    /** Text rendering in one language. */
    private class Render(val lang: String) {

        fun t(key: String, vararg args: Pair<String, Arg>): String = DtcI18n.text(lang, key, mapOf(*args))

        fun list(base: String, vararg args: Pair<String, Arg>): List<String> = DtcI18n.list(lang, base, mapOf(*args))

        fun comp(phrase: String): String = DtcI18n.component(lang, phrase)

        // ---- codes without a title --------------------------------------------------------

        fun untitled(code: String): DtcExplanation {
            val mfr = isManufacturerRange(code)
            val kind = if (mfr) "mfr" else "std"
            val area = "area" to Key("area.${code[0]}")
            return DtcExplanation(
                code = code,
                title = t(if (mfr) "unk.title.mfr" else "unk.title.unknown"),
                system = systemByRange(code),
                subsystem = t("unk.sub.$kind", area),
                severity = CHECK_SOON,
                summary = t("unk.sum.$kind", area, "letter" to Lit(code[0].toString())),
                causes = listOf(t("unk.$kind.c1"), t("unk.c2"), t("unk.c3", area)),
                checks = list("unk.k"),
                driveAdvice = t("txt.advice_unknown"),
                manufacturerSpecific = mfr,
                curated = false,
                known = false,
            )
        }

        fun impossibleNumber(code: String) = DtcExplanation(
            code = code,
            title = t("unk.title.unknown"),
            system = UNKNOWN,
            subsystem = null,
            severity = CHECK_SOON,
            summary = t("unk.sum.impossible"),
            causes = list("unk.imp.c"),
            checks = list("unk.imp.k"),
            driveAdvice = t("txt.advice_unknown"),
            manufacturerSpecific = false,
            curated = false,
            known = false,
        )

        // ---- codes with a title -----------------------------------------------------------

        fun fromTitle(code: String, title: String, displayTitle: String): DtcExplanation {
            val topic = TOPICS.first { it.matches(code, title) }
            val id = topic.id
            val kind = kindOf(title)
            val failure = if (topic.condition) Failure.GENERAL else detectFailure(title)
            val derived = derivePart(title)
            val partEn = topic.part?.invoke(title) ?: derived
            val part: Arg = if (partEn.isEmpty()) Key("word.component") else Comp(partEn)
            val subject: Arg = if (derived.isEmpty()) part else Comp(derived)
            val module = moduleName(title)
            val slots = arrayOf("component" to part, "subject" to subject, "module" to Comp(module))
            val system = topic.system ?: systemByRange(code)
            val variant = topic.variant?.invoke(title)

            var severity = topic.severity(title)
            if (failure == Failure.OVER_TEMP && severity < CHECK_NOW) severity = CHECK_NOW

            // ---- subsystem
            val baseSub = when {
                DtcI18n.has("topic.$id.sub") -> t("topic.$id.sub", *slots)
                derived.isNotEmpty() -> capitalise(comp(derived))
                DtcI18n.has("topic.$id.sub_fallback") -> t("topic.$id.sub_fallback")
                else -> system.label(lang)
            }
            val loc = location(title, topic.o2)
            val subsystem = if (loc != null && !baseSub.contains(loc, ignoreCase = true)) "$baseSub, $loc" else baseSub

            // ---- summary (1–3 sentences)
            val sentences = ArrayList<String>(3)
            val aboutKey = if (variant != null) "topic.$id.about.$variant" else "topic.$id.about"
            if (DtcI18n.has(aboutKey)) {
                var about = t(aboutKey, *slots)
                if (topic.module) moduleEffect(module)?.let { about += " " + t(it) }
                sentences += about
            }
            if (!topic.condition) sentences += failureSentence(failure, kind, slots)
            val noteKey = topic.noteKey?.invoke(title) ?: "topic.$id.note".takeIf { DtcI18n.has(it) } ?: applicabilityNote(title)
            noteKey?.let { sentences += t(it) }
            if (sentences.isEmpty()) sentences += t("fail.fallback.sum", *slots)
            val summary = sentences.filter { it.isNotBlank() }.joinToString(" ")

            // ---- causes, most likely first
            val electrical = failure in ELECTRICAL
            val topicCauses = list(if (variant != null) "topic.$id.$variant.c" else "topic.$id.c", *slots)
            val causes = LinkedHashSet<String>()
            when {
                topic.condition -> causes += topicCauses
                electrical -> {
                    causes += failureCauses(failure, kind, slots)
                    causes += list("topic.$id.cc", *slots)
                    causes += topicCauses
                }
                else -> {
                    causes += topicCauses
                    causes += failureCauses(failure, kind, slots)
                }
            }
            if (causes.size < 2) causes += list("fail.generic.c")

            // ---- checks: safety first, then cheapest/easiest. No do-it-yourself connector work on HV or airbag parts.
            val checks = LinkedHashSet<String>()
            if (topic.hv) checks += t("txt.hv_safety")
            if (topic.srs) checks += t("txt.srs_safety")
            val typeChecks = when {
                topic.condition || topic.srs -> emptyList()
                topic.hv -> hvChecks(failure, slots)
                else -> failureChecks(failure, kind, slots, diy = system != TRANSMISSION)
            }
            val topicChecks = list("topic.$id.k", *slots)
            if (electrical) {
                checks += typeChecks
                checks += topicChecks
            } else {
                checks += topicChecks
                checks += typeChecks
            }
            if (checks.size < MAX_CHECKS && severity != INFO) checks += t("txt.check_clear_retest")
            if (checks.size < 2) checks += t("txt.check_dealer_tool")

            val adviceKey = topic.adviceKey?.invoke(severity, module)
                ?: "topic.$id.adv".takeIf { DtcI18n.has(it) }
                ?: "txt.advice_${severity.name.lowercase(Locale.ROOT)}"

            return DtcExplanation(
                code = code,
                title = displayTitle,
                system = system,
                subsystem = subsystem,
                severity = severity,
                summary = summary,
                causes = causes.filter { it.isNotBlank() }.take(MAX_CAUSES),
                checks = checks.filter { it.isNotBlank() }.take(MAX_CHECKS),
                driveAdvice = t(adviceKey, *slots),
                manufacturerSpecific = isManufacturerRange(code),
                curated = false,
                known = true,
            )
        }

        private fun location(title: String, o2: Boolean): String? {
            BANK_SENSOR.find(title)?.let { m ->
                val sensor = m.groupValues[2]
                val base = t("loc.bank_sensor", "bank" to Lit(m.groupValues[1]), "sensor" to Lit(sensor))
                val where = when {
                    !o2 -> null
                    sensor == "1" -> t("loc.o2_before")
                    sensor == "2" -> t("loc.o2_after")
                    else -> null
                }
                return if (where == null) base else "$base $where"
            }
            BANK.find(title)?.let { return t("loc.bank", "bank" to Lit(it.groupValues[1])) }
            CYLINDER.find(title)?.let { return t("loc.cylinder", "cyl" to Lit(it.groupValues[1])) }
            return null
        }

        private fun failureSentence(f: Failure, kind: Kind, slots: Array<Pair<String, Arg>>): String = when (f) {
            Failure.LOW -> t(if (kind == Kind.ACTUATOR) "fail.low.act.sum" else "fail.low.sen.sum", *slots)
            Failure.HIGH -> t(if (kind == Kind.ACTUATOR) "fail.high.act.sum" else "fail.high.sen.sum", *slots)
            Failure.OPEN -> t("fail.open.sum", *slots)
            Failure.CIRCUIT -> t("fail.circuit.${kind.key}.sum", *slots)
            Failure.INTERMITTENT -> t("fail.intermittent.sum", *slots)
            Failure.RANGE_PERF -> t(if (kind == Kind.SENSOR) "fail.range.sen.sum" else "fail.range.other.sum", *slots)
            Failure.SLOW -> t("fail.slow.sum", *slots)
            Failure.NO_ACTIVITY -> t("fail.noact.sum", *slots)
            Failure.STUCK -> t("fail.stuck.sum", *slots)
            Failure.CORRELATION -> t("fail.corr.sum", *slots)
            Failure.INTERNAL -> t("fail.internal.sum", *slots)
            Failure.PERFORMANCE -> t("fail.perf.sum", *slots)
            Failure.OVER_TEMP -> t("fail.overtemp.sum", *slots)
            Failure.TOO_LOW -> t("fail.toolow.sum", *slots)
            Failure.TOO_HIGH -> t("fail.toohigh.sum", *slots)
            Failure.LOST_COMM, Failure.INVALID_DATA, Failure.GENERAL -> t("fail.general.sum", *slots)
        }

        private fun failureCauses(f: Failure, kind: Kind, slots: Array<Pair<String, Arg>>): List<String> = when (f) {
            Failure.LOW -> list(if (kind == Kind.ACTUATOR) "fail.low.act.c" else "fail.low.sen.c", *slots)
            Failure.HIGH -> list(if (kind == Kind.ACTUATOR) "fail.high.act.c" else "fail.high.sen.c", *slots)
            Failure.OPEN, Failure.CIRCUIT -> list(if (kind == Kind.ACTUATOR) "fail.open.act.c" else "fail.open.sen.c", *slots)
            Failure.INTERMITTENT -> list("fail.intermittent.c", *slots)
            Failure.RANGE_PERF -> list(if (kind == Kind.SENSOR) "fail.range.sen.c" else "fail.range.other.c", *slots)
            Failure.SLOW -> list("fail.slow.c", *slots)
            Failure.NO_ACTIVITY -> list("fail.noact.c", *slots)
            Failure.STUCK -> list("fail.stuck.c", *slots)
            Failure.CORRELATION -> list("fail.corr.c", *slots)
            Failure.INTERNAL -> list("fail.internal.c", *slots)
            Failure.PERFORMANCE -> list("fail.perf.c", *slots)
            Failure.OVER_TEMP -> list("fail.overtemp.c", *slots)
            Failure.TOO_LOW, Failure.TOO_HIGH -> list("fail.measured.c", *slots)
            Failure.LOST_COMM, Failure.INVALID_DATA, Failure.GENERAL -> emptyList()
        }

        /** [diy] = false for parts buried inside an assembly (gearbox internals), where hands-on checks make no sense. */
        private fun failureChecks(f: Failure, kind: Kind, slots: Array<Pair<String, Arg>>, diy: Boolean): List<String> =
            when (f) {
                Failure.LOW, Failure.HIGH, Failure.OPEN, Failure.CIRCUIT, Failure.INTERMITTENT -> buildList {
                    add(t("fail.elec.k1", *slots))
                    add(t("fail.elec.k2", *slots))
                    if (f == Failure.INTERMITTENT) add(t("fail.elec.wiggle", *slots))
                    add(t("fail.elec.${kind.key}", *slots))
                    add(t("fail.elec.replace", *slots))
                }
                Failure.RANGE_PERF, Failure.PERFORMANCE, Failure.SLOW, Failure.NO_ACTIVITY, Failure.CORRELATION ->
                    list("fail.analyse.k", *slots)
                Failure.STUCK -> listOfNotNull(
                    t("fail.stuck.k1", *slots),
                    if (diy) t("fail.stuck.k2", *slots) else null,
                    t("fail.stuck.k3", *slots),
                )
                Failure.TOO_LOW, Failure.TOO_HIGH, Failure.OVER_TEMP -> list("fail.measured.k", *slots)
                Failure.INTERNAL -> list("fail.internal.k", *slots)
                Failure.LOST_COMM, Failure.INVALID_DATA, Failure.GENERAL -> list("fail.general.k", *slots)
            }

        private fun hvChecks(f: Failure, slots: Array<Pair<String, Arg>>): List<String> = when (f) {
            in ELECTRICAL -> list("fail.hv.elec.k", *slots)
            Failure.INTERNAL -> list("fail.hv.internal.k", *slots)
            else -> list("fail.hv.other.k", *slots)
        }

        private fun capitalise(s: String) =
            s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    // ======================================================================================
    // Failure types (from the title wording)
    // ======================================================================================

    private enum class Failure {
        LOST_COMM, INVALID_DATA, OVER_TEMP, LOW, HIGH, INTERMITTENT, RANGE_PERF, SLOW, NO_ACTIVITY, STUCK, OPEN,
        CORRELATION, INTERNAL, CIRCUIT, PERFORMANCE, TOO_LOW, TOO_HIGH, GENERAL,
    }

    private val ELECTRICAL = setOf(Failure.LOW, Failure.HIGH, Failure.OPEN, Failure.CIRCUIT, Failure.INTERMITTENT)

    private val FAILURE_PATTERNS: List<Pair<Failure, Regex>> = listOf(
        Failure.LOST_COMM to rx("Lost Communication"),
        Failure.INVALID_DATA to rx("Invalid Data"),
        Failure.OVER_TEMP to rx("""Over ?Temperature|Over-Temperature|Temperature Too High|Overheat"""),
        Failure.LOW to rx("""\b(?:Circuit|Sensor|Switch|Signal|Sense)(?: [A-H])? Low\b|\bLow Input\b|\bLow Voltage\b"""),
        Failure.HIGH to rx("""\b(?:Circuit|Sensor|Switch|Signal|Sense)(?: [A-H])? High\b|\bHigh Input\b|\bHigh Voltage\b(?! System)"""),
        Failure.INTERMITTENT to rx("Intermittent|Erratic"),
        Failure.RANGE_PERF to rx("""Range/Performance|Range Performance|out of range"""),
        Failure.SLOW to rx("Slow Response"),
        Failure.NO_ACTIVITY to rx("No Activity"),
        Failure.STUCK to rx("Stuck"),
        Failure.OPEN to rx("""/Open\b|\bCircuit Open\b|\bOpen Circuit\b|\bOpen\b"""),
        Failure.CORRELATION to rx("Correlation"),
        Failure.INTERNAL to rx(
            """Memory|Check ?Sum|Processor|\bRAM\b|\bROM\b|EEPROM|Software|Incompatib|Not Programmed|Configuration|Calibration|Watchdog|\bCOP\b|Internal""",
        ),
        Failure.CIRCUIT to rx("""\bCircuit\b|Malfunction"""),
        Failure.PERFORMANCE to rx("Performance|Efficiency|Deterioration"),
        Failure.TOO_LOW to rx("""Too Low|Lower Than|Below|Insufficient|\bLow\b|Under"""),
        Failure.TOO_HIGH to rx("""Too High|Higher Than|Above|\bHigh\b|Overspeed|Overboost|Excessive|\bOver\b"""),
    )

    private fun detectFailure(title: String): Failure =
        FAILURE_PATTERNS.firstOrNull { it.second.containsMatchIn(title) }?.first ?: Failure.GENERAL

    private enum class Kind(val key: String) { SENSOR("sen"), ACTUATOR("act"), OTHER("other") }

    private val HEATER = rx("""\bHeater\b""")
    private val SENSOR_WORDS = rx("""\b(?:Sensor|Switch|Sense)\b""")
    private val ACTUATOR_WORDS = rx(
        """\b(?:Solenoid|Relay|Injector|Coil|Pump|Fan|Valve|Actuator|Lamp|Contactor|Clutch|Glow Plug|Motor Control|Control Circuit|Indicator|Squib|Deployment|Pretensioner|Mount|Heater)\b""",
    )

    private fun kindOf(title: String): Kind = when {
        HEATER.containsMatchIn(title) -> Kind.ACTUATOR
        SENSOR_WORDS.containsMatchIn(title) -> Kind.SENSOR
        ACTUATOR_WORDS.containsMatchIn(title) -> Kind.ACTUATOR
        else -> Kind.OTHER
    }

    // ======================================================================================
    // Title helpers (English)
    // ======================================================================================

    private val PARENTHESES = Regex("""\s*\([^)]*\)""")
    private val QUALIFIERS = rx(
        """\s*-?\s*\bCylinder \d+\b|\s*/?\s*\bBank \d(?: Sensor [\dA-Z])?(?: or Single Sensor)?|\s*\bUnit \d\b""",
    )
    private val CUT = rx(
        """\s+(?:-\s|Control Circuit|Circuit|Range/Performance|Performance|Intermittent|Erratic|Malfunction|Stuck\b|""" +
            """Slow Response|No Activity|Correlation|Over ?Temperature|Temperature Too High|Too (?:Low|High)|Lower Than|""" +
            """Higher Than|Low\b|High\b|Below|Above|Not\b|Incorrect|Insufficient|Detected|Requested|Request|""" +
            """Unstable|Failure|Fault\b|Error|Overspeed|Condition)""",
    )
    private val TRAILING = rx("""(?:\s+(?:Control|-)|/)+$""")
    private val CAPITALISED_WORD = Regex("""\b([A-Z])([a-z]+)\b""")

    /** "Fuel Volume Regulator Control Circuit/Open" -> "fuel volume regulator" (English component phrase). */
    internal fun derivePart(title: String): String {
        var t = PARENTHESES.replace(title, "")
        t = QUALIFIERS.replace(t, "")
        val cut = CUT.find(t)
        if (cut != null && cut.range.first > 0) t = t.substring(0, cut.range.first)
        t = TRAILING.replace(t.trim(), "").trim()
        if (t.length < 3) return ""
        return CAPITALISED_WORD.replace(t) { it.value.lowercase(Locale.ROOT) }
    }

    private val BANK_SENSOR = Regex("""Bank (\d) Sensor (\d)""")
    private val BANK = Regex("""Bank (\d)""")
    private val CYLINDER = Regex("""Cylinder (\d+)""")
    private val BANK_2_PLUS = Regex("""Bank [2-4]\b""")
    private val CYLINDER_5_PLUS = Regex("""Cylinder (?:[5-9]|1[0-2])\b|Ignition Coil [E-L]\b""")

    private fun applicabilityNote(title: String): String? = when {
        BANK_2_PLUS.containsMatchIn(title) -> "note.bank2"
        CYLINDER_5_PLUS.containsMatchIn(title) -> "note.cyl5"
        else -> null
    }

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // ======================================================================================
    // Network helpers
    // ======================================================================================

    private val MODULE_IN_TITLE = rx("""(?:Lost Communication|Invalid Data Received)\s+(?:With|From)\s+(.+)$""")
    private val ECM_PCM = Regex("""^ECM/PCM(?: ([A-Z]))?$""")

    /** English module name from a "Lost Communication With ..." title, or "control module". */
    internal fun moduleName(title: String): String {
        val raw = MODULE_IN_TITLE.find(title)?.groupValues?.get(1)?.trim() ?: return "control module"
        ECM_PCM.find(raw)?.let { m ->
            val suffix = m.groupValues[1].let { if (it.isEmpty()) "" else " $it" }
            return "engine control module (ECM/PCM$suffix)"
        }
        if (raw == "TCM") return "transmission control module (TCM)"
        return CAPITALISED_WORD.replace(raw) { it.value.lowercase(Locale.ROOT) }
    }

    private val CRITICAL_MODULE = rx(
        """ECM|PCM|Engine|Hybrid|Drive Motor|Battery Energy|Brake|ABS|Anti-Lock|Steering|Restraint|Airbag|Vehicle Dynamics|""" +
            """Stability|DC to DC|AC to|Generator|Gateway|Powertrain|Motor Control|Transmission|TCM|Gear Shift|Inverter|Charger""",
    )
    private val HV_MODULE = rx("""Hybrid|Drive Motor|Battery Energy|DC to DC|AC to|Generator|Motor Control|Inverter|Charger""")
    private val ABS_MODULE = rx("Brake|ABS|Anti-Lock|Vehicle Dynamics|Stability")
    private val SRS_MODULE = rx("Restraint|Airbag")

    /** Key of the sentence describing what a lost/invalid module means for the driver, if any. */
    private fun moduleEffect(module: String): String? = when {
        ABS_MODULE.containsMatchIn(module) -> "net.effect.abs"
        rx("Steering").containsMatchIn(module) -> "net.effect.steering"
        SRS_MODULE.containsMatchIn(module) -> "net.effect.srs"
        rx("Cluster|Instrument").containsMatchIn(module) -> "net.effect.cluster"
        rx("HVAC|Climate").containsMatchIn(module) -> "net.effect.hvac"
        rx("ECM|PCM|Engine").containsMatchIn(module) -> "net.effect.engine"
        HV_MODULE.containsMatchIn(module) -> "net.effect.hv"
        else -> null
    }

    private fun networkAdviceKey(severity: DtcSeverity, module: String): String = when {
        HV_MODULE.containsMatchIn(module) -> "txt.advice_hv"
        ABS_MODULE.containsMatchIn(module) -> "txt.advice_abs"
        SRS_MODULE.containsMatchIn(module) -> "txt.advice_srs"
        severity == CHECK_SOON -> "txt.advice_network"
        else -> "txt.advice_check_now"
    }

    private val CONVENTIONAL_GEARBOX = rx(
        """Shift Solenoid|Torque Converter|Gear Ratio|Upshift|Downshift|Skip Shift|Overdrive|Line Pressure|""" +
            """Pressure Control Solenoid|Clutch Switch|Clutch Pedal|Manual|Tiptronic|Shift Lever|Gear Shift Module|""" +
            """Incorrect Gear|Gear [1-9]|Turbine Speed|Intermediate Shaft""",
    )
    private val LEAN = rx("""\bLean\b""")
    private val RICH = rx("""\bRich\b""")

    // ======================================================================================
    // Topics: which system a title belongs to. Text lives under topic.<id>.* in strings_<lang>.tsv.
    // ======================================================================================

    private class Topic(
        val id: String,
        val letters: String = "P",
        val match: Regex? = null,
        val range: (String) -> Boolean = { true },
        val system: DtcSystem? = null,
        val severity: (String) -> DtcSeverity = { CHECK_SOON },
        val condition: Boolean = false,
        val hv: Boolean = false,
        val srs: Boolean = false,
        val o2: Boolean = false,
        /** English component phrase used instead of the one derived from the title. */
        val part: ((String) -> String)? = null,
        /** Title names a module ("Lost Communication With ..."): append its effect to the summary. */
        val module: Boolean = false,
        /** Suffix selecting alternative about/cause keys (topic.<id>.about.<v>, topic.<id>.<v>.c1..). */
        val variant: ((String) -> String?)? = null,
        /** Note key decided from the title (overrides topic.<id>.note). */
        val noteKey: ((String) -> String?)? = null,
        /** Advice key from severity and English module name (overrides topic.<id>.adv). */
        val adviceKey: ((DtcSeverity, String) -> String)? = null,
    ) {
        fun matches(code: String, title: String): Boolean =
            code[0] in letters && range(code) && (match == null || match.containsMatchIn(title))
    }

    private fun sev(s: DtcSeverity): (String) -> DtcSeverity = { s }

    private val moduleSeverity: (String) -> DtcSeverity =
        { t -> if (CRITICAL_MODULE.containsMatchIn(moduleName(t))) CHECK_NOW else CHECK_SOON }
    private val gearboxNote: (String) -> String? = { t -> if (CONVENTIONAL_GEARBOX.containsMatchIn(t)) "note.gearbox" else null }

    private val TOPICS: List<Topic> = listOf(
        // ---- U: network
        Topic(
            "lost_comm", "U", rx("Lost Communication"), system = NETWORK, severity = moduleSeverity, condition = true,
            part = { moduleName(it) }, module = true, adviceKey = { s, m -> networkAdviceKey(s, m) },
        ),
        Topic(
            "invalid_data", "U", rx("Invalid Data"), system = NETWORK, severity = moduleSeverity, condition = true,
            part = { moduleName(it) }, module = true, adviceKey = { s, m -> networkAdviceKey(s, m) },
        ),
        Topic("no_codes", "U", rx("No trouble codes"), system = NETWORK, severity = sev(INFO), condition = true),
        Topic(
            "u_power", "U", rx("""Battery Voltage|Input Power|Ground|Ignition|Accessory Power|Improper Shutdown|Power"""),
            system = COMPUTER_OUTPUTS, condition = true,
        ),
        Topic(
            "u_module", "U", rx("""Control Module|Vehicle Identification Number|Software|Configuration"""),
            system = COMPUTER_OUTPUTS, condition = true,
        ),
        Topic("bus", "U", rx("""\bBus\b|\bCAN\b|Network|Communication|\bLIN\b|Gateway"""), system = NETWORK, condition = true),
        Topic("u_other", "U", system = NETWORK),

        // ---- P: warning-light requests
        Topic("mil_request", match = rx("""MIL Request|Requested MIL|MIL Illumination"""), condition = true),

        // ---- P0A–P0C: hybrid / EV
        Topic(
            "hv_isolation_fault", match = rx("Isolation Fault"), range = HYBRID, system = HYBRID_EV,
            severity = sev(CHECK_NOW), condition = true, hv = true,
        ),
        Topic("hv_isolation_sensor", match = rx("Isolation"), range = HYBRID, system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true),
        Topic(
            "hv_interlock", match = rx("""Inter-?Lock"""), range = HYBRID, system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true,
            part = { "high-voltage interlock loop" },
        ),
        Topic("hv_contactor", match = rx("Contactor|Precharge"), range = HYBRID, system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true),
        Topic(
            "hv_fuse", match = rx("High Voltage Fuse"), range = HYBRID, system = HYBRID_EV,
            severity = sev(CHECK_NOW), condition = true, hv = true,
        ),
        Topic(
            "batt_temp", match = rx("""Hybrid Battery (?:Pack )?(?:Air )?Temperature Sensor"""), range = HYBRID,
            system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true,
        ),
        Topic(
            "batt_cooling", match = rx("""Hybrid Battery Pack (?:Cooling Fan|Air Flow)"""), range = HYBRID,
            system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true,
        ),
        Topic(
            "batt_sense", match = rx("""Hybrid Battery Pack (?:Current Sensor|Voltage Sense)"""), range = HYBRID,
            system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true,
        ),
        Topic(
            "batt", match = rx("""Hybrid Battery|Battery Energy Control Module|Battery Power Off"""), range = HYBRID,
            system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true,
        ),
        Topic(
            "dcdc", match = rx("""DC/DC|14 Volt Power Module|DC to DC"""), range = HYBRID, system = HYBRID_EV,
            severity = sev(CHECK_NOW), hv = true,
        ),
        Topic(
            "pe_cooling", match = rx("""Motor Electronics|Inverter Cooling|Coolant Pump"""), range = HYBRID,
            system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true,
        ),
        Topic("inverter", match = rx("Inverter"), range = HYBRID, system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true),
        Topic(
            "motor_gen", match = rx("""Drive Motor|Generator|Motor Torque|Motor Control|Phase [UVW]"""), range = HYBRID,
            system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true,
        ),
        Topic(
            "engine_start", match = rx("Engine Failed to Start"), system = POWERTRAIN_ENGINE,
            severity = sev(CHECK_NOW), condition = true,
        ),
        Topic("engine_mount", match = rx("Engine Mount"), system = POWERTRAIN_ENGINE),
        Topic("aux_trans_pump", match = rx("Auxiliary Transmission Fluid Pump"), system = TRANSMISSION),
        Topic("hybrid_other", range = HYBRID, system = HYBRID_EV, severity = sev(CHECK_NOW), hv = true),

        // ---- P: equipment this car does not have
        Topic(
            "turbo", match = rx("""Turbo|Supercharger|\bBoost\b|Wastegate|Charge Air Cooler|Intercooler"""),
            system = FUEL_AIR, condition = true,
        ),
        Topic("glow", match = rx("Glow Plug|Intake Air Heater"), system = POWERTRAIN_ENGINE, condition = true),
        Topic(
            "diesel", match = rx("""Diesel|Particulate|Reductant|\bDPF\b|Cetane|Water in Fuel|Injection Pump"""),
            system = EMISSIONS, condition = true,
        ),
        Topic("nox", match = rx("NOx"), system = EMISSIONS, condition = true),
        Topic("cyl_deact", match = rx("Deactivation"), system = POWERTRAIN_ENGINE, condition = true),

        // ---- P: engine
        Topic("misfire", match = rx("Misfire"), system = IGNITION_MISFIRE, severity = sev(CHECK_NOW), condition = true),
        Topic(
            "cam_crank_corr",
            match = rx("""Crankshaft Position.{0,5}Camshaft Position Correlation|Engine Position System|Camshaft Position.{0,5}Crankshaft"""),
            system = POWERTRAIN_ENGINE, severity = sev(CHECK_NOW), condition = true,
        ),
        Topic("cam_sensor", match = rx("Camshaft Position Sensor"), system = POWERTRAIN_ENGINE, severity = sev(CHECK_NOW)),
        Topic(
            "vvt",
            match = rx("""Camshaft|Valve Control Solenoid|Valve Timing|Rocker Arm|Intake Valve Control|Exhaust Valve Control|Valve Lift"""),
            system = POWERTRAIN_ENGINE, severity = sev(CHECK_NOW),
        ),
        Topic(
            "crank", match = rx("""Crankshaft|Engine Speed Input|\bCrank\b|Reluctor|Timing Reference"""),
            system = POWERTRAIN_ENGINE, severity = sev(CHECK_NOW),
        ),
        Topic("knock", match = rx("Knock"), system = IGNITION_MISFIRE),
        Topic(
            "ignition",
            match = rx("""Ignition Coil|Spark|Ignition Control|Ignition Timing|Ignition Module|Ignition Circuit|Ignition Primary|Ignition Secondary"""),
            system = IGNITION_MISFIRE, severity = sev(CHECK_NOW),
        ),
        Topic(
            "o2_heater", match = rx("""(?:HO2S|O2 Sensor|Oxygen Sensor|A/F Sensor) Heater"""), system = FUEL_AIR, o2 = true,
            part = { "oxygen sensor heater" },
        ),
        Topic(
            "o2", match = rx("""O2 Sensor|HO2S|Oxygen Sensor|A/F Sensor|Air[ /-]Fuel Ratio Sensor|Lambda"""), system = FUEL_AIR,
            o2 = true, part = { "oxygen sensor" },
        ),
        Topic(
            "fuel_trim",
            match = rx("""Fuel Trim|Too Lean|Too Rich|\bLean\b|\bRich\b|Air[/-]Fuel Ratio Imbalance|Fuel Imbalance"""),
            system = FUEL_AIR, condition = true,
            variant = { t -> if (LEAN.containsMatchIn(t)) "lean" else if (RICH.containsMatchIn(t)) "rich" else null },
        ),
        Topic("secondary_air", match = rx("Secondary Air"), system = EMISSIONS),
        Topic(
            "intake_manifold", match = rx("""Intake Manifold Runner|Intake Manifold Tuning|\bIMT\b|Manifold Tuning"""),
            system = FUEL_AIR,
        ),
        Topic("maf", match = rx("""Mass or Volume Air Flow|Mass Air Flow|\bMAF\b|Air Flow Sensor"""), system = FUEL_AIR),
        Topic("map", match = rx("""Manifold Absolute Pressure|Barometric|\bMAP\b|Manifold Pressure"""), system = FUEL_AIR),
        Topic(
            "iat", match = rx("""Intake Air Temperature|Ambient Air Temperature|Charge Air Temperature|Air Temperature"""),
            system = FUEL_AIR,
        ),
        Topic(
            "engine_overtemp", match = rx("""(?:Engine|Coolant) (?:Coolant )?Over ?Temperature|Engine Over ?Temperature|Engine Hot"""),
            system = POWERTRAIN_ENGINE, severity = sev(STOP), condition = true,
        ),
        Topic("engine_overspeed", match = rx("Engine Overspeed"), system = POWERTRAIN_ENGINE, condition = true),
        Topic("thermostat", match = rx("""Thermostat|Insufficient Coolant Temperature"""), system = POWERTRAIN_ENGINE, condition = true),
        Topic("ect", match = rx("""Engine Coolant Temperature|Coolant Temperature Sensor|Coolant Temp"""), system = POWERTRAIN_ENGINE),
        Topic(
            "cooling", match = rx("""Coolant|Cooling Fan|\bFan\b|Radiator|Water Pump|Cooling System"""),
            system = POWERTRAIN_ENGINE, severity = sev(CHECK_NOW),
        ),
        Topic(
            "oil_low", match = rx("""Oil Pressure Too Low|Oil Pressure Low|Oil Level Too Low"""), system = POWERTRAIN_ENGINE,
            severity = sev(STOP), condition = true,
        ),
        Topic("oil", match = rx("""(?<!Hydraulic )\bOil\b"""), system = POWERTRAIN_ENGINE, severity = sev(CHECK_NOW)),
        Topic("egr", match = rx("""Exhaust Gas Recirculation|\bEGR\b"""), system = EMISSIONS),
        Topic("throttle", match = rx("""Throttle|Pedal Position|Accelerator"""), system = FUEL_AIR, severity = sev(CHECK_NOW)),
        Topic("idle", match = rx("Idle"), system = SPEED_IDLE),
        Topic("injector", match = rx("Injector|Injection|Contribution/Balance"), system = FUEL_AIR, severity = sev(CHECK_NOW)),
        Topic("fuel_pump", match = rx("Fuel Pump"), system = FUEL_AIR, severity = sev(CHECK_NOW)),
        Topic(
            "fuel_pressure",
            match = rx("""Fuel Rail|Fuel Pressure|Fuel System Pressure|Fuel Volume Regulator|Fuel Shutoff|Fuel System Leak|Fuel Delivery"""),
            system = FUEL_AIR, severity = sev(CHECK_NOW),
        ),
        Topic("evap_leak", match = rx("""(?:Evaporative|EVAP).*Leak|Fuel Cap|Filler Cap"""), system = EMISSIONS, condition = true),
        Topic(
            "evap",
            match = rx("""Evaporative|\bEVAP\b|Purge|Fuel Tank Pressure|Fuel Vapou?r|Fuel Tank Isolation|Vent Valve|Canister|Refueling|Fuel Filler"""),
            system = EMISSIONS,
        ),
        Topic("fuel_level", match = rx("Fuel Level"), system = FUEL_AIR),
        Topic("fuel_misc", match = rx("Fuel"), system = FUEL_AIR),
        Topic(
            "cat_eff", match = rx("""Catalyst.{0,30}Efficiency|Efficiency Below Threshold"""), system = EMISSIONS,
            condition = true,
        ),
        Topic("catalyst", match = rx("Catalyst|Catalytic"), system = EMISSIONS),
        Topic("pcv", match = rx("""Crankcase|\bPCV\b"""), system = EMISSIONS),
        Topic("exhaust", match = rx("Exhaust"), system = EMISSIONS),
        Topic("vss", match = rx("""Vehicle Speed|Wheel Speed"""), system = SPEED_IDLE),
        Topic("cruise", match = rx("""Cruise|Speed Control"""), system = SPEED_IDLE),
        Topic("brake_p", match = rx("Brake"), system = CHASSIS, severity = sev(CHECK_NOW)),
        Topic("power_steering_p", match = rx("Power Steering"), system = CHASSIS),
        Topic("ac_p", match = rx("""A/C|Air Conditioning|Air Conditioner|Refrigerant"""), system = BODY),
        Topic("alternator", match = rx("""Generator|Alternator|Charging System"""), system = COMPUTER_OUTPUTS, condition = true),
        Topic("voltage", match = rx("""System Voltage|\bBattery\b"""), system = COMPUTER_OUTPUTS, severity = sev(CHECK_NOW)),
        Topic(
            "sensor_ref", match = rx("""Reference Voltage|Sensor Reference|5 ?Volt Reference|5V Reference|Sensor Supply"""),
            system = COMPUTER_OUTPUTS, severity = sev(CHECK_NOW), condition = true,
        ),
        Topic("starter", match = rx("Starter"), system = POWERTRAIN_ENGINE),
        Topic(
            "power_relay",
            match = rx("""Power Relay|Main Relay|Ignition Switch|Keep Alive|Engine Off Timer|Power Supply|Power Mode|Run/Crank|Accessory"""),
            system = COMPUTER_OUTPUTS,
        ),
        Topic(
            "module_internal",
            match = rx("""Processor|Memory|Check ?Sum|\bROM\b|\bRAM\b|EEPROM|Watchdog|Internal Control Module|Internal Engine|Internal Temperature"""),
            system = COMPUTER_OUTPUTS, severity = sev(CHECK_NOW),
        ),
        Topic(
            "module",
            match = rx("""Control Module|ECM|PCM|TCM|Software|Incompatib|Programm|\bVIN\b|Calibration|Configuration|Immobili|Security|\bKey\b"""),
            system = COMPUTER_OUTPUTS,
        ),
        Topic(
            "transmission",
            match = rx(
                """Transmission|Shift|Gear|Clutch|Torque Converter|\bTCM\b|Transfer Case|PRNDL|Turbine|Output Shaft|""" +
                    """Input Shaft|Range Sensor|4WD|4x4|Four Wheel|\bPark\b|Pressure Control Solenoid|Differential|""" +
                    """Driveline|Overdrive|Line Pressure|Selector|Tiptronic|Torque Management|Intermediate Shaft|Hydraulic""",
            ),
            system = TRANSMISSION, noteKey = gearboxNote,
        ),
        Topic(
            "outputs", match = rx("""\bMIL\b|Malfunction Indicator|Lamp|Gauge|Tachometer|Speedometer|Indicator|Display|Output"""),
            system = COMPUTER_OUTPUTS,
        ),
        Topic("p_other", noteKey = gearboxNote),

        // ---- C: chassis
        Topic("c_wheel", "C", rx("""Wheel Speed|Tone Wheel|Speed Sensor"""), system = CHASSIS, severity = sev(CHECK_NOW)),
        Topic("c_brake_fluid", "C", rx("Brake Fluid"), system = CHASSIS, severity = sev(CHECK_NOW)),
        Topic("c_brake_temp", "C", rx("Brake Temperature"), system = CHASSIS, severity = sev(STOP), condition = true),
        Topic(
            "c_brake_switch", "C", rx("""Brake Pedal Switch|Brake Switch|Brake Pedal Position|Stop Lamp"""),
            system = CHASSIS, severity = sev(CHECK_NOW),
        ),
        Topic(
            "c_abs", "C",
            rx(
                """ABS|Anti-?Lock|Inlet Control|Outlet Control|Hydraulic|Pump Motor|Valve Relay|TCS|Traction|Stability|""" +
                    """Brake|Booster|Yaw|Lateral|Longitudinal|Accelerat|Roll Rate|Vehicle Dynamics|Hill|Driving Torque|Solenoid""",
            ),
            system = CHASSIS, severity = sev(CHECK_NOW),
        ),
        Topic("c_steering", "C", rx("Steering"), system = CHASSIS, severity = sev(CHECK_NOW)),
        Topic(
            "c_tyre_low", "C", rx("""Low Tire Pressure|Tire Pressure Low|Low Tyre"""), system = CHASSIS,
            severity = sev(CHECK_NOW), condition = true,
        ),
        Topic("c_tyre", "C", rx("Tire|Tyre"), system = CHASSIS),
        Topic("c_susp", "C", rx("""Suspension|Ride Height|Damp|Shock|\bLevel\b|Air Spring"""), system = CHASSIS),
        Topic("c_4wd", "C", rx("""Wheel Drive|4WD|All Wheel|AWD|Transfer"""), system = CHASSIS),
        Topic("c_other", "C", system = CHASSIS),

        // ---- B: body
        Topic(
            "b_srs", "B",
            rx(
                """Deployment|Air ?Bag|Seat ?belt|Pretensioner|Restraint|Occupant|Load Limiter|Frontal|Knee Bolster|Squib|""" +
                    """Crash|Impact|Side Sensor|Rollover""",
            ),
            system = BODY, severity = sev(CHECK_NOW), condition = true, srs = true,
        ),
        Topic(
            "b_climate", "B",
            rx(
                """A/C|Blower|Air Flow Control|Defrost|HVAC|Solar|In Air Temp|In-Car|Outside Air|Ambient|Evaporator|Heater|""" +
                    """Climate|Control Head|Recirculation|Cooling Fan|Compressor|Refrigerant|Mode Door|Blend|Air Mix|\bVent\b""",
            ),
            system = BODY,
        ),
        Topic(
            "b_power", "B",
            rx("""Device Power|Device Ground|Ignition \d|Ignition Run|Battery|System Voltage|Volt Reference|Power Mode|Run/Crank|Accessory|Ground"""),
            system = BODY,
        ),
        Topic(
            "b_module", "B",
            rx("""EEPROM|\bRAM\b|\bROM\b|Option Configuration|Watchdog|\bCOP\b|Check ?sum|Configuration|Programm|Calibration|Module|Internal|Software"""),
            system = BODY,
        ),
        Topic("b_security", "B", rx("""Security|Immobili|\bKey|Alarm|Theft|Transponder|Remote|Keyless|Passive|Entry"""), system = BODY),
        Topic("b_lights", "B", rx("""Lamp|Light|Turn Signal|Headlamp|Headlight|\bFog\b|Hazard|Indicator|Bulb"""), system = BODY),
        Topic("b_instruments", "B", rx("""Speedometer|Tachometer|Gauge|Cluster|Display|Chime|Odometer|Ajar|Warning"""), system = BODY),
        Topic(
            "b_comfort", "B",
            rx("""Door|Lock|Window|Seat|Mirror|Wiper|Washer|Horn|Sunroof|Roof|Trunk|Liftgate|Tailgate|Hood|Steering Wheel|Pedal"""),
            system = BODY,
        ),
        Topic(
            "b_audio", "B",
            rx("""Audio|Radio|Speaker|Antenna|Entertainment|Player|Changer|Navigation|Telephone|Phone|Media|Disc|Cassette|Video|Microphone"""),
            system = BODY, severity = sev(INFO),
        ),
        Topic("b_other", "B", system = BODY),
    )

    /** Topic ids, for tests and diagnostics. */
    internal fun topicIdFor(code: String, title: String): String = TOPICS.first { it.matches(code, title) }.id

    /** Every topic id, so tests can check that the English strings define their texts. */
    internal val topicIds: List<String> get() = TOPICS.map { it.id }

    /** English component phrases this rule set may insert into a `{component}`/`{subject}`/`{module}` slot. */
    internal fun componentPhrases(title: String, code: String): List<String> {
        val topic = TOPICS.first { it.matches(code, title) }
        return listOfNotNull(derivePart(title), topic.part?.invoke(title), moduleName(title)).filter { it.isNotEmpty() }
    }
}
