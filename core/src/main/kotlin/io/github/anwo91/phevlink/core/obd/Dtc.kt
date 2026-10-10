package io.github.anwo91.phevlink.core.obd

/** Where a fault code was read from. */
enum class DtcKind {
    /** Confirmed code (mode 03, or confirmed in an ECU's own DTC memory). */
    STORED,

    /** Pending code (mode 07): seen once, not yet confirmed. */
    PENDING,

    /** Permanent code (mode 0A): can't be cleared with mode 04; clears itself after the monitor passes. */
    PERMANENT,
}

/**
 * One diagnostic trouble code.
 *
 * @param code e.g. "P0420", "U0100"
 * @param ecuRxId CAN id of the ECU that reported it
 * @param ecuName display name of that ECU ([Ecus.nameForRx])
 * @param source "OBD-II mode 03" or "ECU Drive battery (BMU) (KWP)" / "(UDS)" for the experimental extended read
 * @param status DTC status byte (KWP2000/UDS reads only)
 * @param failureType UDS failure-type byte (third DTC byte), e.g. 0x1B
 */
data class DtcRecord(
    val code: String,
    val kind: DtcKind,
    val ecuRxId: Int,
    val ecuName: String,
    val source: String = "OBD-II",
    val status: Int? = null,
    val failureType: Int? = null,
) {
    /** True for the experimental per-ECU (KWP2000/UDS) reads. */
    val extended: Boolean get() = status != null || source.startsWith("ECU ")
}

/**
 * Result of [ObdClient.scanDtcs].
 * @param respondingEcus names of the ECUs that answered at least one DTC request
 * @param errors problems worth showing (e.g. "car not answering")
 */
data class DtcScanResult(
    val dtcs: List<DtcRecord>,
    val respondingEcus: List<String>,
    val errors: List<String>,
    val includedExtended: Boolean,
    val scannedAtMs: Long,
    val message: String,
) {
    val success: Boolean get() = respondingEcus.isNotEmpty()
}

/**
 * Result of [ObdClient.clearDtcs] (OBD-II mode 04, broadcast).
 * @param confirmedBy ECUs that answered 0x44
 * @param refusedBy "ECU name: reason" for ECUs that answered negatively
 * @param remaining codes found by the re-read after clearing (permanent codes stay by design)
 */
data class ClearDtcsResult(
    val success: Boolean,
    val confirmedBy: List<String>,
    val refusedBy: List<String>,
    val remaining: List<DtcRecord>,
    val message: String,
)

/** Two-byte DTC encoding (SAE J2012 / ISO 15031-6) and the response formats of modes 03/07/0A, KWP 0x58, UDS 0x59. */
object DtcCodec {
    private val LETTERS = charArrayOf('P', 'C', 'B', 'U')
    private const val HEX = "0123456789ABCDEF"

    /**
     * `A B` → code: bits 7-6 of A = P/C/B/U, bits 5-4 = first digit (0-3), remaining 12 bits = three hex digits.
     * `04 20` → P0420, `0A 80` → P0A80, `C1 00` → U0100, `40 35` → C0035.
     */
    fun decode(a: Int, b: Int): String {
        val hi = a and 0xFF
        val lo = b and 0xFF
        return buildString(5) {
            append(LETTERS[hi ushr 6])
            append(HEX[(hi ushr 4) and 0x03])
            append(HEX[hi and 0x0F])
            append(HEX[lo ushr 4])
            append(HEX[lo and 0x0F])
        }
    }

    /** 16-bit value → code (`0x0420` → "P0420"). */
    fun decode(raw: Int): String = decode((raw ushr 8) and 0xFF, raw and 0xFF)

    /** "P0420" → 0x0420. Throws [IllegalArgumentException] for malformed codes. */
    fun encode(code: String): Int {
        val c = code.trim().uppercase()
        require(c.length == 5) { "DTC must look like P0420: '$code'" }
        val letter = LETTERS.indexOf(c[0])
        val d1 = c[1] - '0'
        require(letter >= 0 && d1 in 0..3) { "DTC must look like P0420: '$code'" }
        val rest = c.substring(2).toInt(16)
        return (letter shl 14) or (d1 shl 12) or rest
    }

    /**
     * Mode 03/07/0A reply (`43 <count> A B A B ...` on CAN; the count byte is detected and also tolerated when
     * absent). `00 00` padding is skipped.
     */
    fun parseObdResponse(reply: EcuReply, kind: DtcKind, source: String = defaultSource(kind)): List<DtcRecord> {
        val d = reply.data
        if (d.size < 2 || reply.isNegative) return emptyList()
        val count = d.u8(1)
        val withCount = (d.size - 2) % 2 == 0 && (d.size - 2) / 2 >= count
        val start = if (withCount) 2 else 1
        val maxPairs = if (withCount) count else (d.size - 1) / 2
        val out = ArrayList<DtcRecord>()
        var i = start
        var pairs = 0
        while (i + 1 < d.size && pairs < maxPairs) {
            val a = d.u8(i)
            val b = d.u8(i + 1)
            if (a != 0 || b != 0) out += DtcRecord(decode(a, b), kind, reply.rxId, Ecus.nameForRx(reply.rxId), source)
            i += 2
            pairs++
        }
        return out.distinct()
    }

    /** KWP2000 ReadDTCByStatus reply: `58 <count> (hi lo status)*`. */
    fun parseKwp18(reply: EcuReply, ecuName: String = Ecus.nameForRx(reply.rxId)): List<DtcRecord> {
        val d = reply.data
        if (d.size < 2 || d.u8(0) != 0x58) return emptyList()
        val n = minOf(d.u8(1), (d.size - 2) / 3)
        val out = ArrayList<DtcRecord>()
        for (k in 0 until n) {
            val i = 2 + 3 * k
            val a = d.u8(i)
            val b = d.u8(i + 1)
            if (a == 0 && b == 0) continue
            out += DtcRecord(decode(a, b), DtcKind.STORED, reply.rxId, ecuName, "ECU $ecuName (KWP)", status = d.u8(i + 2))
        }
        return out.distinct()
    }

    /**
     * UDS ReadDTCInformation reportDTCByStatusMask reply: `59 02 <availabilityMask> (hi mid lo status)*`.
     * The first two DTC bytes map to the P/C/B/U code, the third is kept as [DtcRecord.failureType].
     * Entries without any fault bit (testFailed, pending, confirmed, warning lamp) are skipped.
     */
    fun parseUds19(reply: EcuReply, ecuName: String = Ecus.nameForRx(reply.rxId)): List<DtcRecord> {
        val d = reply.data
        if (d.size < 3 || d.u8(0) != 0x59 || d.u8(1) != 0x02) return emptyList()
        val out = ArrayList<DtcRecord>()
        var i = 3
        while (i + 3 < d.size) {
            val a = d.u8(i)
            val b = d.u8(i + 1)
            val ft = d.u8(i + 2)
            val st = d.u8(i + 3)
            i += 4
            if ((a == 0 && b == 0) || (st and 0x8F) == 0) continue
            val kind = when {
                st and 0x08 != 0 -> DtcKind.STORED
                st and 0x04 != 0 -> DtcKind.PENDING
                else -> DtcKind.STORED
            }
            out += DtcRecord(decode(a, b), kind, reply.rxId, ecuName, "ECU $ecuName (UDS)", status = st, failureType = ft)
        }
        return out.distinct()
    }

    private fun defaultSource(kind: DtcKind) = when (kind) {
        DtcKind.STORED -> "OBD-II mode 03"
        DtcKind.PENDING -> "OBD-II mode 07"
        DtcKind.PERMANENT -> "OBD-II mode 0A"
    }
}
