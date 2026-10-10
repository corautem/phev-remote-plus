package io.github.anwo91.phevlink.core.obd.dtc

/**
 * How urgently a fault code needs attention. Ordered from least to most serious, so
 * `maxOf(a, b)` / `a > b` compare urgency.
 */
enum class DtcSeverity(val label: String, val advice: String) {
    INFO(
        "Information",
        "No immediate effect on driving. Mention it at the next service.",
    ),
    CHECK_SOON(
        "Check soon",
        "You can drive normally. Have it looked at within the next few weeks or at the next service.",
    ),
    CHECK_NOW(
        "Check now",
        "Get it checked within days. Performance, emissions or a safety system may be affected, or damage may follow. " +
            "Avoid long trips, towing and hard driving until it is fixed.",
    ),
    STOP(
        "Stop driving",
        "Stop as soon as it is safe and switch off. Driving on risks serious damage or a safety problem. " +
            "Arrange for the car to be checked or recovered.",
    ),
}

/** Which part of the car a code belongs to (based on the SAE J2012 code structure and the title). */
enum class DtcSystem(val label: String) {
    POWERTRAIN_ENGINE("Engine"),
    FUEL_AIR("Fuel and air metering"),
    IGNITION_MISFIRE("Ignition and misfire"),
    EMISSIONS("Emission controls"),
    SPEED_IDLE("Vehicle speed, idle control and auxiliary inputs"),
    COMPUTER_OUTPUTS("Control modules, power supply and outputs"),
    TRANSMISSION("Transmission and drivetrain"),
    HYBRID_EV("Hybrid / electric drive system"),
    CHASSIS("Chassis (brakes, ABS, steering, suspension, tyres)"),
    BODY("Body (airbags, climate, lights, comfort)"),
    NETWORK("Network communication between control modules"),
    MANUFACTURER("Manufacturer-specific (powertrain)"),
    UNKNOWN("Unknown"),
}

/** A full plain-English explanation of one diagnostic trouble code. */
data class DtcExplanation(
    /** Normalised upper-case code, e.g. "P0A80". */
    val code: String,
    /** Official description, or e.g. "Manufacturer-specific code (Mitsubishi)" when unknown. */
    val title: String,
    val system: DtcSystem,
    /** e.g. "Drive battery", "Evaporative emissions", "Oxygen sensor bank 1 sensor 1". */
    val subsystem: String?,
    val severity: DtcSeverity,
    /** 1–3 plain-English sentences: what the car detected. */
    val summary: String,
    /** Likely causes, most likely first. */
    val causes: List<String>,
    /** What to check or fix, cheapest/easiest first, including safety notes. */
    val checks: List<String>,
    /** Answer to "Can I keep driving?". */
    val driveAdvice: String,
    /** True for codes in a manufacturer-controlled range (P1xxx, P30xx–P33xx, B/C/U 1xxx–2xxx). */
    val manufacturerSpecific: Boolean,
    /** True = hand-written entry for this exact code; false = derived from rules. */
    val curated: Boolean,
    /** True if the title came from the code database or the curated list. */
    val known: Boolean,
)
