package io.github.anwo91.phevlink.core.obd.dtc

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
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_HV
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_MISFIRE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.ADVICE_NETWORK
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CAUSE_12V
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_12V
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_CHARGE_MODE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_CLEAR_RETEST
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_CLEAR_SEE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_DEALER_TOOL
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_DONGLE
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_FRESH_FUEL
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_MANY_CODES
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.CHECK_OIL
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.HV_SAFETY
import io.github.anwo91.phevlink.core.obd.dtc.DtcText.SRS_SAFETY

/** One hand-written explanation. The title always comes from codes.tsv. */
internal class CuratedDtc(
    val system: DtcSystem,
    val subsystem: String,
    val severity: DtcSeverity,
    val summary: String,
    val causes: List<String>,
    val checks: List<String>,
    val driveAdvice: String,
)

/**
 * Hand-written explanations for the codes an Outlander PHEV (2014–2017) owner is most likely to
 * see. Original text for this project: general guidance, not a substitute for a professional
 * diagnosis. Every code here must exist in codes.tsv (checked by DtcDatabaseTest).
 */
internal object CuratedDtcs {

    private val entries = LinkedHashMap<String, CuratedDtc>()

    operator fun get(code: String): CuratedDtc? = entries[code]

    val codes: Set<String> get() = entries.keys

    private fun add(
        code: String,
        system: DtcSystem,
        subsystem: String,
        severity: DtcSeverity,
        summary: String,
        causes: List<String>,
        checks: List<String>,
        advice: String = DtcText.adviceFor(severity),
    ) {
        require(code !in entries) { "Duplicate curated DTC $code" }
        entries[code] = CuratedDtc(system, subsystem, severity, summary, causes, checks, advice)
    }

    init {
        engineTiming()
        cooling()
        airMetering()
        throttle()
        fuel()
        misfireAndIgnition()
        oxygenSensors()
        emissions()
        speedIdleVoltageModules()
        hybrid()
        network()
        chassisAndBody()
    }

    // ------------------------------------------------------------------------------------
    // Engine timing, crank/cam sensors, engine start
    // ------------------------------------------------------------------------------------

    private fun engineTiming() {
        fun vvt(code: String, cam: String, camName: String) = add(
            code, POWERTRAIN_ENGINE, "Variable valve timing, camshaft $cam ($camName), bank 1", CHECK_NOW,
            "The engine computer adjusts the timing of camshaft $cam with engine oil pressure through an oil control " +
                "valve. The measured camshaft position is more advanced than commanded, or does not follow the command well enough.",
            listOf(
                "Low, old or wrong-grade engine oil (the variable valve timing is driven by oil pressure)",
                "Sticking or faulty oil control valve for camshaft $cam, or a blocked filter screen in front of it",
                "Faulty variable timing actuator (cam phaser) on the camshaft",
                "Stretched timing chain or worn chain tensioner (often together with P0016 or P0017)",
                "Wiring or connector fault at the oil control valve",
            ),
            listOf(
                CHECK_OIL,
                "Read all codes: P0016/P0017 together with this code point towards the timing chain; circuit codes " +
                    "(P0010/P0013) point to the oil control valve wiring",
                "Unplug the oil control valve connector, look for oil or corrosion inside, and refit it firmly",
                "A workshop can test the oil control valve and compare commanded and actual camshaft angle in live data",
                "If the timing chain is suspected, have it checked before long trips: a chain that jumps a tooth can damage the engine",
            ),
            "Usually drivable for short trips, but the engine may idle roughly, lack power and use more fuel. Get it " +
                "checked within days and change the oil if it is due. Stop if the engine starts rattling loudly or the " +
                "oil pressure warning comes on.",
        )
        vvt("P0011", "A", "normally the intake camshaft")
        vvt("P0014", "B", "normally the exhaust camshaft")

        fun correlation(code: String, sensor: String, camName: String) = add(
            code, POWERTRAIN_ENGINE, "Valve timing: crankshaft vs camshaft $sensor ($camName)", CHECK_NOW,
            "The engine computer compares the crankshaft and camshaft position signals. They no longer line up as " +
                "they should, so either the valve timing has shifted or one of the signals is wrong.",
            listOf(
                "Stretched timing chain, worn chain guides or a weak chain tensioner",
                "Low or dirty engine oil upsetting the variable valve timing",
                "Sticking oil control valve or faulty cam phaser",
                "Faulty camshaft or crankshaft position sensor, or damaged sensor wiring",
                "Timing chain fitted one tooth off after an earlier repair",
            ),
            listOf(
                CHECK_OIL,
                "Listen for a rattle from the timing chain end of the engine during the first seconds after a cold start",
                "Inspect the camshaft and crankshaft sensor connectors and wiring",
                "If your tool shows it, compare commanded and actual camshaft angle in live data with a warm engine",
                "A workshop can check the timing marks or measure chain stretch; fix this early, as a chain that jumps " +
                    "a tooth can damage the engine",
            ),
            "Usually drivable for short trips, but the engine may start poorly, run roughly and lack power. Get it " +
                "checked within days. Stop if the engine rattles loudly, misfires badly or the oil pressure warning comes on.",
        )
        correlation("P0016", "A", "normally the intake camshaft")
        correlation("P0017", "B", "normally the exhaust camshaft")

        val engineMayNotStart =
            "The car may still drive on electric power, but the engine may refuse to start or may stall. Get it " +
                "checked within days and avoid trips where you depend on the engine (long distances, low drive battery)."

        add(
            "P0335", POWERTRAIN_ENGINE, "Crankshaft position sensor", CHECK_NOW,
            "The crankshaft position sensor tells the engine computer the engine speed and the position of the " +
                "pistons. Its signal is missing or electrically wrong, so the engine may not start or may stall.",
            listOf(
                "Damaged wiring or a loose or corroded connector at the crankshaft sensor",
                "Faulty crankshaft position sensor",
                "Metal debris on the sensor tip, or a damaged trigger (reluctor) wheel",
                "Supply or ground problem for the sensor",
            ),
            listOf(
                "Inspect the sensor connector and wiring for oil contamination, chafing or rodent damage",
                "If the engine runs, watch engine speed (rpm) in live data: drop-outs confirm an intermittent signal",
                "A workshop can check the sensor signal with an oscilloscope before replacing the sensor",
                "After the repair, clear the code and confirm the engine starts reliably several times",
            ),
            engineMayNotStart,
        )
        add(
            "P0336", POWERTRAIN_ENGINE, "Crankshaft position sensor", CHECK_NOW,
            "The crankshaft position sensor signal is present but irregular: pulses are missing, extra or arrive " +
                "at the wrong time. The engine may misfire, stall or fail to start.",
            listOf(
                "Loose connector or damaged wiring causing an intermittent signal",
                "Metal debris on the sensor tip or a damaged trigger (reluctor) wheel",
                "Faulty crankshaft position sensor",
                "Electrical interference from a damaged ignition component or poorly routed wiring",
            ),
            listOf(
                "Inspect the sensor connector and wiring; make sure the harness is clipped in its original routing",
                "Remove the sensor and check the tip for debris or damage (only if easy to reach)",
                "Check for misfire or ignition codes stored at the same time",
                "A workshop can check the signal with an oscilloscope",
            ),
            engineMayNotStart,
        )
        add(
            "P0340", POWERTRAIN_ENGINE, "Camshaft position sensor (bank 1 or single sensor)", CHECK_NOW,
            "The camshaft position sensor tells the engine computer which cylinder is about to fire. Its signal is " +
                "missing or electrically wrong, so the engine may take longer to start, run poorly or not start.",
            listOf(
                "Loose, oily or corroded connector at the camshaft sensor",
                "Damaged wiring",
                "Faulty camshaft position sensor",
                "Timing chain or camshaft trigger problem (less common; see P0016)",
                "Low 12 V voltage during engine start",
            ),
            listOf(
                "Inspect the sensor connector for oil inside (a leaking seal can let oil in) and refit it firmly",
                "Check the wiring for chafing",
                CHECK_12V,
                "Replace the sensor if wiring and supply are good, then clear the code and test several starts",
            ),
            engineMayNotStart,
        )
        add(
            "P0A0F", POWERTRAIN_ENGINE, "Engine start (hybrid system)", CHECK_NOW,
            "The hybrid system tried to start the petrol engine, but it did not start and run as expected. The engine " +
                "is started automatically when needed, so you may not have noticed the attempt.",
            listOf(
                "Very low fuel level, or the tank ran empty",
                "Old or stale petrol (possible if the engine has rarely run for months)",
                "Fuel supply problem: fuel pump, fuel pump relay or fuse",
                "Crankshaft or camshaft position sensor fault (look for P0335 or P0340)",
                "Ignition or immobiliser problem",
                "Weak 12 V battery affecting the engine computer during the start",
            ),
            listOf(
                "Check the fuel level; add a decent amount of fresh petrol if it is low or the fuel is old",
                "Read all codes: other engine codes stored at the same time usually show the real cause",
                CHECK_12V,
                "Check the fuses and relays for the fuel pump and the engine computer (owner's manual fuse chart)",
                "If it keeps happening, have a workshop check fuel pressure, spark and sensor signals",
            ),
            "You can usually keep driving on electric power while the drive battery has charge, but the engine may not " +
                "be available to drive or recharge. Get it checked within days and avoid long trips. If a message tells " +
                "you to stop, stop safely.",
        )
    }

    // ------------------------------------------------------------------------------------
    // Cooling, thermostat, oil pressure
    // ------------------------------------------------------------------------------------

    private fun cooling() {
        val ectSub = "Engine coolant temperature sensor"
        add(
            "P0116", POWERTRAIN_ENGINE, ectSub, CHECK_SOON,
            "The coolant temperature reading does not behave as expected: for example it does not rise as the engine " +
                "warms up, or it disagrees with other temperature sensors after the car has stood.",
            listOf(
                "Thermostat stuck open, so the engine warms up too slowly (see also P0128)",
                "Drifting coolant temperature sensor",
                "Low coolant level leaving the sensor in air",
                "High resistance or corrosion in the sensor connector",
            ),
            listOf(
                "Check the coolant level in the expansion tank with the engine cold",
                "After the car has stood overnight, compare coolant temperature with intake and outside air temperature " +
                    "in live data: they should be within a few degrees",
                "Watch the coolant temperature with the engine running: it should rise steadily to normal working " +
                    "temperature (typically around 80–100 °C)",
                CHECK_CHARGE_MODE,
                "Inspect the sensor connector for corrosion",
            ),
        )
        add(
            "P0117", POWERTRAIN_ENGINE, ectSub, CHECK_SOON,
            "The coolant temperature sensor signal voltage is too low, which the engine computer reads as an " +
                "impossibly hot engine. It uses a substitute value instead.",
            listOf(
                "Faulty coolant temperature sensor (internal short)",
                "Sensor signal wire shorted to ground",
                "Coolant or water inside the sensor connector",
            ),
            listOf(
                "Compare the coolant temperature in live data with reality: a reading far too hot confirms the fault",
                "Inspect the connector and wiring for damage, chafing and moisture",
                "Measure the sensor resistance against the service data at a known temperature",
                "Replace the sensor if wiring is good, top up coolant afterwards and clear the code",
            ),
            "You can usually drive, but the cooling fan may run all the time and the engine may start or run poorly. " +
                "Watch the temperature warning light and get it fixed within a few weeks.",
        )
        add(
            "P0118", POWERTRAIN_ENGINE, ectSub, CHECK_SOON,
            "The coolant temperature sensor signal voltage is too high, which the engine computer reads as an " +
                "impossibly cold engine, typically because the circuit is open. It uses a substitute value instead.",
            listOf(
                "Unplugged, loose or corroded connector at the sensor",
                "Broken signal wire or open circuit",
                "Faulty coolant temperature sensor (open internally)",
                "Poor sensor ground",
            ),
            listOf(
                "Check live data: a reading near -40 °C usually means an open circuit",
                "Check that the connector is fully plugged in and clean",
                "Follow the wiring for breaks or damage",
                "Replace the sensor if wiring is good, top up coolant afterwards and clear the code",
            ),
            "You can usually drive, but the engine may run rich, use more fuel and the cooling fan may run all the " +
                "time. Get it fixed within a few weeks.",
        )
        add(
            "P0125", POWERTRAIN_ENGINE, "Engine warm-up (closed-loop fuel control)", CHECK_SOON,
            "The engine took too long to warm up enough for the engine computer to start using oxygen sensor feedback " +
                "for fuel control.",
            listOf(
                "Thermostat stuck open or opening too early",
                "Faulty or drifting coolant temperature sensor",
                "Low coolant level",
                "Possible contributing factor on a plug-in hybrid: very cold weather combined with short engine running periods",
            ),
            listOf(
                "Check the coolant level with the engine cold",
                "Watch coolant temperature in live data while the engine warms up; it should climb steadily",
                CHECK_CHARGE_MODE,
                "Check for P0128 or coolant sensor codes; replacing a faulty thermostat often fixes both",
            ),
        )
        add(
            "P0128", POWERTRAIN_ENGINE, "Thermostat / engine warm-up", CHECK_SOON,
            "The engine did not reach its normal working temperature within the expected time. The engine computer " +
                "suspects that the thermostat opens too early or is stuck open.",
            listOf(
                "Thermostat stuck open or opening too early (most common)",
                "Faulty or drifting coolant temperature sensor",
                "Low coolant level",
                "Radiator fan running all the time (fan relay or control fault)",
                "Possible contributing factor on a plug-in hybrid: in very cold weather the engine runs in short " +
                    "periods and cools quickly, which makes a slightly weak thermostat show up sooner",
            ),
            listOf(
                "Check the coolant level with the engine cold",
                "Watch coolant temperature in live data during warm-up: a slow climb that stalls well below normal " +
                    "working temperature points to the thermostat",
                CHECK_CHARGE_MODE,
                "Check that the radiator fan does not run on a cold engine",
                "Replace the thermostat (with a new seal) and refill with coolant of the type specified for the car",
                CHECK_CLEAR_RETEST,
            ),
            "Yes, you can keep driving. Expect slightly higher fuel use, and slower cabin heating when the heat comes " +
                "from the engine. Get it fixed within a few weeks.",
        )
        add(
            "P0217", POWERTRAIN_ENGINE, "Engine cooling (overheating)", STOP,
            "The engine coolant temperature has gone above the safe limit: the engine is overheating.",
            listOf(
                "Low coolant level because of a leak",
                "Radiator cooling fan not working (fuse, relay, fan motor)",
                "Thermostat stuck closed",
                "Water pump failure or slipping drive",
                "Radiator blocked by dirt, or internally clogged",
                "Head gasket failure (less common)",
                "Faulty temperature sensor giving a false alarm (less common)",
            ),
            listOf(
                "Stop and let the engine cool for at least 30 minutes; never open the coolant cap on a hot engine",
                "With the engine cold, check the coolant level and look for leaks under the car and around hoses",
                "Check the radiator fan fuse and relay",
                "Have a workshop pressure-test the cooling system and check fan, thermostat and water pump",
            ),
            "No. Stop as soon as it is safe and switch off: driving an overheating engine can warp the cylinder head " +
                "or destroy the engine. The car can start the engine automatically while in READY, so keep it switched off " +
                "until the cause is fixed.",
        )
        add(
            "P0480", POWERTRAIN_ENGINE, "Radiator cooling fan control", CHECK_NOW,
            "The engine computer controls the radiator cooling fan through a relay or fan controller. It has found an " +
                "electrical fault in that control circuit, so the fan may not run when needed.",
            listOf(
                "Blown fan fuse or failed fan relay",
                "Faulty fan motor or fan controller",
                "Damaged wiring or a corroded connector at the fan (exposed to road spray)",
            ),
            listOf(
                "Check the fan fuses and relay (owner's manual fuse chart)",
                "With the engine hot, or with the air conditioning on, check whether the fan runs",
                "Inspect the fan connector and wiring for corrosion and damage",
                "A workshop can run the fan with an actuator test to separate wiring from fan faults",
            ),
            "Watch the temperature warning closely. Short trips in cool weather are usually fine, but in slow traffic, " +
                "warm weather or when towing the engine can overheat. If the temperature warning comes on, stop safely and switch off.",
        )
        add(
            "P0524", POWERTRAIN_ENGINE, "Engine oil pressure", STOP,
            "The engine oil pressure has dropped below the safe minimum. Without enough oil pressure the engine can be " +
                "badly damaged within minutes.",
            listOf(
                "Low engine oil level (leak or oil consumption)",
                "Worn or failing oil pump, or a blocked oil pump pickup",
                "Wrong oil viscosity or very old oil",
                "Faulty oil pressure sensor or switch giving a false warning",
                "Worn engine bearings (high mileage)",
            ),
            listOf(
                "Stop and check the oil level on the dipstick with the car on level ground; top up with the correct oil if low",
                "Look for oil leaks under the car",
                "If the level is fine but the warning remains, do not run the engine; have a workshop measure the oil " +
                    "pressure with a mechanical gauge",
                "Replace the sensor or switch only after real oil pressure has been confirmed good",
            ),
            "No. Stop as soon as it is safe and switch off. Check the oil level: if it was low and the warning goes away " +
                "after topping up, drive gently to a workshop; otherwise arrange recovery. Remember that the car can start " +
                "the engine automatically while in READY.",
        )
    }

    // ------------------------------------------------------------------------------------
    // Air metering
    // ------------------------------------------------------------------------------------

    private fun airMetering() {
        val maf = "Mass air flow (MAF) sensor"
        val mafAdvice =
            "Usually yes, but the engine may run less smoothly, hesitate or use more fuel. Get it checked within a few weeks."
        add(
            "P0100", FUEL_AIR, maf, CHECK_SOON,
            "The mass air flow sensor in the intake duct measures how much air the engine draws in, which the engine " +
                "computer uses to calculate how much fuel to inject. Its circuit has an electrical fault, so the computer " +
                "uses a substitute value.",
            listOf(
                "Loose or corroded connector at the MAF sensor (often disturbed during air filter changes)",
                "Damaged wiring",
                "Faulty MAF sensor",
                "Supply or ground problem for the sensor (check the fuse)",
            ),
            listOf(
                "Check that the air filter box clips and intake duct are fitted properly and the connector is fully latched",
                "Inspect the wiring and connector for damage and corrosion",
                "Check that the live MAF reading rises smoothly with engine speed",
                "Replace the sensor if wiring, supply and ground are good",
            ),
            mafAdvice,
        )
        add(
            "P0101", FUEL_AIR, maf, CHECK_SOON,
            "The mass air flow reading does not match what the engine computer expects from engine speed, throttle " +
                "position and other sensors.",
            listOf(
                "Air leak in the intake duct between the sensor and the engine (split hose, loose clamp)",
                "Dirty or contaminated MAF sensor element (for example from an over-oiled aftermarket air filter)",
                "Very dirty or blocked air filter",
                "Faulty MAF sensor",
            ),
            listOf(
                "Inspect the air filter and replace it if dirty",
                "Check the intake duct between the sensor and the throttle body for cracks and loose clamps",
                "Clean the sensor element with dedicated MAF cleaner spray only; never touch the element",
                "Check fuel trims in live data: large positive values suggest an intake leak",
                "Try a known-good sensor before buying a new one, if possible",
            ),
            mafAdvice,
        )
        add(
            "P0102", FUEL_AIR, maf, CHECK_SOON,
            "The MAF sensor signal is lower than it should ever be, which usually points to an open or shorted circuit " +
                "or a faulty sensor.",
            listOf(
                "Unplugged, loose or corroded connector at the MAF sensor",
                "Signal wire open or shorted to ground",
                "Faulty MAF sensor",
                "Very large intake air leak (less common)",
            ),
            listOf(
                "Check the connector is fully latched; inspect pins for corrosion",
                "Inspect the wiring for damage",
                "With a multimeter and the wiring diagram, check supply, ground and signal at the connector",
                "Replace the sensor if the wiring is good",
            ),
            mafAdvice,
        )
        add(
            "P0103", FUEL_AIR, maf, CHECK_SOON,
            "The MAF sensor signal is higher than it should ever be, which usually points to a short to a voltage " +
                "supply, a poor ground or a faulty sensor.",
            listOf(
                "Signal wire shorted to a voltage supply",
                "Poor sensor ground",
                "Faulty MAF sensor",
                "Damaged wiring or connector",
            ),
            listOf(
                "Inspect the connector and wiring for damage and corrosion",
                "With a multimeter and the wiring diagram, check supply, ground and signal at the connector",
                "Replace the sensor if the wiring is good",
            ),
            mafAdvice,
        )
        val iat = "Intake air temperature sensor"
        add(
            "P0112", FUEL_AIR, iat, CHECK_SOON,
            "The intake air temperature sensor signal voltage is too low, which the engine computer reads as " +
                "impossibly hot intake air. On many engines this sensor is built into the air flow sensor housing.",
            listOf(
                "Faulty intake air temperature sensor (or the air flow sensor it is built into)",
                "Signal wire shorted to ground",
                "Damaged or wet connector",
            ),
            listOf(
                "Compare intake air temperature in live data with outside temperature on a cold engine: a reading far too hot confirms the fault",
                "Inspect the connector and wiring",
                "Replace the sensor if wiring is good",
            ),
            "Yes, you can keep driving. Engine running and fuel use may be slightly affected. Get it fixed within a few weeks.",
        )
        add(
            "P0113", FUEL_AIR, iat, CHECK_SOON,
            "The intake air temperature sensor signal voltage is too high, which the engine computer reads as " +
                "impossibly cold air, usually because the circuit is open. Live data often shows around -40 °C.",
            listOf(
                "Unplugged or loose connector (common after air filter or intake work)",
                "Broken wire or open circuit",
                "Faulty intake air temperature sensor",
                "Poor sensor ground",
            ),
            listOf(
                "Check that the connector on the air flow sensor / intake duct is plugged in and latched",
                "Inspect the wiring for breaks",
                "Replace the sensor if the wiring is good",
            ),
            "Yes, you can keep driving. Engine running and fuel use may be slightly affected. Get it fixed within a few weeks.",
        )
    }

    // ------------------------------------------------------------------------------------
    // Electronic throttle and accelerator pedal
    // ------------------------------------------------------------------------------------

    private fun throttle() {
        val throttleSub = "Electronic throttle body (throttle position sensor A)"
        val throttleAdvice =
            "The engine computer may limit engine power (limp mode) to stay safe. Electric driving may still be " +
                "possible, but avoid situations where you need full power and get it checked within days."
        val throttleChecks = listOf(
            "Inspect the throttle body connector and wiring for corrosion, water and damage",
            "With the ignition off, remove the intake hose and look for heavy carbon deposits in the throttle body; " +
                "clean carefully with throttle-body cleaner without forcing the flap",
            "After cleaning or replacement, a throttle relearn (initialisation) may be needed; follow the service " +
                "procedure or let a workshop do it",
            "If the code returns, replace the throttle body assembly (the sensors are usually built in)",
        )
        add(
            "P0121", FUEL_AIR, throttleSub, CHECK_NOW,
            "The electronic throttle has two position sensors. Sensor A (normally one of the two in the throttle " +
                "body) gives readings that do not match what the engine computer expects from the other sensor, the air flow or the commanded position.",
            listOf(
                "Dirty throttle body (carbon build-up) making the throttle stick or move unevenly",
                "Worn or faulty throttle position sensor (built into the throttle body)",
                "Poor connection or corrosion at the throttle body connector",
                "Damaged wiring",
            ),
            throttleChecks,
            throttleAdvice,
        )
        add(
            "P0122", FUEL_AIR, throttleSub, CHECK_NOW,
            "The signal from throttle position sensor A is below the lowest value it should ever send, which usually " +
                "points to a short to ground, a missing 5 V supply or a faulty sensor.",
            listOf(
                "Signal or 5 V supply wire shorted to ground, or supply open",
                "Faulty throttle position sensor (throttle body)",
                "Corroded or loose throttle body connector",
                "Another sensor sharing the same 5 V supply pulling it down (look for other sensor codes)",
            ),
            throttleChecks,
            throttleAdvice,
        )
        add(
            "P0123", FUEL_AIR, throttleSub, CHECK_NOW,
            "The signal from throttle position sensor A is above the highest value it should ever send, which usually " +
                "points to an open ground, a short to voltage or a faulty sensor.",
            listOf(
                "Open sensor ground or signal shorted to a voltage supply",
                "Faulty throttle position sensor (throttle body)",
                "Corroded or loose throttle body connector",
            ),
            throttleChecks,
            throttleAdvice,
        )
        add(
            "P2101", FUEL_AIR, "Electronic throttle body (throttle motor)", CHECK_NOW,
            "The engine computer drives the throttle flap with an electric motor. The motor is not moving the throttle " +
                "as commanded, or draws an unexpected current.",
            listOf(
                "Dirty or sticking throttle body (carbon deposits, or ice in very cold weather)",
                "Faulty throttle motor (throttle body)",
                "Wiring or connector fault to the throttle motor",
                "Low 12 V supply voltage",
            ),
            listOf(
                "Inspect the throttle body connector and wiring",
                "Clean the throttle body carefully if it is dirty (ignition off)",
                CHECK_12V,
                "A throttle relearn may be needed after cleaning or replacement",
                "Replace the throttle body if the fault returns",
            ),
            throttleAdvice,
        )
        add(
            "P2135", FUEL_AIR, "Electronic throttle body (sensors A and B)", CHECK_NOW,
            "The throttle body has two position sensors that should always agree. Their signals now disagree by more " +
                "than allowed, so the engine computer cannot trust the throttle position.",
            listOf(
                "Worn or faulty throttle position sensors inside the throttle body",
                "Water or corrosion in the throttle body connector",
                "Damaged wiring, or a problem with the shared 5 V supply or ground",
                "Dirty throttle body (less common for this code)",
            ),
            throttleChecks,
            throttleAdvice,
        )
        add(
            "P2138", FUEL_AIR, "Accelerator pedal position sensors (D and E)", CHECK_NOW,
            "The accelerator pedal has two position sensors (normally sensors D and E) that should always agree. " +
                "Their signals now disagree, so the car cannot trust how far the pedal is pressed.",
            listOf(
                "Worn or faulty accelerator pedal sensor (part of the pedal assembly)",
                "Water or corrosion in the pedal connector (for example from wet footwell carpets)",
                "Damaged wiring between the pedal and the control unit",
                "Problem with the 5 V supply or ground shared by the pedal sensors",
            ),
            listOf(
                "Check the footwell for moisture and the pedal connector for corrosion; refit it firmly",
                "If your tool shows both pedal signals, check that both move smoothly and evenly as you press the pedal slowly",
                "Inspect the wiring under the dashboard for damage",
                "Replace the accelerator pedal assembly if the wiring is good",
            ),
            "Expect reduced power or limited speed (limp mode), and this can also affect electric driving because the " +
                "pedal signal controls the whole drive system. Drive carefully to a workshop and avoid fast roads if power is very limited.",
        )
    }

    // ------------------------------------------------------------------------------------
    // Fuel mixture, injectors, pump
    // ------------------------------------------------------------------------------------

    private fun fuel() {
        add(
            "P0171", FUEL_AIR, "Fuel mixture (fuel trim), bank 1", CHECK_SOON,
            "The engine computer has had to add much more fuel than normal to keep the air-fuel mixture correct. " +
                "The engine is getting too much air or too little fuel.",
            listOf(
                "Air leak after the air flow sensor: cracked intake hose, loose clamp, leaking intake gasket, vacuum or crankcase ventilation hose",
                "Dirty or faulty mass air flow sensor reading too low",
                "Low fuel pressure: weak fuel pump, clogged filter or faulty pressure regulator",
                "Clogged fuel injectors",
                "Exhaust leak in front of the front oxygen sensor",
                "Possible on a plug-in hybrid: old, stale fuel if the engine has rarely run",
            ),
            listOf(
                "Listen and look for air leaks around the intake hoses and manifold with the engine running",
                "Check fuel trims in live data: if long-term trim is high at idle but improves at higher engine speed, " +
                    "an intake leak is likely; if it gets worse under load, suspect fuel delivery",
                "Clean the MAF sensor with dedicated cleaner",
                "Fix misfire, MAF or oxygen sensor codes first if present",
                CHECK_FRESH_FUEL,
                "A workshop can do a smoke test for leaks and measure fuel pressure",
            ),
            "Yes, you can keep driving, but get it checked within a few weeks. A very lean engine can misfire; if the " +
                "check-engine light starts flashing, stop.",
        )
        add(
            "P0172", FUEL_AIR, "Fuel mixture (fuel trim), bank 1", CHECK_SOON,
            "The engine computer has had to remove much more fuel than normal to keep the air-fuel mixture correct. " +
                "The engine is getting too much fuel or too little air.",
            listOf(
                "Leaking or dripping fuel injector",
                "Faulty air flow sensor reading too high, or coolant temperature sensor reading too cold",
                "Fuel pressure too high (pressure regulator fault)",
                "Purge valve stuck open, letting fuel vapour into the intake (often with evaporative system codes)",
                "Very dirty air filter or blocked air intake",
                "Engine oil diluted with fuel (possible when the engine often runs only briefly while cold)",
            ),
            listOf(
                "Check the air filter and air intake",
                "Check coolant temperature and air flow readings in live data for plausibility",
                "Check the oil level: if it rises over time or the oil smells of petrol, it is diluted with fuel; change the oil",
                "Check for evaporative system codes; a workshop can test whether the purge valve seals",
                "A workshop can check fuel pressure and injector leak-down",
            ),
            "Yes, you can keep driving, but get it checked within a few weeks. A rich mixture raises fuel use and can " +
                "damage the catalytic converter over time.",
        )
        for (cyl in 1..4) {
            add(
                "P020$cyl", FUEL_AIR, "Fuel injector, cylinder $cyl", CHECK_NOW,
                "The engine computer has found an electrical fault (usually an open circuit) in the injector circuit " +
                    "for cylinder $cyl. That cylinder will not get fuel and will misfire.",
                listOf(
                    "Unplugged, loose or corroded connector on the cylinder $cyl injector",
                    "Broken wire in the injector harness",
                    "Faulty injector (open coil)",
                    "Fuse or relay fault in the injector supply (would usually affect several injectors)",
                ),
                listOf(
                    "Check that the cylinder $cyl injector connector is fully latched and clean",
                    "Inspect the injector harness for damage",
                    "Measure the injector resistance and compare with the other injectors; they should be very similar",
                    "Check the fuse and relay supplying the injectors",
                    "Repair the wiring or replace the injector, then clear the code and check for misfires",
                ),
                "The engine will run unevenly on three cylinders, and unburnt fuel can damage the catalytic converter. " +
                    "Get it repaired within days and avoid long trips where the engine has to run. If the check-engine " +
                    "light flashes, stop as soon as it is safe.",
            )
        }
        add(
            "P0230", FUEL_AIR, "Fuel pump control circuit", CHECK_NOW,
            "The engine computer controls the fuel pump through a relay or pump control unit. It has found an " +
                "electrical fault in that circuit, so the pump may not run and the engine may not start.",
            listOf(
                "Failed fuel pump relay or blown fuse",
                "Damaged wiring or corroded connector in the pump circuit",
                "Faulty fuel pump control unit, if fitted",
                "Faulty fuel pump drawing too much current",
            ),
            listOf(
                "Check the fuel pump fuse and relay (owner's manual fuse chart)",
                "Check whether the engine starts and runs normally; note any hesitation or stalling",
                "Inspect the wiring and connectors in the pump circuit",
                "A workshop can check pump current and fuel pressure",
            ),
            "You can usually keep driving on electric power, but the engine may not start or may stall. Get it checked " +
                "within days and avoid long trips that need the engine.",
        )
    }

    // ------------------------------------------------------------------------------------
    // Misfire and ignition
    // ------------------------------------------------------------------------------------

    private fun misfireAndIgnition() {
        add(
            "P0300", IGNITION_MISFIRE, "Misfire detection (several cylinders)", CHECK_NOW,
            "The engine computer has detected misfires that are not limited to one cylinder, or that move between " +
                "cylinders. A misfire means the fuel in a cylinder did not burn properly.",
            listOf(
                "Worn spark plugs (they age with time as well as distance; check the service schedule)",
                "Failing ignition coil(s)",
                "Old or poor-quality fuel, or water in the fuel (possible if the engine rarely runs)",
                "Air leak in the intake or a dirty air flow sensor causing a lean mixture",
                "Low fuel pressure (fuel pump, filter)",
                "Valve timing problems (see P0016) or low compression (less common)",
            ),
            listOf(
                "Read all codes: P0171, P0016 or P0335 stored at the same time can explain the misfire",
                "Inspect and replace the spark plugs if due; check the coil boots for oil, cracks or burn marks",
                CHECK_FRESH_FUEL,
                "If your tool shows misfire counters per cylinder, see whether one or two cylinders dominate",
                "A workshop can check fuel pressure, compression and the ignition system",
            ),
            ADVICE_MISFIRE,
        )
        for (cyl in 1..4) {
            add(
                "P030$cyl", IGNITION_MISFIRE, "Misfire detection, cylinder $cyl", CHECK_NOW,
                "The engine computer has detected repeated misfires in cylinder $cyl: the fuel in that cylinder is not burning properly.",
                listOf(
                    "Faulty ignition coil on cylinder $cyl (most common)",
                    "Worn or fouled spark plug on cylinder $cyl",
                    "Clogged or faulty fuel injector on cylinder $cyl",
                    "Air leak near the intake port of cylinder $cyl",
                    "Low compression in cylinder $cyl (valve or piston ring problem, less common)",
                ),
                listOf(
                    "Swap the ignition coil from cylinder $cyl with a neighbouring cylinder, clear the codes and drive: " +
                        "if the misfire moves with the coil, replace the coil",
                    "Remove and inspect the spark plug: replace it if worn, oily or cracked (normally all four at once)",
                    "Check the coil and injector connectors and wiring on cylinder $cyl",
                    "If coil and plug are fine, have a workshop test the injector and the compression",
                ),
                ADVICE_MISFIRE,
            )
        }
        val knockSub = "Knock sensor (bank 1 or single sensor)"
        val knockAdvice =
            "Yes, you can keep driving. The engine computer uses safer, retarded ignition timing, so the engine may feel " +
                "slightly weaker and use a little more fuel. Use the recommended fuel grade and avoid heavy loads until it is fixed."
        val knockChecks = listOf(
            "Inspect the knock sensor connector and wiring (often hidden under the intake manifold)",
            "Check that the sensor is bolted tight to the engine block (a loose sensor cannot sense knock)",
            "Measure the sensor and wiring against the service data, or have a workshop check the signal",
            "Replace the sensor if needed and tighten it to the specified torque",
        )
        add(
            "P0325", IGNITION_MISFIRE, knockSub, CHECK_SOON,
            "The knock sensor listens for engine knock (uncontrolled combustion) so the engine computer can adjust the " +
                "ignition timing. Its circuit has an electrical fault.",
            listOf(
                "Damaged wiring or a corroded connector at the knock sensor",
                "Faulty knock sensor",
                "Sensor loose on the engine block",
            ),
            knockChecks,
            knockAdvice,
        )
        add(
            "P0327", IGNITION_MISFIRE, knockSub, CHECK_SOON,
            "The knock sensor signal is lower than expected, usually because of an open or shorted circuit or a faulty " +
                "sensor. The engine computer switches to safer ignition timing.",
            listOf(
                "Signal wire open or shorted to ground",
                "Faulty knock sensor",
                "Corroded or loose connector",
                "Sensor loose on the engine block",
            ),
            knockChecks,
            knockAdvice,
        )
        add(
            "P0328", IGNITION_MISFIRE, knockSub, CHECK_SOON,
            "The knock sensor signal is higher than expected, usually because of a wiring fault, a faulty sensor or " +
                "strong mechanical noise from the engine.",
            listOf(
                "Signal wire shorted to a voltage supply or picking up interference",
                "Faulty knock sensor",
                "Real mechanical noise from the engine (loose heat shield, worn bearings, valve noise)",
            ),
            knockChecks + "Listen for unusual mechanical noises from the engine",
            knockAdvice,
        )
        val coils = listOf('A', 'B', 'C', 'D')
        for ((i, letter) in coils.withIndex()) {
            val cyl = i + 1
            add(
                "P035$cyl", IGNITION_MISFIRE, "Ignition coil $letter (normally cylinder $cyl)", CHECK_NOW,
                "The engine computer has found an electrical fault in the circuit of ignition coil $letter (coil A is " +
                    "normally cylinder 1, B cylinder 2 and so on). That cylinder will probably misfire.",
                listOf(
                    "Faulty ignition coil",
                    "Loose, corroded or damaged coil connector",
                    "Damaged wiring between the coil and the engine computer",
                    "Fault in the coil power supply (fuse or relay; would usually affect several coils)",
                ),
                listOf(
                    "Check that the coil connector is fully latched; look for corrosion or a broken latch",
                    "Swap the coil with a neighbouring cylinder, clear the codes and see whether the code follows the coil",
                    "Inspect the wiring harness to the coils",
                    "Replace the coil if it follows; otherwise have the wiring and supply checked",
                ),
                ADVICE_MISFIRE,
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Oxygen sensors (bank 1; the Outlander PHEV's four-cylinder engine has only bank 1)
    // ------------------------------------------------------------------------------------

    private fun oxygenSensors() {
        fun where(sensor: Int) = if (sensor == 1) "before the catalytic converter" else "after the catalytic converter"
        fun sub(sensor: Int, heater: Boolean = false) =
            "Oxygen sensor ${if (heater) "heater, " else ""}bank 1 sensor $sensor (${where(sensor)})"
        fun role(sensor: Int) = if (sensor == 1) {
            "The front oxygen sensor measures oxygen in the exhaust so the engine computer can keep the fuel mixture correct."
        } else {
            "The rear oxygen sensor checks how well the catalytic converter is cleaning the exhaust."
        }
        val heaterAdvice =
            "Yes, you can keep driving. Emissions and fuel use may rise, mostly after cold starts; since a plug-in " +
                "hybrid's engine cold-starts often, do not leave it for too long."
        val heaterChecks = listOf(
            "Check the fuse for the oxygen sensor heaters (owner's manual fuse chart)",
            "Inspect the sensor connector and the wiring along the exhaust for melting or chafing",
            "Measure the heater resistance at the sensor connector and compare with the service data; an open circuit means the heater is dead",
            "Replace the sensor if the heater is open or shorted (use the correct type for the car)",
            CHECK_CLEAR_RETEST,
        )
        fun heater(code: String, sensor: Int, kind: String) {
            val side = if (sensor == 1) "front" else "rear"
            val monitored = "Depending on how the circuit is monitored, this can mean a short, an open circuit or a failed heater."
            val finding = when (kind) {
                "low" -> "that the heater control circuit of the $side sensor (${where(sensor)}) reads lower than expected. $monitored"
                "high" -> "that the heater control circuit of the $side sensor (${where(sensor)}) reads higher than expected. $monitored"
                else -> "an electrical fault in the heater circuit of the $side sensor (${where(sensor)})."
            }
            add(
                code, FUEL_AIR, sub(sensor, heater = true), CHECK_SOON,
                "Oxygen sensors only work when hot, so each has a built-in electric heater. The engine computer has found $finding",
                listOf(
                    "Heater element failed inside the sensor (burnt out or shorted; common on older sensors, and the sensor must then be replaced)",
                    "Wiring damaged by heat from the exhaust (melted, chafed, shorted or broken)",
                    "Loose or corroded connector",
                    "Blown fuse for the oxygen sensor heaters",
                ),
                heaterChecks,
                heaterAdvice,
            )
        }
        heater("P0031", 1, "low")
        heater("P0032", 1, "high")
        heater("P0135", 1, "circuit")
        heater("P0037", 2, "low")
        heater("P0038", 2, "high")
        heater("P0141", 2, "circuit")

        val sensorChecks = listOf(
            "Inspect the sensor connector and wiring along the exhaust for melting, chafing or corrosion",
            "Check for exhaust leaks near the sensor (ticking or puffing noise, soot marks)",
            "With a fully warm engine, watch the sensor in live data: a front sensor should react quickly to " +
                "changes in mixture; a rear sensor is normally fairly steady",
            "Fix fuel trim, misfire and heater codes first; they often explain oxygen sensor codes",
            "Replace the sensor if wiring and exhaust are good (use the correct type for the car)",
        )
        fun o2Advice(sensor: Int) = if (sensor == 1) {
            "Yes, you can keep driving. Fuel use and emissions may rise and the engine may run less smoothly. Get it " +
                "checked within a few weeks; a long-term mixture problem can damage the catalytic converter."
        } else {
            "Yes, you can keep driving. The rear sensor mainly monitors the catalytic converter, so the engine usually " +
                "runs normally. Get it checked within a few weeks."
        }
        fun o2(code: String, sensor: Int, problem: String, causes: List<String>) = add(
            code, FUEL_AIR, sub(sensor), CHECK_SOON,
            "${role(sensor)} $problem",
            causes,
            sensorChecks,
            o2Advice(sensor),
        )
        for (s in listOf(1, 2)) {
            // circuit, low voltage, high voltage, slow response, no activity
            val codes = if (s == 1) {
                listOf("P0130", "P0131", "P0132", "P0133", "P0134")
            } else {
                listOf("P0136", "P0137", "P0138", "P0139", "P0140")
            }
            o2(
                codes[0], s, "The engine computer has found a fault in this sensor's signal circuit.",
                listOf(
                    "Damaged wiring or a corroded connector (the wiring runs close to the hot exhaust)",
                    "Faulty or aged oxygen sensor",
                    "Exhaust leak near the sensor",
                    "Sensor contaminated by oil, coolant or fuel additives",
                ),
            )
            o2(
                codes[1], s, if (s == 1) {
                    "Its signal stays at a low voltage. On a conventional oxygen sensor this means it reports lean exhaust " +
                        "(too much oxygen) all the time; on a wide-band (air-fuel ratio) sensor it points more to a circuit or sensor fault."
                } else {
                    "Its signal stays at a low voltage, which means it reports lean exhaust (too much oxygen) all the time."
                },
                listOf(
                    "Exhaust leak in front of or near the sensor, letting in outside air",
                    "Faulty or aged oxygen sensor",
                    "Signal wire shorted to ground",
                    "A real lean mixture (intake air leak, low fuel pressure; look for P0171)",
                ),
            )
            o2(
                codes[2], s, if (s == 1) {
                    "Its signal stays at a high voltage. On a conventional oxygen sensor this means it reports rich exhaust " +
                        "(too little oxygen) all the time; on a wide-band (air-fuel ratio) sensor it points more to a circuit or sensor fault."
                } else {
                    "Its signal stays at a high voltage, which means it reports rich exhaust (too little oxygen) all the time."
                },
                listOf(
                    "Faulty or contaminated oxygen sensor (oil, coolant or silicone contamination)",
                    "Signal wire shorted to a voltage supply",
                    "A real rich mixture (leaking injector, purge valve stuck open; look for P0172)",
                ),
            )
            o2(
                codes[3], s, "The sensor reacts too slowly when the mixture changes.",
                listOf(
                    "Aged oxygen sensor (normal wear over many years)",
                    "Sensor contaminated by oil, coolant or fuel additives",
                    "Exhaust leak near the sensor",
                    "Heater problem keeping the sensor too cold (look for heater codes)",
                ),
            )
            o2(
                codes[4], s, "Its signal does not change at all, as if the sensor were dead or disconnected.",
                listOf(
                    "Faulty oxygen sensor",
                    "Open circuit or unplugged connector",
                    "Heater fault keeping the sensor too cold to work",
                    "Exhaust leak near the sensor",
                ),
            )
        }
        o2(
            "P2195", 1, "Its signal is biased towards lean or stuck lean: it reports more oxygen than the fuel " +
                "control expects.",
            listOf(
                "Air leak in the intake or an exhaust leak in front of the sensor",
                "Aged or contaminated front oxygen sensor",
                "Air flow sensor reading too low",
                "Low fuel pressure or clogged injectors",
            ),
        )
        o2(
            "P2196", 1, "Its signal is biased towards rich or stuck rich: it reports less oxygen than the fuel " +
                "control expects.",
            listOf(
                "Contaminated or aged front oxygen sensor",
                "Leaking fuel injector or fuel pressure too high",
                "Purge valve stuck open",
                "Engine oil diluted with fuel",
            ),
        )
    }

    // ------------------------------------------------------------------------------------
    // Catalyst, evaporative system, EGR
    // ------------------------------------------------------------------------------------

    private fun emissions() {
        add(
            "P0420", EMISSIONS, "Catalytic converter, bank 1", CHECK_SOON,
            "The engine computer uses the oxygen sensors before and after the catalytic converter to judge how well the " +
                "converter stores oxygen and cleans the exhaust. The result is below the required level, so the converter " +
                "is probably no longer working well enough.",
            listOf(
                "Aged catalytic converter (efficiency falls with age and mileage)",
                "Converter damaged by earlier misfires, or by oil or coolant being burnt",
                "Exhaust leak near the oxygen sensors",
                "Faulty or slow rear (or front) oxygen sensor giving a false result",
                "Engine running too rich or too lean (fix fuel trim codes first)",
            ),
            listOf(
                "Fix any misfire, fuel trim or oxygen sensor codes first; they can cause or fake this code",
                "Check for exhaust leaks (ticking or puffing noise, soot marks) between the engine and the rear oxygen sensor",
                "Compare front and rear oxygen sensor signals in live data with a fully warm engine: the rear should be fairly steady",
                CHECK_CHARGE_MODE,
                "Clear the code and drive; if it returns quickly, the converter is probably worn",
                "Replace the converter with a quality, type-approved part; cheap universal converters often bring the code back",
            ),
            "Yes, you can keep driving. The car may fail the periodic roadworthiness test (EU-kontroll in Norway) with " +
                "this fault, and a broken-up converter can rattle or restrict the exhaust over time.",
        )

        val evapSub = "Evaporative emissions (fuel vapour) system"
        val evapIntro =
            "The evaporative emission (EVAP) system collects petrol vapour from the tank in a charcoal canister and " +
                "later burns it in the engine."
        val evapAdvice =
            "Yes, you can keep driving. It does not affect how the car drives, but it lets fuel vapour escape and can " +
                "cause a failed roadworthiness test. If you smell petrol strongly, have it checked soon."
        val capCheck =
            "Check the fuel cap: it should click when tightened, and the rubber seal should be clean, soft and uncracked. " +
                "Replace a worn cap with one of the correct type"
        val refuelNote =
            "Plug-in hybrids commonly use a sealed, pressurised fuel tank: always refuel as described in the owner's " +
                "manual (for example using the fuel-lid release button and waiting until the lid opens)"
        add(
            "P0440", EMISSIONS, evapSub, CHECK_SOON,
            "$evapIntro The engine computer has found a general fault in this system.",
            listOf(
                "Loose, worn or wrong fuel cap",
                "Leaking or disconnected evaporative hose",
                "Faulty purge valve or vent valve",
                "Damaged charcoal canister",
                "Fault in the fuel tank pressure sensor",
            ),
            listOf(capCheck, refuelNote, "Read all codes; more specific EVAP codes (P0441–P0457) narrow it down",
                "A workshop can do a smoke test to find leaks"),
            evapAdvice,
        )
        add(
            "P0441", EMISSIONS, evapSub, CHECK_SOON,
            "$evapIntro The vapour flow from the canister to the engine (purge flow) is not as expected: too little, " +
                "too much, or flowing when it should not.",
            listOf(
                "Purge valve stuck open or stuck closed",
                "Blocked, kinked or disconnected purge hose",
                "Electrical fault on the purge valve (look for P0443)",
                "Charcoal canister blocked (for example after repeatedly overfilling the tank)",
            ),
            listOf(
                "Inspect the purge hoses between canister, purge valve and intake for cracks and loose ends",
                "A workshop can test whether the purge valve opens and closes when commanded and seals when closed",
                "Avoid topping up the tank after the pump nozzle clicks off; liquid fuel can damage the canister",
                refuelNote,
            ),
            evapAdvice,
        )
        fun leak(code: String, size: String, causes: List<String>) = add(
            code, EMISSIONS, evapSub, CHECK_SOON,
            "$evapIntro The engine computer seals the system and checks that it holds pressure or vacuum; it has found a $size leak.",
            causes,
            listOf(
                capCheck,
                "Inspect the visible EVAP hoses and the filler neck for cracks, rust and loose connections",
                refuelNote,
                "Clear the code and drive for a few days; the leak test only runs under certain conditions, so it can take a while to confirm a fix",
                "If it returns, a workshop can find the leak with a smoke test",
            ),
            evapAdvice,
        )
        leak(
            "P0442", "small",
            listOf(
                "Fuel cap seal worn, cracked or dirty, or the cap not tightened",
                "Small crack in an EVAP hose or loose hose clamp",
                "Purge or vent valve not sealing fully",
                "Corroded filler neck or leaking canister",
            ),
        )
        leak(
            "P0455", "large",
            listOf(
                "Fuel cap missing, loose or not clicked shut",
                "Disconnected or split EVAP hose",
                "Purge valve stuck open, or vent valve stuck",
                "Damaged charcoal canister",
            ),
        )
        leak(
            "P0456", "very small",
            listOf(
                "Fuel cap seal slightly worn or dirty (most common)",
                "Tiny crack in an EVAP hose or seal",
                "Purge or vent valve not sealing perfectly",
                "Small leak at the filler neck or fuel tank seals",
            ),
        )
        add(
            "P0457", EMISSIONS, evapSub, CHECK_SOON,
            "$evapIntro The leak test suggests the fuel cap is loose or missing.",
            listOf(
                "Fuel cap not tightened until it clicks, or left off after refuelling",
                "Worn or damaged fuel cap seal",
                "Dirt on the filler neck sealing surface",
                "Wrong (non-original) fuel cap",
            ),
            listOf(
                "Check that the fuel cap is fitted and turned until it clicks",
                "Clean the cap seal and the filler neck rim; replace the cap if the seal is cracked or hard",
                refuelNote,
                "Clear the code; it can take a few days of driving before the system re-tests and the light stays off",
            ),
            "Yes. Tighten or refit the fuel cap and drive normally.",
        )
        add(
            "P0443", EMISSIONS, evapSub, CHECK_SOON,
            "$evapIntro The engine computer has found an electrical fault in the circuit of the purge valve, which " +
                "lets the vapour flow to the engine.",
            listOf(
                "Unplugged, loose or corroded purge valve connector",
                "Faulty purge valve (open or shorted coil)",
                "Damaged wiring",
                "Blown fuse in the valve supply",
            ),
            listOf(
                "Check the purge valve connector (usually in the engine bay on the vapour hose to the intake)",
                "Measure the valve coil resistance and compare with the service data",
                "Inspect the wiring and fuse",
                "Replace the purge valve if the coil is open or shorted",
            ),
            evapAdvice,
        )
        add(
            "P0446", EMISSIONS, evapSub, CHECK_SOON,
            "$evapIntro The vent valve, which lets fresh air into the canister and closes during leak tests, is not " +
                "working correctly or has an electrical fault.",
            listOf(
                "Vent valve or its filter blocked by dirt, insects or ice",
                "Faulty vent valve",
                "Wiring or connector fault (the valve is usually near the canister, exposed to road dirt)",
                "Blocked vent hose",
            ),
            listOf(
                "Inspect the vent valve, its hose and its filter for dirt and blockage",
                "Check the connector and wiring for corrosion",
                "A workshop can test the valve with an actuator test",
                refuelNote,
            ),
            evapAdvice,
        )
        val egrSub = "Exhaust gas recirculation (EGR)"
        add(
            "P0401", EMISSIONS, egrSub, CHECK_SOON,
            "The EGR system feeds a small amount of exhaust back into the intake to lower combustion temperatures and " +
                "NOx emissions. The engine computer measured less EGR flow than expected.",
            listOf(
                "Carbon deposits blocking the EGR valve or passages (most common)",
                "EGR valve sticking or faulty",
                "Electrical fault on the EGR valve (look for P0403)",
                "Faulty sensor used to judge EGR flow, such as the manifold pressure sensor",
            ),
            listOf(
                "Check for other EGR or sensor codes",
                "A workshop can command the EGR valve open at idle: the engine should run noticeably rougher if gas flows",
                "Clean or replace the EGR valve and clean the passages",
                CHECK_CLEAR_RETEST,
            ),
        )
        add(
            "P0403", EMISSIONS, egrSub, CHECK_SOON,
            "The engine computer has found an electrical fault in the control circuit of the EGR valve.",
            listOf(
                "Unplugged, loose or corroded EGR valve connector",
                "Faulty EGR valve (motor or solenoid)",
                "Damaged wiring (the valve sits in a hot area)",
            ),
            listOf(
                "Inspect the EGR valve connector and wiring for heat damage and corrosion",
                "Measure the valve resistance against the service data",
                "Replace the EGR valve if it is faulty",
            ),
        )
    }

    // ------------------------------------------------------------------------------------
    // Vehicle speed, idle, 12 V supply, control modules, MIL request
    // ------------------------------------------------------------------------------------

    private fun speedIdleVoltageModules() {
        add(
            "P0500", SPEED_IDLE, "Vehicle speed signal", CHECK_SOON,
            "The engine computer is not receiving a valid vehicle speed signal. On modern cars the speed is usually " +
                "calculated from the wheel speed sensors and shared over the data network.",
            listOf(
                "Wheel speed sensor or ABS fault (look for an ABS warning light and C or U codes)",
                "Network communication problem with the ABS unit",
                "Wiring or connector fault",
                CAUSE_12V,
            ),
            listOf(
                "Check whether the speedometer works and whether the ABS warning light is on",
                "Read the ABS unit with a capable tool if possible",
                CHECK_MANY_CODES,
                "Inspect the wheel speed sensor wiring at all four wheels",
            ),
            "Usually drivable, but the speedometer, cruise control or other functions may not work correctly. Get it " +
                "checked within a few weeks, or within days if the ABS warning light is also on.",
        )
        val idleSub = "Idle speed control"
        add(
            "P0506", SPEED_IDLE, idleSub, CHECK_SOON,
            "When the engine idles, the engine computer controls its speed with the electronic throttle. The idle speed " +
                "stays lower than the target.",
            listOf(
                "Dirty throttle body (carbon deposits restricting the air at idle)",
                "Blocked air filter or intake",
                "Throttle body needs an idle relearn after cleaning, replacement or battery disconnection",
                "Engine running poorly for another reason (look for misfire codes)",
            ),
            listOf(
                "Check the air filter",
                "Clean the throttle body carefully (ignition off) and perform the idle relearn procedure",
                "Check for other engine codes",
            ),
        )
        add(
            "P0507", SPEED_IDLE, idleSub, CHECK_SOON,
            "When the engine idles, the engine computer controls its speed with the electronic throttle. The idle speed " +
                "stays higher than the target.",
            listOf(
                "Air leak in the intake (cracked hose, leaking gasket, vacuum or crankcase ventilation hose)",
                "Dirty or sticking throttle body",
                "Throttle body needs an idle relearn after cleaning, replacement or battery disconnection",
                "Faulty coolant temperature sensor making the engine computer think the engine is cold",
            ),
            listOf(
                "Listen for hissing air leaks around the intake with the engine idling",
                "Clean the throttle body carefully (ignition off) and perform the idle relearn procedure",
                "Check coolant temperature in live data for plausibility",
            ),
        )
        val voltSub = "12 V supply (system voltage)"
        add(
            "P0562", COMPUTER_OUTPUTS, voltSub, CHECK_NOW,
            "The engine computer has measured its 12 V supply below the normal range. On the Outlander PHEV the 12 V " +
                "system is fed by the 12 V auxiliary battery and charged by the DC/DC converter, so the cause is usually " +
                "the battery, the converter or the connections between them.",
            listOf(
                CAUSE_12V,
                "DC/DC converter not charging (look for P0A94 or other DC/DC codes)",
                "Loose or corroded battery terminals or ground straps",
                "Car left standing for a long time, or an accessory or OBD dongle draining the battery while parked",
                "Voltage drop in the supply wiring or fuse to the engine computer",
            ),
            listOf(
                CHECK_12V,
                "Clean and tighten the battery terminals and check the ground straps",
                CHECK_DONGLE,
                "Check for DC/DC converter codes",
                "If the battery is old (many last around 4–6 years) or fails a test, replace it with the correct type",
            ),
            "Get it checked within days. A weak 12 V system can stop the car from going into READY or make functions " +
                "switch off without warning. Avoid long trips until the cause is found.",
        )
        add(
            "P0563", COMPUTER_OUTPUTS, voltSub, CHECK_NOW,
            "The engine computer has measured its 12 V supply above the normal range. Too high a voltage can damage " +
                "electronics and the 12 V battery.",
            listOf(
                "DC/DC converter regulating the 12 V output too high",
                "External battery charger or jump-start booster connected while the car was on",
                "Poor ground connection causing false readings",
                "Internal fault in the 12 V battery",
            ),
            listOf(
                "Measure the 12 V battery voltage with the car in READY; clearly above about 15 V is too high",
                "Disconnect any charger or booster and check whether the code returns",
                "Check the ground straps and battery terminals",
                "Have a dealer check the DC/DC converter output",
            ),
            "Get it checked within days. Too high a voltage can damage the 12 V battery and electronics.",
        )
        val ecmSub = "Engine control module (internal)"
        val ecmCauses = listOf(
            "Internal fault in the engine control module",
            "Interrupted or failed software update",
            "Severe 12 V voltage dip or spike (for example during jump-starting)",
            "Water or corrosion damage to the module or its connectors",
        )
        val ecmChecks = listOf(
            CHECK_12V,
            CHECK_CLEAR_SEE,
            "Ask a Mitsubishi dealer whether a software update or re-programming is available for the module",
            "If it keeps returning, the module may need replacing and programming to the car",
        )
        val ecmAdvice =
            "The engine may run poorly, stall or refuse to start, and other functions may be affected. Get it checked within days."
        add(
            "P0601", COMPUTER_OUTPUTS, ecmSub, CHECK_NOW,
            "The engine control module has found an error when checking its own program memory (checksum). The stored " +
                "software may be corrupt.",
            ecmCauses, ecmChecks, ecmAdvice,
        )
        add(
            "P0606", COMPUTER_OUTPUTS, ecmSub, CHECK_NOW,
            "The engine control module has found a fault in its own processor during a self-test.",
            ecmCauses, ecmChecks, ecmAdvice,
        )
        add(
            "P0700", TRANSMISSION, "Transmission / drive unit control (warning light request)", CHECK_SOON,
            "Another control module, responsible for the transmission or drive unit, has stored its own fault code and " +
                "asked the engine computer to turn on the check-engine light. The real fault code is stored in that other module.",
            listOf(
                "A fault stored in the transmission or drive-unit control module (read that module for the actual code)",
                CAUSE_12V,
                "Wiring or network fault between the modules",
            ),
            listOf(
                CHECK_DEALER_TOOL,
                CHECK_MANY_CODES,
                "Note any change in how the car drives (jerks, noises, loss of drive) and tell the workshop",
                "The Outlander PHEV has no conventional multi-speed gearbox, so the stored fault may concern the transaxle, " +
                    "its clutch or another drive-unit function",
            ),
            "Usually yes, if the car drives normally. Have the other module read within a few weeks, or within days if " +
                "the car jerks, makes noises or loses drive.",
        )
    }

    // ------------------------------------------------------------------------------------
    // Hybrid / EV system
    // ------------------------------------------------------------------------------------

    private fun hybrid() {
        val battery = "Drive battery (high-voltage traction battery)"
        add(
            "P0AA6", HYBRID_EV, "High-voltage insulation monitoring", CHECK_NOW,
            "The high-voltage system is electrically insulated from the car body, and a monitor checks this insulation " +
                "all the time. It has measured too low an insulation resistance: somewhere, high voltage may be leaking " +
                "towards the body or chassis.",
            listOf(
                "Moisture or water in a high-voltage component or connector (for example after heavy rain, washing the " +
                    "engine bay, or driving through deep water)",
                "Damaged insulation on an orange high-voltage cable (chafing, rodent damage or earlier accident damage)",
                "Insulation breakdown inside a high-voltage component, such as the electric air-conditioning compressor, " +
                    "the electric heater, the on-board charger, an inverter, a motor or the drive battery",
                "Fault in the insulation monitoring circuit itself (less common)",
            ),
            listOf(
                HV_SAFETY,
                "Note when the fault appears: only in wet weather or after washing points to moisture; only while " +
                    "charging points to the charging components; only with heating or air conditioning running may point to those units",
                "Look, without touching, for visible damage to orange cables under the car and in the engine bay",
                "Do not pressure-wash the engine bay or the underside near high-voltage parts",
                "Have a Mitsubishi dealer or a high-voltage-trained workshop locate the fault; they isolate the components " +
                    "one by one with an insulation tester",
            ),
            "Follow the dashboard warnings. If the car shows a message to stop the vehicle, or will not go into READY, " +
                "stop safely, do not keep restarting it and arrange recovery. If the car drives normally, get it checked " +
                "within days and avoid deep water. Never touch damaged orange cables.",
        )
        add(
            "P0A80", HYBRID_EV, battery, CHECK_NOW,
            "The battery management system has judged that the drive battery has degraded beyond its limit, for example " +
                "through lost capacity or cells that have drifted too far apart, and that it needs repair or replacement.",
            listOf(
                "Age- and use-related capacity loss (all lithium-ion batteries lose capacity over time)",
                "One or more weak cells or modules that no longer stay balanced with the rest",
                "Battery cooling problems that have speeded up ageing",
                "Less commonly, a fault in the voltage or temperature sensing that makes the battery look worse than it is",
            ),
            listOf(
                HV_SAFETY,
                "Read all codes: drive battery sensor, cooling or voltage-sensing codes stored at the same time may point to a repairable cause",
                "Have a Mitsubishi dealer run a drive battery check (capacity/health measurement) with their diagnostic tool",
                "Ask about the drive battery warranty: drive batteries often have a longer warranty than the rest of the " +
                    "car (see your warranty booklet)",
                "Get quotes for both module repair by a high-voltage specialist and full battery replacement; replacing " +
                    "individual modules is sometimes possible",
            ),
            "The car usually still drives, but expect reduced electric range and power and the engine running more " +
                "often. Get it checked within days. If the dashboard tells you to stop, stop safely. Never open the drive battery.",
        )
        add(
            "P0A7F", HYBRID_EV, battery, CHECK_SOON,
            "The battery management system has detected that the drive battery has deteriorated, for example lost " +
                "capacity or cells drifting apart. The car may limit electric range or power.",
            listOf(
                "Normal age- and use-related capacity loss",
                "Cells or modules becoming unbalanced",
                "Battery cooling problems speeding up ageing",
                "Fault in the voltage or temperature sensing (check for other drive battery codes)",
            ),
            listOf(
                HV_SAFETY,
                "Read all codes: sensor or cooling codes stored at the same time may point to a repairable cause",
                "Have a Mitsubishi dealer run a drive battery check to measure the remaining capacity",
                "Ask about the drive battery warranty (see your warranty booklet)",
                "Charging fully now and then (rather than always stopping part-way) helps the system judge the battery correctly",
            ),
            "Yes, you can drive normally. Expect shorter electric range than when new. Have the battery checked within " +
                "a few weeks, and sooner if more warnings appear.",
        )
        add(
            "P0A7D", HYBRID_EV, battery, CHECK_NOW,
            "The drive battery's state of charge has fallen below the minimum reserve that the hybrid system wants to keep.",
            listOf(
                "The car ran out of petrol, so the engine could not recharge the drive battery",
                "The car stood unused for a long time with a nearly empty drive battery",
                "Engine or generator problem preventing self-charging (look for other codes)",
                "Fault in the drive battery or its monitoring",
            ),
            listOf(
                HV_SAFETY,
                "Refuel if the tank is low",
                "Plug the car in, charge it fully and clear the code",
                "Check for engine or generator codes that would stop the car from charging itself",
                "If it returns with fuel in the tank and after a full charge, have the drive battery checked by a dealer",
            ),
            "Charge the car (or refuel) as soon as possible. With a very low drive battery the car may limit power or may " +
                "not be able to start the engine. Do not leave the car standing for weeks with an empty drive battery.",
        )
        add(
            "P0A7E", HYBRID_EV, battery, CHECK_NOW,
            "The drive battery has become hotter than its safe limit. The car will usually limit power and charging to protect it.",
            listOf(
                "Fault in the drive battery cooling system",
                "Repeated rapid (CHAdeMO) charging combined with hard driving in hot weather",
                "Faulty temperature sensor giving a false high reading (look for P0A9B–P0A9E)",
                "Internal battery fault (less common)",
            ),
            listOf(
                HV_SAFETY,
                "Let the car rest in the shade, switched off, until the battery has cooled",
                "Check that the air conditioning works properly; in many plug-in hybrids it also helps cool the drive battery",
                "Avoid back-to-back rapid charging in hot weather",
                "If it happens in normal conditions, have the battery cooling and temperature sensors checked by a dealer",
            ),
            "Power and charging may be limited. Let the car cool down before continuing, drive gently and get it checked " +
                "within days. If a message tells you to stop the vehicle, stop safely.",
        )
        add(
            "P0A9C", HYBRID_EV, "Drive battery temperature sensor A", CHECK_NOW,
            "Several temperature sensors inside the drive battery let the battery management system protect it. Sensor A " +
                "gives readings that do not fit with the other sensors or with how the temperature should change.",
            listOf(
                "Faulty temperature sensor inside the battery pack",
                "Wiring or connector problem inside the pack or at the battery management unit",
                "A real uneven temperature in the pack caused by a cooling problem",
                "Fault in the battery management unit",
            ),
            listOf(
                HV_SAFETY,
                "Read all codes, including other drive battery temperature and cooling codes",
                "Have a dealer compare all battery temperature readings in live data; after the car has stood overnight they should be nearly equal",
                "Repairs need the drive battery to be opened and must be done by a high-voltage-trained technician",
            ),
            ADVICE_HV,
        )
        add(
            "P0AFA", HYBRID_EV, battery, CHECK_NOW,
            "The total voltage of the drive battery is lower than the hybrid system allows.",
            listOf(
                "Drive battery deeply discharged (stood for a long time, or ran out of fuel)",
                "Weak cells or modules whose voltage drops under load",
                "Fault in the voltage-sensing circuit",
                "High-voltage connection problem (main relay or connector)",
            ),
            listOf(
                HV_SAFETY,
                "Plug in and charge fully, then clear the code and see whether it returns",
                "Note whether it appears only under hard acceleration or in very cold weather (points to weak cells)",
                "Have a dealer run a drive battery check",
            ),
            ADVICE_HV,
        )
        add(
            "P0AFB", HYBRID_EV, battery, CHECK_NOW,
            "The total voltage of the drive battery is higher than the hybrid system allows.",
            listOf(
                "Fault in the voltage-sensing circuit or battery management unit",
                "Charging-control fault (on-board charger or regenerative braking control)",
                "Cells out of balance so that part of the pack reads high",
            ),
            listOf(
                HV_SAFETY,
                "Note whether it appears while charging, during long downhill regeneration or right after charging to full",
                "If it appears while charging, stop charging and have it checked",
                "Have a dealer check the drive battery and charging system",
            ),
            ADVICE_HV,
        )
        add(
            "P0A94", HYBRID_EV, "DC/DC converter (charges the 12 V battery)", CHECK_NOW,
            "The DC/DC converter turns drive battery voltage into about 12–14 V to power the car's electronics and charge " +
                "the 12 V battery; the Outlander PHEV has no conventional alternator. The converter is not delivering the " +
                "expected output, so the 12 V battery may slowly run flat.",
            listOf(
                "Internal fault in the DC/DC converter",
                "Poor connection, corrosion or a blown main fuse between the converter and the 12 V battery",
                "Failing 12 V battery drawing abnormal current",
                "Possible overheating because of a fault in the power electronics cooling",
            ),
            listOf(
                HV_SAFETY,
                "Measure the 12 V battery voltage with the car off and then in READY: in READY it should rise to roughly " +
                    "13–14.5 V. If it stays at or below the resting voltage, the converter is not charging",
                "Check the 12 V battery terminals and the ground strap to the body for corrosion and tightness",
                "Test the 12 V battery and replace it if weak",
                "With everything cold, check the coolant level of the power electronics cooling circuit (see the owner's manual)",
                "Have a dealer test the DC/DC converter",
            ),
            "Limit driving and get it checked within days. If the converter is not charging, the 12 V battery runs down " +
                "while you drive and the car can shut down without much warning. Switch off unnecessary electrical loads " +
                "(seat heating, rear window heater, high fan speed).",
        )
        val peCooling = "Power electronics cooling circuit"
        val peCoolingAdvice =
            "Power may be limited to protect the electronics. Drive gently and get it checked within days; avoid hot " +
                "weather driving at high load, steep climbs and towing. Stop if a temperature warning or a message to stop appears."
        val peCoolantCheck =
            "With everything cold, check the coolant level in the power electronics reservoir (plug-in hybrids normally " +
                "have a separate circuit from the engine; see the owner's manual). Never open a hot cooling system"
        add(
            "P0A93", HYBRID_EV, peCooling, CHECK_NOW,
            "The inverters and other power electronics are cooled by a coolant circuit with an electric pump. The control " +
                "unit has found that this circuit is not cooling as well as expected.",
            listOf(
                "Low coolant level in the power electronics circuit (leak)",
                "Weak or failed electric coolant pump",
                "Air trapped in the circuit after a repair",
                "Radiator blocked by dirt or leaves, or a cooling fan problem",
                "Faulty coolant temperature sensor",
            ),
            listOf(
                HV_SAFETY,
                peCoolantCheck,
                "Look for coolant leaks (puddles or dried residue) under the front of the car",
                "Clean leaves and dirt from the front of the radiators",
                "If the level is correct, have a workshop bleed the circuit and test the pump",
            ),
            peCoolingAdvice,
        )
        add(
            "P0A05", HYBRID_EV, peCooling, CHECK_NOW,
            "The electric pump that circulates coolant through the power electronics has an electrical fault in its " +
                "control circuit, so it may not run. Without it, the inverters and motors can overheat.",
            listOf(
                "Blown fuse or failed relay for the electric pump",
                "Unplugged, loose or corroded connector at the pump",
                "Broken wire in the pump circuit",
                "Failed electric pump",
            ),
            listOf(
                HV_SAFETY,
                "Check the fuse and relay for the electric water pump (owner's manual or service fuse chart)",
                "Inspect the pump connector, which is often low in the engine bay and exposed to road spray",
                peCoolantCheck,
                "A workshop can test the pump with an actuator test",
            ),
            peCoolingAdvice,
        )
        add(
            "P0A7C", HYBRID_EV, peCooling, CHECK_NOW,
            "The power electronics (inverters and motor control units) have become hotter than their safe limit, and " +
                "the car will limit power to protect them.",
            listOf(
                "Low coolant level or a leak in the power electronics circuit",
                "Electric coolant pump not running (look for P0A05)",
                "Radiator blocked or cooling fan not working",
                "Sustained heavy load in hot weather (towing, long steep climbs)",
                "Faulty temperature sensor (less common)",
            ),
            listOf(
                HV_SAFETY,
                "Stop and let the car cool down",
                peCoolantCheck,
                "Check the radiators for blockage and the cooling fan for operation",
                "Have a workshop check the pump and the cooling circuit",
            ),
            peCoolingAdvice,
        )
        add(
            "P0A78", HYBRID_EV, "Inverter for drive motor A", CHECK_NOW,
            "The inverter for drive motor A converts drive battery power into the alternating current that drives the " +
                "motor. It is not performing as the control unit expects, for example current or voltage out of range.",
            listOf(
                "Internal inverter fault",
                "Overheating because of a cooling problem (look for cooling codes)",
                "Problem with the motor or the high-voltage cables between inverter and motor",
                "Low 12 V supply to the inverter's control electronics",
            ),
            listOf(
                HV_SAFETY,
                CHECK_12V,
                "Read all codes: cooling, motor position sensor or insulation codes may point to the cause",
                peCoolantCheck,
                "Have a Mitsubishi dealer diagnose the inverter; this needs high-voltage training and special tools",
            ),
            ADVICE_HV,
        )
        add(
            "P0A3F", HYBRID_EV, "Drive motor A position sensor (resolver)", CHECK_NOW,
            "A position sensor (resolver) inside drive motor A tells the motor control unit the exact rotor position, " +
                "which it needs to drive the motor. The sensor signal is missing or electrically wrong.",
            listOf(
                "Loose, wet or corroded connector on the resolver wiring between the motor and its control unit",
                "Damaged resolver wiring",
                "Faulty resolver inside the motor (usually means removing the motor)",
                "Fault in the motor control unit",
            ),
            listOf(
                HV_SAFETY,
                "Note whether the fault happened at start-up, while driving, or over bumps (bumps suggest a wiring problem)",
                "Have a workshop inspect the resolver connector and measure the resolver windings; the resolver circuit " +
                    "itself is low-voltage but sits next to high-voltage parts",
            ),
            "The car may lose drive from this motor, run with reduced power or refuse to go into READY. If it drives, " +
                "go straight to a workshop and avoid long trips. If a message tells you to stop, stop safely.",
        )
        val interlock = "High-voltage interlock (safety loop)"
        val interlockCauses = listOf(
            "High-voltage connector or service plug not fully latched, often after a repair or inspection",
            "Damaged or corroded interlock pin or wire in a high-voltage connector",
            "Water in a connector",
            "Fault in the control unit that monitors the loop (less common)",
        )
        val interlockChecks = listOf(
            HV_SAFETY,
            "Think back: was any high-voltage part (battery, inverter, charger, air-conditioning compressor, heater) " +
                "worked on recently? If so, return to that workshop first",
            "Have a high-voltage-trained technician check each connector in the interlock loop",
            "Clear the code and check whether the car goes into READY normally",
        )
        val interlockAdvice =
            "The car may refuse to go into READY or may shut the high-voltage system down. If it drives, get it checked " +
                "within days. If a message tells you to stop, stop safely. Never try to reseat high-voltage connectors yourself."
        add(
            "P0A0A", HYBRID_EV, interlock, CHECK_NOW,
            "The high-voltage interlock is a low-voltage signal loop that runs through the high-voltage connectors and " +
                "covers. The control unit has found a fault in this loop, so it may assume a connector or cover is open " +
                "and switch the high voltage off for safety.",
            interlockCauses, interlockChecks, interlockAdvice,
        )
        add(
            "P0A0D", HYBRID_EV, interlock, CHECK_NOW,
            "The high-voltage interlock is a low-voltage signal loop that runs through the high-voltage connectors and " +
                "covers. The voltage on the loop is too high, which often means the loop is open somewhere, so the system " +
                "may switch the high voltage off for safety.",
            interlockCauses, interlockChecks, interlockAdvice,
        )
        add(
            "P0A95", HYBRID_EV, "High-voltage fuse", CHECK_NOW,
            "A fuse in the high-voltage system is reported as blown or open. Depending on what it protects, functions " +
                "such as electric heating, air conditioning, charging or drive may stop working.",
            listOf(
                "Short circuit or overload in a high-voltage component after the fuse (for example the electric heater " +
                    "or air-conditioning compressor)",
                "Fuse that has aged or failed by itself (less common)",
                "Fault in the fuse monitoring circuit",
            ),
            listOf(
                HV_SAFETY,
                "Note which functions stopped working (heating, air conditioning, charging); this helps the workshop find the circuit",
                "Have a high-voltage-trained technician find the cause before the fuse is replaced; a new fuse will blow again if the fault remains",
            ),
            ADVICE_HV,
        )
    }

    // ------------------------------------------------------------------------------------
    // Network (U) codes
    // ------------------------------------------------------------------------------------

    private fun network() {
        fun lost(
            code: String,
            module: String,
            severity: DtcSeverity,
            effect: String,
            extraCauses: List<String> = emptyList(),
            extraChecks: List<String> = emptyList(),
            advice: String = if (severity == CHECK_SOON) ADVICE_NETWORK else DtcText.adviceFor(severity),
        ) = add(
            code, NETWORK, "Lost communication with the $module", severity,
            "Control modules share information over the car's data network (CAN bus), and messages from the $module " +
                "stopped arriving. $effect",
            listOf(CAUSE_12V) + extraCauses + listOf(
                "The $module has no power: blown fuse, failed relay or poor ground connection",
                "Loose, wet or corroded connector at the $module or on the network wiring",
                "Damaged network (CAN) wiring, for example a chafed or pinched harness",
                "The $module itself has failed (less common; rule out power and wiring first)",
            ),
            listOf(CHECK_MANY_CODES, CHECK_12V) + extraChecks + listOf(
                "Check the fuses for the $module; the owner's manual has the fuse chart",
                "Look for water ingress or corrosion around the $module and its connectors",
                "Clear all codes, drive, and read again: codes that come back point to the real problem",
                CHECK_DONGLE,
                "A workshop can check the network wiring and terminating resistors (a healthy CAN bus normally measures " +
                    "about 60 ohms between CAN-High and CAN-Low with the car switched off)",
                CHECK_DEALER_TOOL,
            ),
            advice,
        )
        lost(
            "U0100", "engine control module (ECM)", CHECK_NOW,
            "The engine may not start or run, and the car may drive on electric power only with a warning shown.",
            extraChecks = listOf("If the engine does not start, check the engine control module fuses and main relay first"),
            advice = "The car may still drive on electric power, but the engine may not be available. Get it checked " +
                "within days and avoid long trips. If a message tells you to stop, stop safely.",
        )
        lost(
            "U0101", "transmission control module (TCM)", CHECK_SOON,
            "The Outlander PHEV has no conventional automatic gearbox; which unit answers as 'TCM' depends on the car's " +
                "software, so have a Mitsubishi-compatible tool confirm which module is affected.",
        )
        lost(
            "U0110", "drive motor control unit", CHECK_NOW,
            "This unit runs an electric drive motor through its inverter, so drive power may be reduced or the car may " +
                "not go into READY.",
            extraChecks = listOf(HV_SAFETY),
            advice = ADVICE_HV,
        )
        lost(
            "U0111", "drive battery management unit (BMU)", CHECK_NOW,
            "This unit monitors the drive battery, so the car may limit power and charging, or refuse to go into READY.",
            extraChecks = listOf(HV_SAFETY),
            advice = ADVICE_HV,
        )
        lost(
            "U0121", "ABS control module", CHECK_NOW,
            "ABS, traction and stability control may be switched off; normal braking still works.",
            advice = "Normal braking still works, but ABS and stability control may be off, so wheels can lock under hard " +
                "braking. Drive carefully, especially on wet, icy or snowy roads, and get it checked within days.",
        )
        lost(
            "U0131", "power steering control module", CHECK_NOW,
            "Steering assistance may be reduced or lost, making the steering heavy.",
            advice = "If the steering is heavy, drive slowly and carefully to a workshop or arrange recovery; heavy " +
                "steering needs much more effort in emergencies. If the steering feels normal, get it checked within days.",
        )
        lost(
            "U0140", "body control module (BCM)", CHECK_SOON,
            "Lights, locks, wipers or other body functions may behave strangely or stop working.",
        )
        lost(
            "U0151", "airbag (restraints) control module", CHECK_NOW,
            "The airbag warning light will usually be on, and the airbags and seatbelt pretensioners may not deploy in a crash.",
            extraChecks = listOf(SRS_SAFETY),
            advice = "The car drives normally, but the airbags may not protect you in a crash. Get it repaired as soon " +
                "as possible (within days).",
        )
        lost(
            "U0155", "instrument cluster", CHECK_SOON,
            "Gauges, warning lights or messages may not be shown correctly while this fault is present.",
            advice = "Usually yes, but warning lights and messages may not be shown while the instrument cluster is " +
                "offline, so you may miss other warnings. Have it checked within days.",
        )
        lost(
            "U0164", "climate control (HVAC) module", CHECK_SOON,
            "Heating, air conditioning or windscreen demisting may not work properly.",
            advice = "Yes, if the windscreen stays clear. In cold or wet weather, do not drive if you cannot keep the " +
                "windscreen free of mist and ice. Have it checked within a few weeks.",
        )
        lost(
            "U0293", "hybrid control unit", CHECK_NOW,
            "This unit coordinates the engine, electric motors and drive battery, so the car may refuse to go into READY, " +
                "or may run with reduced power and an EV system warning.",
            extraChecks = listOf(HV_SAFETY),
            advice = ADVICE_HV,
        )

        val busCauses = listOf(
            CAUSE_12V,
            "Damaged network wiring: CAN-High and CAN-Low shorted together, to ground or to voltage",
            "A faulty control module disturbing the network",
            "Water or corrosion in a connector on the network",
            "Aftermarket devices on the diagnostic socket or spliced into the wiring (dongles, trackers, dashcams)",
        )
        val busChecks = listOf(
            CHECK_12V,
            CHECK_DONGLE,
            "Note which functions stopped working when the code appeared; it shows which part of the network is affected",
            "Look for water ingress (wet carpets, damp connectors) and recent work on the wiring",
            "A workshop can measure the network wiring and terminating resistors (a healthy CAN bus normally measures " +
                "about 60 ohms between CAN-High and CAN-Low with the car switched off) and disconnect modules one by one",
            CHECK_DEALER_TOOL,
        )
        add(
            "U0001", NETWORK, "Data network (high-speed CAN bus)", CHECK_SOON,
            "The control modules share information over a high-speed data network (CAN bus). A module has detected a " +
                "fault on the network itself rather than with one specific module.",
            busCauses, busChecks, ADVICE_NETWORK,
        )
        add(
            "U0073", NETWORK, "Data network (CAN bus A)", CHECK_SOON,
            "A control module has detected so many errors on network A that it has disconnected itself from the network " +
                "('bus off'). Messages to and from that module are lost while this happens.",
            busCauses, busChecks, ADVICE_NETWORK,
        )
        add(
            "U3003", COMPUTER_OUTPUTS, "12 V supply voltage to a control module", CHECK_SOON,
            "A control module has measured its 12 V supply outside its normal operating range, usually too low.",
            listOf(
                CAUSE_12V,
                "DC/DC converter not charging properly (look for P0A94 or other DC/DC codes)",
                "Loose or corroded battery terminal or ground connection",
                "Voltage dip during jump-starting, while a charger was connected, or while the 12 V battery was flat",
            ),
            listOf(
                CHECK_12V,
                "Clean and tighten the battery terminals and check the ground straps",
                "Check for DC/DC converter codes",
                CHECK_DONGLE,
                CHECK_CLEAR_SEE,
            ),
        )
    }

    // ------------------------------------------------------------------------------------
    // Chassis (C) and body (B)
    // ------------------------------------------------------------------------------------

    private fun chassisAndBody() {
        fun wheel(code: String, wheel: String) = add(
            code, CHASSIS, "Wheel speed sensor, $wheel", CHECK_NOW,
            "Each wheel has a speed sensor used by ABS, traction and stability control, and by other systems that need " +
                "road speed. The $wheel sensor's signal is missing, implausible or electrically faulty.",
            listOf(
                "Damaged or corroded sensor connector or wiring near the wheel (exposed to water, salt and stones)",
                "Dirt, rust or metal debris on the sensor tip or the magnetic encoder ring",
                "Faulty wheel speed sensor",
                "Wheel bearing play or a damaged encoder ring (the encoder is often part of the wheel bearing)",
                "Sensor not seated properly after brake or suspension work",
            ),
            listOf(
                "Inspect the wiring from the sensor along the suspension to the body for damage, especially after winter",
                "Unplug the connector and look for corrosion",
                "If your tool can read the ABS unit (generic OBD readers usually cannot), compare the four wheel speeds " +
                    "while driving slowly in a straight line",
                "Check the wheel bearing for play or noise",
                "Clean the sensor tip and its mounting, or replace the sensor",
            ),
            "Normal braking still works, but ABS, traction and stability control are probably switched off, so wheels " +
                "can lock under hard braking. Drive carefully, especially on wet, icy or snowy roads, and get it checked within days.",
        )
        wheel("C0031", "left front")
        wheel("C0034", "right front")
        wheel("C0037", "left rear")
        wheel("C003A", "right rear")
        add(
            "C0077", CHASSIS, "Tyre pressure monitoring (TPMS)", CHECK_NOW,
            "The tyre pressure monitoring system has detected that one or more tyres are below the warning threshold.",
            listOf(
                "A tyre has lost pressure (puncture, leaking valve or rim)",
                "Natural pressure drop in cold weather (roughly 0.1 bar per 10 °C)",
                "Tyre pressures not adjusted after a seasonal tyre change",
                "Faulty tyre pressure sensor or flat sensor battery (usually gives a separate TPMS fault)",
            ),
            listOf(
                "Check all four tyres with a gauge when cold and set them to the pressures on the door-pillar label",
                "Look for nails, cuts or bulges and listen for hissing",
                "After adjusting, drive for a while; the warning should go off. Some systems need a reset or relearn after " +
                    "a tyre change, see the owner's manual",
                "If a tyre keeps losing pressure, have it repaired by a tyre shop",
            ),
            "Check the tyre pressures at the first safe opportunity. If a tyre is visibly flat, the car pulls to one side " +
                "or you hear flapping, stop and change the wheel or call for assistance. Driving on a very under-inflated tyre can destroy it.",
        )
        fun airbag(code: String, which: String, extraCause: String) = add(
            code, BODY, "Airbags (SRS): $which", CHECK_NOW,
            "The airbag control unit constantly checks the firing circuit of every airbag. It has found a fault in the " +
                "$which first-stage circuit, so this airbag may not deploy in a crash. The airbag warning light is normally on.",
            listOf(
                extraCause,
                "Loose or corroded airbag connector",
                "Faulty airbag module",
                "Fault in the airbag control unit (less common)",
            ),
            listOf(
                SRS_SAFETY,
                "Think back: was the steering wheel, dashboard, seat or radio area worked on recently?",
                "Have a workshop read the airbag unit with a suitable tool and check the connectors and wiring",
                "After the repair, make sure the airbag warning light goes out a few seconds after switching on",
            ),
            "The car drives normally, but the airbag may not protect you in a crash. Get it repaired as soon as possible " +
                "(within days).",
        )
        airbag(
            "B0001", "driver front airbag",
            "Worn clock spring (the spiral cable in the steering wheel that connects the airbag while the wheel turns), a common cause",
        )
        airbag(
            "B0010", "passenger front airbag",
            "Damaged wiring or connector behind the dashboard (for example after dashboard or radio work)",
        )
    }
}
