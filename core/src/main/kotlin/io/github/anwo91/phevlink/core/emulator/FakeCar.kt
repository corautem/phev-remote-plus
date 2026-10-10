package io.github.anwo91.phevlink.core.emulator

import io.github.anwo91.phevlink.core.model.CarSetting
import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.Packet
import io.github.anwo91.phevlink.core.protocol.PacketType
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.SecurityKey
import io.github.anwo91.phevlink.core.protocol.StreamDecoder
import io.github.anwo91.phevlink.core.protocol.toHex
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A software Outlander PHEV for tests, demos and developing without the car.
 *
 * Speaks the car side of the protocol: answers pings, starts a session after a
 * few pings, streams its registers, validates the client's XOR keys (answering
 * 0xbb with the expected key, like the real car), applies register writes and
 * pushes the resulting changes. Fault injection hooks simulate the flaky
 * behaviour the app has to survive.
 */
class FakeCar(
    val modelYear: ModelYear = ModelYear.MY14,
    private val log: (String) -> Unit = {},
) {
    // ---------------------------------------------------------------- faults
    /** When false the car never starts a session (an unregistered phone). */
    @Volatile var acceptsClient = true
    /** Pings needed before the car sends its start packet. */
    @Volatile var pingsBeforeStart = 3
    /** Deliver bytes in random small chunks to exercise stream reassembly. */
    @Volatile var fragmentWrites = false
    /** After this many accepted writes, the car's expected key index jumps (forces a 0xbb resync). */
    @Volatile var desyncAfterWrites = -1
    /** Silently ignore the next n register writes (no ack) to exercise retries. */
    @Volatile var dropNextWrites = 0
    /** Stop answering anything (simulates walking out of range). */
    @Volatile var silent = false
    /** In registration mode, 0x10 writes register the client. */
    @Volatile var registrationMode = false
    /** Simulated minutes per real second for charging and climate countdowns. */
    @Volatile var minutesPerSecond = 1.0
    /** The car acks vehicle-setting writes (0x0f) but never applies or reports them. */
    @Volatile var ignoreSettingChanges = false
    /** The car acks climate writes but never starts (low battery, quick charging…). */
    @Volatile var ignoreClimate = false
    /** Status byte sent in 0x16 notifications (2 = normal; the official app refuses changes on 1 or 3). */
    @Volatile var settingsStatus = VehicleSettingsCatalog.STATUS_NORMAL

    // ----------------------------------------------------------------- model
    private val lock = Any()
    private val regs = LinkedHashMap<Int, ByteArray>()
    private var pendingMy14Mode = 0x02
    private var climateEndsAtSimMin = 0.0
    private var simMinutes = 0.0
    val acceptedWrites = CopyOnWriteArrayList<Pair<Int, String>>()
    /** Vehicle settings (register 0x16), by id. */
    private val settings = LinkedHashMap<Int, CarSetting>(DEFAULT_SETTINGS)
    /** Number of "save settings" (0x0e) writes received. */
    @Volatile var savedSettingsCount = 0
        private set
    /** MY14 climate writes whose echoed timer bytes did not match the car's registers. */
    @Volatile var scheduleEchoMismatches = 0

    init {
        synchronized(lock) {
            val my14 = modelYear == ModelYear.MY14
            regs[0x01] = bytes(0x01, 0x00)
            regs[Reg.READ_CHARGE_GUN] = bytes(0x01, 0x00)
            regs[Reg.READ_PRE_AC_STATE] = if (my14) bytes(0) else bytes(0, 0, 0)
            regs[Reg.READ_CAR_CLOCK] = clockBytes(LocalDateTime.now())
            regs[Reg.READ_VIN] = byteArrayOf(0x03) + "JMAXDGG2WFZ012345".toByteArray() + bytes(0x01, 0x01)
            regs[Reg.READ_IGNITION_AC] = if (my14) bytes(0, 0) else bytes(0, 0, 0, 0, 0)
            regs[Reg.READ_AC_MODE] = bytes(0x02)
            regs[Reg.READ_BATTERY] = bytes(64, 0, 0, 0)
            regs[Reg.READ_CHARGE_PLUG] = bytes(0, 0)
            regs[Reg.READ_CHARGE_STATUS] = bytes(0, 0xff, 0xff)
            regs[Reg.READ_LIGHTS] = bytes(0, 0, 0, 2, 2)
            regs[Reg.READ_DOORS] = bytes(1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            regs[Reg.READ_SSID] = "REMOTEf00d42".toByteArray().copyOf(32).also { b ->
                for (i in 12 until 32) b[i] = 0xff.toByte()
            }
            regs[Reg.READ_ECU_VERSION] = "0052022000".toByteArray() + bytes(0x11, 0, 0)
            // Climate timer registers the official app echoes back in the MY14 climate write.
            regs[0x19] = bytes(0x01, 0x7d, 0x38, 0xb0, 0x01, 0x83, 0xbd, 0x00, 0x01)
            regs[0x22] = bytes(0x00, 0x01, 0x00, 0x02, 0x03, 0x04)
            if (!my14) regs[Reg.READ_WIFI_ALWAYS_ON_MY18] = bytes(0)
        }
    }

    fun register(reg: Int): ByteArray? = synchronized(lock) { regs[reg]?.copyOf() }
    /** Current (mask, value) of vehicle setting [id], or null if this car does not report it. */
    fun setting(id: Int): Pair<Int, Int>? = synchronized(lock) { settings[id]?.let { it.mask to it.value } }
    val batteryPercent: Int get() = synchronized(lock) { regs[Reg.READ_BATTERY]!![0].toInt() and 0xff }
    val registrations: Int get() = synchronized(lock) { regs[Reg.READ_VIN]!![19].toInt() and 0xff }

    // --------------------------------------------------------------- network
    private var server: ServerSocket? = null
    private val sessions = CopyOnWriteArrayList<Conn>()
    private var sim: ScheduledExecutorService? = null
    val port: Int get() = server?.localPort ?: -1

    fun start(port: Int = 0, host: String = "127.0.0.1"): FakeCar {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(host, port))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                val c = Conn(s)
                sessions += c
                Thread(c::run, "fakecar-conn").apply { isDaemon = true }.start()
            }
        }, "fakecar-accept").apply { isDaemon = true }.start()
        sim = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fakecar-sim").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate(::simulate, 1, 1, TimeUnit.SECONDS) }
        log("FakeCar ($modelYear) listening on $host:${ss.localPort}")
        return this
    }

    fun stop() {
        runCatching { server?.close() }
        sessions.forEach { it.close() }
        sessions.clear()
        sim?.shutdownNow()
    }

    /** Drop every client connection (simulates the Wi-Fi blinking). */
    fun kickClients() = sessions.forEach { it.close() }

    /** Force a mid-session re-key on every client. */
    fun rekey() = sessions.forEach { it.sendStart() }

    // ------------------------------------------------------------ simulation
    fun plugIn(charge: Boolean = true) = update {
        regs[Reg.READ_CHARGE_PLUG] = bytes(0, 1)
        if (charge) startChargingLocked()
    }

    fun unplug() = update {
        regs[Reg.READ_CHARGE_PLUG] = bytes(0, 0)
        regs[Reg.READ_CHARGE_STATUS] = bytes(0, 0xff, 0xff)
    }

    fun setBattery(percent: Int) = update { regs[Reg.READ_BATTERY]!![0] = percent.toByte() }

    fun setDoor(index: Int, open: Boolean) = update { regs[Reg.READ_DOORS]!![index] = if (open) 1 else 0 }

    fun setLocked(locked: Boolean) = update { regs[Reg.READ_DOORS]!![0] = if (locked) 1 else 2 }

    fun setIgnition(code: Int) = update { regs[Reg.READ_IGNITION_AC]!![0] = code.toByte() }

    private fun startChargingLocked() {
        val level = regs[Reg.READ_BATTERY]!![0].toInt() and 0xff
        val minutes = ((100 - level) * 3.2).toInt().coerceAtLeast(1)
        regs[Reg.READ_CHARGE_STATUS] = bytes(1, minutes and 0xff, minutes shr 8)
        regs[Reg.READ_CHARGE_PLUG] = bytes(2, 2)
    }

    private fun simulate() {
        sessions.forEach { it.resendStaleDump() }
        simulateModel()
    }

    private fun simulateModel() = update {
        simMinutes += minutesPerSecond
        val cs = regs[Reg.READ_CHARGE_STATUS]!!
        if (cs[0].toInt() == 1) {
            val battery = regs[Reg.READ_BATTERY]!!
            var remaining = ((cs[2].toInt() and 0xff) shl 8) or (cs[1].toInt() and 0xff)
            remaining = (remaining - minutesPerSecond.toInt().coerceAtLeast(1)).coerceAtLeast(0)
            val level = (100 - remaining / 3.2).toInt().coerceIn(0, 100)
            battery[0] = level.toByte()
            if (remaining <= 0 || level >= 100) {
                battery[0] = 100
                regs[Reg.READ_CHARGE_STATUS] = bytes(0, 0xff, 0xff)
                regs[Reg.READ_CHARGE_PLUG] = bytes(0, 1)
            } else {
                regs[Reg.READ_CHARGE_STATUS] = bytes(1, remaining and 0xff, remaining shr 8)
            }
        }
        val pre = regs[Reg.READ_PRE_AC_STATE]!!
        if (pre[0].toInt() == 2 && simMinutes >= climateEndsAtSimMin) {
            pre[0] = 0
            regs[Reg.READ_IGNITION_AC]!![1] = 0
        }
    }

    /** Mutate registers, then notify every client of what changed. */
    private fun update(block: () -> Unit) {
        val changed: List<Pair<Int, ByteArray>>
        synchronized(lock) {
            val before = regs.mapValues { it.value.copyOf() }
            block()
            changed = regs.filter { (k, v) -> !(before[k]?.contentEquals(v) ?: false) }.map { it.key to it.value.copyOf() }
        }
        for ((r, v) in changed) sessions.forEach { it.notify(r, v) }
    }

    private fun applyWrite(reg: Int, data: ByteArray): Boolean {
        acceptedWrites += reg to data.toHex()
        if (reg == Reg.WRITE_SETTING || reg == Reg.WRITE_SAVE_SETTINGS) {
            // Notify outside the model lock (Conn.notify takes the connection's monitor).
            val payloads = synchronized(lock) { applySettingWriteLocked(reg, data) }
            for (p in payloads) sessions.forEach { it.notify(Reg.READ_SETTINGS, p, explicit = true) }
            return false
        }
        var resend = false
        update {
            fun d(i: Int) = data.getOrNull(i)?.toInt()?.and(0xff) ?: 0
            when (reg) {
                Reg.WRITE_HEADLIGHTS -> regs[Reg.READ_DOORS]!![9] = if (d(0) == 1) 1 else 0
                Reg.WRITE_PARKING_LIGHTS -> regs[Reg.READ_BATTERY]!![2] = if (d(0) == 1) 1 else 0
                Reg.WRITE_AC_SCHEDULE_MY14 -> if (modelYear == ModelYear.MY14 && data.size == 15) {
                    pendingMy14Mode = d(6)
                    val r22 = regs[0x22]!!
                    val r19 = regs[0x19]!!
                    val echoOk = (0 until 6).all { data[it] == r22[it] } && (0 until 8).all { data[7 + it] == r19[1 + it] }
                    if (!echoOk) {
                        scheduleEchoMismatches++
                        log("car: climate write did not echo timer registers: ${data.toHex()}")
                    }
                }
                Reg.WRITE_AC_SWITCH_MY14 -> if (modelYear == ModelYear.MY14) {
                    if (d(0) == 2) climateOnLocked(pendingMy14Mode) else climateOffLocked()
                }
                Reg.WRITE_AC_MY18 -> if (modelYear != ModelYear.MY14) {
                    if (d(0) == 2) climateOnLocked(d(1) or (d(2) shl 4)) else climateOffLocked()
                }
                Reg.WRITE_WIFI_ALWAYS_ON -> if (modelYear != ModelYear.MY14) regs[Reg.READ_WIFI_ALWAYS_ON_MY18] = bytes(if (d(0) == 1) 1 else 0)
                Reg.WRITE_REQUEST_UPDATE -> resend = true
                Reg.WRITE_REGISTER_CLIENT -> if (registrationMode) {
                    regs[Reg.READ_VIN]!![19] = (registrations + 1).coerceAtMost(4).toByte()
                    registrationMode = false
                }
                Reg.WRITE_UNREGISTER_CLIENT -> regs[Reg.READ_VIN]!![19] = (registrations - 1).coerceAtLeast(0).toByte()
                Reg.WRITE_ACK_AC_TERMINATION -> regs[Reg.READ_PRE_AC_STATE]!![0] = 0
                Reg.WRITE_CLOCK_SYNC -> if (data.size >= 7) regs[Reg.READ_CAR_CLOCK] = data.copyOf(7)
                Reg.WRITE_CHARGE_TIMER_CANCEL -> if ((regs[Reg.READ_CHARGE_PLUG]!![1].toInt()) != 0) startChargingLocked()
            }
        }
        return resend
    }

    /** Vehicle settings: 0x0f [id, value] / [0xfe, 0] and 0x0e [0]. Returns the 0x16 payloads to send. */
    private fun applySettingWriteLocked(reg: Int, data: ByteArray): List<ByteArray> {
        fun d(i: Int) = data.getOrNull(i)?.toInt()?.and(0xff) ?: 0
        if (reg == Reg.WRITE_SAVE_SETTINGS) {
            savedSettingsCount++
            return emptyList()
        }
        if (ignoreSettingChanges) {
            log("car: ignoring setting write ${data.toHex()}")
            return emptyList()
        }
        if (d(0) == VehicleSettingsCatalog.RESET_ID) {
            settings.clear()
            settings.putAll(DEFAULT_SETTINGS)
            return settingsPayloadsLocked()
        }
        val id = d(0)
        val value = d(1)
        val cur = settings[id]
        if (cur == null || !cur.hasOption(value)) {
            // Like an invalid value on the real car: acked, but nothing changes or gets reported.
            log("car: rejected setting $id = $value")
            return emptyList()
        }
        val updated = cur.copy(value = value)
        settings[id] = updated
        return VehicleSettingsCatalog.encodeNotifications(listOf(id to updated), settingsStatus)
    }

    /** All settings as the car streams them: several 0x16 notifications, 3 settings each. */
    private fun settingsPayloadsLocked(): List<ByteArray> =
        VehicleSettingsCatalog.encodeNotifications(settings.map { it.key to it.value }, settingsStatus)

    private fun climateOnLocked(modeByte: Int) {
        val mode = modeByte and 0x0f
        if (mode == 0) {
            climateOffLocked(); return
        }
        // Like the real car: acked, but remote climate only runs with the power switch OFF.
        if (regs[Reg.READ_IGNITION_AC]!![0].toInt() != 0 || ignoreClimate) {
            log("car: climate not started (power switch not OFF, or ignoreClimate)")
            return
        }
        val minutes = when ((modeByte and 0xf0) shr 4) { 1 -> 20; 2 -> 30; else -> 10 }
        regs[Reg.READ_PRE_AC_STATE]!![0] = 2
        regs[Reg.READ_IGNITION_AC]!![1] = 1
        regs[Reg.READ_AC_MODE] = bytes(modeByte)
        climateEndsAtSimMin = simMinutes + minutes
    }

    private fun climateOffLocked() {
        regs[Reg.READ_PRE_AC_STATE]!![0] = 0
        regs[Reg.READ_IGNITION_AC]!![1] = 0
    }

    /** Car opens a door while climate runs -> the car cancels it. */
    fun openDoorDuringClimate() = update {
        regs[Reg.READ_DOORS]!![3] = 1
        if (regs[Reg.READ_PRE_AC_STATE]!![0].toInt() == 2) {
            regs[Reg.READ_PRE_AC_STATE]!![0] = 3
            regs[Reg.READ_IGNITION_AC]!![1] = 0
        }
    }

    // ------------------------------------------------------------ connection
    private inner class Conn(private val socket: Socket) {
        private val key = SecurityKey()
        private val decoder = StreamDecoder()
        private val out = socket.getOutputStream()
        private var pings = 0
        private var started = false
        private var writesAccepted = 0
        /** A queued notification: [payload] null = the register's value at send time. */
        private inner class DumpItem(val reg: Int, val payload: ByteArray?) {
            fun bytes(): ByteArray = payload?.copyOf() ?: synchronized(lock) { regs[reg]!!.copyOf() }
        }
        private var dumpQueue = ArrayDeque<DumpItem>()
        private var dumping = false
        private var lastDumpSent: Pair<DumpItem, Long>? = null
        @Volatile private var closed = false

        fun close() {
            closed = true
            runCatching { socket.close() }
        }

        fun run() {
            val buf = ByteArray(2048)
            try {
                val input = socket.getInputStream()
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (silent) continue
                    for (item in decoder.feed(buf.copyOf(n))) {
                        if (item is StreamDecoder.Item.Decoded) handle(item.packet, item.xor)
                    }
                }
            } catch (_: IOException) {
            } finally {
                close()
                sessions -= this
            }
        }

        private fun handle(p: Packet, xor: Int) {
            when (p.type) {
                PacketType.PING_REQ -> {
                    send(Packet(PacketType.PING_RESP, 1, p.register, bytes(0)))
                    pings++
                    if (!started && acceptsClient && pings >= pingsBeforeStart) sendStart()
                }
                PacketType.START_RESP_MY14, PacketType.START_RESP_MY18, PacketType.START_RESP_MY24 -> {
                    if (!started) {
                        started = true
                        synchronized(lock) {
                            dumpQueue = ArrayDeque(
                                regs.keys.map { DumpItem(it, null) } +
                                    settingsPayloadsLocked().map { DumpItem(Reg.READ_SETTINGS, it) }
                            )
                        }
                        dumping = true
                        sendNextDump()
                    }
                }
                PacketType.SEND -> {
                    val expected = key.sendKey(advance = false)
                    if (key.isEstablished && xor != expected) {
                        log("car: bad xor 0x%02x, expected 0x%02x".format(xor, expected))
                        send(Packet(PacketType.BAD_ENCODING, 1, 0x00, bytes(expected, 0, 0)))
                        return
                    }
                    key.sendKey(advance = true)
                    if (p.ack == 1) {
                        if (dumping) sendNextDump()
                        return
                    }
                    if (dropNextWrites > 0) {
                        dropNextWrites--
                        log("car: dropping write 0x%02x".format(p.register))
                        return
                    }
                    writesAccepted++
                    if (desyncAfterWrites in 1..writesAccepted) {
                        desyncAfterWrites = -1
                        repeat(3) { key.sendKey(advance = true) }
                    }
                    send(Packet(PacketType.RESP, 1, p.register, bytes(0)))
                    val resend = applyWrite(p.register, p.data)
                    if (resend) {
                        synchronized(lock) { regs.toList() }.forEach { (r, v) -> notify(r, v) }
                        synchronized(lock) { settingsPayloadsLocked() }.forEach { notify(Reg.READ_SETTINGS, it, explicit = true) }
                    }
                }
            }
        }

        fun sendStart() {
            val type = when (modelYear) {
                ModelYear.MY18 -> PacketType.START_REQ_MY18
                ModelYear.MY24 -> PacketType.START_REQ_MY24
                else -> PacketType.START_REQ_MY14
            }
            val seed = ByteArray(8) { Random.nextInt(256).toByte() } + bytes(0x01)
            val p = Packet(type, 0, 0x01, seed)
            send(p)
            key.update(p.toPlainBytes())
        }

        @Synchronized
        private fun sendNextDump() {
            val item = dumpQueue.removeFirstOrNull()
            if (item == null) {
                dumping = false; lastDumpSent = null; return
            }
            lastDumpSent = item to System.currentTimeMillis()
            send(Packet(PacketType.RESP, 0, item.reg, item.bytes()))
        }

        /** Like the real car, repeat a notification that was never acknowledged. */
        @Synchronized
        fun resendStaleDump() {
            val (item, at) = lastDumpSent ?: return
            if (!dumping || System.currentTimeMillis() - at < 1_500) return
            lastDumpSent = item to System.currentTimeMillis()
            send(Packet(PacketType.RESP, 0, item.reg, item.bytes()))
        }

        /**
         * Send a notification. [explicit] payloads (0x16, which the car spreads over
         * several notifications) are sent as given; otherwise the register's latest
         * value is what counts.
         */
        @Synchronized
        fun notify(reg: Int, value: ByteArray, explicit: Boolean = false) {
            if (!started || silent) return
            if (dumping) {
                // Still streaming the initial dump: make sure the new value goes out with it.
                if (explicit) dumpQueue.addLast(DumpItem(reg, value.copyOf()))
                else if (dumpQueue.none { it.reg == reg && it.payload == null }) dumpQueue.addLast(DumpItem(reg, null))
                return
            }
            send(Packet(PacketType.RESP, 0, reg, value))
        }

        @Synchronized
        private fun send(p: Packet) {
            if (closed) return
            val xor = when {
                PacketType.isUnencoded(p.type) -> 0
                p.type == PacketType.RESP -> key.recvKey(advance = true)
                else -> key.recvKey(advance = false)
            }
            val bytes = p.encode(xor)
            try {
                if (fragmentWrites && bytes.size > 2) {
                    var i = 0
                    while (i < bytes.size) {
                        val n = Random.nextInt(1, 4).coerceAtMost(bytes.size - i)
                        out.write(bytes, i, n); out.flush()
                        i += n
                        Thread.sleep(1)
                    }
                } else {
                    out.write(bytes); out.flush()
                }
            } catch (_: Exception) {
                close()
            }
        }
    }

    companion object {
        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /** Mask with all [n] options available. */
        private fun all(n: Int) = (1 shl n) - 1

        /**
         * Factory settings of a plausible 2015 European Outlander PHEV: most
         * functions with every option available, the wipers without a rain sensor
         * (id 4: options 0..2 only), a headlight auto-cutout offering two "On"
         * variants (id 12), and some ids the car reports as not equipped (mask 0).
         */
        val DEFAULT_SETTINGS: Map<Int, CarSetting> = linkedMapOf(
            1 to CarSetting(all(2), 0),
            2 to CarSetting(all(2), 1),
            3 to CarSetting(all(7), 0),
            4 to CarSetting(0b0111, 2),
            5 to CarSetting(all(3), 2),
            6 to CarSetting(all(4), 2),
            7 to CarSetting(all(5), 1),
            8 to CarSetting(0, 0),
            9 to CarSetting(all(5), 2),
            10 to CarSetting(all(2), 0),
            11 to CarSetting(all(7), 2),
            12 to CarSetting(0b1111, 2),
            13 to CarSetting(all(4), 2),
            14 to CarSetting(all(2), 0),
            15 to CarSetting(all(2), 0),
            16 to CarSetting(all(5), 0),
            17 to CarSetting(all(2), 0),
            19 to CarSetting(all(2), 0),
            20 to CarSetting(all(5), 0),
            21 to CarSetting(all(4), 3),
            22 to CarSetting(all(4), 0),
            23 to CarSetting(0, 0),
            24 to CarSetting(all(4), 1),
            25 to CarSetting(0, 0),
            26 to CarSetting(all(6), 0),
            27 to CarSetting(0, 0),
            28 to CarSetting(all(2), 0),
            29 to CarSetting(all(2), 0),
            32 to CarSetting(all(3), 0),
            33 to CarSetting(0, 0),
            34 to CarSetting(all(2), 0),
            35 to CarSetting(all(2), 0),
            36 to CarSetting(all(3), 1),
            41 to CarSetting(0, 0),
            42 to CarSetting(all(5), 2),
            43 to CarSetting(all(3), 0),
            50 to CarSetting(all(2), 1),
            51 to CarSetting(all(2), 1),
            53 to CarSetting(all(2), 0),
            54 to CarSetting(all(3), 0),
            55 to CarSetting(all(3), 0),
            56 to CarSetting(all(2), 0),
            57 to CarSetting(all(2), 0),
            58 to CarSetting(all(2), 1),
            60 to CarSetting(all(2), 0),
        )

        fun clockBytes(t: LocalDateTime): ByteArray {
            val dow = if (t.dayOfWeek == DayOfWeek.SUNDAY) 0 else t.dayOfWeek.value
            return bytes(t.year - 2000, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, dow)
        }

        /** Run standalone: `java -cp core.jar ...FakeCarKt [port] [MY14|MY18]`. */
        @JvmStatic
        fun main(args: Array<String>) {
            val port = args.getOrNull(0)?.toIntOrNull() ?: 8080
            val my = args.getOrNull(1)?.let { ModelYear.valueOf(it) } ?: ModelYear.MY14
            FakeCar(my) { println(it) }.start(port, "0.0.0.0")
            Thread.currentThread().join()
        }
    }
}
