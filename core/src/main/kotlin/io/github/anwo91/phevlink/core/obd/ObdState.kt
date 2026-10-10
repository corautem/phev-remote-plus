package io.github.anwo91.phevlink.core.obd

import java.math.BigDecimal
import java.math.RoundingMode

/** Lifecycle of an [ObdClient]. */
enum class ObdPhase {
    /** Not connected (initial state, or after [ObdClient.disconnect]). */
    DISCONNECTED,

    /** [ObdClient.connect] started. */
    CONNECTING,

    /** Resetting/configuring the adapter and discovering the car. */
    INITIALISING,

    /** Adapter ready (check [ObdState.carReachable] for the car). */
    READY,

    /** Live polling is running ([ObdClient.startLive]). */
    POLLING,

    /** Unrecoverable for this client (link lost, adapter dead). See [ObdState.message]; create a new client to retry. */
    ERROR,
}

/**
 * One live value.
 *
 * @param id stable id: "obd.0C" (mode 01 PID of the engine ECU), "obd.05@7E9" (other OBD ECU),
 *   "bmu.soc" (see [PhevSignals]), "bmu.soh_pct" (derived), "adapter.voltage"
 * @param value numeric value, null when only [text] makes sense (raw hex of unknown PIDs)
 * @param text readable form when the number alone isn't enough ("on"/"off", "MIL on, 1 DTC", fuel type, raw hex)
 * @param ecuRxId CAN id of the ECU that produced it
 * @param note source/formula and caveats (for an info dialog)
 */
data class ObdValue(
    val id: String,
    val name: String,
    val group: String,
    val value: Double?,
    val text: String?,
    val unit: String,
    val confidence: Confidence,
    val updatedAtMs: Long,
    val ecuRxId: Int? = null,
    val note: String = "",
) {
    /** "83 °C", "1726 rpm", "3.979 V", "on", "BE 1F" — [decimals] maximum fraction digits. */
    fun formatted(decimals: Int = 2): String {
        if (value == null) return text ?: "—"
        if (text != null) return text
        val n = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val num = if (n == "-0") "0" else n
        return if (unit.isEmpty()) num else "$num $unit"
    }
}

/**
 * An ECU that answered.
 * @param reportedName name the ECU reported itself (mode 09 PID 0A), if any
 * @param probe the id is not documented for the PHEV (see [KnownEcu.probe])
 */
data class EcuInfo(
    val txId: Int,
    val rxId: Int,
    val name: String,
    val obd: Boolean,
    val probe: Boolean,
    val source: String,
    val reportedName: String? = null,
)

/**
 * Everything the UI needs, published by [ObdClient.state].
 *
 * @param adapter what the adapter is and can do (null before the first successful init)
 * @param carReachable the car answered the last check (ignition on)
 * @param message human-readable status or error with advice, e.g. "Adapter answers but the car doesn't — ..."
 * @param batteryMessage non-null when drive-battery data is unavailable, with the reason (adapter limits, BMU silent)
 * @param supportedPids mode 01 PIDs per ECU reply id (0x7E8 → {0x01, 0x04, ...}), bitmap PIDs included
 * @param values live values by id, see [ObdValue.id]
 * @param batteryHealth last decoded BMU snapshot
 * @param dtcs result of the last DTC scan; [lastDtcScanMs] when it ran
 * @param ecus ECUs that answered during discovery/probing
 * @param adapterVoltage 12 V battery voltage measured by the adapter
 * @param unavailable requests/ECUs disabled for this session after failing twice in a row (key → reason)
 * @param pollCycle increments after every poll cycle that sent at least one request (CSV logging uses it to emit
 *   one row per cycle); [lastPollMs] is when that cycle ended
 */
data class ObdState(
    val phase: ObdPhase = ObdPhase.DISCONNECTED,
    val adapter: ElmInfo? = null,
    val carReachable: Boolean = false,
    val message: String? = null,
    val batteryMessage: String? = null,
    val supportedPids: Map<Int, Set<Int>> = emptyMap(),
    val values: Map<String, ObdValue> = emptyMap(),
    val batteryHealth: BatteryHealth? = null,
    val cells: CellData? = null,
    val dtcs: List<DtcRecord> = emptyList(),
    val lastDtcScanMs: Long? = null,
    val vin: String? = null,
    val ecus: List<EcuInfo> = emptyList(),
    val adapterVoltage: Double? = null,
    val unavailable: Map<String, String> = emptyMap(),
    val pollCycle: Long = 0,
    val lastPollMs: Long? = null,
) {
    /** Values grouped for display, groups in first-seen order. */
    fun valuesByGroup(): Map<String, List<ObdValue>> = values.values.groupBy { it.group }
}
