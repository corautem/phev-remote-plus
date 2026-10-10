package io.github.anwo91.phevlink.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ObdLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ELM327 over Bluetooth Low Energy (a UART-like GATT service: one characteristic notifies the adapter's output,
 * one accepts our commands).
 *
 * Connection sequence ([open]): `connectGatt(autoConnect = false, TRANSPORT_LE)` → MTU request ([REQUESTED_MTU])
 * → service discovery → pick the characteristics ([KNOWN_SERVICES] first, then the first service with a
 * notify/indicate and a write characteristic) → enable notifications (CCCD write). Every step has a timeout; on
 * any failure the GATT client is closed (no leaked connections).
 *
 * GATT operations are serialised (Android allows one outstanding operation): each write waits for
 * `onCharacteristicWrite`/`onDescriptorWrite`. Writes are split into `MTU - 3` byte chunks. Notifications go into
 * a [ByteInbox]; a disconnect fails it so [receive] throws [IOException].
 *
 * Handles the Android 13 (API 33) API change: new `writeCharacteristic`/`writeDescriptor`/`onCharacteristicChanged`
 * signatures on 33+, the deprecated value-on-the-object variants below.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked in open() before any GATT call.
class BluetoothLeLink private constructor(
    private val device: BluetoothDevice,
    private val address: String,
    override val description: String,
    private val log: DiagLog,
) : ObdLink {

    private class Uart(val service: UUID, val notify: UUID, val write: UUID, val label: String)

    private class Pick(val service: BluetoothGattService, val notify: BluetoothGattCharacteristic, val write: BluetoothGattCharacteristic)

    /** One GATT write in flight; completed from the binder thread with the GATT status. */
    private class PendingOp(val uuid: UUID) {
        val result = CompletableDeferred<Int>()
    }

    private val inbox = ByteInbox()
    private val closed = AtomicBoolean(false)
    private val opMutex = Mutex()
    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Int>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var mtuResult: CompletableDeferred<Int>? = null
    @Volatile private var pending: PendingOp? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    @Volatile private var notifyUuid: UUID? = null
    @Volatile private var ready = false
    @Volatile private var unconfirmedWrites = 0

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val what = if (ready) "lost" else "failed"
                linkDown(IOException("Bluetooth LE connection $what (${statusText(status)})"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BluetoothLeLink.mtu = mtu
            mtuResult?.complete(status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesDiscovered.complete(status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val op = pending
            if (op != null && op.uuid == characteristic.uuid) op.result.complete(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val op = pending
            if (op != null && op.uuid == descriptor.uuid) op.result.complete(status)
        }

        // API 33+: the value comes as a parameter (the deprecated overload below is not called any more).
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == notifyUuid) inbox.offer(value.copyOf())
        }

        // Below API 33.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value: ByteArray = characteristic.value ?: return
            if (characteristic.uuid == notifyUuid) inbox.offer(value.copyOf())
        }
    }

    private fun linkDown(e: IOException) {
        connected.completeExceptionally(e)
        servicesDiscovered.completeExceptionally(e)
        mtuResult?.completeExceptionally(e)
        pending?.result?.completeExceptionally(e)
        if (ready && !closed.get()) log.warn("OBD: ${e.message}")
        ready = false
        inbox.fail(e)
    }

    // ------------------------------------------------------------------ ObdLink

    override suspend fun send(bytes: ByteArray) {
        inbox.failure?.let { throw it }
        val g = gatt
        val c = writeChar
        if (closed.get() || !ready || g == null || c == null) throw IOException("Bluetooth LE link is closed")
        opMutex.withLock {
            val size = (mtu - 3).coerceIn(20, 512)
            var off = 0
            while (off < bytes.size) {
                val end = minOf(bytes.size, off + size)
                writeChunk(g, c, bytes.copyOfRange(off, end))
                off = end
            }
        }
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? = inbox.receive(timeoutMs)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        linkDown(IOException("Bluetooth LE link closed"))
        val g = gatt
        gatt = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    // ------------------------------------------------------------------ setup

    private suspend fun start(context: Context) {
        val t0 = SystemClock.elapsedRealtime()
        log.link("OBD: BLE: connecting to $address")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw ObdConnectException(ObdConnectException.Reason.UNREACHABLE, "connectGatt refused")
        gatt = g
        if (closed.get()) {
            // close() ran while connectGatt was in progress and could not see the handle.
            runCatching { g.close() }
            throw IOException("Bluetooth LE link closed")
        }
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                ?: throw ObdConnectException(
                    ObdConnectException.Reason.UNREACHABLE,
                    "No Bluetooth LE connection within ${CONNECT_TIMEOUT_MS / 1000} s",
                )
        } catch (e: ObdConnectException) {
            throw e
        } catch (e: IOException) {
            throw ObdConnectException(ObdConnectException.Reason.UNREACHABLE, e.message ?: "Bluetooth LE connection failed", e)
        }
        log.link("OBD: BLE: connected in ${SystemClock.elapsedRealtime() - t0} ms")

        // Bigger MTU: fewer chunks per command. Optional — many adapters stay at 23.
        val m = CompletableDeferred<Int>()
        mtuResult = m
        val asked = try {
            g.requestMtu(REQUESTED_MTU)
        } catch (e: Exception) {
            false
        }
        if (asked) {
            val st = withTimeoutOrNull(MTU_TIMEOUT_MS) { m.await() }
            log.link("OBD: BLE: MTU " + if (st == null) "request not answered, using $mtu" else "$mtu (status $st)")
        } else {
            log.link("OBD: BLE: MTU request refused, using $mtu")
        }
        mtuResult = null

        if (!g.discoverServices()) throw IOException("Bluetooth LE service discovery refused")
        val st = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { servicesDiscovered.await() }
            ?: throw IOException("Bluetooth LE service discovery timed out")
        if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth LE service discovery failed (${statusText(st)})")
        val services: List<BluetoothGattService> = g.services ?: emptyList()
        val chosen = pickCharacteristics(services)
        if (chosen == null) {
            val summary = services.joinToString { s ->
                s.uuid.toString() + "[" + (s.characteristics ?: emptyList()).joinToString(" ") { c ->
                    c.uuid.toString().take(8) + ":" + propsText(c.properties)
                } + "]"
            }
            log.warn("OBD: BLE: no usable UART service. Services: $summary")
            throw ObdConnectException(ObdConnectException.Reason.NO_UART_SERVICE, "No OBD data service on this Bluetooth LE device")
        }
        val n = chosen.notify
        val w = chosen.write
        writeType = if ((w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        log.link(
            "OBD: BLE: service ${chosen.service.uuid}, notify ${n.uuid} (${propsText(n.properties)}), " +
                "write ${w.uuid} (${propsText(w.properties)}), " +
                (if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) "write without response" else "write with response") +
                ", chunk ${(mtu - 3).coerceIn(20, 512)} bytes",
        )

        notifyUuid = n.uuid
        if (!g.setCharacteristicNotification(n, true)) throw IOException("Bluetooth LE: could not enable notifications")
        val cccd = n.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val value = if ((n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            opMutex.withLock { writeDescriptor(g, cccd, value) }
        } else {
            log.warn("OBD: BLE: notify characteristic has no CCCD — relying on setCharacteristicNotification only")
        }
        writeChar = w
        ready = true
        log.link("OBD: socket connected (Bluetooth LE) in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private fun pickCharacteristics(services: List<BluetoothGattService>): Pick? {
        for (k in KNOWN_SERVICES) {
            val s = services.firstOrNull { it.uuid == k.service } ?: continue
            val n = s.getCharacteristic(k.notify) ?: continue
            val w = s.getCharacteristic(k.write) ?: continue
            if (canNotify(n) && canWrite(w)) {
                log.link("OBD: BLE: found ${k.label} service")
                return Pick(s, n, w)
            }
        }
        for (s in services) {
            if (s.uuid in STANDARD_SERVICES) continue
            val chars: List<BluetoothGattCharacteristic> = s.characteristics ?: continue
            // Prefer one characteristic that does both (FFE1-style), else separate ones.
            val both = chars.firstOrNull { canNotify(it) && canWrite(it) }
            val n = chars.firstOrNull { canNotify(it) } ?: continue
            val w = chars.firstOrNull { canWrite(it) && it !== n } ?: both ?: continue
            log.link("OBD: BLE: no known OBD service — using ${s.uuid}")
            return Pick(s, n, w)
        }
        return null
    }

    // ------------------------------------------------------------------ GATT operations (call with opMutex held)

    private suspend fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        val op = PendingOp(d.uuid)
        pending = op
        try {
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                legacyWriteDescriptor(g, d, value)
            }
            if (!started) throw IOException("Bluetooth LE: descriptor write refused")
            val st = withTimeoutOrNull(OP_TIMEOUT_MS) { op.result.await() }
                ?: throw IOException("Bluetooth LE: descriptor write not confirmed within ${OP_TIMEOUT_MS / 1000} s")
            if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth LE: descriptor write failed (${statusText(st)})")
        } finally {
            if (pending === op) pending = null
        }
    }

    private suspend fun writeChunk(g: BluetoothGatt, c: BluetoothGattCharacteristic, chunk: ByteArray) {
        val type = writeType
        var busyRetries = 0
        while (true) {
            if (closed.get()) throw inbox.failure ?: IOException("Bluetooth LE link closed")
            val op = PendingOp(c.uuid)
            pending = op
            try {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    legacyWriteCharacteristic(g, c, chunk, type)
                }
                if (!started) {
                    // Usually "busy": the stack hasn't finished the previous operation yet.
                    if (busyRetries++ < MAX_BUSY_RETRIES) {
                        delay(BUSY_RETRY_DELAY_MS)
                        continue
                    }
                    throw IOException("Bluetooth LE: write refused by the phone's Bluetooth stack")
                }
                val noResponse = type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                val st = withTimeoutOrNull(if (noResponse) NO_RESPONSE_CONFIRM_MS else OP_TIMEOUT_MS) { op.result.await() }
                if (st == null) {
                    if (noResponse) {
                        // Some stacks don't confirm writes without response. Carry on, but note it once.
                        if (unconfirmedWrites++ == 0) log.warn("OBD: BLE: write without response not confirmed — continuing")
                        return
                    }
                    throw IOException("Bluetooth LE: write not confirmed within ${OP_TIMEOUT_MS / 1000} s")
                }
                if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth LE: write failed (${statusText(st)})")
                return
            } finally {
                if (pending === op) pending = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        d.setValue(value)
        return g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    private fun legacyWriteCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean {
        c.setWriteType(type)
        c.setValue(value)
        return g.writeCharacteristic(c)
    }

    companion object {
        /** Client Characteristic Configuration Descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val REQUESTED_MTU = 185
        private const val DEFAULT_MTU = 23
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MTU_TIMEOUT_MS = 3_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val OP_TIMEOUT_MS = 3_000L
        private const val NO_RESPONSE_CONFIRM_MS = 1_000L
        private const val MAX_BUSY_RETRIES = 10
        private const val BUSY_RETRY_DELAY_MS = 20L

        private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")

        /** Known OBD UART services, most specific first. */
        private val KNOWN_SERVICES = listOf(
            Uart(
                UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
                UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
                UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
                "Vgate iCar Pro",
            ),
            Uart(
                UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                "Nordic UART",
            ),
            Uart(uuid16("FFF0"), uuid16("FFF1"), uuid16("FFF2"), "FFF0"),
            Uart(uuid16("FFE0"), uuid16("FFE1"), uuid16("FFE1"), "FFE0"),
        )

        /** Generic Access, Generic Attribute, Device Information, Battery: never the data channel. */
        private val STANDARD_SERVICES = setOf(uuid16("1800"), uuid16("1801"), uuid16("180A"), uuid16("180F"))

        private fun canNotify(c: BluetoothGattCharacteristic): Boolean =
            (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0

        private fun canWrite(c: BluetoothGattCharacteristic): Boolean =
            (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

        private fun propsText(p: Int): String = buildList {
            if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) add("read")
            if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) add("write")
            if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("write-nr")
            if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) add("notify")
            if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) add("indicate")
        }.joinToString("/").ifEmpty { "none" }

        /** GATT status with a hint (for the diagnostic log). */
        fun statusText(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "status 0"
            8 -> "status 8: connection timeout — adapter out of range or switched off"
            19 -> "status 19: closed by the adapter"
            22 -> "status 22: closed by the phone"
            62 -> "status 62: connection could not be established"
            133 -> "status 133: GATT_ERROR — common Android Bluetooth hiccup, a retry usually helps"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "status $status: insufficient authentication (pairing needed)"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "status $status: insufficient encryption (pairing needed)"
            else -> "status $status"
        }

        /**
         * Connects to the BLE adapter at [address]. Must be called with BLUETOOTH_CONNECT granted (Android 12+).
         * Stop any BLE scan first: scanning while connecting is a classic cause of status 133.
         *
         * @throws ObdConnectException with a [ObdConnectException.Reason] the UI can explain, or [IOException]
         */
        suspend fun open(context: Context, address: String, name: String, log: DiagLog): BluetoothLeLink {
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
            val link = BluetoothLeLink(device, address, "Bluetooth LE $name ($address)", log)
            try {
                link.start(context.applicationContext)
                return link
            } catch (e: SecurityException) {
                link.close()
                throw ObdConnectException(ObdConnectException.Reason.PERMISSION, "Bluetooth permission refused: ${e.message}", e)
            } catch (t: Throwable) {
                link.close()
                throw t
            }
        }
    }
}
