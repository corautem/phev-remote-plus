package io.github.anwo91.phevlink.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.AdapterGuess
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Finding OBD adapters: bonded (paired) Bluetooth devices, a BLE scan, and the permission/Bluetooth-state
 * helpers the UI needs before connecting.
 *
 * Permissions:
 * - Android 12+ (API 31+): BLUETOOTH_CONNECT (bonded list, connecting) and BLUETOOTH_SCAN (BLE scan; declared
 *   with `neverForLocation`).
 * - Android 10–11 (API 29–30): BLUETOOTH/BLUETOOTH_ADMIN are install-time; ACCESS_FINE_LOCATION is needed for
 *   the BLE scan only (and Location must be switched on for scan results).
 */
@SuppressLint("MissingPermission") // every call site checks the permission first (see hasConnectPermission/hasScanPermission)
object ObdDevices {

    /** True when [name] looks like an OBD adapter ("OBDII", "OBDLink MX+", "Android-Vlink", "IOS-Vlink", "V-LINK"…). */
    fun looksLikeObd(name: String?): Boolean = AdapterGuess.guess(name).rank == AdapterGuess.Rank.ADAPTER

    /** Adapters first, then possible ones, unknown devices and proprietary scan tools; by name within each. */
    fun sorted(list: Collection<ObdCandidate>): List<ObdCandidate> =
        list.sortedWith(compareBy<ObdCandidate>({ it.guess.rank.ordinal }, { it.spec.name.lowercase(Locale.ROOT) }))

    /** The phone's Bluetooth adapter, or null when there is no Bluetooth hardware. */
    fun adapter(context: Context): BluetoothAdapter? =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    /** The phone has Bluetooth at all. */
    fun hasBluetooth(context: Context): Boolean = adapter(context) != null

    /** The phone has Bluetooth LE. */
    fun hasBluetoothLe(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** Bluetooth is switched on (false without Bluetooth hardware). Needs no runtime permission. */
    fun isBluetoothOn(context: Context): Boolean =
        runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    /**
     * Intent that asks the user to switch Bluetooth on (start it with an activity-result launcher).
     * On Android 12+ BLUETOOTH_CONNECT must be granted first, otherwise starting it throws SecurityException.
     */
    fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** Runtime permissions to request before listing, scanning and connecting on Android [sdk]. */
    fun requiredPermissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdk >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** All of [requiredPermissions] are granted. */
    fun hasPermissions(context: Context): Boolean = requiredPermissions().all { granted(context, it) }

    /** Allowed to list bonded devices and connect (Android 12+: BLUETOOTH_CONNECT; always true before). */
    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    /** Allowed to scan for BLE devices (Android 12+: BLUETOOTH_SCAN; before: ACCESS_FINE_LOCATION). */
    fun hasScanPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * All bonded (paired) Bluetooth devices, OBD-looking ones first, then by name. Classic and dual-mode devices
     * are listed as [ObdDeviceSpec.Kind.CLASSIC], LE-only devices as [ObdDeviceSpec.Kind.BLE].
     * Empty without Bluetooth, with Bluetooth off, or without permission. Fast; may be called on the main thread.
     */
    fun bondedDevices(context: Context): List<ObdCandidate> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        val bonded: Set<BluetoothDevice> = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            emptySet()
        } catch (e: RuntimeException) {
            emptySet()
        }
        return sorted(bonded.mapNotNull { d -> candidate(d, d.name) })
    }

    /** [d] as a list entry, with a guess from its name and Bluetooth class, or null if it can't be read. */
    private fun candidate(d: BluetoothDevice, name: String?, services: List<String> = emptyList(), companies: List<Int> = emptyList()): ObdCandidate? =
        try {
            val address = d.address
            if (address == null) {
                null
            } else {
                val shown = name?.takeIf { it.isNotBlank() } ?: address
                val kind = if (d.type == BluetoothDevice.DEVICE_TYPE_LE) ObdDeviceSpec.Kind.BLE else ObdDeviceSpec.Kind.CLASSIC
                // BLE-only devices have no meaningful class (it reads as "misc").
                val major = if (kind == ObdDeviceSpec.Kind.CLASSIC) runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull() else null
                ObdCandidate(ObdDeviceSpec(kind, address, shown), AdapterGuess.guess(name, major, services, companies))
            }
        } catch (e: SecurityException) {
            null
        }

    /**
     * Classic Bluetooth discovery (about 12 s) for adapters that are not paired yet — most older ELM327 dongles are
     * Classic-only and never show up in a BLE scan. Connecting to one makes Android ask for its PIN (often 1234).
     * Emits each device once. Needs [hasScanPermission].
     */
    fun discoverClassic(context: Context, log: DiagLog? = null): Flow<ObdCandidate> = callbackFlow {
        val adapter = adapter(context)
        if (adapter == null || !hasScanPermission(context) || !hasConnectPermission(context)) {
            log?.warn("OBD: Bluetooth discovery not possible (Bluetooth off or permission missing)")
            close()
            return@callbackFlow
        }
        val seen = HashSet<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val d: BluetoothDevice = (
                            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            ) ?: return
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { d.name }.getOrNull()
                        // Unnamed devices are almost never adapters and can't be told apart in a list.
                        if (name.isNullOrBlank()) return
                        val cand = candidate(d, name) ?: return
                        if (synchronized(seen) { seen.add(cand.spec.address) }) trySend(cand)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started = try {
            adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            false
        }
        if (!started) {
            log?.warn("OBD: Bluetooth discovery could not start")
            runCatching { context.unregisterReceiver(receiver) }
            close()
            return@callbackFlow
        }
        log?.link("OBD: Bluetooth discovery started")
        awaitClose {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
            log?.link("OBD: Bluetooth discovery stopped (${synchronized(seen) { seen.size }} named devices)")
        }
    }

    /**
     * Scans for BLE devices that advertise a name, for about [durationMs] (low-latency mode), emitting each device
     * once (deduplicated by address), OBD-looking or not. Stops when the time is up or when the collector cancels.
     * Completes immediately (empty) without Bluetooth LE, with Bluetooth off, or without [hasScanPermission].
     * Stop collecting before connecting: scanning while connecting causes BLE errors on many phones.
     */
    fun scanBle(context: Context, durationMs: Long = 10_000, log: DiagLog? = null): Flow<ObdCandidate> = callbackFlow {
        val adapter = adapter(context)
        val scanner = if (adapter != null && hasScanPermission(context)) {
            runCatching { adapter.bluetoothLeScanner }.getOrNull()
        } else {
            null
        }
        if (scanner == null) {
            log?.warn("OBD: BLE scan not possible (Bluetooth off, no BLE, or permission missing)")
            close()
            return@callbackFlow
        }
        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handle(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                log?.warn("OBD: BLE scan failed (error $errorCode)")
                close()
            }

            private fun handle(result: ScanResult) {
                val d = result.device ?: return
                val address = d.address ?: return
                val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                    ?: runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return
                val fresh = synchronized(seen) { seen.add(address) }
                if (!fresh) return
                val record = result.scanRecord
                val services = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
                val companies = record?.manufacturerSpecificData?.let { m -> (0 until m.size()).map { m.keyAt(it) } }.orEmpty()
                trySend(ObdCandidate(ObdDeviceSpec(ObdDeviceSpec.Kind.BLE, address, name), AdapterGuess.guess(name, null, services, companies)))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = try {
            scanner.startScan(emptyList<ScanFilter>(), settings, callback)
            true
        } catch (e: Exception) {
            log?.warn("OBD: BLE scan could not start (${e.javaClass.simpleName}: ${e.message})")
            false
        }
        if (!started) {
            close()
            return@callbackFlow
        }
        log?.link("OBD: BLE scan started")
        launch {
            delay(durationMs)
            close()
        }
        awaitClose {
            runCatching { scanner.stopScan(callback) }
            log?.link("OBD: BLE scan stopped (${synchronized(seen) { seen.size }} named devices)")
        }
    }
}

/** A device in the adapter list, with a guess whether it is an OBD adapter. */
data class ObdCandidate(val spec: ObdDeviceSpec, val guess: AdapterGuess.Guess) {
    val hidden: Boolean get() = guess.rank == AdapterGuess.Rank.NOT_ADAPTER
}
