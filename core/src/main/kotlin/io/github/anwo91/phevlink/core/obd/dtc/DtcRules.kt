package io.github.anwo91.phevlink.core.obd.dtc

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
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_CHECK_NOW
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_HV
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_MISFIRE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_NETWORK
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_UNKNOWN
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CAUSE_12V
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_12V
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_CHARGE_MODE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_CLEAR_RETEST
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_CLEAR_SEE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_DEALER_TOOL
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_DONGLE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_FREEZE_FRAME
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_FRESH_FUEL
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_MANY_CODES
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_OIL
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.HV_SAFETY
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.SRS_SAFETY
import java.util.Locale

/**
 * Rule-based explanations for codes without a curated entry. The system comes from the SAE J2012
 * code structure and keywords in the title; causes and checks come from the system ("topic") and
 * from the failure type in the title wording ("Circuit Low", "Range/Performance", ...).
 */
internal object DtcRules {

    private const val MAX_CAUSES = 6
    private const val READ_ALL = "Read all codes; codes stored at the same time often show the cause"
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

    private fun areaOf(code: String): String = when (code[0]) {
        'P' -> "powertrain (engine, hybrid system or drive unit)"
        'C' -> "chassis (brakes, ABS, steering, suspension, tyres)"
        'B' -> "body (airbags, climate control, lights, locks and comfort)"
        else -> "network communication between control modules"
    }

    // ======================================================================================
    // Entry point
    // ======================================================================================

    fun explain(code: String, title: String?): DtcExplanation = when {
        code[1] !in '0'..'3' -> impossibleNumber(code)
        title == null && isManufacturerRange(code) -> manufacturer(code)
        title == null -> unlisted(code)
        else -> fromTitle(code, title)
    }

    // ---- codes without a title ----------------------------------------------------------

    private fun manufacturer(code: String): DtcExplanation {
        val area = areaOf(code)
        return DtcExplanation(
            code = code,
            title = "Manufacturer-specific code (Mitsubishi)",
            system = systemByRange(code),
            subsystem = "Mitsubishi-defined code: $area",
            severity = CHECK_SOON,
            summary = "This is a manufacturer-specific code: Mitsubishi defines what it means, and it is not part of the " +
                "public generic code list, so this app cannot say exactly what it means. The letter ${code[0]} shows it " +
                "belongs to the $area.",
            causes = listOf(
                "The exact cause depends on Mitsubishi's own definition of this code",
                "If several codes appeared at the same time: a weak 12 V auxiliary battery or a power/ground problem shared by several modules",
                "A genuine fault in the $area",
            ),
            checks = unknownChecks(),
            driveAdvice = ADVICE_UNKNOWN,
            manufacturerSpecific = true,
            curated = false,
            known = false,
        )
    }

    private fun unlisted(code: String): DtcExplanation {
        val area = areaOf(code)
        return DtcExplanation(
            code = code,
            title = "Unknown code",
            system = systemByRange(code),
            subsystem = "Standard code range: $area",
            severity = CHECK_SOON,
            summary = "This number is in the range for standard (SAE/ISO) codes, but it is not in this app's code list. " +
                "It may be a newer standard code, a reserved number used by the manufacturer, or a misread. " +
                "The letter ${code[0]} shows it belongs to the $area.",
            causes = listOf(
                "The exact cause depends on the official definition of this code",
                "If several codes appeared at the same time: a weak 12 V auxiliary battery or a power/ground problem shared by several modules",
                "A genuine fault in the $area",
            ),
            checks = unknownChecks(),
            driveAdvice = ADVICE_UNKNOWN,
            manufacturerSpecific = false,
            curated = false,
            known = false,
        )
    }

    private fun impossibleNumber(code: String) = DtcExplanation(
        code = code,
        title = "Unknown code",
        system = UNKNOWN,
        subsystem = null,
        severity = CHECK_SOON,
        summary = "A real trouble code always has 0, 1, 2 or 3 as its second character, so this code was probably " +
            "mistyped or misread by the scan tool.",
        causes = listOf(
            "Typing mistake when entering the code",
            "Scan tool or adapter misreading the code (try reading the codes again)",
        ),
        checks = listOf(
            "Check the code on the scan tool display and enter it again",
            "Read the codes again with the car switched on; if it still shows, try another scan tool or app",
            CHECK_DEALER_TOOL,
        ),
        driveAdvice = ADVICE_UNKNOWN,
        manufacturerSpecific = false,
        curated = false,
        known = false,
    )

    private fun unknownChecks() = listOf(
        CHECK_FREEZE_FRAME,
        CHECK_MANY_CODES,
        CHECK_CLEAR_SEE,
        "Have a Mitsubishi dealer or a workshop with a Mitsubishi-compatible diagnostic tool (MUT-III or equivalent) " +
            "read it; they can see the exact definition and which control unit stored it",
        "Search for the code together with 'Outlander PHEV' in owner forums to see other owners' experiences " +
            "(treat forum advice with care)",
    )

    // ======================================================================================
    // Codes with a title
    // ======================================================================================

    private fun fromTitle(code: String, title: String): DtcExplanation {
        val topic = TOPICS.first { it.matches(code, title) }
        val kind = kindOf(title)
        val failure = if (topic.condition) Failure.GENERAL else detectFailure(title)
        val derived = derivePart(title)
        val part = topic.part?.invoke(title) ?: derived.ifEmpty { "component" }
        val subject = derived.ifEmpty { part }
        val system = topic.system ?: systemByRange(code)

        var severity = topic.severity(title)
        if (failure == Failure.OVER_TEMP && severity < CHECK_NOW) severity = CHECK_NOW

        // ---- subsystem
        val baseSub = topic.subsystem?.invoke(title) ?: capitalise(derived.ifEmpty { system.label })
        val loc = location(title, topic.o2)
        val subsystem = if (loc != null && !baseSub.contains(loc, ignoreCase = true)) "$baseSub, $loc" else baseSub

        // ---- summary (1–3 sentences)
        val sentences = ArrayList<String>(3)
        topic.about(title)?.let { sentences += it }
        if (!topic.condition) sentences += failureSentence(failure, kind, part, subject)
        (topic.note(title) ?: applicabilityNote(title))?.let { sentences += it }
        if (sentences.isEmpty()) sentences += "The control module has set this code for the $part."
        val summary = sentences.joinToString(" ")

        // ---- causes, most likely first
        val electrical = failure in ELECTRICAL
        val causes = LinkedHashSet<String>()
        when {
            topic.condition -> causes += topic.causes(title)
            electrical -> {
                causes += failureCauses(failure, kind, part)
                causes += topic.circuitCauses
                causes += topic.causes(title)
            }
            else -> {
                causes += topic.causes(title)
                causes += failureCauses(failure, kind, part)
            }
        }
        if (causes.size < 2) causes += GENERIC_CAUSES

        // ---- checks: safety first, then cheapest/easiest. No do-it-yourself connector work on HV or airbag parts.
        val checks = LinkedHashSet<String>()
        if (topic.hv) checks += HV_SAFETY
        if (topic.srs) checks += SRS_SAFETY
        val typeChecks = when {
            topic.condition || topic.srs -> emptyList()
            topic.hv -> hvChecks(failure, part)
            else -> failureChecks(failure, kind, part, diy = system != TRANSMISSION)
        }
        if (electrical) {
            checks += typeChecks
            checks += topic.checks(title)
        } else {
            checks += topic.checks(title)
            checks += typeChecks
        }
        if (checks.size < MAX_CHECKS && severity != INFO) checks += CHECK_CLEAR_RETEST
        if (checks.size < 2) checks += CHECK_DEALER_TOOL

        val advice = topic.advice?.invoke(severity, title) ?: DtcText.adviceFor(severity)

        return DtcExplanation(
            code = code,
            title = title,
            system = system,
            subsystem = subsystem,
            severity = severity,
            summary = summary,
            causes = causes.take(MAX_CAUSES),
            checks = checks.take(MAX_CHECKS),
            driveAdvice = advice,
            manufacturerSpecific = isManufacturerRange(code),
            curated = false,
            known = true,
        )
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

    private enum class Kind { SENSOR, ACTUATOR, OTHER }

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

    private fun failureSentence(f: Failure, kind: Kind, part: String, subject: String): String = when (f) {
        Failure.LOW -> if (kind == Kind.ACTUATOR) {
            "The voltage on the control circuit of the $part is lower than expected, which usually points to a short to ground or a missing supply."
        } else {
            "The signal from the $part is below the lowest value it should ever send, which usually points to a short to ground, a missing supply or a faulty sensor."
        }
        Failure.HIGH -> if (kind == Kind.ACTUATOR) {
            "The voltage on the control circuit of the $part is higher than expected, which usually points to an open circuit or a short to a voltage supply."
        } else {
            "The signal from the $part is above the highest value it should ever send, which usually points to an open circuit (broken wire, unplugged connector), a short to a voltage supply or a faulty sensor."
        }
        Failure.OPEN -> "The control module has found an open circuit (a broken electrical connection) in the circuit of the $part."
        Failure.CIRCUIT -> when (kind) {
            Kind.SENSOR -> "The control module has found an electrical fault in the $part circuit: the signal is missing or outside what the circuit can produce."
            Kind.ACTUATOR -> "The control module has found an electrical fault in the circuit of the $part, typically an open circuit, a short or a failed part."
            Kind.OTHER -> "The control module has found an electrical fault in the $part circuit."
        }
        Failure.INTERMITTENT -> "The signal from the $part drops out or jumps now and then, which is typical of a loose connection or a damaged wire."
        Failure.RANGE_PERF -> if (kind == Kind.SENSOR) {
            "The $part signal is within its electrical limits but does not make sense compared with other readings, so the sensor may be drifting or the system it measures may have a real problem."
        } else {
            "The $part does not respond the way the control module expects."
        }
        Failure.SLOW -> "The $part reacts more slowly than it should when conditions change."
        Failure.NO_ACTIVITY -> "The $part signal does not change at all, as if the sensor were dead or disconnected."
        Failure.STUCK -> "The $part appears to be stuck: it does not move to, or stay in, the commanded position or state."
        Failure.CORRELATION -> "Two related signals ($subject) do not agree with each other as they should."
        Failure.INTERNAL -> "A control module has found a fault in its own memory, software or configuration."
        Failure.PERFORMANCE -> "The $part, or the system it belongs to, is not performing as well as the control module expects."
        Failure.OVER_TEMP -> "The $subject has become hotter than its safe limit."
        Failure.TOO_LOW -> "The $subject is lower than the control module expects."
        Failure.TOO_HIGH -> "The $subject is higher than the control module expects."
        Failure.LOST_COMM, Failure.INVALID_DATA, Failure.GENERAL -> "The control module has detected a fault related to the $part."
    }

    private fun failureCauses(f: Failure, kind: Kind, part: String): List<String> = when (f) {
        Failure.LOW -> if (kind == Kind.ACTUATOR) {
            listOf(
                "Control wire to the $part shorted to ground",
                "Faulty $part (internal short)",
                "Blown fuse or failed relay in the supply to the $part",
                "Damaged or corroded connector",
            )
        } else {
            listOf(
                "Signal wire shorted to ground, for example chafed insulation",
                "Faulty $part",
                "Damaged or corroded connector at the $part",
                "Missing or low supply/reference voltage to the sensor",
            )
        }
        Failure.HIGH -> if (kind == Kind.ACTUATOR) {
            listOf(
                "Control wire shorted to a voltage supply",
                "Open circuit or unplugged connector at the $part",
                "Faulty $part",
                "Fault in the control module output (less common)",
            )
        } else {
            listOf(
                "Open circuit: broken wire or unplugged/loose connector at the $part",
                "Signal wire shorted to a voltage supply",
                "Faulty $part",
                "Poor ground connection for the sensor",
            )
        }
        Failure.OPEN, Failure.CIRCUIT -> if (kind == Kind.ACTUATOR) {
            listOf(
                "Unplugged, loose or corroded connector at the $part",
                "Broken or chafed wire between the $part and its control module",
                "Faulty $part (open winding or element)",
                "Blown fuse in the supply to the $part",
            )
        } else {
            listOf(
                "Unplugged, loose or corroded connector at the $part",
                "Broken or chafed wiring between the $part and the control module",
                "Faulty $part",
                "Supply or ground problem for the $part",
            )
        }
        Failure.INTERMITTENT -> listOf(
            "Loose connector or pins that lose contact with vibration or temperature changes",
            "Chafed wire that shorts or breaks now and then",
            "Moisture or corrosion in the connector",
            "The $part beginning to fail internally",
        )
        Failure.RANGE_PERF -> if (kind == Kind.SENSOR) {
            listOf(
                "Drifting, dirty or ageing $part",
                "A real fault in the system the sensor measures",
                "High resistance in the wiring or connector (corrosion)",
                "Other faults present at the same time that upset the comparison",
            )
        } else {
            listOf(
                "The $part sticking, worn or contaminated",
                "Weak supply voltage or poor connection to the $part",
                "Faulty $part",
            )
        }
        Failure.SLOW -> listOf(
            "Ageing $part (normal wear over many years)",
            "Contamination of the $part (oil, coolant, deposits or additives)",
            "Wiring or connector problem",
            "Leak near the sensor that dilutes the measurement (for example an exhaust or air leak)",
        )
        Failure.NO_ACTIVITY -> listOf(
            "Faulty $part",
            "Open circuit or unplugged connector",
            "For heated sensors: a heater fault that keeps the sensor too cold to work",
        )
        Failure.STUCK -> listOf(
            "The $part is mechanically sticking (dirt, deposits, corrosion or ice)",
            "Faulty $part",
            "Wiring or connector problem preventing operation",
            "Fault in the parts it moves or controls",
        )
        Failure.CORRELATION -> listOf(
            "One of the two sensors is faulty or drifting",
            "Wiring or connector problem on one of the signals",
            "A real mechanical problem making the two readings disagree",
        )
        Failure.INTERNAL -> listOf(
            "Internal fault in the control module",
            "Low or unstable 12 V supply during start, charging or programming",
            "Interrupted or incompatible software update, or a module not configured after replacement",
            "Poor ground connection to the module",
        )
        Failure.PERFORMANCE -> listOf(
            "Worn or faulty $part",
            "Wiring or connector problem",
            "Related faults in the same system (check other codes)",
        )
        Failure.OVER_TEMP -> listOf(
            "Cooling problem: low coolant, failed pump or fan, blocked radiator or air intake",
            "Heavy load in hot conditions",
            "Faulty temperature sensor giving a false high reading",
        )
        Failure.TOO_LOW, Failure.TOO_HIGH -> listOf(
            "A real fault in the $part or the system around it",
            "Faulty sensor giving a wrong reading",
            "Wiring or connector problem",
        )
        Failure.LOST_COMM, Failure.INVALID_DATA, Failure.GENERAL -> emptyList()
    }

    /** [diy] = false for parts buried inside an assembly (gearbox internals), where hands-on checks make no sense. */
    private fun failureChecks(f: Failure, kind: Kind, part: String, diy: Boolean): List<String> = when (f) {
        Failure.LOW, Failure.HIGH, Failure.OPEN, Failure.CIRCUIT, Failure.INTERMITTENT -> buildList {
            add(
                "Inspect the $part connector: with the car switched off, unplug it, look for corrosion, water or bent " +
                    "pins, then refit it firmly until it latches",
            )
            add("Follow the wiring from the $part and look for chafing, melted insulation or rodent damage")
            if (f == Failure.INTERMITTENT) {
                add(
                    "With the engine running (or the car in READY), gently wiggle the wiring and connector while watching " +
                        "live data or the warning light; a change shows where the fault is",
                )
            }
            when (kind) {
                Kind.SENSOR -> add("With a multimeter and the wiring diagram, check the sensor's supply, ground and signal at the connector")
                Kind.ACTUATOR -> add("Check the fuse for the $part and measure its resistance against the service data")
                Kind.OTHER -> add("Have a workshop measure the circuit against the wiring diagram")
            }
            add("Replace the $part only after the wiring has been confirmed good")
        }
        Failure.RANGE_PERF, Failure.PERFORMANCE, Failure.SLOW, Failure.NO_ACTIVITY, Failure.CORRELATION -> listOf(
            "Read all codes and fix circuit faults in the same system first",
            "Compare the live data for the $part with related readings and with the expected values",
            "Inspect the connector and wiring for corrosion or damage",
        )
        Failure.STUCK -> listOfNotNull(
            "Read all codes and fix circuit faults in the same system first",
            if (diy) "Check that the $part moves freely and is not blocked by dirt, deposits or ice (only if it is easy to reach)" else null,
            "A workshop can operate the $part with an actuator test",
        )
        Failure.TOO_LOW, Failure.TOO_HIGH, Failure.OVER_TEMP -> listOf(
            READ_ALL,
            "Check the related live data to see whether the reading is real or caused by a faulty sensor",
        )
        Failure.INTERNAL -> listOf(
            CHECK_12V,
            CHECK_CLEAR_SEE,
            "Ask a Mitsubishi dealer about software updates, configuration or re-programming of the module",
        )
        Failure.LOST_COMM, Failure.INVALID_DATA, Failure.GENERAL -> listOf(
            CHECK_FREEZE_FRAME,
            READ_ALL,
        )
    }

    private fun hvChecks(f: Failure, part: String): List<String> = when (f) {
        in ELECTRICAL -> listOf(
            "Do not unplug connectors on or near high-voltage parts yourself; have a high-voltage-trained technician " +
                "inspect the wiring and connectors of the $part",
            "Note when the fault appears (start-up, driving, charging, wet weather) to help the workshop",
            CHECK_12V,
        )
        Failure.INTERNAL -> listOf(
            CHECK_12V,
            "Ask a Mitsubishi dealer about software updates, configuration or re-programming of the control unit",
        )
        else -> listOf(
            READ_ALL,
            "Note when the fault appears (start-up, driving, charging, wet weather) to help the workshop",
        )
    }

    private val GENERIC_CAUSES = listOf(
        "Faulty component in this circuit",
        "Wiring or connector problem (corrosion, loose pins, chafed insulation)",
        "Related fault elsewhere in the same system (check other stored codes)",
    )

    // ======================================================================================
    // Title helpers
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

    /** "Fuel Volume Regulator Control Circuit/Open" -> "fuel volume regulator". */
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

    private fun location(title: String, o2: Boolean): String? {
        BANK_SENSOR.find(title)?.let { m ->
            val sensor = m.groupValues[2]
            val where = when {
                !o2 -> ""
                sensor == "1" -> " (before the catalytic converter)"
                sensor == "2" -> " (after the catalytic converter)"
                else -> ""
            }
            return "bank ${m.groupValues[1]} sensor $sensor$where"
        }
        BANK.find(title)?.let { return "bank ${it.groupValues[1]}" }
        CYLINDER.find(title)?.let { return "cylinder ${it.groupValues[1]}" }
        return null
    }

    private val BANK_2_PLUS = Regex("""Bank [2-4]\b""")
    private val CYLINDER_5_PLUS = Regex("""Cylinder (?:[5-9]|1[0-2])\b|Ignition Coil [E-L]\b""")

    private fun applicabilityNote(title: String): String? = when {
        BANK_2_PLUS.containsMatchIn(title) ->
            "Bank 2 only exists on engines with two rows of cylinders (V-engines); the Outlander PHEV's four-cylinder " +
                "engine has only bank 1, so this code is not expected on this car and may be a misread."
        CYLINDER_5_PLUS.containsMatchIn(title) ->
            "The Outlander PHEV's engine has four cylinders, so this code is not expected on this car and may be a misread."
        else -> null
    }

    private fun capitalise(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // ======================================================================================
    // Network helpers
    // ======================================================================================

    private val MODULE_IN_TITLE = rx("""(?:Lost Communication|Invalid Data Received)\s+(?:With|From)\s+(.+)$""")
    private val ECM_PCM = Regex("""^ECM/PCM(?: ([A-Z]))?$""")

    private fun moduleName(title: String): String {
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

    private fun moduleEffect(module: String): String? = when {
        rx("Brake|ABS|Anti-Lock|Vehicle Dynamics|Stability").containsMatchIn(module) ->
            "ABS, traction or stability control may be switched off; normal braking should still work."
        rx("Steering").containsMatchIn(module) -> "Steering assistance or steering-angle based functions may be affected."
        rx("Restraint|Airbag").containsMatchIn(module) -> "The airbag warning light will usually be on, and airbags may not deploy in a crash."
        rx("Cluster|Instrument").containsMatchIn(module) -> "Gauges, warning lights or messages may not be shown correctly."
        rx("HVAC|Climate").containsMatchIn(module) -> "Heating, air conditioning or windscreen demisting may not work properly."
        rx("ECM|PCM|Engine").containsMatchIn(module) -> "The engine may not start or may run with reduced function."
        HV_MODULE.containsMatchIn(module) -> "The car may limit power or charging, show an EV system warning, or not go into READY."
        else -> null
    }

    private val NETWORK_CAUSES_TAIL = listOf(
        "Blown fuse, failed relay or poor ground at the module",
        "Loose, wet or corroded connector at the module or on the network wiring",
        "Damaged network (CAN) wiring, for example a chafed or pinched harness",
        "The module itself has failed (less common; rule out power and wiring first)",
    )

    private val NETWORK_CHECKS = listOf(
        CHECK_MANY_CODES,
        CHECK_12V,
        "Check the fuses for the module named in the code (owner's manual fuse chart)",
        "Look for water ingress or corrosion around the module and its connectors",
        "Clear all codes, drive, and read again: codes that come back point to the real problem",
        CHECK_DONGLE,
        "A workshop can check the network wiring and terminating resistors (a healthy CAN bus normally measures about " +
            "60 ohms between CAN-High and CAN-Low with the car switched off)",
    )

    private fun networkAdvice(severity: DtcSeverity, module: String): String = when {
        HV_MODULE.containsMatchIn(module) -> ADVICE_HV
        rx("Brake|ABS|Anti-Lock|Vehicle Dynamics|Stability").containsMatchIn(module) -> ADVICE_ABS
        rx("Restraint|Airbag").containsMatchIn(module) -> ADVICE_SRS
        severity == CHECK_SOON -> ADVICE_NETWORK
        else -> ADVICE_CHECK_NOW
    }

    // ======================================================================================
    // Topics
    // ======================================================================================

    private const val ADVICE_ABS =
        "Normal braking still works, but ABS, traction and stability control may be switched off, so wheels can lock " +
            "under hard braking. Drive carefully, especially on wet, icy or snowy roads, and get it checked within days."
    private const val ADVICE_SRS =
        "The car drives normally, but the airbags or seatbelt pretensioners may not protect you in a crash. Get it " +
            "repaired as soon as possible (within days)."
    private const val ADVICE_THROTTLE =
        "The car may limit engine power (limp mode) to stay safe; a pedal sensor fault can also limit electric driving. " +
            "Drive carefully, avoid situations where you need full power, and get it checked within days."
    private const val ADVICE_STEERING =
        "If the steering is heavy or behaves oddly, drive slowly and carefully to a workshop or arrange recovery. If it " +
            "feels normal, get it checked within days."
    private const val ADVICE_COOLING =
        "Watch the temperature warning closely. If it comes on, or steam appears, stop safely and switch off. Avoid " +
            "slow traffic, hot weather and towing until it is fixed, and get it checked within days."
    private const val ADVICE_OIL_LOW =
        "No. Stop as soon as it is safe and switch off. Check the oil level: if it was low and the warning goes away " +
            "after topping up, drive gently to a workshop; otherwise arrange recovery. Remember that the car can start " +
            "the engine automatically while in READY."
    private const val ADVICE_ISOLATION =
        "Follow the dashboard warnings. If the car shows a message to stop the vehicle, or will not go into READY, stop " +
            "safely, do not keep restarting it and arrange recovery. If the car drives normally, get it checked within " +
            "days and avoid deep water. Never touch damaged orange cables."
    private const val ADVICE_DCDC =
        "Limit driving and get it checked within days. If the 12 V battery is not being charged it runs down while you " +
            "drive, and the car can shut down without much warning. Switch off unnecessary electrical loads."
    private const val ADVICE_BRAKE_SWITCH =
        "The brakes themselves normally still work, but brake lights, cruise control or the shift lock may be affected. " +
            "Check that the brake lights work (ask someone to look) and get it checked within days."

    private const val NOTE_TURBO =
        "The 2014–2017 Outlander PHEV has a naturally aspirated 2.0-litre petrol engine without turbo- or supercharger, " +
            "so this code is not expected on this car; have it confirmed with a Mitsubishi-compatible tool."
    private const val NOTE_DIESEL =
        "This concerns diesel engine equipment, which the Outlander PHEV's petrol engine does not have, so this code is " +
            "not expected on this car; have it confirmed with a Mitsubishi-compatible tool."
    private const val NOTE_NOX =
        "NOx sensors and NOx after-treatment are mainly used on diesel and lean-burn engines and are not expected on the " +
            "Outlander PHEV's petrol engine; have the code confirmed with a Mitsubishi-compatible tool."
    private const val NOTE_CYL_DEACT =
        "The Outlander PHEV's four-cylinder engine does not use cylinder deactivation, so this code is not expected on " +
            "this car; have it confirmed with a Mitsubishi-compatible tool."
    private const val NOTE_ALTERNATOR =
        "The Outlander PHEV has no conventional alternator (the DC/DC converter charges the 12 V battery), so this code " +
            "is not expected on this car; check the 12 V battery, look for DC/DC converter codes, and have it confirmed " +
            "with a Mitsubishi-compatible tool."
    private const val NOTE_GEARBOX =
        "The Outlander PHEV has no conventional multi-speed gearbox (it uses a fixed-ratio transaxle with a clutch for " +
            "direct engine drive at higher speeds), so this code may not apply; have it confirmed with a Mitsubishi-compatible tool."

    private val LEAN = rx("""\bLean\b""")
    private val RICH = rx("""\bRich\b""")

    private val CONVENTIONAL_GEARBOX = rx(
        """Shift Solenoid|Torque Converter|Gear Ratio|Upshift|Downshift|Skip Shift|Overdrive|Line Pressure|""" +
            """Pressure Control Solenoid|Clutch Switch|Clutch Pedal|Manual|Tiptronic|Shift Lever|Gear Shift Module|""" +
            """Incorrect Gear|Gear [1-9]|Turbine Speed|Intermediate Shaft""",
    )

    private const val HV_CAUSE_12V_RELAY = "Low 12 V supply voltage to the control electronics or relays"

    private val NOT_FITTED_CAUSES: (String) -> List<String> = {
        listOf(
            "The scan tool misread the code, or it was entered wrongly",
            "A fault in the component the code refers to (only possible if the car actually has it)",
        )
    }
    private val NOT_FITTED_CHECKS: (String) -> List<String> = {
        listOf(
            "Read the codes again and compare with what the scan tool shows",
            CHECK_DEALER_TOOL,
        )
    }

    private val PE_COOLANT_CHECK =
        "With everything cold, check the coolant level in the power electronics reservoir (plug-in hybrids normally have " +
            "a separate circuit from the engine; see the owner's manual). Never open a hot cooling system"

    private class Topic(
        val id: String,
        val letters: String = "P",
        val match: Regex? = null,
        val range: (String) -> Boolean = { true },
        val system: DtcSystem? = null,
        val subsystem: ((String) -> String)? = null,
        val part: ((String) -> String)? = null,
        val about: (String) -> String? = { null },
        val causes: (String) -> List<String> = { emptyList() },
        val circuitCauses: List<String> = emptyList(),
        val checks: (String) -> List<String> = { emptyList() },
        val severity: (String) -> DtcSeverity = { CHECK_SOON },
        val condition: Boolean = false,
        val hv: Boolean = false,
        val srs: Boolean = false,
        val o2: Boolean = false,
        val advice: ((DtcSeverity, String) -> String)? = null,
        val note: (String) -> String? = { null },
    ) {
        fun matches(code: String, title: String): Boolean =
            code[0] in letters && range(code) && (match == null || match.containsMatchIn(title))
    }

    private fun fixed(s: String): (String) -> String = { s }
    private fun list(vararg items: String): (String) -> List<String> = { items.toList() }
    private fun sev(s: DtcSeverity): (String) -> DtcSeverity = { s }
    private fun adv(s: String): (DtcSeverity, String) -> String = { _, _ -> s }

    private val TOPICS: List<Topic> = listOf(
        // ---------------------------------------------------------------- U: network
        Topic(
            "lost_comm", letters = "U", match = rx("Lost Communication"), system = NETWORK,
            subsystem = { t -> "Lost communication with the ${moduleName(t)}" },
            part = { t -> moduleName(t) },
            about = { t ->
                val m = moduleName(t)
                "Control modules share information over the car's data network (CAN bus), and messages from the $m " +
                    "stopped arriving." + (moduleEffect(m)?.let { " $it" } ?: "")
            },
            causes = { t -> listOf(CAUSE_12V) + NETWORK_CAUSES_TAIL.map { it.replace("the module", "the ${moduleName(t)}") } },
            checks = { NETWORK_CHECKS },
            severity = { t -> if (CRITICAL_MODULE.containsMatchIn(moduleName(t))) CHECK_NOW else CHECK_SOON },
            condition = true,
            advice = { s, t -> networkAdvice(s, moduleName(t)) },
        ),
        Topic(
            "invalid_data", letters = "U", match = rx("Invalid Data"), system = NETWORK,
            subsystem = { t -> "Invalid data from the ${moduleName(t)}" },
            part = { t -> moduleName(t) },
            about = { t ->
                val m = moduleName(t)
                "Messages from the $m arrive over the data network, but contain values that are implausible or out of " +
                    "range." + (moduleEffect(m)?.let { " $it" } ?: "")
            },
            causes = { t ->
                val m = moduleName(t)
                listOf(
                    "A fault inside the $m, or in one of its sensors, making it send implausible values (look for codes stored in that module)",
                    CAUSE_12V,
                    "Software mismatch or missing configuration after a module was replaced or updated",
                    "Poor connections or interference on the network wiring",
                )
            },
            checks = { t ->
                listOf(
                    "Have the ${moduleName(t)} read with a capable tool: its own codes usually show the root cause",
                    CHECK_MANY_CODES,
                    CHECK_12V,
                    "Think back: was any module replaced, updated or disconnected recently?",
                    CHECK_DEALER_TOOL,
                )
            },
            severity = { t -> if (CRITICAL_MODULE.containsMatchIn(moduleName(t))) CHECK_NOW else CHECK_SOON },
            condition = true,
            advice = { s, t -> networkAdvice(s, moduleName(t)) },
        ),
        Topic(
            "no_codes", letters = "U", match = rx("No trouble codes"), system = NETWORK,
            subsystem = fixed("No fault stored"),
            about = fixed("This entry means 'no trouble codes stored'; it is not a fault."),
            causes = list("Reported by some modules as a placeholder when no fault is stored", "Possibly a misread by the scan tool"),
            checks = list("No action needed", "Read the codes again to confirm"),
            severity = sev(INFO), condition = true,
        ),
        Topic(
            "u_power", letters = "U",
            match = rx("""Battery Voltage|Input Power|Ground|Ignition|Accessory Power|Improper Shutdown|Power"""),
            system = COMPUTER_OUTPUTS, subsystem = fixed("Control module power supply"),
            about = fixed("A control module reports a problem with its power supply, ground or ignition (power mode) signal."),
            causes = list(
                CAUSE_12V,
                "Blown fuse or failed relay feeding the module",
                "Loose or corroded power or ground connection at the module",
                "Fault in the ignition / power mode signal",
                "Power lost while the module was still working (for example a battery disconnected with the car on)",
            ),
            checks = list(CHECK_12V, "Check the fuses for the affected module", "Check ground connections and battery terminals", CHECK_DONGLE),
            condition = true,
        ),
        Topic(
            "u_module", letters = "U",
            match = rx("""Control Module|Vehicle Identification Number|Software|Configuration"""),
            system = COMPUTER_OUTPUTS, subsystem = fixed("Control module"),
            about = fixed("A control module reports a fault with itself, its software or its configuration (for example the stored VIN)."),
            causes = list(
                "Module not configured or coded correctly, for example after replacement",
                "Software mismatch between modules",
                CAUSE_12V,
                "Internal module fault",
            ),
            checks = list(CHECK_DEALER_TOOL, "Think back: was any module replaced, updated or disconnected recently?", CHECK_12V),
            condition = true,
        ),
        Topic(
            "bus", letters = "U", match = rx("""\bBus\b|\bCAN\b|Network|Communication|\bLIN\b|Gateway"""), system = NETWORK,
            subsystem = fixed("Data network (CAN bus)"),
            about = fixed(
                "The control modules share information over data networks (CAN buses). A module has detected a fault on " +
                    "the network itself rather than with one specific module.",
            ),
            causes = list(
                CAUSE_12V,
                "Damaged network wiring: CAN-High and CAN-Low shorted together, to ground or to voltage",
                "A faulty control module disturbing the network",
                "Water or corrosion in a connector on the network",
                "Aftermarket devices on the diagnostic socket or spliced into the wiring (dongles, trackers, dashcams)",
            ),
            checks = list(
                CHECK_12V,
                CHECK_DONGLE,
                "Note which functions stopped working when the code appeared; it shows which part of the network is affected",
                "Look for water ingress (wet carpets, damp connectors) and recent work on the wiring",
                "A workshop can measure the network wiring and terminating resistors and disconnect modules one by one",
                CHECK_DEALER_TOOL,
            ),
            condition = true, advice = adv(ADVICE_NETWORK),
        ),
        Topic(
            "u_other", letters = "U", system = NETWORK, subsystem = fixed("Network communication"),
            about = fixed("This code concerns communication between the car's control modules."),
            causes = list(CAUSE_12V, "Wiring or connector fault on the data network", "Fault in one of the control modules"),
            checks = list(CHECK_MANY_CODES, CHECK_12V, CHECK_DONGLE, CHECK_DEALER_TOOL),
            advice = adv(ADVICE_NETWORK),
        ),

        // ---------------------------------------------------------------- P: warning-light requests
        Topic(
            "mil_request", match = rx("""MIL Request|Requested MIL|MIL Illumination"""),
            subsystem = fixed("Warning light request from another module"),
            about = fixed(
                "Another control module has stored its own fault and asked for the check-engine light to be switched on. " +
                    "The actual fault code is stored in that module.",
            ),
            causes = list(
                "A fault stored in the requesting module (read that module for the real code)",
                CAUSE_12V,
                "Wiring or network problem between modules",
            ),
            checks = list(CHECK_DEALER_TOOL, CHECK_MANY_CODES, "Note any change in how the car drives and tell the workshop"),
            condition = true,
            advice = adv(
                "Usually yes, if the car drives normally. Have the other module read within a few weeks, or within days " +
                    "if the car behaves differently or shows more warnings.",
            ),
        ),

        // ---------------------------------------------------------------- P0A–P0C: hybrid / EV
        Topic(
            "hv_isolation_fault", match = rx("Isolation Fault"), range = HYBRID, system = HYBRID_EV,
            subsystem = fixed("High-voltage insulation monitoring"),
            about = fixed(
                "The high-voltage system is insulated from the car body, and a monitor checks this insulation all the time. " +
                    "It has measured too low an insulation resistance, so high voltage may be leaking towards the body.",
            ),
            causes = list(
                "Moisture or water in a high-voltage component or connector (heavy rain, engine-bay washing, deep water)",
                "Damaged insulation on an orange high-voltage cable (chafing, rodent or accident damage)",
                "Insulation breakdown inside a high-voltage component (air-conditioning compressor, heater, charger, inverter, motor or drive battery)",
                "Fault in the insulation monitoring circuit itself (less common)",
            ),
            checks = list(
                "Note when the fault appears: only in wet weather, only while charging, or only with heating or air conditioning on",
                "Look, without touching, for visible damage to orange cables under the car and in the engine bay",
                "Have a Mitsubishi dealer or a high-voltage-trained workshop locate the fault with an insulation tester",
            ),
            severity = sev(CHECK_NOW), condition = true, hv = true, advice = adv(ADVICE_ISOLATION),
        ),
        Topic(
            "hv_isolation_sensor", match = rx("Isolation"), range = HYBRID, system = HYBRID_EV,
            subsystem = fixed("High-voltage insulation monitoring (sensor circuit)"),
            about = fixed("The insulation monitor checks that the high-voltage system stays insulated from the car body."),
            causes = list("Fault in the insulation monitoring circuit or the unit it is part of", "Wiring or connector problem"),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_HV),
        ),
        Topic(
            "hv_interlock", match = rx("""Inter-?Lock"""), range = HYBRID, system = HYBRID_EV,
            subsystem = fixed("High-voltage interlock (safety loop)"), part = fixed("high-voltage interlock loop"),
            about = fixed(
                "The high-voltage interlock is a low-voltage signal loop through the high-voltage connectors and covers; " +
                    "if it is broken, the system switches the high voltage off for safety.",
            ),
            causes = list(
                "High-voltage connector or service plug not fully latched, often after a repair or inspection",
                "Damaged or corroded interlock pin or wire in a high-voltage connector",
                "Water in a connector",
                "Fault in the control unit that monitors the loop (less common)",
            ),
            circuitCauses = listOf("High-voltage connector or service plug not fully latched, often after a repair or inspection"),
            checks = list(
                "Think back: was any high-voltage part worked on recently? If so, return to that workshop first",
                "Have a high-voltage-trained technician check each connector in the interlock loop",
            ),
            severity = sev(CHECK_NOW), hv = true,
            advice = adv(
                "The car may refuse to go into READY or may shut the high-voltage system down. If it drives, get it " +
                    "checked within days. If a message tells you to stop, stop safely. Never try to reseat high-voltage connectors yourself.",
            ),
        ),
        Topic(
            "hv_contactor", match = rx("Contactor|Precharge"), range = HYBRID, system = HYBRID_EV,
            subsystem = fixed("Drive battery main relays (contactors)"),
            about = fixed(
                "Main relays (contactors) connect the drive battery to the rest of the high-voltage system, and a " +
                    "precharge circuit limits the current surge when they close.",
            ),
            causes = list(
                "Faulty main relay or precharge relay/resistor",
                "Wiring or connector fault in the relay control circuit",
                "Fault in the battery management unit that controls the relays",
                HV_CAUSE_12V_RELAY,
            ),
            checks = list(CHECK_12V, "Have a Mitsubishi dealer or high-voltage-trained workshop test the relays and their control circuit"),
            severity = sev(CHECK_NOW), hv = true,
            advice = adv(
                "The car may refuse to go into READY or may shut down the high-voltage system. A relay stuck closed means " +
                    "high voltage may stay switched on, so stay away from high-voltage parts. Get it checked within days; " +
                    "if a message tells you to stop, stop safely.",
            ),
        ),
        Topic(
            "hv_fuse", match = rx("High Voltage Fuse"), range = HYBRID, system = HYBRID_EV,
            subsystem = fixed("High-voltage fuse"),
            about = fixed("A fuse in the high-voltage system is reported as blown or open; functions it protects may stop working."),
            causes = list(
                "Short circuit or overload in a high-voltage component after the fuse",
                "Fuse that has aged or failed by itself (less common)",
                "Fault in the fuse monitoring circuit",
            ),
            checks = list(
                "Note which functions stopped working (heating, air conditioning, charging, drive)",
                "Have a high-voltage-trained technician find the cause before the fuse is replaced",
            ),
            severity = sev(CHECK_NOW), condition = true, hv = true, advice = adv(ADVICE_HV),
        ),
        Topic(
            "batt_temp", match = rx("""Hybrid Battery (?:Pack )?(?:Air )?Temperature Sensor"""), range = HYBRID,
            system = HYBRID_EV, subsystem = fixed("Drive battery temperature sensors"),
            about = fixed(
                "Temperature sensors in the drive battery let the battery management system protect it from overheating " +
                    "and from being charged when too cold.",
            ),
            causes = list(
                "Faulty temperature sensor",
                "Wiring or connector problem inside the battery pack",
                "A real temperature difference caused by a cooling problem",
                "Fault in the battery management unit",
            ),
            checks = list(
                "Have a dealer compare all battery temperature readings; after the car has stood overnight they should be nearly equal",
                "Repairs usually mean opening the drive battery and must be done by a high-voltage-trained technician",
            ),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_HV),
        ),
        Topic(
            "batt_cooling", match = rx("""Hybrid Battery Pack (?:Cooling Fan|Air Flow)"""), range = HYBRID,
            system = HYBRID_EV, subsystem = fixed("Drive battery cooling"),
            about = fixed("The drive battery is kept within a safe temperature range by a cooling system with fans, ducts or valves."),
            causes = list(
                "Fan, duct or valve blocked by dirt or debris",
                "Faulty fan motor or valve",
                "Blown fuse or failed relay",
                "Wiring or connector fault",
            ),
            checks = list(
                "Check the fuses and relays for the battery cooling (owner's manual or service fuse chart)",
                "Avoid hard driving and repeated rapid charging in hot weather until it is fixed",
            ),
            severity = sev(CHECK_NOW), hv = true,
            advice = adv(
                "Drive gently; power and charging may be limited to protect the drive battery. Avoid repeated rapid " +
                    "charging in hot weather and get it checked within days.",
            ),
        ),
        Topic(
            "batt_sense", match = rx("""Hybrid Battery Pack (?:Current Sensor|Voltage Sense)"""), range = HYBRID,
            system = HYBRID_EV, subsystem = fixed("Drive battery current and voltage sensing"),
            about = fixed(
                "The battery management system measures the drive battery's voltage and current to calculate the state of " +
                    "charge and protect the cells.",
            ),
            causes = list("Faulty sensor", "Wiring or connector problem", "Fault in the battery management unit"),
            checks = list("Have a Mitsubishi dealer check the drive battery monitoring with their diagnostic tool"),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_HV),
        ),
        Topic(
            "batt", match = rx("""Hybrid Battery|Battery Energy Control Module|Battery Power Off"""), range = HYBRID,
            system = HYBRID_EV, subsystem = fixed("Drive battery (high-voltage traction battery)"),
            about = fixed(
                "The drive battery stores the energy for electric driving, and its management unit monitors the cells' " +
                    "voltage, temperature and state of charge.",
            ),
            causes = list(
                "Drive battery ageing, weak cells or cells out of balance",
                "Fault in the battery's monitoring circuits or management unit",
                "Cooling problem",
                "Deep discharge after long standing or after running out of fuel",
            ),
            checks = list(
                READ_ALL,
                "Plug in and charge fully, then see whether the code returns",
                "Have a Mitsubishi dealer run a drive battery check",
            ),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_HV),
        ),
        Topic(
            "dcdc", match = rx("""DC/DC|14 Volt Power Module|DC to DC"""), range = HYBRID, system = HYBRID_EV,
            subsystem = fixed("DC/DC converter (12 V supply)"),
            about = fixed(
                "The DC/DC converter turns drive battery voltage into about 12–14 V to power the car's electronics and charge " +
                    "the 12 V battery; the Outlander PHEV has no conventional alternator.",
            ),
            causes = list(
                "Fault in the DC/DC converter",
                "Poor connection, corrosion or a blown main fuse between the converter and the 12 V battery",
                "Failing 12 V battery drawing abnormal current",
                "Possible overheating because of a power electronics cooling fault",
            ),
            checks = list(
                "Measure the 12 V battery voltage with the car off and then in READY: in READY it should rise to roughly " +
                    "13–14.5 V; if it does not, the converter is not charging",
                "Check the 12 V battery terminals and ground strap for corrosion and tightness",
                "Have the 12 V battery tested; replace it if weak",
            ),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_DCDC),
        ),
        Topic(
            "pe_cooling", match = rx("""Motor Electronics|Inverter Cooling|Coolant Pump"""), range = HYBRID,
            system = HYBRID_EV, subsystem = fixed("Power electronics cooling"),
            about = fixed("The inverters and motor electronics are cooled by their own coolant circuit with an electric pump."),
            causes = list(
                "Low coolant level in the power electronics circuit (leak)",
                "Weak or failed electric coolant pump",
                "Air trapped in the circuit after a repair",
                "Radiator blocked by dirt or leaves, or a cooling fan problem",
            ),
            checks = list(PE_COOLANT_CHECK, "Look for coolant leaks under the front of the car", "Clean leaves and dirt from the front of the radiators"),
            severity = sev(CHECK_NOW), hv = true,
            advice = adv(
                "Power may be limited to protect the electronics. Drive gently and get it checked within days; avoid " +
                    "steep climbs, towing and high loads in hot weather. Stop if a temperature warning or a message to stop appears.",
            ),
        ),
        Topic(
            "inverter", match = rx("Inverter"), range = HYBRID, system = HYBRID_EV,
            subsystem = fixed("Inverter (motor/generator power electronics)"),
            about = fixed(
                "Inverters convert the drive battery's direct current into the alternating current that drives the electric " +
                    "motors, and back again when the battery is being charged by the generator or by regenerative braking.",
            ),
            causes = list(
                "Internal inverter fault",
                "Cooling problem (look for power electronics cooling codes)",
                "Problem with the motor or the high-voltage cables",
                HV_CAUSE_12V_RELAY,
            ),
            checks = list(PE_COOLANT_CHECK, "Have a Mitsubishi dealer diagnose the inverter; this needs high-voltage training and special tools"),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_HV),
        ),
        Topic(
            "motor_gen", match = rx("""Drive Motor|Generator|Motor Torque|Motor Control|Phase [UVW]"""), range = HYBRID,
            system = HYBRID_EV, subsystem = fixed("Electric drive motors and generator"),
            about = fixed(
                "The Outlander PHEV has electric drive motors and a generator driven by the engine; each has sensors and " +
                    "control electronics that are monitored all the time.",
            ),
            causes = list(
                "Fault in the motor control unit or inverter",
                "Faulty sensor inside the motor or generator",
                "Cooling problem (for temperature-related codes)",
                "Wiring or connector fault between the motor and its control unit",
            ),
            checks = list(
                "Read all codes and note when the fault happened (start-up, acceleration, charging, over bumps)",
                "Have a Mitsubishi dealer diagnose the motor and its control unit",
            ),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_HV),
        ),
        Topic(
            "engine_start", match = rx("Engine Failed to Start"), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Engine start (hybrid system)"),
            about = fixed("The hybrid system tried to start the petrol engine, but it did not start and run as expected."),
            causes = list(
                "Very low fuel level, or old/stale petrol",
                "Fuel supply problem (pump, relay, fuse)",
                "Crankshaft or camshaft sensor, ignition or immobiliser fault",
                "Weak 12 V battery",
            ),
            checks = list("Check the fuel level and add fresh petrol", "Read all codes for the real cause", CHECK_12V),
            severity = sev(CHECK_NOW), condition = true,
        ),
        Topic(
            "engine_mount", match = rx("Engine Mount"), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Electronically controlled engine mount"),
            about = fixed("Some cars use electronically controlled engine mounts to reduce vibration."),
            causes = list("Wiring or connector fault at the mount", "Faulty mount actuator", "Blown fuse"),
            checks = list("Inspect the mount connector and wiring", "Listen and feel for increased vibration when the engine starts and stops"),
            severity = sev(CHECK_SOON),
        ),
        Topic(
            "aux_trans_pump", match = rx("Auxiliary Transmission Fluid Pump"), system = TRANSMISSION,
            subsystem = fixed("Auxiliary transmission fluid pump"),
            about = fixed("An electric auxiliary pump keeps transmission fluid circulating when the engine-driven pump is not running."),
            causes = list("Faulty electric pump or its control unit", "Wiring or connector fault", "Low fluid level or internal leakage"),
            checks = list("Have a workshop check fluid level and the pump circuit"),
            severity = sev(CHECK_SOON), note = fixed(NOTE_GEARBOX),
        ),
        Topic(
            "hybrid_other", range = HYBRID, system = HYBRID_EV,
            subsystem = { t -> derivePart(t).let { if (it.isEmpty()) "Hybrid system" else capitalise(it) } },
            about = fixed("The hybrid control system coordinates the engine, electric motors and drive battery, and monitors their parts."),
            causes = list("Fault in a hybrid system component or its control unit", "Wiring or connector problem", CAUSE_12V),
            checks = list(READ_ALL, CHECK_12V, CHECK_DEALER_TOOL),
            severity = sev(CHECK_NOW), hv = true, advice = adv(ADVICE_HV),
        ),

        // ---------------------------------------------------------------- P: equipment this car does not have
        Topic(
            "turbo", match = rx("""Turbo|Supercharger|\bBoost\b|Wastegate|Charge Air Cooler|Intercooler"""),
            system = FUEL_AIR, subsystem = fixed("Turbocharger / supercharger"),
            about = fixed("This code concerns a turbocharger or supercharger, which raises the air pressure in the engine's intake."),
            causes = NOT_FITTED_CAUSES, checks = NOT_FITTED_CHECKS, condition = true, note = fixed(NOTE_TURBO),
        ),
        Topic(
            "glow", match = rx("Glow Plug|Intake Air Heater"), system = POWERTRAIN_ENGINE, subsystem = fixed("Glow plugs (diesel engines)"),
            about = fixed("This code concerns glow plugs or an intake air heater, which help a diesel engine start in the cold."),
            causes = NOT_FITTED_CAUSES, checks = NOT_FITTED_CHECKS, condition = true, note = fixed(NOTE_DIESEL),
        ),
        Topic(
            "diesel", match = rx("""Diesel|Particulate|Reductant|\bDPF\b|Cetane|Water in Fuel|Injection Pump"""),
            system = EMISSIONS, subsystem = fixed("Diesel fuel and exhaust after-treatment"),
            about = fixed("This code concerns diesel fuel or exhaust after-treatment equipment (particulate filter, exhaust fluid dosing)."),
            causes = NOT_FITTED_CAUSES, checks = NOT_FITTED_CHECKS, condition = true, note = fixed(NOTE_DIESEL),
        ),
        Topic(
            "nox", match = rx("NOx"), system = EMISSIONS, subsystem = fixed("NOx sensor / NOx after-treatment"),
            about = fixed("This code concerns a NOx sensor or NOx after-treatment in the exhaust."),
            causes = NOT_FITTED_CAUSES, checks = NOT_FITTED_CHECKS, condition = true, note = fixed(NOTE_NOX),
        ),
        Topic(
            "cyl_deact", match = rx("Deactivation"), system = POWERTRAIN_ENGINE, subsystem = fixed("Cylinder deactivation"),
            about = fixed("This code concerns cylinder deactivation, which shuts off some cylinders at light load to save fuel."),
            causes = NOT_FITTED_CAUSES, checks = NOT_FITTED_CHECKS, condition = true, note = fixed(NOTE_CYL_DEACT),
        ),

        // ---------------------------------------------------------------- P: engine
        Topic(
            "misfire", match = rx("Misfire"), system = IGNITION_MISFIRE, subsystem = fixed("Misfire detection"),
            about = fixed(
                "The engine computer has detected misfires: the fuel in one or more cylinders is not burning properly. " +
                    "Unburnt fuel can overheat and damage the catalytic converter.",
            ),
            causes = list(
                "Worn spark plugs or a failing ignition coil",
                "Clogged or faulty fuel injector",
                "Old or poor-quality fuel (possible if the engine rarely runs)",
                "Air leak in the intake or a dirty air flow sensor",
                "Low fuel pressure",
                "Valve timing problem or low compression (less common)",
            ),
            checks = list(
                "Read all codes: cylinder-specific misfire, fuel trim or sensor codes narrow it down",
                "Inspect the spark plugs and coils; swap a coil between cylinders to see whether the misfire follows it",
                CHECK_FRESH_FUEL,
                "A workshop can check fuel pressure, injectors and compression",
            ),
            severity = sev(CHECK_NOW), condition = true, advice = adv(ADVICE_MISFIRE),
        ),
        Topic(
            "cam_crank_corr",
            match = rx("""Crankshaft Position.{0,5}Camshaft Position Correlation|Engine Position System|Camshaft Position.{0,5}Crankshaft"""),
            system = POWERTRAIN_ENGINE, subsystem = fixed("Valve timing (crankshaft/camshaft correlation)"),
            about = fixed(
                "The engine computer compares the crankshaft and camshaft position signals. They no longer line up as they " +
                    "should, so either the valve timing has shifted or one of the signals is wrong.",
            ),
            causes = list(
                "Stretched timing chain, worn guides or weak tensioner",
                "Low or dirty engine oil upsetting the variable valve timing",
                "Sticking oil control valve or faulty cam phaser",
                "Faulty camshaft or crankshaft position sensor",
            ),
            checks = list(
                CHECK_OIL,
                "Listen for a rattle from the timing chain end of the engine just after a cold start",
                "A workshop can check timing marks or chain stretch; fix this early, as a chain that jumps a tooth can damage the engine",
            ),
            severity = sev(CHECK_NOW), condition = true,
        ),
        Topic(
            "cam_sensor", match = rx("Camshaft Position Sensor"), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Camshaft position sensor"),
            about = fixed("The camshaft position sensor tells the engine computer which cylinder is about to fire."),
            causes = list("Faulty camshaft position sensor", "Oil in the sensor connector", "Timing chain or camshaft trigger problem (less common)"),
            checks = list("Inspect the sensor connector for oil inside and refit it firmly"),
            severity = sev(CHECK_NOW),
            advice = adv(
                "The car may still drive on electric power, but the engine may start poorly or stall. Get it checked within days.",
            ),
        ),
        Topic(
            "vvt",
            match = rx("""Camshaft|Valve Control Solenoid|Valve Timing|Rocker Arm|Intake Valve Control|Exhaust Valve Control|Valve Lift"""),
            system = POWERTRAIN_ENGINE, subsystem = fixed("Variable valve timing / valve control"),
            about = fixed("The engine computer adjusts the valve timing with oil-pressure-driven actuators controlled by solenoid valves."),
            causes = list(
                "Low, old or wrong-grade engine oil",
                "Sticking or faulty oil control valve, or a blocked filter screen",
                "Faulty cam phaser (variable timing actuator)",
                "Stretched timing chain",
            ),
            checks = list(CHECK_OIL, "A workshop can test the oil control valve and compare commanded and actual camshaft angle"),
            severity = sev(CHECK_NOW),
        ),
        Topic(
            "crank", match = rx("""Crankshaft|Engine Speed Input|\bCrank\b|Reluctor|Timing Reference"""), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Crankshaft position sensor"),
            about = fixed("The crankshaft position sensor tells the engine computer the engine speed and piston position."),
            causes = list("Faulty crankshaft position sensor", "Metal debris on the sensor or a damaged trigger wheel", "Intermittent wiring fault"),
            checks = list("Inspect the sensor connector and wiring for oil, chafing or rodent damage"),
            severity = sev(CHECK_NOW),
            advice = adv(
                "The car may still drive on electric power, but the engine may refuse to start or stall. Get it checked within days.",
            ),
        ),
        Topic(
            "knock", match = rx("Knock"), system = IGNITION_MISFIRE, subsystem = fixed("Knock sensor"),
            about = fixed("The knock sensor listens for engine knock so the engine computer can adjust the ignition timing."),
            causes = list("Faulty knock sensor", "Sensor loose on the engine block", "Real mechanical engine noise"),
            checks = list("Check that the sensor is bolted tight to the engine block", "Use the recommended fuel grade"),
            advice = adv(
                "Yes, you can keep driving. The engine computer uses safer ignition timing, so the engine may feel slightly " +
                    "weaker. Avoid heavy loads and get it checked within a few weeks.",
            ),
        ),
        Topic(
            "ignition",
            match = rx("""Ignition Coil|Spark|Ignition Control|Ignition Timing|Ignition Module|Ignition Circuit|Ignition Primary|Ignition Secondary"""),
            system = IGNITION_MISFIRE, subsystem = fixed("Ignition system"),
            about = fixed("The ignition coils create the spark that ignites the fuel in each cylinder."),
            causes = list("Faulty ignition coil", "Worn spark plug", "Wiring or connector fault at the coil"),
            checks = list("Swap the coil with a neighbouring cylinder and see whether the code follows the coil"),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_MISFIRE),
        ),
        Topic(
            "o2_heater", match = rx("""(?:HO2S|O2 Sensor|Oxygen Sensor|A/F Sensor) Heater"""), system = FUEL_AIR,
            subsystem = fixed("Oxygen sensor heater"), part = fixed("oxygen sensor heater"), o2 = true,
            about = fixed("Oxygen sensors only work when hot, so each has a built-in electric heater."),
            causes = list("Heater element burnt out (common on older sensors)", "Blown heater fuse", "Melted or chafed wiring near the exhaust"),
            circuitCauses = listOf("Heater element burnt out or shorted (the sensor must then be replaced)"),
            checks = list("Check the oxygen sensor heater fuse", "Measure the heater resistance at the sensor connector and compare with the service data"),
            advice = adv(
                "Yes, you can keep driving. Emissions and fuel use may rise, mostly after cold starts. Get it fixed within a few weeks.",
            ),
        ),
        Topic(
            "o2", match = rx("""O2 Sensor|HO2S|Oxygen Sensor|A/F Sensor|Air[ /-]Fuel Ratio Sensor|Lambda"""), system = FUEL_AIR,
            subsystem = fixed("Oxygen sensor"), part = fixed("oxygen sensor"), o2 = true,
            about = fixed(
                "Oxygen sensors measure the oxygen in the exhaust: the front sensor helps keep the fuel mixture correct and " +
                    "the rear sensor checks the catalytic converter.",
            ),
            causes = list(
                "Aged or contaminated oxygen sensor",
                "Exhaust leak near the sensor",
                "A real mixture problem (look for fuel trim codes)",
                "Wiring damaged by heat from the exhaust",
            ),
            checks = list(
                "Check for exhaust leaks near the sensor (ticking or puffing noise, soot marks)",
                "Fix fuel trim, misfire and heater codes first",
            ),
        ),
        Topic(
            "fuel_trim",
            match = rx("""Fuel Trim|Too Lean|Too Rich|\bLean\b|\bRich\b|Air[/-]Fuel Ratio Imbalance|Fuel Imbalance"""),
            system = FUEL_AIR, subsystem = fixed("Fuel mixture (fuel trim)"),
            about = { t ->
                val base = "The engine computer constantly corrects the amount of fuel injected (fuel trim) to keep the " +
                    "air-fuel mixture correct, and the corrections have reached their limit."
                when {
                    LEAN.containsMatchIn(t) -> "$base The engine is running lean: too much air or too little fuel."
                    RICH.containsMatchIn(t) -> "$base The engine is running rich: too much fuel or too little air."
                    else -> base
                }
            },
            causes = { t ->
                when {
                    LEAN.containsMatchIn(t) -> listOf(
                        "Air leak after the air flow sensor (hoses, clamps, gaskets, crankcase ventilation)",
                        "Dirty or faulty air flow sensor",
                        "Low fuel pressure (pump, filter, regulator) or clogged injectors",
                        "Exhaust leak in front of the oxygen sensor",
                        "Possible on a plug-in hybrid: old, stale fuel if the engine rarely runs",
                    )
                    RICH.containsMatchIn(t) -> listOf(
                        "Leaking fuel injector or fuel pressure too high",
                        "Faulty air flow or coolant temperature sensor",
                        "Purge valve stuck open",
                        "Blocked air filter",
                        "Engine oil diluted with fuel (possible with many short, cold engine runs)",
                    )
                    else -> listOf("Air leak or fuel delivery problem", "Faulty sensor used for mixture control", "Injector problem")
                }
            },
            checks = list(
                "Check fuel trims in live data at idle and at higher engine speed",
                "Look and listen for intake air leaks",
                "Fix misfire, air flow sensor and oxygen sensor codes first",
                CHECK_FRESH_FUEL,
            ),
            condition = true,
        ),
        Topic(
            "secondary_air", match = rx("Secondary Air"), system = EMISSIONS, subsystem = fixed("Secondary air injection"),
            about = fixed("Some petrol engines pump fresh air into the exhaust after a cold start to heat the catalytic converter faster."),
            causes = list("Faulty air pump or relay", "Stuck or leaking switching/check valve", "Leaking or blocked hoses"),
            checks = list("Inspect the hoses and valves for damage", "Check the pump fuse and relay"),
        ),
        Topic(
            "intake_manifold", match = rx("""Intake Manifold Runner|Intake Manifold Tuning|\bIMT\b|Manifold Tuning"""),
            system = FUEL_AIR, subsystem = fixed("Intake manifold runner / tuning valve"),
            about = fixed("Some engines use flaps or valves in the intake manifold to change the air path for better torque and power."),
            causes = list("Flap or valve sticking because of carbon deposits", "Faulty actuator or position sensor", "Vacuum leak or wiring fault"),
            checks = list("Inspect the actuator wiring and any vacuum hoses to it"),
        ),
        Topic(
            "maf", match = rx("""Mass or Volume Air Flow|Mass Air Flow|\bMAF\b|Air Flow Sensor"""), system = FUEL_AIR,
            subsystem = fixed("Mass air flow (MAF) sensor"),
            about = fixed("The mass air flow sensor measures how much air the engine draws in, which is used to calculate the fuel."),
            causes = list("Air leak in the intake duct after the sensor", "Dirty or contaminated sensor element", "Blocked air filter"),
            checks = list(
                "Check the air filter and the intake duct between sensor and throttle body",
                "Clean the sensor element with dedicated MAF cleaner (never touch the element)",
            ),
        ),
        Topic(
            "map", match = rx("""Manifold Absolute Pressure|Barometric|\bMAP\b|Manifold Pressure"""), system = FUEL_AIR,
            subsystem = fixed("Manifold / barometric pressure sensor"),
            about = fixed("Pressure sensors measure the intake manifold and outside air pressure for fuel and ignition calculations."),
            causes = list("Cracked or disconnected vacuum hose to the sensor", "Intake air leak", "Faulty pressure sensor"),
            checks = list("Inspect the sensor's vacuum hose and the intake for leaks"),
        ),
        Topic(
            "iat", match = rx("""Intake Air Temperature|Ambient Air Temperature|Charge Air Temperature|Air Temperature"""),
            system = FUEL_AIR, subsystem = fixed("Air temperature sensor"),
            about = fixed("Air temperature sensors tell the engine computer how warm the incoming or outside air is."),
            causes = list("Faulty or drifting temperature sensor", "Sensor connector disturbed during air filter work"),
            checks = list("On a cold engine after standing overnight, air and coolant temperatures in live data should be similar"),
        ),
        Topic(
            "engine_overtemp", match = rx("""(?:Engine|Coolant) (?:Coolant )?Over ?Temperature|Engine Over ?Temperature|Engine Hot"""),
            system = POWERTRAIN_ENGINE, subsystem = fixed("Engine cooling (overheating)"),
            about = fixed("The engine coolant temperature has gone above the safe limit: the engine is overheating."),
            causes = list(
                "Low coolant because of a leak",
                "Radiator fan not working",
                "Thermostat stuck closed or water pump failure",
                "Blocked radiator",
                "Head gasket failure (less common)",
            ),
            checks = list(
                "Stop and let the engine cool for at least 30 minutes; never open the coolant cap on a hot engine",
                "With the engine cold, check the coolant level and look for leaks",
                "Have a workshop pressure-test the cooling system",
            ),
            severity = sev(STOP), condition = true,
        ),
        Topic(
            "engine_overspeed", match = rx("Engine Overspeed"), system = POWERTRAIN_ENGINE, subsystem = fixed("Engine speed limit"),
            about = fixed("The engine speed went above the allowed maximum."),
            causes = list("Engine revved too high, for example during a fault in throttle or drive control", "Wrong engine speed signal"),
            checks = list("Read all codes for related faults", "Note what you were doing when it happened"),
            condition = true,
        ),
        Topic(
            "thermostat", match = rx("""Thermostat|Insufficient Coolant Temperature"""), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Thermostat / engine warm-up"),
            about = fixed(
                "The engine did not reach its normal working temperature within the expected time; the thermostat may open too " +
                    "early or be stuck open.",
            ),
            causes = list(
                "Thermostat stuck open or opening too early",
                "Faulty coolant temperature sensor",
                "Low coolant level",
                "Possible contributing factor on a plug-in hybrid: very cold weather with short engine running periods",
            ),
            checks = list(
                "Check the coolant level with the engine cold",
                "Watch the coolant temperature in live data during warm-up",
                CHECK_CHARGE_MODE,
            ),
            condition = true,
        ),
        Topic(
            "ect", match = rx("""Engine Coolant Temperature|Coolant Temperature Sensor|Coolant Temp"""), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Engine coolant temperature sensor"),
            about = fixed("The coolant temperature sensor tells the engine computer how warm the engine is."),
            causes = list("Faulty or drifting coolant temperature sensor", "Thermostat problem", "Low coolant level"),
            checks = list(
                "Check the coolant level with the engine cold",
                "After the car has stood overnight, coolant and air temperatures in live data should be within a few degrees",
            ),
        ),
        Topic(
            "cooling", match = rx("""Coolant|Cooling Fan|\bFan\b|Radiator|Water Pump|Cooling System"""), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Engine cooling system"),
            about = fixed("The cooling system keeps the engine at its working temperature with coolant, a pump, the radiator and fans."),
            causes = list("Low coolant level or leak", "Fan, fan relay or fuse fault", "Faulty pump or thermostat"),
            checks = list("Check the coolant level with the engine cold", "Check the radiator fan fuses and relay"),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_COOLING),
        ),
        Topic(
            "oil_low", match = rx("""Oil Pressure Too Low|Oil Pressure Low|Oil Level Too Low"""), system = POWERTRAIN_ENGINE,
            subsystem = fixed("Engine oil pressure"),
            about = fixed("The engine oil pressure or level has dropped below the safe minimum, which can damage the engine within minutes."),
            causes = list("Low oil level", "Worn or failing oil pump", "Wrong or very old oil", "Faulty oil pressure sensor or switch"),
            checks = list(
                "Stop and check the oil level on the dipstick with the car on level ground",
                "If the level is fine but the warning remains, have the oil pressure measured with a mechanical gauge",
            ),
            severity = sev(STOP), condition = true, advice = adv(ADVICE_OIL_LOW),
        ),
        Topic(
            "oil", match = rx("""(?<!Hydraulic )\bOil\b"""), system = POWERTRAIN_ENGINE, subsystem = fixed("Engine oil system"),
            about = fixed("The engine computer monitors the engine oil pressure, level or temperature."),
            causes = list("Low or old engine oil", "Faulty oil sensor or switch", "Wiring or connector fault"),
            checks = list(CHECK_OIL),
            severity = sev(CHECK_NOW),
        ),
        Topic(
            "egr", match = rx("""Exhaust Gas Recirculation|\bEGR\b"""), system = EMISSIONS,
            subsystem = fixed("Exhaust gas recirculation (EGR)"),
            about = fixed("The EGR system feeds a little exhaust back into the intake to lower combustion temperatures and NOx emissions."),
            causes = list("Carbon deposits in the EGR valve or passages", "Sticking or faulty EGR valve", "Faulty sensor used to judge EGR flow"),
            checks = list("Clean or replace the EGR valve and clean the passages"),
        ),
        Topic(
            "throttle", match = rx("""Throttle|Pedal Position|Accelerator"""), system = FUEL_AIR,
            subsystem = fixed("Electronic throttle / accelerator pedal"),
            about = fixed("The engine has an electronic throttle and the accelerator pedal has position sensors, each with two signals that must agree."),
            causes = list(
                "Dirty or sticking throttle body (carbon deposits, or ice in very cold weather)",
                "Faulty throttle body or accelerator pedal sensor",
                "Water or corrosion in the connector",
                "Problem with the shared 5 V sensor supply",
            ),
            checks = list(
                "Clean the throttle body carefully if it is dirty (ignition off); a throttle relearn may be needed afterwards",
                "Check the footwell for moisture if the pedal is involved",
            ),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_THROTTLE),
        ),
        Topic(
            "idle", match = rx("Idle"), system = SPEED_IDLE, subsystem = fixed("Idle speed control"),
            about = fixed("When the engine idles, the engine computer controls its speed with the electronic throttle."),
            causes = list("Dirty throttle body", "Intake air leak", "Throttle needs an idle relearn after cleaning or battery disconnection"),
            checks = list("Clean the throttle body carefully and perform the idle relearn", "Listen for intake air leaks at idle"),
        ),
        Topic(
            "injector", match = rx("Injector|Injection|Contribution/Balance"), system = FUEL_AIR, subsystem = fixed("Fuel injectors"),
            about = fixed("The fuel injectors spray petrol into the engine and are switched by the engine computer."),
            causes = list("Clogged or leaking injector", "Faulty injector coil", "Wiring or connector fault"),
            checks = list("Measure the injector resistances and compare them with each other", "A workshop can test injector flow and leak-down"),
            severity = sev(CHECK_NOW),
            advice = adv(
                "The engine may misfire and run unevenly, which can damage the catalytic converter. Get it repaired within " +
                    "days; if the check-engine light flashes, stop as soon as it is safe.",
            ),
        ),
        Topic(
            "fuel_pump", match = rx("Fuel Pump"), system = FUEL_AIR, subsystem = fixed("Fuel pump"),
            about = fixed("The electric fuel pump in the tank supplies petrol to the engine."),
            causes = list("Failed fuel pump relay or fuse", "Worn fuel pump", "Wiring or connector fault"),
            checks = list("Check the fuel pump fuse and relay", "A workshop can measure fuel pressure and pump current"),
            severity = sev(CHECK_NOW),
            advice = adv(
                "You can usually keep driving on electric power, but the engine may not start or may stall. Get it checked within days.",
            ),
        ),
        Topic(
            "fuel_pressure",
            match = rx("""Fuel Rail|Fuel Pressure|Fuel System Pressure|Fuel Volume Regulator|Fuel Shutoff|Fuel System Leak|Fuel Delivery"""),
            system = FUEL_AIR, subsystem = fixed("Fuel pressure / delivery"),
            about = fixed("The fuel system must deliver petrol at the correct pressure for the injectors to work properly."),
            causes = list("Weak fuel pump or clogged fuel filter", "Faulty pressure regulator or sensor", "Fuel leak"),
            checks = list("If you smell petrol, do not drive; have it checked for leaks", "A workshop can measure fuel pressure"),
            severity = sev(CHECK_NOW),
        ),
        Topic(
            "evap_leak", match = rx("""(?:Evaporative|EVAP).*Leak|Fuel Cap|Filler Cap"""), system = EMISSIONS,
            subsystem = fixed("Evaporative emissions (fuel vapour) system"),
            about = fixed(
                "The evaporative (EVAP) system collects petrol vapour from the tank in a charcoal canister. The engine " +
                    "computer seals it and checks that it holds pressure or vacuum, and it has found a leak.",
            ),
            causes = list(
                "Loose, worn or wrong fuel cap (most common)",
                "Cracked or disconnected EVAP hose",
                "Purge or vent valve not sealing",
                "Corroded filler neck or damaged canister",
            ),
            checks = list(
                "Check the fuel cap: it should click when tightened and the seal should be clean and uncracked",
                "Inspect visible EVAP hoses and the filler neck",
                "Plug-in hybrids commonly use a sealed, pressurised fuel tank: refuel as described in the owner's manual",
                "If it returns, a workshop can find the leak with a smoke test",
            ),
            condition = true,
            advice = adv("Yes, you can keep driving. Fuel vapour escapes, so have it fixed within a few weeks. If you smell petrol strongly, have it checked soon."),
        ),
        Topic(
            "evap",
            match = rx("""Evaporative|\bEVAP\b|Purge|Fuel Tank Pressure|Fuel Vapou?r|Fuel Tank Isolation|Vent Valve|Canister|Refueling|Fuel Filler"""),
            system = EMISSIONS, subsystem = fixed("Evaporative emissions (fuel vapour) system"),
            about = fixed("The evaporative (EVAP) system collects petrol vapour from the tank in a charcoal canister and later burns it in the engine."),
            causes = list("Faulty purge or vent valve", "Blocked or disconnected EVAP hose", "Faulty tank pressure sensor"),
            checks = list(
                "Plug-in hybrids commonly use a sealed, pressurised fuel tank: refuel as described in the owner's manual",
                "Inspect the EVAP hoses and valves",
            ),
        ),
        Topic(
            "fuel_level", match = rx("Fuel Level"), system = FUEL_AIR, subsystem = fixed("Fuel level sensor"),
            about = fixed("The fuel level sensor in the tank drives the fuel gauge and the range estimate."),
            causes = list("Worn or faulty fuel level sender (float and resistor track)", "Corroded connector at the fuel tank sender", "Wiring fault"),
            checks = list(
                "Compare the fuel gauge with how much fuel goes in when you fill up",
                "Remember that on a plug-in hybrid the gauge can stay still for weeks when the engine rarely runs; that alone is not a fault",
            ),
        ),
        Topic(
            "fuel_misc", match = rx("Fuel"), system = FUEL_AIR, subsystem = fixed("Fuel system"),
            about = fixed("The engine computer monitors the fuel system (level, temperature, composition and supply)."),
            causes = list("Faulty fuel system sensor", "Wiring or connector fault", "Old or contaminated fuel"),
            checks = list(CHECK_FRESH_FUEL),
        ),
        Topic(
            "cat_eff", match = rx("""Catalyst.{0,30}Efficiency|Efficiency Below Threshold"""), system = EMISSIONS,
            subsystem = fixed("Catalytic converter"),
            about = fixed(
                "The engine computer compares the oxygen sensors before and after the catalytic converter and has found that " +
                    "the converter is no longer cleaning the exhaust well enough.",
            ),
            causes = list(
                "Aged or damaged catalytic converter",
                "Exhaust leak near the oxygen sensors",
                "Faulty or slow oxygen sensor giving a false result",
                "Engine mixture problems or misfires",
            ),
            checks = list(
                "Fix misfire, fuel trim and oxygen sensor codes first",
                "Check for exhaust leaks",
                CHECK_CHARGE_MODE,
                "Replace the converter with a quality, type-approved part if it is confirmed worn",
            ),
            condition = true,
        ),
        Topic(
            "catalyst", match = rx("Catalyst|Catalytic"), system = EMISSIONS, subsystem = fixed("Catalytic converter"),
            about = fixed("The catalytic converter cleans the exhaust; some cars monitor its temperature or heat it electrically."),
            causes = list("Faulty catalyst temperature sensor or heater", "Wiring damaged by exhaust heat"),
            checks = list("Inspect the wiring near the exhaust for heat damage"),
        ),
        Topic(
            "pcv", match = rx("""Crankcase|\bPCV\b"""), system = EMISSIONS, subsystem = fixed("Crankcase ventilation (PCV)"),
            about = fixed("The crankcase ventilation system routes oil vapour from the engine back into the intake to be burnt."),
            causes = list("Cracked or disconnected ventilation hose", "Sticking PCV valve", "Faulty PCV heater (if fitted)"),
            checks = list("Inspect the crankcase ventilation hoses for cracks and loose ends"),
        ),
        Topic(
            "exhaust", match = rx("Exhaust"), system = EMISSIONS, subsystem = fixed("Exhaust system sensors"),
            about = fixed("The engine computer monitors exhaust pressure or temperature with sensors in the exhaust."),
            causes = list("Faulty exhaust sensor", "Wiring damaged by exhaust heat", "Exhaust leak or restriction"),
            checks = list("Inspect the wiring near the exhaust for heat damage"),
        ),
        Topic(
            "vss", match = rx("""Vehicle Speed|Wheel Speed"""), system = SPEED_IDLE, subsystem = fixed("Vehicle speed signal"),
            about = fixed("Vehicle speed is normally calculated from the wheel speed sensors and shared over the data network."),
            causes = list("Wheel speed sensor or ABS fault", "Network communication problem", CAUSE_12V),
            checks = list("Check whether the speedometer works and whether the ABS warning light is on", CHECK_MANY_CODES),
        ),
        Topic(
            "cruise", match = rx("""Cruise|Speed Control"""), system = SPEED_IDLE, subsystem = fixed("Cruise control"),
            about = fixed("The cruise control system and its switches are monitored by the control unit."),
            causes = list("Faulty cruise control switch", "Brake switch fault", "Wiring or connector fault"),
            checks = list("Check whether the brake lights work (the brake switch also cancels cruise control)"),
            advice = adv("Yes. Cruise control may not work until it is fixed."),
        ),
        Topic(
            "brake_p", match = rx("Brake"), system = CHASSIS, subsystem = fixed("Brake pedal switch / brake booster signals"),
            about = fixed(
                "The brake pedal switch tells several control units when you brake (brake lights, cruise control and the " +
                    "drive control); some cars also monitor the brake booster.",
            ),
            causes = list("Faulty or badly adjusted brake pedal switch", "Wiring or connector fault", "Faulty brake booster sensor"),
            checks = list("Check that the brake lights work (ask someone to look)", "Check the switch at the top of the brake pedal for damage or misadjustment"),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_BRAKE_SWITCH),
        ),
        Topic(
            "power_steering_p", match = rx("Power Steering"), system = CHASSIS, subsystem = fixed("Power steering signal"),
            about = fixed("The engine or drive control receives a signal from the power steering system."),
            causes = list("Power steering sensor or switch fault", "Wiring or connector fault"),
            checks = list("Check whether the steering feels normal"),
        ),
        Topic(
            "ac_p", match = rx("""A/C|Air Conditioning|Air Conditioner|Refrigerant"""), system = BODY,
            subsystem = fixed("Air conditioning"),
            about = fixed("The air conditioning is monitored and controlled together with the engine and drive system."),
            causes = list("Low refrigerant (leak)", "Faulty pressure sensor or relay", "Wiring or connector fault"),
            checks = list(
                "Check whether the air conditioning cools; have the refrigerant level checked if it does not",
                "On many plug-in hybrids the air conditioning also helps cool the drive battery, so do not leave it unrepaired",
            ),
        ),
        Topic(
            "alternator", match = rx("""Generator|Alternator|Charging System"""), system = COMPUTER_OUTPUTS,
            subsystem = fixed("12 V charging system"),
            about = fixed("This code concerns the charging system (alternator) that would normally charge the 12 V battery."),
            causes = list(CAUSE_12V, "DC/DC converter fault", "The scan tool misread the code"),
            checks = list(CHECK_12V, "Look for DC/DC converter codes", CHECK_DEALER_TOOL),
            condition = true, note = fixed(NOTE_ALTERNATOR),
        ),
        Topic(
            "voltage", match = rx("""System Voltage|\bBattery\b"""), system = COMPUTER_OUTPUTS, subsystem = fixed("12 V supply"),
            about = fixed(
                "The 12 V system is fed by the 12 V auxiliary battery, which on the Outlander PHEV is charged by the DC/DC " +
                    "converter rather than an alternator.",
            ),
            causes = list(
                CAUSE_12V,
                "DC/DC converter not charging properly (look for DC/DC codes)",
                "Loose or corroded battery terminals or ground straps",
                "Battery drained by long standing or an accessory left connected",
            ),
            checks = list(CHECK_12V, "Clean and tighten the battery terminals and check the ground straps", CHECK_DONGLE),
            severity = sev(CHECK_NOW),
            advice = adv(
                "Get it checked within days. A weak 12 V system can stop the car from going into READY or make functions " +
                    "switch off without warning.",
            ),
        ),
        Topic(
            "sensor_ref", match = rx("""Reference Voltage|Sensor Reference|5 ?Volt Reference|5V Reference|Sensor Supply"""),
            system = COMPUTER_OUTPUTS, subsystem = fixed("Sensor supply voltage (5 V reference)"),
            about = fixed(
                "The engine computer supplies a regulated 5 V reference to several sensors. This supply is open, shorted " +
                    "or out of range, which can affect several sensors at once.",
            ),
            causes = list(
                "A sensor on the shared supply with an internal short",
                "Wiring shorted to ground or voltage",
                "Water in a connector",
                "Fault in the control module (less common)",
            ),
            checks = list(
                "Note which sensor codes are stored at the same time; they probably share this supply",
                "Inspect the engine wiring harness for chafing near hot or moving parts, and connectors for water",
                "A workshop can unplug the sensors on this supply one at a time: if the fault clears, that sensor is shorted",
            ),
            severity = sev(CHECK_NOW), condition = true,
        ),
        Topic(
            "starter", match = rx("Starter"), system = POWERTRAIN_ENGINE, subsystem = fixed("Engine starting circuit"),
            about = fixed("The control unit monitors the circuit used to start the engine."),
            causes = list("Relay or fuse fault", "Wiring or connector fault", CAUSE_12V),
            checks = list("Check the relevant fuses and relays", CHECK_12V),
        ),
        Topic(
            "power_relay",
            match = rx("""Power Relay|Main Relay|Ignition Switch|Keep Alive|Engine Off Timer|Power Supply|Power Mode|Run/Crank|Accessory"""),
            system = COMPUTER_OUTPUTS, subsystem = fixed("Module power supply and relays"),
            about = fixed("The control module monitors its own power supply, main relay or ignition (power mode) signal."),
            causes = list(CAUSE_12V, "Faulty main relay or blown fuse", "Loose or corroded power or ground connection"),
            checks = list(CHECK_12V, "Check the fuses and main relay for the engine control module"),
        ),
        Topic(
            "module_internal",
            match = rx("""Processor|Memory|Check ?Sum|\bROM\b|\bRAM\b|EEPROM|Watchdog|Internal Control Module|Internal Engine|Internal Temperature"""),
            system = COMPUTER_OUTPUTS, subsystem = fixed("Control module (internal)"),
            causes = list("Internal fault in the control module", "Interrupted software update", "Severe 12 V voltage dip or spike"),
            checks = list(CHECK_12V, "Ask a Mitsubishi dealer about software updates or re-programming"),
            severity = sev(CHECK_NOW),
        ),
        Topic(
            "module",
            match = rx("""Control Module|ECM|PCM|TCM|Software|Incompatib|Programm|\bVIN\b|Calibration|Configuration|Immobili|Security|\bKey\b"""),
            system = COMPUTER_OUTPUTS, subsystem = fixed("Control module"),
            causes = list("Module not programmed or configured correctly (for example after replacement)", "Internal module fault", CAUSE_12V),
            checks = list(CHECK_DEALER_TOOL, "Think back: was any module replaced, updated or disconnected recently?"),
        ),
        Topic(
            "transmission",
            match = rx(
                """Transmission|Shift|Gear|Clutch|Torque Converter|\bTCM\b|Transfer Case|PRNDL|Turbine|Output Shaft|""" +
                    """Input Shaft|Range Sensor|4WD|4x4|Four Wheel|\bPark\b|Pressure Control Solenoid|Differential|""" +
                    """Driveline|Overdrive|Line Pressure|Selector|Tiptronic|Torque Management|Intermediate Shaft|Hydraulic""",
            ),
            system = TRANSMISSION, subsystem = fixed("Transmission / drive unit"),
            about = fixed("The drive unit's control system monitors its sensors, switches and actuators."),
            causes = list("Faulty sensor, switch or solenoid in the drive unit", "Wiring or connector fault", "Low or old transmission fluid"),
            checks = list("Note any change in how the car drives (jerks, noises, loss of drive)", CHECK_DEALER_TOOL),
            note = { t -> if (CONVENTIONAL_GEARBOX.containsMatchIn(t)) NOTE_GEARBOX else null },
        ),
        Topic(
            "outputs", match = rx("""\bMIL\b|Malfunction Indicator|Lamp|Gauge|Tachometer|Speedometer|Indicator|Display|Output"""),
            system = COMPUTER_OUTPUTS, subsystem = fixed("Warning lamps and output circuits"),
            about = fixed("The control module drives warning lamps, gauges or other outputs and monitors those circuits."),
            causes = list("Wiring or connector fault", "Fault in the instrument cluster or output device"),
            checks = list("Check that the warning lights come on briefly when the car is switched on"),
        ),
        Topic(
            "p_other", letters = "P",
            causes = list("Faulty component in this system", "Wiring or connector problem", "Related fault elsewhere (check other stored codes)"),
            checks = list(READ_ALL, CHECK_DEALER_TOOL),
            note = { t -> if (CONVENTIONAL_GEARBOX.containsMatchIn(t)) NOTE_GEARBOX else null },
        ),

        // ---------------------------------------------------------------- C: chassis
        Topic(
            "c_wheel", letters = "C", match = rx("""Wheel Speed|Tone Wheel|Speed Sensor"""), system = CHASSIS,
            subsystem = fixed("Wheel speed sensors (ABS)"),
            about = fixed("Each wheel has a speed sensor used by ABS, traction and stability control, and by other systems that need road speed."),
            causes = list(
                "Damaged or corroded sensor wiring or connector near the wheel (water, salt, stones)",
                "Dirt, rust or metal debris on the sensor tip or the encoder (tone) ring",
                "Faulty wheel speed sensor",
                "Wheel bearing play or a damaged encoder ring",
            ),
            checks = list(
                "Inspect the wiring from each sensor along the suspension, especially after winter",
                "Check the wheel bearings for play or noise",
                "If your tool can read the ABS unit, compare the four wheel speeds while driving slowly in a straight line",
            ),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_ABS),
        ),
        Topic(
            "c_brake_fluid", letters = "C", match = rx("Brake Fluid"), system = CHASSIS, subsystem = fixed("Brake fluid"),
            about = fixed("The brake system monitors the brake fluid level or pressure."),
            causes = list("Low brake fluid (worn pads or a leak)", "Faulty fluid level switch", "Wiring fault"),
            checks = list("Check the brake fluid level in the reservoir", "Look for leaks at the wheels and under the car"),
            severity = sev(CHECK_NOW),
            advice = adv(
                "Check the brake fluid level now. If it is below MIN, or the brake pedal feels soft or travels further than " +
                    "usual, do not drive: arrange recovery.",
            ),
        ),
        Topic(
            "c_brake_temp", letters = "C", match = rx("Brake Temperature"), system = CHASSIS, subsystem = fixed("Brake temperature"),
            about = fixed("The brake system has estimated or measured that the brakes are overheating."),
            causes = list("Long downhill braking or very hard use", "Sticking brake caliper or handbrake", "Faulty sensor or estimate"),
            checks = list("Let the brakes cool, then check whether one wheel is much hotter than the others (sticking brake)"),
            severity = sev(STOP), condition = true,
            advice = adv(
                "Stop as soon as it is safe and let the brakes cool down; overheated brakes lose effectiveness. If a wheel " +
                    "stays hot or smells burnt, have the brakes checked before driving on.",
            ),
        ),
        Topic(
            "c_brake_switch", letters = "C", match = rx("""Brake Pedal Switch|Brake Switch|Brake Pedal Position|Stop Lamp"""),
            system = CHASSIS, subsystem = fixed("Brake pedal switch / brake light signal"),
            about = fixed("The brake pedal switch or sensor tells the ABS, stability control and other units when, and how hard, you brake."),
            causes = list("Faulty or badly adjusted brake pedal switch or sensor", "Wiring or connector fault", "Blown brake light fuse or bulb (for brake light circuits)"),
            checks = list("Check that the brake lights work (ask someone to look)", "Check the switch at the top of the brake pedal for damage or misadjustment"),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_BRAKE_SWITCH),
        ),
        Topic(
            "c_abs", letters = "C",
            match = rx(
                """ABS|Anti-?Lock|Inlet Control|Outlet Control|Hydraulic|Pump Motor|Valve Relay|TCS|Traction|Stability|""" +
                    """Brake|Booster|Yaw|Lateral|Longitudinal|Accelerat|Roll Rate|Vehicle Dynamics|Hill|Driving Torque|Solenoid""",
            ),
            system = CHASSIS, subsystem = fixed("ABS, traction and stability control"),
            about = fixed("The ABS unit controls brake pressure at each wheel for ABS, traction and stability control."),
            causes = list("Fault in the ABS hydraulic unit or its control unit", "Wiring or connector fault", CAUSE_12V),
            checks = list("Check whether the ABS or stability control warning lights are on", "Check the brake fluid level", CHECK_12V),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_ABS),
        ),
        Topic(
            "c_steering", letters = "C", match = rx("Steering"), system = CHASSIS, subsystem = fixed("Steering"),
            about = fixed("The steering angle sensor and electric power steering are monitored by their control units."),
            causes = list("Steering angle sensor needs calibration (for example after wheel alignment)", "Faulty sensor", "Wiring or connector fault"),
            checks = list("Check whether the steering feels normal", "A workshop can calibrate the steering angle sensor"),
            severity = sev(CHECK_NOW), advice = adv(ADVICE_STEERING),
        ),
        Topic(
            "c_tyre_low", letters = "C", match = rx("""Low Tire Pressure|Tire Pressure Low|Low Tyre"""), system = CHASSIS,
            subsystem = fixed("Tyre pressure monitoring (TPMS)"),
            about = fixed("The tyre pressure monitoring system has detected that one or more tyres are below the warning threshold."),
            causes = list("Puncture or slow leak", "Natural pressure drop in cold weather", "Pressures not adjusted after a tyre change"),
            checks = list("Check all four tyres with a gauge when cold and set them to the pressures on the door-pillar label", "Look for nails, cuts or bulges"),
            severity = sev(CHECK_NOW), condition = true,
            advice = adv(
                "Check the tyre pressures at the first safe opportunity. If a tyre is visibly flat or the car pulls to one " +
                    "side, stop and change the wheel or call for assistance.",
            ),
        ),
        Topic(
            "c_tyre", letters = "C", match = rx("Tire|Tyre"), system = CHASSIS, subsystem = fixed("Tyre pressure monitoring (TPMS)"),
            about = fixed("The tyre pressure monitoring system uses sensors or wheel speed data to watch the tyre pressures."),
            causes = list("Faulty tyre pressure sensor or flat sensor battery", "Sensors not registered after a wheel change", "Wrong tyre size"),
            checks = list("Check the tyre pressures manually", "A tyre shop can test and register the sensors"),
        ),
        Topic(
            "c_susp", letters = "C", match = rx("""Suspension|Ride Height|Damp|Shock|\bLevel\b|Air Spring"""), system = CHASSIS,
            subsystem = fixed("Suspension"),
            causes = list("Faulty suspension sensor or actuator", "Wiring or connector fault"),
            checks = list("Look for damage to the suspension and its wiring"),
        ),
        Topic(
            "c_4wd", letters = "C", match = rx("""Wheel Drive|4WD|All Wheel|AWD|Transfer"""), system = CHASSIS,
            subsystem = fixed("Four-wheel drive control"),
            causes = list("Fault in the four-wheel drive control or its signals", "Wiring or connector fault"),
            checks = list("Note whether four-wheel drive or traction behaves differently", CHECK_DEALER_TOOL),
        ),
        Topic(
            "c_other", letters = "C", system = CHASSIS,
            causes = list("Faulty chassis sensor or actuator", "Wiring or connector fault", CAUSE_12V),
            checks = list("Check whether any chassis warning lights (ABS, brake, steering) are on", CHECK_DEALER_TOOL),
        ),

        // ---------------------------------------------------------------- B: body
        Topic(
            "b_srs", letters = "B",
            match = rx(
                """Deployment|Air ?Bag|Seat ?belt|Pretensioner|Restraint|Occupant|Load Limiter|Frontal|Knee Bolster|Squib|""" +
                    """Crash|Impact|Side Sensor|Rollover""",
            ),
            system = BODY, subsystem = fixed("Airbags and seatbelt pretensioners (SRS)"),
            about = fixed(
                "The airbag (SRS) control unit constantly checks the airbags, seatbelt pretensioners and crash sensors. It has " +
                    "found a fault in the part named in this code, so it may not work in a crash; the airbag warning light is normally on.",
            ),
            causes = list(
                "Loose or corroded connector (often under the seats, behind the dashboard or in the steering wheel)",
                "Worn clock spring in the steering wheel (for driver airbag and steering wheel switch circuits)",
                "Faulty airbag, pretensioner or sensor",
                "Recent work on seats, dashboard or steering wheel",
            ),
            checks = list(
                "Think back: were the seats, dashboard or steering wheel worked on recently?",
                "Have a workshop read the airbag unit with a suitable tool and check the connectors and wiring",
            ),
            severity = sev(CHECK_NOW), condition = true, srs = true, advice = adv(ADVICE_SRS),
        ),
        Topic(
            "b_climate", letters = "B",
            match = rx(
                """A/C|Blower|Air Flow Control|Defrost|HVAC|Solar|In Air Temp|In-Car|Outside Air|Ambient|Evaporator|Heater|""" +
                    """Climate|Control Head|Recirculation|Cooling Fan|Compressor|Refrigerant|Mode Door|Blend|Air Mix|\bVent\b""",
            ),
            system = BODY, subsystem = fixed("Climate control"),
            about = fixed("The climate control monitors its sensors, blower, flaps and air-conditioning components."),
            causes = list("Faulty sensor, flap motor or blower", "Wiring or connector fault", "Low refrigerant (for air-conditioning codes)"),
            checks = list("Check which climate functions do not work (heat, cooling, fan speed, air direction)"),
            advice = adv(
                "Yes, if the windscreen stays clear. In cold or wet weather, do not drive if you cannot keep the windscreen " +
                    "free of mist and ice. Have it checked within a few weeks.",
            ),
        ),
        Topic(
            "b_power", letters = "B",
            match = rx("""Device Power|Device Ground|Ignition \d|Ignition Run|Battery|System Voltage|Volt Reference|Power Mode|Run/Crank|Accessory|Ground"""),
            system = BODY, subsystem = fixed("Module power supply"),
            about = fixed("A body control module reports a problem with its power supply, ground or ignition signal."),
            causes = list(CAUSE_12V, "Blown fuse or failed relay", "Loose or corroded power or ground connection"),
            checks = list(CHECK_12V, "Check the fuses for the affected functions", CHECK_DONGLE),
        ),
        Topic(
            "b_module", letters = "B",
            match = rx("""EEPROM|\bRAM\b|\bROM\b|Option Configuration|Watchdog|\bCOP\b|Check ?sum|Configuration|Programm|Calibration|Module|Internal|Software"""),
            system = BODY, subsystem = fixed("Body control module (internal)"),
            causes = list("Internal module fault", "Module not configured after replacement", CAUSE_12V),
            checks = list(CHECK_12V, CHECK_DEALER_TOOL),
        ),
        Topic(
            "b_security", letters = "B", match = rx("""Security|Immobili|\bKey|Alarm|Theft|Transponder|Remote|Keyless|Passive|Entry"""),
            system = BODY, subsystem = fixed("Keys, immobiliser and security"),
            causes = list("Weak key battery", "Faulty key, antenna or immobiliser unit", "Wiring or connector fault"),
            checks = list("Try the spare key", "Replace the key battery"),
        ),
        Topic(
            "b_lights", letters = "B", match = rx("""Lamp|Light|Turn Signal|Headlamp|Headlight|\bFog\b|Hazard|Indicator|Bulb"""),
            system = BODY, subsystem = fixed("Lights and indicators"),
            causes = list("Blown bulb or failed LED unit", "Corroded lamp connector", "Wiring fault or blown fuse"),
            checks = list("Walk around the car and check all lights, including brake lights and indicators"),
            advice = adv(
                "Usually yes, but check all lights first: a failed brake light, indicator or headlight must be fixed before " +
                    "driving, especially in the dark.",
            ),
        ),
        Topic(
            "b_instruments", letters = "B", match = rx("""Speedometer|Tachometer|Gauge|Cluster|Display|Chime|Odometer|Ajar|Warning"""),
            system = BODY, subsystem = fixed("Instrument cluster and warnings"),
            causes = list("Fault in the instrument cluster", "Faulty switch or sensor feeding the display", "Wiring or connector fault"),
            checks = list("Check that warning lights come on briefly when the car is switched on"),
        ),
        Topic(
            "b_comfort", letters = "B",
            match = rx("""Door|Lock|Window|Seat|Mirror|Wiper|Washer|Horn|Sunroof|Roof|Trunk|Liftgate|Tailgate|Hood|Steering Wheel|Pedal"""),
            system = BODY, subsystem = fixed("Doors, windows, seats and comfort"),
            causes = list("Faulty switch, motor or actuator", "Wiring broken in a door hinge area or under a seat", "Blown fuse"),
            checks = list("Check which function does not work", "Check the fuses for that function"),
        ),
        Topic(
            "b_audio", letters = "B",
            match = rx("""Audio|Radio|Speaker|Antenna|Entertainment|Player|Changer|Navigation|Telephone|Phone|Media|Disc|Cassette|Video|Microphone"""),
            system = BODY, subsystem = fixed("Audio and infotainment"),
            causes = list("Faulty audio or infotainment unit", "Wiring or connector fault"),
            checks = list("Check which audio function does not work", "Restart the system by switching the car fully off and on"),
            severity = sev(INFO),
        ),
        Topic(
            "b_other", letters = "B", system = BODY,
            causes = list("Faulty body sensor, switch or actuator", "Wiring or connector fault", CAUSE_12V),
            checks = list("Check which body function does not work", CHECK_12V, CHECK_DEALER_TOOL),
        ),
    )

    /** Topic ids, for tests and diagnostics. */
    internal fun topicIdFor(code: String, title: String): String = TOPICS.first { it.matches(code, title) }.id
}
