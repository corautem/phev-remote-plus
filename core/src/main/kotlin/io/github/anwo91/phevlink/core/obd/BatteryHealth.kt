package io.github.anwo91.phevlink.core.obd

import kotlin.math.roundToLong

/**
 * Drive-battery snapshot decoded from the BMU's `21 01` reply (and optionally the charger's `21 01` reply).
 * Byte meanings and confidence levels: see [PhevSignals.battery].
 *
 * @param soc state of charge from byte 0 (%), documented
 * @param soc2 second SoC value from byte 1 (%), inferred
 * @param packVoltage pack voltage (V), documented
 * @param capacityAh the BMU's full-charge capacity estimate ("battery health"), documented
 * @param remainingAh charge currently stored (Ah), documented
 * @param sohPercent approximate state of health = [capacityAh] / 40 Ah (nominal capacity of the 12 kWh pack) × 100
 * @param maxCellV highest cell voltage (inferred), [maxCellNo] its cell number
 * @param minCellV lowest cell voltage (inferred), [minCellNo] its cell number
 * @param spreadMv max − min cell voltage in mV (derived from inferred values)
 * @param energyKwh approximate stored energy = [remainingAh] × [packVoltage] / 1000
 * @param charges100V / [charges200V] charge counters from the on-board charger (if it answered)
 * @param rawBmu BMU reply bytes **after** the `61 01` echo (index = byte number used in the docs), for exploration
 * @param rawObc OBC reply bytes after the `61 01` echo
 */
data class BatteryHealth(
    val soc: Double?,
    val soc2: Double?,
    val packVoltage: Double?,
    val capacityAh: Double?,
    val remainingAh: Double?,
    val sohPercent: Double?,
    val maxCellV: Double?,
    val maxCellNo: Int?,
    val minCellV: Double?,
    val minCellNo: Int?,
    val spreadMv: Double?,
    val energyKwh: Double?,
    val charges100V: Int?,
    val charges200V: Int?,
    val updatedAtMs: Long,
    val rawBmu: ByteArray?,
    val rawObc: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BatteryHealth) return false
        return soc == other.soc && soc2 == other.soc2 && packVoltage == other.packVoltage && capacityAh == other.capacityAh &&
            remainingAh == other.remainingAh && sohPercent == other.sohPercent && maxCellV == other.maxCellV &&
            maxCellNo == other.maxCellNo && minCellV == other.minCellV && minCellNo == other.minCellNo &&
            spreadMv == other.spreadMv && energyKwh == other.energyKwh && charges100V == other.charges100V &&
            charges200V == other.charges200V && updatedAtMs == other.updatedAtMs &&
            rawBmu.contentEquals(other.rawBmu) && rawObc.contentEquals(other.rawObc)
    }

    override fun hashCode(): Int {
        var h = listOf(soc, soc2, packVoltage, capacityAh, remainingAh, maxCellV, minCellV, updatedAtMs).hashCode()
        h = 31 * h + (rawBmu?.contentHashCode() ?: 0)
        h = 31 * h + (rawObc?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        /** Ids of the derived values the client publishes in [ObdState.values]. */
        const val ID_SOH = "bmu.soh_pct"
        const val ID_SPREAD = "bmu.cell_spread_mv"
        const val ID_ENERGY = "bmu.energy_kwh"

        /**
         * Decodes [bmuPayload] (bytes after `61 01`) and optionally [obcPayload].
         * Returns null when not even the state of charge can be decoded.
         */
        fun decode(bmuPayload: ByteArray, obcPayload: ByteArray?, nowMs: Long): BatteryHealth? {
            fun bmu(id: String): Double? = PhevSignals.byId(id)?.let { SignalDecoder.decode(it, bmuPayload) }
            fun obc(id: String): Double? = obcPayload?.let { p -> PhevSignals.byId(id)?.let { SignalDecoder.decode(it, p) } }
            val soc = bmu("bmu.soc") ?: return null
            val pack = bmu("bmu.pack_v")
            val cap = bmu("bmu.capacity_ah")
            val rem = bmu("bmu.remaining_ah")
            val maxV = bmu("bmu.cell_max_v")
            val minV = bmu("bmu.cell_min_v")
            val spread = if (maxV != null && minV != null && maxV >= minV) ((maxV - minV) * 1000.0).roundToLong().toDouble() else null
            return BatteryHealth(
                soc = soc,
                soc2 = bmu("bmu.soc2"),
                packVoltage = pack,
                capacityAh = cap,
                remainingAh = rem,
                sohPercent = cap?.let { it * 100.0 / PhevSignals.NOMINAL_CAPACITY_AH },
                maxCellV = maxV,
                maxCellNo = bmu("bmu.cell_max_no")?.toInt(),
                minCellV = minV,
                minCellNo = bmu("bmu.cell_min_no")?.toInt(),
                spreadMv = spread,
                energyKwh = if (rem != null && pack != null) rem * pack / 1000.0 else null,
                charges100V = obc("obc.charges_100v")?.toInt(),
                charges200V = obc("obc.charges_200v")?.toInt(),
                updatedAtMs = nowMs,
                rawBmu = bmuPayload.copyOf(),
                rawObc = obcPayload?.copyOf(),
            )
        }
    }
}
