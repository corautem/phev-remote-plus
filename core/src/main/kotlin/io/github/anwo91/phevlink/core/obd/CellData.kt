package io.github.anwo91.phevlink.core.obd

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-cell drive-battery data, decoded from the BMU's `21 02` (cell voltages) and `21 03` (module temperatures)
 * replies — the view that apps like EvBatMon and Watchdog show.
 *
 * The exact frame layout for the 2014–2017 car is not officially documented; it is pieced together from the OVMS
 * community decoders. So the parser tries the known candidate layouts and **cross-checks** the result against the
 * BMU's own `21 01` summary (highest/lowest cell and their numbers, which we decode reliably). Only a parse that
 * matches the summary is marked [verified]; otherwise the numbers are kept but flagged, and the raw frames are
 * carried so one capture from a real car can pin the layout down.
 *
 * @param cellsMv per-cell voltage in millivolts, cell 1 first
 * @param moduleTempsC module temperatures in °C (may be empty if 21 03 was not read or not plausible)
 * @param verified true when the decoded cells agree with the 21 01 summary
 * @param layout which candidate layout matched ("contiguous", "skip-ff", or "unverified")
 */
data class CellData(
    val cellsMv: List<Int>,
    val moduleTempsC: List<Double>,
    val verified: Boolean,
    val layout: String,
    val updatedAtMs: Long,
    val rawCells: ByteArray,
    val rawTemps: ByteArray?,
) {
    val count: Int get() = cellsMv.size
    val maxMv: Int get() = cellsMv.maxOrNull() ?: 0
    val minMv: Int get() = cellsMv.minOrNull() ?: 0
    val avgMv: Int get() = if (cellsMv.isEmpty()) 0 else cellsMv.average().roundToInt()
    val spreadMv: Int get() = maxMv - minMv
    /** 1-based number of the highest / lowest cell. */
    val maxCell: Int get() = cellsMv.indexOf(maxMv) + 1
    val minCell: Int get() = cellsMv.indexOf(minMv) + 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CellData) return false
        return cellsMv == other.cellsMv && moduleTempsC == other.moduleTempsC && verified == other.verified &&
            layout == other.layout && updatedAtMs == other.updatedAtMs &&
            rawCells.contentEquals(other.rawCells) && (rawTemps?.contentEquals(other.rawTemps ?: ByteArray(0)) ?: (other.rawTemps == null))
    }

    override fun hashCode(): Int {
        var h = cellsMv.hashCode()
        h = 31 * h + moduleTempsC.hashCode()
        h = 31 * h + rawCells.contentHashCode()
        return h
    }

    companion object {
        const val EXPECTED_CELLS = 80
        /** The decoded max/min must be within this of the summary (mV) to count as verified. */
        private const val TOLERANCE_MV = 12

        /**
         * Decodes the `21 02` [cellsPayload] (bytes after the `61 02` echo) and the optional `21 03` [tempsPayload],
         * cross-checking against [summary] (the BMU 21 01 snapshot). Returns null if nothing plausible came out.
         */
        fun decode(cellsPayload: ByteArray, tempsPayload: ByteArray?, summary: BatteryHealth?, nowMs: Long): CellData? {
            val candidates = listOf(
                "contiguous" to u16be(cellsPayload),
                "skip-ff" to u16be(cellsPayload.filter { (it.toInt() and 0xff) != 0xff && (it.toInt() and 0xff) != 0xfe }.toByteArray()),
            )
            // Keep only plausible Li-ion cell voltages (2.0–4.5 V), take up to 80.
            fun plausible(mv: List<Int>) = mv.filter { it in 2000..4500 }.take(EXPECTED_CELLS)

            val summaryMax = summary?.maxCellV?.let { (it * 1000).roundToInt() }
            val summaryMin = summary?.minCellV?.let { (it * 1000).roundToInt() }

            var best: Triple<List<Int>, String, Boolean>? = null
            for ((name, all) in candidates) {
                val cells = plausible(all)
                if (cells.size < EXPECTED_CELLS / 2) continue
                val matches = summaryMax != null && summaryMin != null &&
                    abs((cells.maxOrNull() ?: 0) - summaryMax) <= TOLERANCE_MV &&
                    abs((cells.minOrNull() ?: 0) - summaryMin) <= TOLERANCE_MV
                if (matches) { best = Triple(cells, name, true); break }
                if (best == null) best = Triple(cells, name, false)
            }
            val (cells, layout, verified) = best ?: return null

            val temps = tempsPayload?.let { decodeTemps(it) }.orEmpty()
            return CellData(
                cellsMv = cells,
                moduleTempsC = temps,
                verified = verified,
                layout = if (verified) layout else "unverified",
                updatedAtMs = nowMs,
                rawCells = cellsPayload.copyOf(),
                rawTemps = tempsPayload?.copyOf(),
            )
        }

        private fun u16be(b: ByteArray): List<Int> =
            (0 until b.size / 2).map { ((b[2 * it].toInt() and 0xff) shl 8) or (b[2 * it + 1].toInt() and 0xff) }

        /** Module temps as one byte each, °C = raw − 50 (OVMS i-MiEV family). Only kept when all are plausible. */
        private fun decodeTemps(b: ByteArray): List<Double> {
            val t = b.map { (it.toInt() and 0xff) - 50.0 }.filter { it > -45 }
            val plausible = t.filter { it in -40.0..80.0 }
            return if (plausible.size >= t.size - 1 && plausible.isNotEmpty()) plausible else emptyList()
        }
    }
}
