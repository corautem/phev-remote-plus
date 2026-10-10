package io.github.anwo91.phevlink.core.obd

/** How much a signal definition can be trusted. */
enum class Confidence {
    /** Standard (SAE J1979) or confirmed on a 2014–2017 Outlander PHEV. */
    VERIFIED,

    /** Documented by the community for the PHEV (e.g. the myoutlanderphev.com forum). */
    COMMUNITY,

    /** Inferred, or documented for a different model; may be wrong on this car. */
    EXPERIMENTAL,
}

/**
 * A manufacturer-specific signal, read with [request] from [target] and decoded as
 * `raw * mul / div + add` where `raw` is [bitLength] bits starting at [bitIndex].
 *
 * Bit numbering (same as OBDb): bit 0 is the most significant bit of the first byte **after** the positive-response
 * echo (for request `21 01` the reply is `61 01 <byte0> <byte1> ...`), counting MSB-first, so `bitIndex = 8 * n`
 * addresses byte n. Multi-byte values are big-endian.
 *
 * Values outside [min]..[max] are treated as invalid and hidden. [flag] signals (1 bit) are shown as on/off.
 * Requests shared by several signals are fetched once per poll ([SignalRequest]).
 */
class SignalDef(
    val id: String,
    val name: String,
    val group: String,
    val target: EcuTarget,
    val request: ByteArray,
    val bitIndex: Int,
    val bitLength: Int,
    val signed: Boolean = false,
    val mul: Double = 1.0,
    val div: Double = 1.0,
    val add: Double = 0.0,
    val unit: String = "",
    val min: Double,
    val max: Double,
    val confidence: Confidence,
    val note: String = "",
    val pollSeconds: Double = 5.0,
    val flag: Boolean = false,
) {
    init {
        require(bitLength in 1..32) { "$id: bitLength $bitLength" }
        require(bitIndex >= 0) { "$id: bitIndex $bitIndex" }
        require(div != 0.0) { "$id: div 0" }
        require(request.isNotEmpty()) { "$id: empty request" }
    }

    /** "761:2101" — identifies the shared request. */
    val requestKey: String get() = SignalRequest.keyOf(target, request)

    override fun toString(): String = "SignalDef($id, $requestKey bit $bitIndex+$bitLength)"
}

/** All signals that share one (target, request) pair: fetched once per poll cycle. */
class SignalRequest(val target: EcuTarget, val request: ByteArray, val signals: List<SignalDef>) {
    val key: String = keyOf(target, request)

    /** Shortest poll period of its signals. */
    val pollSeconds: Double = signals.minOfOrNull { it.pollSeconds } ?: 5.0

    val ecuName: String get() = Ecus.nameForTx(target.txId)

    override fun toString(): String = "SignalRequest($key, ${signals.size} signals)"

    companion object {
        fun keyOf(target: EcuTarget, request: ByteArray) = ObdHex.canId(target.txId) + ":" + ObdHex.format(request, "")

        /** Groups [signals] by (target, request), keeping first-seen order. */
        fun group(signals: List<SignalDef>): List<SignalRequest> =
            signals.groupBy { it.requestKey }.values.map { SignalRequest(it.first().target, it.first().request, it) }
    }
}

/** Bit extraction and scaling for [SignalDef]. */
object SignalDecoder {
    /** [bitLength] bits from [bitIndex] (MSB-first), or null if [data] is too short. */
    fun extract(data: ByteArray, bitIndex: Int, bitLength: Int, signed: Boolean = false): Long? {
        if (bitLength !in 1..32 || bitIndex < 0 || bitIndex + bitLength > data.size * 8) return null
        var v = 0L
        for (k in 0 until bitLength) {
            val bit = bitIndex + k
            v = (v shl 1) or ((data.u8(bit / 8) shr (7 - bit % 8)) and 1).toLong()
        }
        if (signed && (v shr (bitLength - 1)) and 1L == 1L) v -= 1L shl bitLength
        return v
    }

    /**
     * Decodes [def] from [payload] (the bytes after the positive-response echo).
     * Returns null when the payload is too short or the value is outside min..max.
     */
    fun decode(def: SignalDef, payload: ByteArray): Double? {
        val raw = extract(payload, def.bitIndex, def.bitLength, def.signed) ?: return null
        val v = raw.toDouble() * def.mul / def.div + def.add
        if (v.isNaN() || v < def.min - EPS || v > def.max + EPS) return null
        return v
    }

    /**
     * The payload after the echo of [request] in a positive [reply] (`61 01 ...` for `21 01`),
     * or null if the reply doesn't belong to the request.
     */
    fun payloadOf(request: ByteArray, reply: EcuReply): ByteArray? {
        val d = reply.data
        if (d.size < request.size || d.u8(0) != ((request.u8(0) + 0x40) and 0xFF)) return null
        for (i in 1 until request.size) if (d[i] != request[i]) return null
        return d.copyOfRange(request.size, d.size)
    }

    private const val EPS = 1e-9
}
