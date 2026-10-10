package io.github.anwo91.phevlink.core.emulator

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

    // ----------------------------------------------------------------- model
    private val lock = Any()
    private val regs = LinkedHashMap<Int, ByteArray>()
    private var pendingMy14Mode = 0x02
    private var climateEndsAtSimMin = 0.0
    private var simMinutes = 0.0
    val acceptedWrites = CopyOnWriteArrayList<Pair<Int, String>>()
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
        }
    }

    fun register(reg: Int): ByteArray? = synchronized(lock) { regs[reg]?.copyOf() }
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

    private fun climateOnLocked(modeByte: Int) {
        val mode = modeByte and 0x0f
        if (mode == 0) {
            climateOffLocked(); return
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
        private var dumpQueue = ArrayDeque<Int>()
        private var dumping = false
        private var lastDumpSent: Pair<Int, Long>? = null
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
                        synchronized(lock) { dumpQueue = ArrayDeque(regs.keys) }
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
                    if (resend) synchronized(lock) { regs.toList() }.forEach { (r, v) -> notify(r, v) }
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
            val r = dumpQueue.removeFirstOrNull()
            if (r == null) {
                dumping = false; lastDumpSent = null; return
            }
            val v = synchronized(lock) { regs[r]!!.copyOf() }
            lastDumpSent = r to System.currentTimeMillis()
            send(Packet(PacketType.RESP, 0, r, v))
        }

        /** Like the real car, repeat a notification that was never acknowledged. */
        @Synchronized
        fun resendStaleDump() {
            val (r, at) = lastDumpSent ?: return
            if (!dumping || System.currentTimeMillis() - at < 1_500) return
            lastDumpSent = r to System.currentTimeMillis()
            val v = synchronized(lock) { regs[r]!!.copyOf() }
            send(Packet(PacketType.RESP, 0, r, v))
        }

        @Synchronized
        fun notify(reg: Int, value: ByteArray) {
            if (!started || silent) return
            if (dumping) {
                // Still streaming the initial dump: make sure the new value goes out with it.
                if (reg !in dumpQueue) dumpQueue.addLast(reg)
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
