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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.anwo91.phevlink.core.diag.DiagLog
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

    /** Name fragments of common OBD adapters (compared case-insensitively). */
    private val OBD_NAME_HINTS = listOf(
        "OBD", "ELM", "VGATE", "ICAR", "V-LINK", "VLINK", "KONNWEI", "KW90", "KW9", "VEEPEAK", "VIECAR",
        "CARISTA", "LELINK", "SCAN TOOL", "SCANTOOL", "CAN OBD", "IOS-VLINK", "ANDROID-VLINK", "THINKCAR", "FIXD",
    )

    /** True when [name] looks like an OBD adapter ("OBDII", "OBDLink MX+", "Android-Vlink", "IOS-Vlink", "V-LINK"…). */
    fun looksLikeObd(name: String?): Boolean {
        val n = name?.uppercase(Locale.ROOT) ?: return false
        return OBD_NAME_HINTS.any { n.contains(it) }
    }

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
    fun bondedDevices(context: Context): List<ObdDeviceSpec> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        val bonded: Set<BluetoothDevice> = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            emptySet()
        } catch (e: RuntimeException) {
            emptySet()
        }
        return bonded.mapNotNull { d ->
            try {
                val address = d.address ?: return@mapNotNull null
                val name = d.name?.takeIf { it.isNotBlank() } ?: address
                val kind = if (d.type == BluetoothDevice.DEVICE_TYPE_LE) ObdDeviceSpec.Kind.BLE else ObdDeviceSpec.Kind.CLASSIC
                ObdDeviceSpec(kind, address, name)
            } catch (e: SecurityException) {
                null
            }
        }.sortedWith(compareBy<ObdDeviceSpec>({ !it.looksLikeObd }, { it.name.lowercase(Locale.ROOT) }))
    }

    /**
     * Scans for BLE devices that advertise a name, for about [durationMs] (low-latency mode), emitting each device
     * once (deduplicated by address), OBD-looking or not. Stops when the time is up or when the collector cancels.
     * Completes immediately (empty) without Bluetooth LE, with Bluetooth off, or without [hasScanPermission].
     * Stop collecting before connecting: scanning while connecting causes BLE errors on many phones.
     */
    fun scanBle(context: Context, durationMs: Long = 10_000, log: DiagLog? = null): Flow<ObdDeviceSpec> = callbackFlow {
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
                if (fresh) trySend(ObdDeviceSpec(ObdDeviceSpec.Kind.BLE, address, name))
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
