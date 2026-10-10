package io.github.anwo91.phevlink.core.obd

/**
 * Emission readiness ("self-test") status from mode 01 PID 01 (since codes were cleared) or PID 41 (this drive
 * cycle), SAE J1979.
 *
 * PID 01 byte A: bit 7 = MIL on, bits 0–6 = number of confirmed emission DTCs (PID 41: byte A reserved).
 * Byte B: bit 3 = 0 spark ignition / 1 compression ignition; bits 0–2 = continuous monitors supported
 * (misfire, fuel system, comprehensive components); bits 4–6 = the same monitors NOT complete.
 * Byte C: non-continuous monitors supported, byte D: the same NOT complete (1 = incomplete). For spark
 * ignition: bit 0 catalyst, 1 heated catalyst, 2 EVAP, 3 secondary air, 4 A/C refrigerant, 5 O2 sensor,
 * 6 O2 sensor heater, 7 EGR/VVT. For compression ignition: 0 NMHC catalyst, 1 NOx/SCR, 3 boost pressure,
 * 5 exhaust gas sensor, 6 particulate filter, 7 EGR/VVT.
 */
data class Readiness(
    val milOn: Boolean?,
    val dtcCount: Int?,
    val sparkIgnition: Boolean,
    val monitors: List<Monitor>,
) {
    /**
     * @param key stable id (also the translation key suffix): misfire, fuel_system, components, catalyst,
     *   heated_catalyst, evap, secondary_air, ac_refrigerant, o2_sensor, o2_heater, egr, nmhc_catalyst, nox_scr,
     *   boost_pressure, exhaust_sensor, pm_filter
     */
    data class Monitor(val key: String, val name: String, val supported: Boolean, val complete: Boolean)

    val supported: List<Monitor> get() = monitors.filter { it.supported }
    val incomplete: List<Monitor> get() = monitors.filter { it.supported && !it.complete }
    val allComplete: Boolean get() = incomplete.isEmpty()

    companion object {
        private val CONTINUOUS = listOf(
            "misfire" to "Misfire", "fuel_system" to "Fuel system", "components" to "Comprehensive components",
        )
        private val SPARK = listOf(
            "catalyst" to "Catalyst", "heated_catalyst" to "Heated catalyst", "evap" to "Evaporative system (EVAP)",
            "secondary_air" to "Secondary air system", "ac_refrigerant" to "A/C refrigerant",
            "o2_sensor" to "Oxygen sensor", "o2_heater" to "Oxygen sensor heater", "egr" to "EGR / VVT system",
        )
        private val COMPRESSION = listOf(
            "nmhc_catalyst" to "NMHC catalyst", "nox_scr" to "NOx / SCR aftertreatment", null,
            "boost_pressure" to "Boost pressure", null, "exhaust_sensor" to "Exhaust gas sensor",
            "pm_filter" to "Particulate filter", "egr" to "EGR / VVT system",
        )

        /**
         * Decodes the 4 data bytes after `41 01` (or `41 41` with [thisDriveCycle] = true). Null if too short.
         */
        fun decode(data: ByteArray, thisDriveCycle: Boolean = false): Readiness? {
            if (data.size < 4) return null
            val a = data[0].toInt() and 0xFF
            val b = data[1].toInt() and 0xFF
            val c = data[2].toInt() and 0xFF
            val d = data[3].toInt() and 0xFF
            val spark = b and 0x08 == 0
            val monitors = ArrayList<Monitor>()
            CONTINUOUS.forEachIndexed { i, (key, name) ->
                val sup = b and (1 shl i) != 0
                val incomplete = b and (1 shl (i + 4)) != 0
                monitors += Monitor(key, name, sup, sup && !incomplete)
            }
            val table: List<Pair<String, String>?> = if (spark) SPARK else COMPRESSION
            table.forEachIndexed { i, entry ->
                if (entry == null) return@forEachIndexed
                val sup = c and (1 shl i) != 0
                val incomplete = d and (1 shl i) != 0
                monitors += Monitor(entry.first, entry.second, sup, sup && !incomplete)
            }
            return Readiness(
                milOn = if (thisDriveCycle) null else a and 0x80 != 0,
                dtcCount = if (thisDriveCycle) null else a and 0x7F,
                sparkIgnition = spark,
                monitors = monitors,
            )
        }
    }
}
