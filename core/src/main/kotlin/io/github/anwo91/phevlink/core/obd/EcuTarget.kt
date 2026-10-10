package io.github.anwo91.phevlink.core.obd

/**
 * Where a request goes and where the answer comes from (11-bit CAN ids).
 *
 * @param txId request id, e.g. `0x7E0` (engine), `0x761` (battery ECU) or `0x7DF` (functional broadcast).
 * @param rxId expected reply id, e.g. `0x7E8` / `0x762`; null when unknown or for broadcasts.
 * @param flowControl the reply id is not `txId + 8`, so the adapter must be told where to send the
 *   ISO-TP flow-control frame (`ATFCSH<tx>` / `ATFCSD300000` / `ATFCSM1`) or multi-frame replies stall
 *   after the first frame.
 * @param broadcast functional request: every ECU may answer, the receive filter is reset.
 */
data class EcuTarget(
    val txId: Int,
    val rxId: Int?,
    val flowControl: Boolean = false,
    val broadcast: Boolean = false,
) {
    init {
        require(txId in 0..0x7FF) { "only 11-bit CAN ids are supported: $txId" }
        require(rxId == null || rxId in 0..0x7FF) { "only 11-bit CAN ids are supported: $rxId" }
    }

    /** "7E0→7E8" or "broadcast 7DF", for logs. */
    val label: String
        get() = if (broadcast) "broadcast ${ObdHex.canId(txId)}" else "${ObdHex.canId(txId)}→${rxId?.let { ObdHex.canId(it) } ?: "?"}"

    override fun toString(): String = label

    companion object {
        /** OBD-II functional address: all emission-related ECUs answer (7E8..7EF). */
        val OBD_BROADCAST = EcuTarget(0x7DF, null, flowControl = false, broadcast = true)

        /** Physically addressed ECU; flow control is needed when the reply id is not `tx + 8`. */
        fun physical(txId: Int, rxId: Int = txId + 8) = EcuTarget(txId, rxId, flowControl = rxId != txId + 8)

        /** Standard OBD ECU by its reply id (`0x7E8` → request id `0x7E0`). */
        fun obd(rxId: Int) = EcuTarget(rxId - 8, rxId)
    }
}

/**
 * One complete (reassembled) reply from one ECU. [data] starts with the response service id,
 * e.g. `41 0C 1A F8` or `61 01 82 83 ...`, and is cut to the ISO-TP length.
 */
class EcuReply(val rxId: Int, val data: ByteArray) {
    /** Response service id (first byte), or -1 for an empty reply. */
    val sid: Int get() = if (data.isEmpty()) -1 else data.u8(0)

    /** `7F <sid> <nrc>`. */
    val isNegative: Boolean get() = data.size >= 3 && data.u8(0) == 0x7F

    /** The refused service id of a negative reply. */
    val negativeServiceId: Int? get() = if (isNegative) data.u8(1) else null

    /** The negative response code of a negative reply (see [Nrc]). */
    val nrc: Int? get() = if (isNegative) data.u8(2) else null

    /** Display name of the sender, see [Ecus.nameForRx]. */
    val ecuName: String get() = Ecus.nameForRx(rxId)

    override fun equals(other: Any?): Boolean = other is EcuReply && other.rxId == rxId && other.data.contentEquals(data)
    override fun hashCode(): Int = rxId * 31 + data.contentHashCode()
    override fun toString(): String = "${if (rxId == UNKNOWN_RX) "???" else ObdHex.canId(rxId)}: ${ObdHex.format(data)}"

    companion object {
        /** Sender unknown (adapter printed data without CAN headers). */
        const val UNKNOWN_RX = -1
    }
}

/**
 * An ECU the app knows how to address.
 *
 * @param probe the id is a guess for the PHEV (not documented in the sources used); treat answers as a discovery.
 * @param obd answers standard OBD-II (SAE J1979) requests on the functional address.
 * @param source where the id comes from.
 */
data class KnownEcu(
    val txId: Int,
    val rxId: Int,
    val name: String,
    val probe: Boolean,
    val obd: Boolean,
    val source: String,
) {
    val target: EcuTarget get() = EcuTarget.physical(txId, rxId)
}

/**
 * ECU name table for display. Ids come from SAE J1979 (7E0..7E7 / 7E8..7EF), the myoutlanderphev.com forum
 * (BMU, OBC, motors) and the OBDb Mitsubishi-Outlander signal set (non-PHEV Outlander: cluster, A/C, ABS, AWC).
 * Entries marked [KnownEcu.probe] are not documented for the PHEV and are only probed.
 */
object Ecus {
    private const val SRC_J1979 = "SAE J1979 (OBD-II standard)"
    private const val SRC_FORUM = "myoutlanderphev.com forum (PHEV)"
    private const val SRC_OBDB = "OBDb Mitsubishi-Outlander (non-PHEV)"
    private const val SRC_PROBE = "probe only (not documented for the PHEV)"

    val ENGINE = KnownEcu(0x7E0, 0x7E8, "Engine (ECM)", probe = false, obd = true, source = SRC_J1979)
    val TRANSMISSION = KnownEcu(0x7E1, 0x7E9, "Transmission/7E9", probe = true, obd = true, source = SRC_PROBE)
    val HYBRID = KnownEcu(0x7E2, 0x7EA, "Hybrid/EV control (if present)", probe = true, obd = true, source = SRC_PROBE)
    val BMU = KnownEcu(0x761, 0x762, "Drive battery (BMU)", probe = false, obd = false, source = SRC_FORUM)
    val OBC = KnownEcu(0x765, 0x766, "On-board charger (OBC)", probe = false, obd = false, source = SRC_FORUM)
    val FRONT_MOTOR = KnownEcu(0x753, 0x754, "Front motor controller", probe = false, obd = false, source = SRC_FORUM)
    val REAR_MOTOR = KnownEcu(0x755, 0x756, "Rear motor controller", probe = false, obd = false, source = SRC_FORUM)
    val CLUSTER = KnownEcu(0x6A0, 0x514, "Instrument cluster", probe = false, obd = false, source = SRC_OBDB)
    val AIRCON = KnownEcu(0x688, 0x511, "Air conditioning", probe = false, obd = false, source = SRC_OBDB)
    val ABS = KnownEcu(0x784, 0x785, "ABS/ASC", probe = false, obd = false, source = SRC_OBDB)
    val AWC = KnownEcu(0x7B6, 0x7B7, "AWC (4WD)", probe = false, obd = false, source = SRC_OBDB)

    val all: List<KnownEcu> =
        listOf(ENGINE, TRANSMISSION, HYBRID, BMU, OBC, FRONT_MOTOR, REAR_MOTOR, CLUSTER, AIRCON, ABS, AWC)

    fun byRx(rxId: Int): KnownEcu? = all.firstOrNull { it.rxId == rxId }
    fun byTx(txId: Int): KnownEcu? = all.firstOrNull { it.txId == txId }

    /** Display name for a reply id: the table name, else "OBD ECU 7EB" / "ECU 6F1". */
    fun nameForRx(rxId: Int): String = when {
        rxId == EcuReply.UNKNOWN_RX -> "ECU (unknown id)"
        else -> byRx(rxId)?.name ?: if (rxId in 0x7E8..0x7EF) "OBD ECU ${ObdHex.canId(rxId)}" else "ECU ${ObdHex.canId(rxId)}"
    }

    /** Display name for a request id. */
    fun nameForTx(txId: Int): String = byTx(txId)?.name ?: "ECU ${ObdHex.canId(txId)}"
}
