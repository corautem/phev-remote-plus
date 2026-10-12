package io.github.anwo91.phevlink.obd

import android.content.Context
import android.content.SharedPreferences

/**
 * An OBD adapter the app can connect to.
 *
 * @param kind how to reach it
 * @param address Bluetooth MAC address ("00:1D:A5:68:98:8B") for [Kind.CLASSIC] and [Kind.BLE],
 *   `host:port` for [Kind.WIFI] (port defaults to [WifiTcpLink.DEFAULT_PORT] when missing)
 * @param name name to show (Bluetooth name, or a label for Wi-Fi adapters)
 */
data class ObdDeviceSpec(
    val kind: Kind,
    val address: String,
    val name: String,
) {
    enum class Kind {
        /** Bluetooth Classic, Serial Port Profile (RFCOMM). Most ELM327 clones, OBDLink LX/MX+, Vgate iCar 2. */
        CLASSIC,

        /** Bluetooth Low Energy with a UART-style GATT service. Vgate iCar Pro BLE, OBDLink CX, Veepeak BLE, Konnwei... */
        BLE,

        /** TCP over the adapter's own Wi-Fi network (usually 192.168.0.10:35000). */
        WIFI,

        /**
         * The simulated adapter of demo mode (an in-process FakeElm). Never stored as a last/recent device and never
         * read back from storage, so it can't outlive the demo.
         */
        DEMO,
    }

    /** True when [name] looks like an OBD adapter (see [ObdDevices.looksLikeObd]). */
    val looksLikeObd: Boolean get() = kind == Kind.WIFI || kind == Kind.DEMO || ObdDevices.looksLikeObd(name)

    /** The demo-mode simulated adapter. */
    val isDemo: Boolean get() = kind == Kind.DEMO

    /** Same adapter (kind and address), regardless of the name. */
    fun sameDevice(other: ObdDeviceSpec): Boolean = kind == other.kind && address.equals(other.address, ignoreCase = true)

    /** One-line form for storage: `KIND<TAB>address<TAB>name`. */
    internal fun encode(): String = kind.name + SEP + clean(address) + SEP + clean(name)

    companion object {
        private const val SEP = '\t'

        /** The usual Wi-Fi ELM327: 192.168.0.10:35000. */
        fun defaultWifi(name: String = "Wi-Fi OBD adapter"): ObdDeviceSpec =
            ObdDeviceSpec(Kind.WIFI, "${WifiTcpLink.DEFAULT_HOST}:${WifiTcpLink.DEFAULT_PORT}", name)

        /** The simulated adapter used while demo mode is on. */
        fun demo(name: String): ObdDeviceSpec = ObdDeviceSpec(Kind.DEMO, DEMO_ADDRESS, name)

        const val DEMO_ADDRESS = "demo"

        private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

        /** Parses [encode]'s output; null for anything malformed. */
        internal fun decode(line: String): ObdDeviceSpec? {
            val parts = line.split(SEP, limit = 3)
            if (parts.size < 2) return null
            val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
            if (kind == Kind.DEMO) return null // never persisted; ignore if it somehow was
            val address = parts[1].trim()
            if (address.isEmpty()) return null
            val name = parts.getOrNull(2)?.trim().orEmpty().ifEmpty { address }
            return ObdDeviceSpec(kind, address, name)
        }
    }
}

/**
 * Remembers the last used adapter and a short list of recent ones, in the private SharedPreferences
 * file "obd" (deliberately separate from the DataStore settings). All calls are cheap and thread-safe.
 */
internal class ObdDeviceStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The adapter used last, or null. */
    fun last(): ObdDeviceSpec? = prefs.getString(KEY_LAST, null)?.let { ObdDeviceSpec.decode(it) }

    /** Recently used adapters, most recent first (at most [MAX_RECENT]). */
    fun recent(): List<ObdDeviceSpec> =
        prefs.getString(KEY_RECENT, null).orEmpty()
            .split('\n')
            .mapNotNull { if (it.isBlank()) null else ObdDeviceSpec.decode(it) }

    /** Makes [spec] the last used adapter and moves it to the front of the recent list. */
    @Synchronized
    fun remember(spec: ObdDeviceSpec): List<ObdDeviceSpec> {
        if (spec.isDemo) return recent() // the demo adapter is never stored
        val list = (listOf(spec) + recent().filterNot { it.sameDevice(spec) }).take(MAX_RECENT)
        prefs.edit()
            .putString(KEY_LAST, spec.encode())
            .putString(KEY_RECENT, list.joinToString("\n") { it.encode() })
            .apply()
        return list
    }

    /** Removes [spec] from the recent list (and as last device). */
    @Synchronized
    fun forget(spec: ObdDeviceSpec): List<ObdDeviceSpec> {
        val list = recent().filterNot { it.sameDevice(spec) }
        val e = prefs.edit().putString(KEY_RECENT, list.joinToString("\n") { it.encode() })
        if (last()?.sameDevice(spec) == true) e.remove(KEY_LAST)
        e.apply()
        return list
    }

    companion object {
        const val FILE = "obd"
        private const val KEY_LAST = "last_device"
        private const val KEY_RECENT = "recent_devices"
        const val MAX_RECENT = 8
    }
}
