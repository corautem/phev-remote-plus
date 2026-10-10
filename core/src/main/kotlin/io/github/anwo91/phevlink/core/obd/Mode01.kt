package io.github.anwo91.phevlink.core.obd

import java.util.Locale

/**
 * A standard OBD-II mode 01 PID with its SAE J1979 formula.
 *
 * @param bytes minimum number of data bytes (A, B, ...) the formula needs
 * @param pollSeconds suggested polling period (1 = every live tick)
 */
class PidDef internal constructor(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val pollSeconds: Double,
    private val calc: (ByteArray) -> Double?,
    private val describe: ((ByteArray) -> String?)? = null,
) {
    /** Decodes the data bytes that follow `41 <pid>`. */
    fun decode(data: ByteArray): Mode01.Decoded {
        if (data.size < bytes) return Mode01.Decoded(null, ObdHex.format(data))
        return Mode01.Decoded(calc(data), describe?.invoke(data))
    }

    override fun toString() = "PID ${ObdHex.byte(pid)} $name"
}

/** SAE J1979 service 01 (current data): supported-PID bitmaps and decoders. */
object Mode01 {
    /** [value] is the number (null for text-only), [text] an optional readable form. */
    data class Decoded(val value: Double?, val text: String?)

    private fun a(d: ByteArray) = d.u8(0).toDouble()
    private fun ab(d: ByteArray) = (d.u8(0) * 256 + d.u8(1)).toDouble()

    /** ISO 15031-5 / J1979 fuel type coding (PID 51). */
    val FUEL_TYPES = listOf(
        "Not available", "Gasoline", "Methanol", "Ethanol", "Diesel", "LPG", "CNG", "Propane", "Electric",
        "Bifuel gasoline", "Bifuel methanol", "Bifuel ethanol", "Bifuel LPG", "Bifuel CNG", "Bifuel propane",
        "Bifuel electricity", "Bifuel electric & combustion", "Hybrid gasoline", "Hybrid ethanol", "Hybrid diesel",
        "Hybrid electric", "Hybrid electric & combustion", "Hybrid regenerative", "Bifuel diesel",
    )

    private fun def(pid: Int, name: String, unit: String, bytes: Int, poll: Double, calc: (ByteArray) -> Double?, text: ((ByteArray) -> String?)? = null) =
        pid to PidDef(pid, name, unit, bytes, poll, calc, text)

    /** Decoders for the common PIDs; any other supported PID is shown as raw hex. */
    val pids: Map<Int, PidDef> = linkedMapOf(
        def(0x01, "Monitor status (MIL, DTC count)", "DTCs", 1, 10.0, { d -> (d.u8(0) and 0x7F).toDouble() }) { d ->
            val n = d.u8(0) and 0x7F
            "MIL ${if (d.u8(0) and 0x80 != 0) "on" else "off"}, $n DTC${if (n == 1) "" else "s"}"
        },
        def(0x04, "Calculated engine load", "%", 1, 1.0, { a(it) * 100.0 / 255.0 }),
        def(0x05, "Engine coolant temperature", "°C", 1, 5.0, { a(it) - 40.0 }),
        def(0x06, "Short term fuel trim (bank 1)", "%", 1, 2.0, { (a(it) - 128.0) * 100.0 / 128.0 }),
        def(0x07, "Long term fuel trim (bank 1)", "%", 1, 5.0, { (a(it) - 128.0) * 100.0 / 128.0 }),
        def(0x08, "Short term fuel trim (bank 2)", "%", 1, 2.0, { (a(it) - 128.0) * 100.0 / 128.0 }),
        def(0x09, "Long term fuel trim (bank 2)", "%", 1, 5.0, { (a(it) - 128.0) * 100.0 / 128.0 }),
        def(0x0B, "Intake manifold absolute pressure", "kPa", 1, 1.0, { a(it) }),
        def(0x0C, "Engine speed", "rpm", 2, 1.0, { ab(it) / 4.0 }),
        def(0x0D, "Vehicle speed", "km/h", 1, 1.0, { a(it) }),
        def(0x0E, "Timing advance", "° before TDC", 1, 1.0, { a(it) / 2.0 - 64.0 }),
        def(0x0F, "Intake air temperature", "°C", 1, 5.0, { a(it) - 40.0 }),
        def(0x10, "Mass air flow", "g/s", 2, 1.0, { ab(it) / 100.0 }),
        def(0x11, "Throttle position", "%", 1, 1.0, { a(it) * 100.0 / 255.0 }),
        def(0x1F, "Run time since engine start", "s", 2, 5.0, { ab(it) }),
        def(0x21, "Distance travelled with MIL on", "km", 2, 30.0, { ab(it) }),
        def(0x2F, "Fuel tank level", "%", 1, 10.0, { a(it) * 100.0 / 255.0 }),
        def(0x30, "Warm-ups since codes cleared", "count", 1, 30.0, { a(it) }),
        def(0x31, "Distance since codes cleared", "km", 2, 30.0, { ab(it) }),
        def(0x33, "Barometric pressure", "kPa", 1, 30.0, { a(it) }),
        def(0x42, "Control module voltage", "V", 2, 5.0, { ab(it) / 1000.0 }),
        def(0x43, "Absolute load value", "%", 2, 1.0, { ab(it) * 100.0 / 255.0 }),
        def(0x44, "Commanded air-fuel equivalence ratio", "λ", 2, 2.0, { ab(it) * 2.0 / 65536.0 }),
        def(0x45, "Relative throttle position", "%", 1, 1.0, { a(it) * 100.0 / 255.0 }),
        def(0x46, "Ambient air temperature", "°C", 1, 10.0, { a(it) - 40.0 }),
        def(0x49, "Accelerator pedal position D", "%", 1, 1.0, { a(it) * 100.0 / 255.0 }),
        def(0x4A, "Accelerator pedal position E", "%", 1, 1.0, { a(it) * 100.0 / 255.0 }),
        def(0x4D, "Time run with MIL on", "min", 2, 30.0, { ab(it) }),
        def(0x4E, "Time since trouble codes cleared", "min", 2, 30.0, { ab(it) }),
        def(0x51, "Fuel type", "", 1, 300.0, { a(it) }) { d -> FUEL_TYPES.getOrNull(d.u8(0)) ?: "type ${d.u8(0)}" },
        def(0x5C, "Engine oil temperature", "°C", 1, 5.0, { a(it) - 40.0 }),
        def(0xA6, "Odometer", "km", 4, 60.0, { d ->
            ((d.u8(0).toLong() shl 24) or (d.u8(1).toLong() shl 16) or (d.u8(2).toLong() shl 8) or d.u8(3).toLong()) / 10.0
        }),
    )

    /** PIDs 00, 20, 40 ... C0 are "supported PIDs" bitmaps. */
    fun isBitmapPid(pid: Int): Boolean = pid and 0x1F == 0

    /**
     * Decodes a supported-PIDs bitmap: [bitmap] holds the 4 bytes after `41 <base>`; bit 7 of the first byte
     * is PID base+1. `BE 1F A8 13` for base 00 → 01,03,04,05,06,07,0C,0D,0E,0F,10,11,13,15,1C,1F,20.
     */
    fun parseSupported(base: Int, bitmap: ByteArray): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (i in 0 until minOf(4, bitmap.size) * 8) {
            if ((bitmap.u8(i / 8) shr (7 - i % 8)) and 1 == 1) out += base + i + 1
        }
        return out
    }

    /** Decodes the data bytes after `41 <pid>`; unknown PIDs come back as raw hex text. */
    fun decode(pid: Int, data: ByteArray): Decoded = pids[pid]?.decode(data) ?: Decoded(null, ObdHex.format(data))

    fun nameOf(pid: Int): String = pids[pid]?.name ?: "PID ${ObdHex.byte(pid)}"
    fun unitOf(pid: Int): String = pids[pid]?.unit ?: ""

    /** Value id used in [ObdState.values]: "obd.0C" for the engine ECU (7E8), "obd.0C@7E9" for others. */
    fun valueId(pid: Int, rxId: Int = 0x7E8): String =
        "obd." + ObdHex.byte(pid) + if (rxId == 0x7E8) "" else "@" + ObdHex.canId(rxId)
}

/** SAE J1979 service 09 (vehicle information). */
object Mode09 {
    /** VIN from a `49 02 [01] <17 ASCII>` reply; null if it doesn't look like a VIN. */
    fun parseVin(reply: EcuReply): String? {
        val d = reply.data
        if (d.size < 3 || d.u8(0) != 0x49 || d.u8(1) != 0x02) return null
        val text = ascii(d.copyOfRange(2, d.size))
        val vin = text.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        val candidate = if (vin.length > 17) vin.takeLast(17) else vin
        return candidate.takeIf { it.length >= 11 }
    }

    /** ECU name from a `49 0A 01 <acronym 4> <sep> <name 15>` reply, e.g. "ECM-EngineControl". */
    fun parseEcuName(reply: EcuReply): String? {
        val d = reply.data
        if (d.size < 4 || d.u8(0) != 0x49 || d.u8(1) != 0x0A) return null
        val body = d.copyOfRange(3, d.size)
        val s = buildString {
            for (b in body) {
                val c = b.toInt() and 0xFF
                append(if (c == 0) '\u0000' else if (c in 0x20..0x7E) c.toChar() else ' ')
            }
        }
        val parts = s.split('\u0000', '-').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.joinToString("-").takeIf { it.isNotEmpty() }
    }

    private fun ascii(b: ByteArray): String = buildString {
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c in 0x20..0x7E) append(c.toChar())
        }
    }
}
