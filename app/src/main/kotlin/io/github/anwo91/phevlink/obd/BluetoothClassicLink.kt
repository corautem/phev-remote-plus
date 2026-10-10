package io.github.anwo91.phevlink.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ObdLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.UUID

/**
 * ELM327 over Bluetooth Classic SPP (RFCOMM) — the most common kind of OBD dongle.
 *
 * Open with [open]; it tries, in this order, a secure SPP socket, an insecure SPP socket, and the hidden
 * `createRfcommSocket(1)` (RFCOMM channel 1 without SDP lookup — the classic fix for cheap clones whose SDP
 * record is broken, which fail with "read failed, socket might closed or timeout"). Each try has a hard timeout
 * (the socket is closed from a watchdog to abort the blocking connect).
 *
 * Reading runs on a dedicated thread (see [StreamPipe]); when the adapter disappears [receive] throws
 * [IOException]. [close] is idempotent.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked in open() before any call that needs it.
class BluetoothClassicLink private constructor(
    private val socket: BluetoothSocket,
    override val description: String,
    log: DiagLog,
) : ObdLink {

    private val pipe = StreamPipe("Bluetooth", socket.inputStream, socket.outputStream, { socket.close() }, log)

    init {
        pipe.start()
    }

    override suspend fun send(bytes: ByteArray) {
        pipe.send(bytes)
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? = pipe.receive(timeoutMs)

    override fun close() {
        pipe.close()
    }

    companion object {
        private const val BOND_TIMEOUT_MS = 45_000L

        /**
         * An adapter found by a scan isn't paired yet. Pairing first (Android shows its PIN dialog; old ELM327s
         * use 1234 or 0000) instead of letting the RFCOMM connect trigger it: the connect attempt would time out
         * while the user is still typing the PIN.
         */
        private suspend fun ensureBonded(device: BluetoothDevice, name: String, log: DiagLog) {
            val state = try { device.bondState } catch (e: SecurityException) { return }
            if (state == BluetoothDevice.BOND_BONDED) return
            log.link("OBD: $name is not paired — asking Android to pair (old adapters use PIN 1234 or 0000)")
            val started = try {
                state == BluetoothDevice.BOND_BONDING || device.createBond()
            } catch (e: SecurityException) {
                throw ObdConnectException(ObdConnectException.Reason.PERMISSION, "Bluetooth permission refused: ${e.message}", e)
            }
            if (!started) {
                log.warn("OBD: Android refused to start pairing with $name — trying to connect anyway")
                return
            }
            val t0 = SystemClock.elapsedRealtime()
            var sawBonding = false
            while (SystemClock.elapsedRealtime() - t0 < BOND_TIMEOUT_MS) {
                delay(300)
                when (try { device.bondState } catch (e: SecurityException) { BluetoothDevice.BOND_NONE }) {
                    BluetoothDevice.BOND_BONDED -> {
                        log.link("OBD: paired with $name after ${(SystemClock.elapsedRealtime() - t0) / 1000} s")
                        return
                    }
                    BluetoothDevice.BOND_BONDING -> sawBonding = true
                    else -> if (sawBonding || SystemClock.elapsedRealtime() - t0 > 5_000) {
                        throw ObdConnectException(
                            ObdConnectException.Reason.PAIRING_FAILED,
                            "Pairing with $name was cancelled or refused" + if (sawBonding) " (wrong PIN?)" else "",
                        )
                    }
                }
            }
            throw ObdConnectException(ObdConnectException.Reason.PAIRING_FAILED, "Pairing with $name did not finish within ${BOND_TIMEOUT_MS / 1000} s")
        }

        /** Serial Port Profile service UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Per-try timeout. A device that is off or out of range usually fails after ~5 s on its own. */
        private const val ATTEMPT_TIMEOUT_MS = 10_000L

        private class Strategy(val label: String, val create: (BluetoothDevice) -> BluetoothSocket)

        private val STRATEGIES = listOf(
            Strategy("secure SPP") { it.createRfcommSocketToServiceRecord(SPP_UUID) },
            Strategy("insecure SPP") { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            Strategy("RFCOMM channel 1 (fallback)") { device ->
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            },
        )

        /**
         * Connects to the SPP adapter at [address]. Must be called with BLUETOOTH_CONNECT granted (Android 12+).
         *
         * @throws ObdConnectException with a [ObdConnectException.Reason] the UI can explain
         */
        suspend fun open(context: Context, address: String, name: String, log: DiagLog): BluetoothClassicLink {
            val adapter = ObdDevices.adapter(context)
                ?: throw ObdConnectException(ObdConnectException.Reason.NO_BLUETOOTH, "This phone has no Bluetooth")
            if (!ObdDevices.hasConnectPermission(context)) {
                throw ObdConnectException(ObdConnectException.Reason.PERMISSION, "BLUETOOTH_CONNECT not granted")
            }
            if (!ObdDevices.isBluetoothOn(context)) {
                throw ObdConnectException(ObdConnectException.Reason.BLUETOOTH_OFF, "Bluetooth is off")
            }
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                throw ObdConnectException(ObdConnectException.Reason.BAD_ADDRESS, "Invalid Bluetooth address '$address'")
            }
            val device: BluetoothDevice = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                throw ObdConnectException(ObdConnectException.Reason.BAD_ADDRESS, "Invalid Bluetooth address '$address'", e)
            }
            // An ongoing discovery slows RFCOMM connects down dramatically. Needs BLUETOOTH_SCAN on 12+; harmless if missing.
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                    log.link("OBD: cancelled a running Bluetooth discovery before connecting")
                }
            } catch (e: SecurityException) {
                log.warn("OBD: could not cancel Bluetooth discovery (${e.message})")
            }

            ensureBonded(device, name, log)

            val description = "Bluetooth Classic $name ($address)"
            var last: Exception? = null
            for ((i, strategy) in STRATEGIES.withIndex()) {
                if (i > 0) delay(400) // let the Bluetooth stack settle between tries
                if (!ObdDevices.isBluetoothOn(context)) {
                    throw ObdConnectException(ObdConnectException.Reason.BLUETOOTH_OFF, "Bluetooth was turned off while connecting", last)
                }
                val socket = try {
                    strategy.create(device)
                } catch (e: SecurityException) {
                    throw ObdConnectException(ObdConnectException.Reason.PERMISSION, "Bluetooth permission refused: ${e.message}", e)
                } catch (e: Exception) {
                    log.warn("OBD: ${strategy.label}: could not create socket (${e.javaClass.simpleName}: ${e.message})")
                    last = e
                    continue
                }
                val t0 = SystemClock.elapsedRealtime()
                log.link("OBD: Bluetooth Classic: trying ${strategy.label} to $address")
                try {
                    connectBlocking(ATTEMPT_TIMEOUT_MS, abort = { socket.close() }) { socket.connect() }
                    val link = try {
                        BluetoothClassicLink(socket, description, log)
                    } catch (e: IOException) {
                        runCatching { socket.close() }
                        throw e
                    }
                    log.link("OBD: socket connected (${strategy.label}) in ${SystemClock.elapsedRealtime() - t0} ms")
                    return link
                } catch (e: CancellationException) {
                    runCatching { socket.close() }
                    throw e
                } catch (e: SecurityException) {
                    runCatching { socket.close() }
                    throw ObdConnectException(ObdConnectException.Reason.PERMISSION, "Bluetooth permission refused: ${e.message}", e)
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    last = e
                    log.warn(
                        "OBD: ${strategy.label} failed after ${SystemClock.elapsedRealtime() - t0} ms: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    )
                }
                currentCoroutineContext().ensureActive()
            }
            throw ObdConnectException(
                ObdConnectException.Reason.UNREACHABLE,
                "No Bluetooth connection to $address (${last?.message ?: "unknown error"})",
                last,
            )
        }
    }
}
