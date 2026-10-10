package io.github.anwo91.phevlink.core.obd

import io.github.anwo91.phevlink.core.obd.Confidence.COMMUNITY
import io.github.anwo91.phevlink.core.obd.Confidence.EXPERIMENTAL

/**
 * Manufacturer-specific signals for the 2014–2017 Outlander PHEV (CAN 11-bit 500 kbit/s), all read-only
 * (service 0x21 ReadDataByLocalIdentifier in the default session; nothing here changes ECU state).
 *
 * Sources:
 * - Drive battery (BMU 761/762), charger (OBC 765/766) and motors (753/754, 755/756): myoutlanderphev.com forum
 *   thread "Extracting useful data from your OBDII port" (user anko). Documented values are [Confidence.COMMUNITY];
 *   values only inferred from the thread's example reply are [Confidence.EXPERIMENTAL].
 * - Everything else: the OBDb Mitsubishi-Outlander signal set (https://github.com/OBDb/Mitsubishi-Outlander,
 *   CC BY-SA 4.0) for the 3rd-generation Outlander (non-PHEV). All [Confidence.EXPERIMENTAL]: the PHEV shares
 *   body/chassis ECUs with it, but none of this is confirmed on a PHEV. Signals that need a diagnostic session,
 *   are diesel/turbo-only or are for the 4th generation are left out.
 */
object PhevSignals {
    const val GROUP_BATTERY = "Drive battery"
    const val GROUP_CHARGER = "Charger"
    const val GROUP_MOTORS = "Electric motors"
    const val GROUP_ENGINE = "Engine (Mitsubishi)"
    const val GROUP_CLUSTER = "Instrument cluster"
    const val GROUP_CLIMATE = "Air conditioning"
    const val GROUP_ABS = "Wheel speeds (ABS)"
    const val GROUP_AWC = "AWC (4WD)"

    /** Nominal capacity of the 12 kWh pack of 2013–2018 cars (80 cells, ~300 V). Used for the approximate SoH. */
    const val NOMINAL_CAPACITY_AH = 40.0

    val BMU: EcuTarget = Ecus.BMU.target
    val OBC: EcuTarget = Ecus.OBC.target
    val FRONT_MOTOR: EcuTarget = Ecus.FRONT_MOTOR.target
    val REAR_MOTOR: EcuTarget = Ecus.REAR_MOTOR.target
    val ENGINE: EcuTarget = Ecus.ENGINE.target
    val CLUSTER: EcuTarget = Ecus.CLUSTER.target
    val AIRCON: EcuTarget = Ecus.AIRCON.target
    val ABS: EcuTarget = Ecus.ABS.target
    val AWC: EcuTarget = Ecus.AWC.target

    private const val FORUM = "Source: myoutlanderphev.com forum, \"Extracting useful data from your OBDII port\" (anko)."
    private const val INFERRED =
        "Inferred from the forum's example reply (consistent with the pack voltage: 318.2 V / 80 cells = 3.98 V); not documented."
    private const val OBDB = "From OBDb Outlander (non-PHEV) definitions; may not apply to the PHEV."
    private const val OBDB_ENGINE = "OBDb marks it verified for model year 2011 only."
    private const val OBDB_DEBUG = "OBDb marks it unverified."

    private fun r21(pid: Int) = bytesOf(0x21, pid)

    private fun forum(
        id: String, name: String, group: String, target: EcuTarget, request: ByteArray, bix: Int, len: Int,
        div: Double = 1.0, add: Double = 0.0, unit: String, min: Double, max: Double,
        confidence: Confidence, note: String, poll: Double,
    ) = SignalDef(id, name, group, target, request, bix, len, false, 1.0, div, add, unit, min, max, confidence, note, poll)

    private fun obdb(
        id: String, obdbId: String, name: String, group: String, target: EcuTarget, pid: Int, bix: Int, len: Int,
        mul: Double = 1.0, div: Double = 1.0, add: Double = 0.0, unit: String = "", min: Double = 0.0, max: Double,
        poll: Double, flag: Boolean = false, extra: String = OBDB_DEBUG,
    ) = SignalDef(
        id, name, group, target, r21(pid), bix, len, false, mul, div, add, unit, min, max, EXPERIMENTAL,
        "$OBDB $extra OBDb id $obdbId.", poll, flag,
    )

    private fun ecm(id: String, obdbId: String, name: String, pid: Int, bix: Int, len: Int, mul: Double = 1.0, div: Double = 1.0, add: Double = 0.0, unit: String = "", min: Double = 0.0, max: Double, poll: Double, flag: Boolean = false) =
        obdb(id, obdbId, name, GROUP_ENGINE, ENGINE, pid, bix, len, mul, div, add, unit, min, max, poll, flag, OBDB_ENGINE)

    private fun flag(id: String, obdbId: String, name: String, group: String, target: EcuTarget, pid: Int, bix: Int, poll: Double, extra: String = OBDB_DEBUG) =
        obdb(id, obdbId, name, group, target, pid, bix, 1, max = 1.0, poll = poll, flag = true, extra = extra)

    // ------------------------------------------------------------------ forum (PHEV)

    /**
     * BMU 761→762, request 21 01 (55-byte multi-frame reply; needs custom flow control).
     * Byte n = n-th byte after `61 01`.
     */
    val battery: List<SignalDef> = listOf(
        forum("bmu.soc", "State of charge (BMU)", GROUP_BATTERY, BMU, r21(0x01), 0, 8, div = 2.0, add = -5.0, unit = "%",
            min = 0.0, max = 110.0, confidence = COMMUNITY, note = "Byte 0 / 2 − 5. $FORUM", poll = 5.0),
        forum("bmu.soc2", "State of charge (second value)", GROUP_BATTERY, BMU, r21(0x01), 8, 8, div = 2.0, add = -5.0, unit = "%",
            min = 0.0, max = 110.0, confidence = EXPERIMENTAL, note = "Byte 1 / 2 − 5. $INFERRED", poll = 5.0),
        forum("bmu.cell_max_v", "Highest cell voltage", GROUP_BATTERY, BMU, r21(0x01), 16, 16, div = 1000.0, unit = "V",
            min = 2.0, max = 4.5, confidence = EXPERIMENTAL, note = "Bytes 2–3 / 1000. $INFERRED", poll = 5.0),
        forum("bmu.cell_max_no", "Cell with the highest voltage", GROUP_BATTERY, BMU, r21(0x01), 32, 8, unit = "#",
            min = 0.0, max = 80.0, confidence = EXPERIMENTAL, note = "Byte 4. $INFERRED", poll = 5.0),
        forum("bmu.cell_min_v", "Lowest cell voltage", GROUP_BATTERY, BMU, r21(0x01), 40, 16, div = 1000.0, unit = "V",
            min = 2.0, max = 4.5, confidence = EXPERIMENTAL, note = "Bytes 5–6 / 1000. $INFERRED", poll = 5.0),
        forum("bmu.cell_min_no", "Cell with the lowest voltage", GROUP_BATTERY, BMU, r21(0x01), 56, 8, unit = "#",
            min = 0.0, max = 80.0, confidence = EXPERIMENTAL, note = "Byte 7. $INFERRED", poll = 5.0),
        forum("bmu.pack_v", "Battery pack voltage", GROUP_BATTERY, BMU, r21(0x01), 64, 16, div = 10.0, unit = "V",
            min = 150.0, max = 420.0, confidence = COMMUNITY, note = "Bytes 8–9 / 10. $FORUM", poll = 5.0),
        forum("bmu.capacity_ah", "Battery capacity (health)", GROUP_BATTERY, BMU, r21(0x01), 216, 16, div = 10.0, unit = "Ah",
            min = 1.0, max = 60.0, confidence = COMMUNITY,
            note = "Bytes 27–28 / 10: the BMU's estimate of the full-charge capacity (new: about 40 Ah). $FORUM", poll = 5.0),
        forum("bmu.remaining_ah", "Current charge", GROUP_BATTERY, BMU, r21(0x01), 232, 16, div = 10.0, unit = "Ah",
            min = 0.0, max = 60.0, confidence = COMMUNITY, note = "Bytes 29–30 / 10. $FORUM", poll = 5.0),
    )

    /** OBC 765→766, request 21 01. */
    val charger: List<SignalDef> = listOf(
        forum("obc.charges_100v", "Number of charges at 100 V", GROUP_CHARGER, OBC, r21(0x01), 0, 16, unit = "count",
            min = 0.0, max = 65535.0, confidence = COMMUNITY, note = "Bytes 0–1. $FORUM", poll = 300.0),
        forum("obc.charges_200v", "Number of charges at 200 V", GROUP_CHARGER, OBC, r21(0x01), 16, 16, unit = "count",
            min = 0.0, max = 65535.0, confidence = COMMUNITY, note = "Bytes 2–3. $FORUM", poll = 300.0),
    )

    /** Front motor 753→754 and rear motor 755→756, request 21 02. */
    val motors: List<SignalDef> = listOf(
        forum("motor.front_rpm", "Front motor speed", GROUP_MOTORS, FRONT_MOTOR, r21(0x02), 16, 16, add = -20000.0, unit = "rpm",
            min = -20000.0, max = 20000.0, confidence = COMMUNITY, note = "Bytes 2–3 − 20000. $FORUM", poll = 2.0),
        forum("motor.rear_rpm", "Rear motor speed", GROUP_MOTORS, REAR_MOTOR, r21(0x02), 16, 16, add = -20000.0, unit = "rpm",
            min = -20000.0, max = 20000.0, confidence = COMMUNITY, note = "Bytes 2–3 − 20000. $FORUM", poll = 2.0),
    )

    // ------------------------------------------------------------------ OBDb (non-PHEV Outlander)

    /** Engine ECU 7E0→7E8, service 21 (non-debug OBDb commands; boost pressure left out: turbo only). */
    val engine: List<SignalDef> = listOf(
        ecm("ecm.battery_v", "OUTLANDER_MPI_BATTERY_V", "12 V battery voltage (engine ECU)", 0x01, 0, 8, mul = 18.0, div = 255.0, unit = "V", max = 18.0, poll = 2.0),
        ecm("ecm.speed", "OUTLANDER_MPI_VSS", "Vehicle speed (engine ECU)", 0x01, 8, 8, mul = 2.0, unit = "km/h", max = 200.0, poll = 2.0),
        ecm("ecm.crank_rpm", "OUTLANDER_MPI_CRANK_ANGLE_SNS", "Engine speed (crank angle sensor)", 0x01, 16, 16, div = 8.0, unit = "rpm", max = 8000.0, poll = 2.0),
        ecm("ecm.target_idle_rpm", "OUTLANDER_MPI_TGT_IDLE_RPM", "Target idle speed", 0x01, 32, 8, mul = 2000.0, div = 255.0, unit = "rpm", max = 2000.0, poll = 2.0),
        ecm("ecm.iat", "OUTLANDER_MPI_IAT", "Intake air temperature (engine ECU)", 0x02, 0, 8, add = -40.0, unit = "°C", min = -40.0, max = 120.0, poll = 5.0),
        ecm("ecm.coolant", "OUTLANDER_MPI_ECT", "Coolant temperature (engine ECU)", 0x02, 8, 8, add = -40.0, unit = "°C", min = -40.0, max = 215.0, poll = 5.0),
        ecm("ecm.map", "OUTLANDER_MPI_MANIFOLD_PRES", "Manifold pressure", 0x03, 0, 16, div = 512.0, unit = "kPa", max = 126.0, poll = 2.0),
        ecm("ecm.afs_v", "OUTLANDER_MPI_AIR_FLOW_SNS_V", "Air flow sensor voltage", 0x03, 16, 8, mul = 5.0, div = 255.0, unit = "V", max = 5.0, poll = 2.0),
        ecm("ecm.load", "OUTLANDER_MPI_ENGINE_LOAD", "Engine load (engine ECU)", 0x03, 32, 8, mul = 100.0, div = 255.0, unit = "%", max = 100.0, poll = 2.0),
        ecm("ecm.tps_main_v", "OUTLANDER_MPI_THROTTLE_POSITION_MAIN", "Throttle position sensor (main)", 0x04, 0, 8, mul = 5.0, div = 255.0, unit = "V", max = 5.0, poll = 2.0),
        ecm("ecm.tps_sub_v", "OUTLANDER_MPI_THROTTLE_POSITION_SUB", "Throttle position sensor (sub)", 0x04, 8, 8, mul = 5.0, div = 255.0, unit = "V", max = 5.0, poll = 2.0),
        ecm("ecm.tps_target_v", "OUTLANDER_MPI_TGT_THROTTLE_POSITION_MAIN", "Target throttle position (main)", 0x04, 16, 8, mul = 5.0, div = 255.0, unit = "V", max = 5.0, poll = 2.0),
        ecm("ecm.app_main_v", "OUTLANDER_MPI_ACCELERATOR_POSITION_MAIN", "Accelerator pedal sensor (main)", 0x04, 24, 8, mul = 5.0, div = 255.0, unit = "V", max = 5.0, poll = 2.0),
        ecm("ecm.app_sub_v", "OUTLANDER_MPI_ACCELERATOR_POSITION_SUB", "Accelerator pedal sensor (sub)", 0x04, 32, 8, mul = 5.0, div = 255.0, unit = "V", max = 5.0, poll = 2.0),
        ecm("ecm.ign_advance", "OUTLANDER_MPI_IGN_ADVANCE", "Ignition advance", 0x05, 0, 8, add = -20.0, min = -20.0, max = 235.0, poll = 2.0),
        ecm("ecm.injector_ms", "OUTLANDER_MPI_INJECTOR_DRIVE_TIME", "Injector drive time", 0x05, 8, 8, div = 4.0, unit = "ms", max = 63.75, poll = 2.0),
        ecm("ecm.stft", "OUTLANDER_MPI_SHORT_TERM_FUEL_CORRECTION", "Short term fuel correction", 0x07, 8, 8, mul = 20.0, div = 51.0, add = -50.196078, unit = "%", min = -50.2, max = 49.8, poll = 5.0),
        ecm("ecm.ltft", "OUTLANDER_MPI_LONG_TERM_FUEL_CORRECTION", "Long term fuel correction", 0x07, 16, 8, mul = 20.0, div = 51.0, add = -50.196078, unit = "%", min = -50.2, max = 49.8, poll = 5.0),
        ecm("ecm.knock_retard", "OUTLANDER_MPI_DETONATION_RETARD", "Knock (detonation) retard", 0x08, 8, 8, mul = 90.0, div = 255.0, max = 90.0, poll = 5.0),
        ecm("ecm.knock_learned", "OUTLANDER_MPI_DETONATION_CTRL_LEARNED", "Knock control learned value", 0x08, 16, 8, mul = 100.0, div = 255.0, unit = "%", max = 100.0, poll = 5.0),
        ecm("ecm.fan_duty", "OUTLANDER_MPI_COOLING_FAN_DUTY", "Cooling fan duty", 0x0E, 0, 8, mul = 0.3922, unit = "%", max = 100.011, poll = 5.0),
        ecm("ecm.purge", "OUTLANDER_MPI_PURGE_VALVE_CTRL", "Purge valve control", 0x0F, 0, 8, mul = 100.0, div = 255.0, unit = "%", max = 100.0, poll = 5.0),
        ecm("ecm.throttle_target_v", "OUTLANDER_MPI_TGT_THROTTLE_CTRL", "Target throttle control", 0x12, 0, 16, div = 512.0, unit = "V", max = 5.0, poll = 2.0),
        ecm("ecm.throttle_servo", "OUTLANDER_MPI_THROTTLE_CTRL_SERVO", "Throttle control servo", 0x12, 16, 8, mul = 100.0, div = 255.0, unit = "%", max = 100.0, poll = 2.0),
        ecm("ecm.calc_load", "OUTLANDER_MPI_CALCULATED_LOAD", "Calculated load (engine ECU)", 0x1A, 0, 8, mul = 100.0, div = 255.0, unit = "%", max = 100.0, poll = 2.0),
        ecm("ecm.abs_load", "OUTLANDER_MPI_ABSOLUTE_LOAD", "Absolute load (engine ECU)", 0x1A, 8, 8, mul = 100.0, div = 255.0, unit = "%", max = 100.0, poll = 2.0),
        flag("ecm.idle", "OUTLANDER_MPI_IDLE_STS", "Idle status", GROUP_ENGINE, ENGINE, 0x1D, 0, 2.0, OBDB_ENGINE),
        flag("ecm.cranking", "OUTLANDER_MPI_CRANKING_SIG_ST", "Cranking signal", GROUP_ENGINE, ENGINE, 0x1D, 1, 2.0, OBDB_ENGINE),
        flag("ecm.off_condition", "OUTLANDER_MPI_OFF_COND", "Off condition", GROUP_ENGINE, ENGINE, 0x1D, 4, 2.0, OBDB_ENGINE),
        flag("ecm.neutral", "OUTLANDER_MPI_NEUTRAL_STS", "Neutral status", GROUP_ENGINE, ENGINE, 0x1D, 6, 2.0, OBDB_ENGINE),
        flag("ecm.ignition_switch", "OUTLANDER_MPI_IGN_SWITCH", "Ignition switch", GROUP_ENGINE, ENGINE, 0x1D, 15, 2.0, OBDB_ENGINE),
        flag("ecm.brake_closed", "OUTLANDER_MPI_NORMAL_BRAKE_CLOSED_STS", "Normal brake closed status", GROUP_ENGINE, ENGINE, 0x1D, 30, 2.0, OBDB_ENGINE),
        flag("ecm.brake_switch", "OUTLANDER_MPI_BRAKE_SWITCH", "Brake switch", GROUP_ENGINE, ENGINE, 0x1D, 31, 2.0, OBDB_ENGINE),
        flag("ecm.relay_engine", "OUTLANDER_MPI_ENGINE_CTRL_RELAY", "Engine control relay", GROUP_ENGINE, ENGINE, 0x1E, 2, 5.0, OBDB_ENGINE),
        flag("ecm.relay_throttle", "OUTLANDER_MPI_THROTTLE_CTRL_RELAY", "Throttle control relay", GROUP_ENGINE, ENGINE, 0x1E, 5, 5.0, OBDB_ENGINE),
        flag("ecm.relay_fuel_pump", "OUTLANDER_MPI_FUEL_PUMP_RELAY", "Fuel pump relay", GROUP_ENGINE, ENGINE, 0x1E, 6, 5.0, OBDB_ENGINE),
        flag("ecm.relay_ac", "OUTLANDER_MPI_A_C_COMPRESSOR_RELAY", "A/C compressor relay", GROUP_ENGINE, ENGINE, 0x1E, 7, 5.0, OBDB_ENGINE),
    )

    /** Combination meter 6A0→514. */
    val cluster: List<SignalDef> = listOf(
        obdb("cluster.fuel_level", "OUTLANDER_FLI", "Fuel level (cluster)", GROUP_CLUSTER, CLUSTER, 0xA3, 16, 8, add = -16.0, unit = "%", max = 100.0, poll = 30.0),
        obdb("cluster.odometer", "OUTLANDER_ODO", "Odometer (cluster)", GROUP_CLUSTER, CLUSTER, 0xAD, 0, 24, unit = "km", max = 16777215.0, poll = 60.0),
        obdb("cluster.voltage", "OUTLANDER_DASHBOARD_V", "Cluster supply voltage", GROUP_CLUSTER, CLUSTER, 0xAF, 0, 8, div = 10.0, unit = "V", max = 25.5, poll = 10.0),
        obdb("cluster.service_km", "OUTLANDER_MILEAGE_BEFORE_SERVICING", "Distance to next service", GROUP_CLUSTER, CLUSTER, 0xBC, 0, 8, mul = 100.0, unit = "km", max = 25500.0, poll = 300.0),
        obdb("cluster.service_months", "OUTLANDER_PERIOD_BEFORE_SERVICING_MONTHS", "Months to next service", GROUP_CLUSTER, CLUSTER, 0xBC, 32, 8, unit = "months", max = 255.0, poll = 300.0),
    )

    /** A/C ECU 688→511. */
    val climate: List<SignalDef> = listOf(
        obdb("ac.interior_temp", "OUTLANDER_AC_INTERIOR_T", "Interior temperature", GROUP_CLIMATE, AIRCON, 0x10, 0, 8, div = 4.0, add = -16.0, unit = "°C", min = -16.0, max = 47.75, poll = 10.0),
        obdb("ac.air_thermo", "OUTLANDER_AC_AIR_THERMO_SNS", "Air temperature sensor (A/C)", GROUP_CLIMATE, AIRCON, 0x10, 8, 8, div = 4.0, add = -16.0, unit = "°C", min = -16.0, max = 47.75, poll = 10.0),
        obdb("ac.coolant", "OUTLANDER_AC_ECT", "Engine coolant temperature (A/C ECU)", GROUP_CLIMATE, AIRCON, 0x10, 32, 8, add = -40.0, unit = "°C", min = -40.0, max = 215.0, poll = 10.0),
        obdb("ac.ambient_temp", "OUTLANDER_AC_AAT", "Ambient temperature (A/C ECU)", GROUP_CLIMATE, AIRCON, 0x11, 0, 8, div = 2.0, add = -40.0, unit = "°C", min = -40.0, max = 87.5, poll = 10.0),
        flag("ac.switch", "OUTLANDER_AC_SWITCH", "A/C switch", GROUP_CLIMATE, AIRCON, 0x23, 6, 5.0),
        flag("ac.compressor_request", "OUTLANDER_AC_A_C_COMPRESSOR_DRIVE_REQUEST", "A/C compressor drive request", GROUP_CLIMATE, AIRCON, 0x23, 7, 5.0),
        obdb("ac.driver_setting", "OUTLANDER_AC_DRIVER_T", "Driver temperature setting", GROUP_CLIMATE, AIRCON, 0x34, 0, 8, div = 2.0, unit = "°C", max = 50.0, poll = 10.0),
        obdb("ac.passenger_setting", "OUTLANDER_AC_PASSENGER_T", "Passenger temperature setting", GROUP_CLIMATE, AIRCON, 0x34, 8, 8, div = 2.0, unit = "°C", max = 50.0, poll = 10.0),
    )

    /** ABS/ASC 784→785. */
    val abs: List<SignalDef> = listOf(
        obdb("abs.wheel_fl", "OUTLANDER_ABS_FL_VSS", "Wheel speed front left", GROUP_ABS, ABS, 0x01, 0, 16, div = 100.0, unit = "km/h", max = 655.35, poll = 1.0),
        obdb("abs.wheel_fr", "OUTLANDER_ABS_FR_VSS", "Wheel speed front right", GROUP_ABS, ABS, 0x01, 16, 16, div = 100.0, unit = "km/h", max = 655.35, poll = 1.0),
        obdb("abs.wheel_rl", "OUTLANDER_ABS_RL_VSS", "Wheel speed rear left", GROUP_ABS, ABS, 0x01, 32, 16, div = 100.0, unit = "km/h", max = 655.35, poll = 1.0),
        obdb("abs.wheel_rr", "OUTLANDER_ABS_RR_VSS", "Wheel speed rear right", GROUP_ABS, ABS, 0x01, 48, 16, div = 100.0, unit = "km/h", max = 655.35, poll = 1.0),
    )

    /** AWC (4WD) ECU 7B6→7B7. */
    val awc: List<SignalDef> = listOf(
        obdb("awc.accessory_v", "OUTLANDER_AWC_ACC_V", "Accessory voltage (AWC)", GROUP_AWC, AWC, 0x0D, 24, 8, div = 10.0, unit = "V", max = 25.5, poll = 10.0),
        obdb("awc.odometer", "OUTLANDER_AWC_ODO", "Odometer (AWC)", GROUP_AWC, AWC, 0x0D, 40, 24, div = 10.0, unit = "km", max = 1677721.5, poll = 10.0),
        obdb("awc.ambient_temp", "OUTLANDER_AWC_AAT", "Ambient temperature (AWC)", GROUP_AWC, AWC, 0x0D, 64, 8, div = 2.0, add = -40.0, unit = "°C", min = -40.0, max = 87.5, poll = 10.0),
        obdb("awc.module_v", "OUTLANDER_AWC_ECU_V", "AWC module voltage", GROUP_AWC, AWC, 0x13, 8, 16, div = 64.0, unit = "V", max = 1023.0, poll = 10.0),
    )

    /** Every signal, battery first (its request doubles as the BMU probe). */
    val all: List<SignalDef> = battery + charger + motors + engine + cluster + climate + abs + awc

    /** Group names in display order. */
    val groups: List<String> = all.map { it.group }.distinct()

    /** Signals by group, in display order. */
    fun byGroup(): Map<String, List<SignalDef>> = all.groupBy { it.group }

    fun inGroup(group: String): List<SignalDef> = all.filter { it.group == group }

    fun byId(id: String): SignalDef? = all.firstOrNull { it.id == id }

    fun withConfidence(confidence: Confidence): List<SignalDef> = all.filter { it.confidence == confidence }

    /** The distinct requests needed for [signals] (each fetched once per poll cycle). */
    fun requests(signals: List<SignalDef> = all): List<SignalRequest> = SignalRequest.group(signals)
}
