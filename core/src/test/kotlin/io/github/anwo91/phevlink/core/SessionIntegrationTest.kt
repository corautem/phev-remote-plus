package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.session.ConnectionConfig
import io.github.anwo91.phevlink.core.session.LinkStatus
import io.github.anwo91.phevlink.core.session.PhevConnection
import io.github.anwo91.phevlink.core.session.SessionConfig
import io.github.anwo91.phevlink.core.session.TcpConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end: our client against the FakeCar over real TCP. */
class SessionIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = DiagLog()
    private var car: FakeCar? = null
    private var conn: PhevConnection? = null

    @After
    fun tearDown() = runBlocking {
        conn?.stop("test done")
        car?.stop()
        scope.cancel()
    }

    private fun setup(
        my: ModelYear = ModelYear.MY14,
        session: SessionConfig = SessionConfig(),
        configure: FakeCar.() -> Unit = {},
    ): Pair<FakeCar, PhevConnection> {
        val c = FakeCar(my).apply(configure).start()
        val cfg = ConnectionConfig(session = session, backoffMs = listOf(200, 400, 800))
        val pc = PhevConnection(TcpConnector("127.0.0.1", c.port), log, scope, cfg)
        car = c; conn = pc
        return c to pc
    }

    private suspend fun PhevConnection.awaitState(timeoutMs: Long = 8_000, predicate: (CarState) -> Boolean): CarState =
        withTimeout(timeoutMs) { car.first(predicate) }

    private suspend fun PhevConnection.awaitConnected(timeoutMs: Long = 8_000) =
        withTimeout(timeoutMs) { status.first { it is LinkStatus.Connected } }

    private fun dumpOnFailure(block: suspend () -> Unit) = runBlocking {
        try {
            block()
        } catch (t: Throwable) {
            System.err.println("---- diag log ----\n" + log.dump())
            throw t
        }
    }

    @Test
    fun handshakeMy14AndReadState() = dumpOnFailure {
        val (_, pc) = setup()
        pc.start()
        pc.awaitConnected()
        val s = pc.awaitState { it.vin != null && it.batteryPercent != null && it.locked != null && it.ssid != null }
        assertEquals(ModelYear.MY14, s.modelYear)
        assertEquals("JMAXDGG2WFZ012345", s.vin)
        assertEquals(64, s.batteryPercent)
        assertEquals(true, s.locked)
        assertEquals(1, s.registrations)
        assertEquals("REMOTEf00d42", s.ssid)
    }

    @Test
    fun climateOnMy14EncodesDurationAndEchoesTimers() = dumpOnFailure {
        val (fake, pc) = setup()
        val r = pc.execute(CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_20))
        assertTrue("$r", r is CommandResult.Success)
        val s = pc.awaitState { it.climate.state == PreAcState.RUNNING && it.climate.duration == ClimateDuration.MIN_20 }
        assertEquals(ClimateMode.HEAT, s.climate.mode)
        assertEquals(ClimateDuration.MIN_20, s.climate.duration)
        assertEquals(0, fake.scheduleEchoMismatches)
        // mode byte = HEAT(2) | 20min(1)<<4 = 0x12 at data[6]
        val write = fake.acceptedWrites.first { it.first == 0x02 }.second
        assertEquals("12", write.substring(12, 14))

        val off = pc.execute(CarCommand.ClimateOff)
        assertTrue("$off", off is CommandResult.Success)
        pc.awaitState { it.climate.state == PreAcState.OFF }
    }

    @Test
    fun climateOnMy18UsesSingleRegister() = dumpOnFailure {
        val (fake, pc) = setup(ModelYear.MY18)
        val r = pc.execute(CarCommand.ClimateOn(ClimateMode.WINDSCREEN, ClimateDuration.MIN_30))
        assertTrue("$r", r is CommandResult.Success)
        val s = pc.awaitState { it.climate.state == PreAcState.RUNNING && it.climate.mode == ClimateMode.WINDSCREEN }
        assertEquals(ClimateDuration.MIN_30, s.climate.duration)
        assertEquals("02030200", fake.acceptedWrites.first { it.first == 0x1b }.second)
    }

    @Test
    fun lightsAndLocate() = dumpOnFailure {
        val (fake, pc) = setup()
        assertTrue(pc.execute(CarCommand.Headlights(true)) is CommandResult.Success)
        pc.awaitState { it.headlights == true }
        assertTrue(pc.execute(CarCommand.Locate(seconds = 1)) is CommandResult.Success)
        pc.awaitState { it.headlights == false }
        assertTrue(pc.execute(CarCommand.ParkingLights(true)) is CommandResult.Success)
        pc.awaitState { it.parkingLights == true }
        assertTrue(fake.acceptedWrites.count { it.first == 0x0a } >= 3)
    }

    @Test
    fun survivesFragmentedStream() = dumpOnFailure {
        val (_, pc) = setup { fragmentWrites = true }
        pc.start()
        pc.awaitState { it.batteryPercent != null && it.vin != null }
        assertTrue(pc.execute(CarCommand.Headlights(true)) is CommandResult.Success)
    }

    @Test
    fun recoversFromKeyDesync() = dumpOnFailure {
        val (fake, pc) = setup { desyncAfterWrites = 1 }
        // first write desyncs the car; the second must recover via 0xbb resync
        assertTrue(pc.execute(CarCommand.Headlights(true)) is CommandResult.Success)
        val r = pc.execute(CarCommand.Headlights(false))
        assertTrue("$r", r is CommandResult.Success)
        assertTrue(log.snapshot().any { it.text.contains("re-synchronised") })
        assertEquals(false, pc.awaitState { it.headlights == false }.headlights)
        assertTrue(fake.acceptedWrites.isNotEmpty())
    }

    @Test
    fun retriesWhenCarDropsAWrite() = dumpOnFailure {
        val (_, pc) = setup(session = SessionConfig(writeTimeoutMs = 600, syncClockOnConnect = false))
        pc.start()
        pc.awaitConnected()
        pc.awaitState { it.vin != null }
        car!!.dropNextWrites = 1
        val r = pc.execute(CarCommand.ParkingLights(true))
        assertTrue("$r", r is CommandResult.Success)
        assertTrue(log.snapshot().any { it.text.startsWith("No ack") })
    }

    @Test
    fun commandSurvivesReconnect() = dumpOnFailure {
        val (fake, pc) = setup()
        pc.start()
        pc.awaitConnected()
        fake.kickClients()
        val r = pc.execute(CarCommand.Headlights(true), timeoutMs = 15_000)
        assertTrue("$r", r is CommandResult.Success)
        pc.awaitState { it.headlights == true }
    }

    @Test
    fun midSessionRekeyKeepsWorking() = dumpOnFailure {
        val (_, pc) = setup()
        pc.start()
        pc.awaitConnected()
        pc.awaitState { it.ecuVersion != null && 0x22 in it.registers }
        car!!.rekey()
        delay(300)
        assertTrue(pc.execute(CarCommand.Headlights(true)) is CommandResult.Success)
        pc.awaitState { it.headlights == true }
    }

    @Test
    fun unregisteredPhoneGivesClearError() = dumpOnFailure {
        val (_, pc) = setup(session = SessionConfig(handshakeTimeoutMs = 1_500)) { acceptsClient = false }
        pc.start()
        val st = withTimeout(8_000) { pc.status.first { it is LinkStatus.Retrying } } as LinkStatus.Retrying
        assertTrue(st.lastError, st.lastError.contains("not registered"))
    }

    @Test
    fun silentCarIsDetected() = dumpOnFailure {
        val (fake, pc) = setup(session = SessionConfig(silenceTimeoutMs = 1_500))
        pc.start()
        pc.awaitConnected()
        fake.silent = true
        val st = withTimeout(8_000) { pc.status.first { it is LinkStatus.Retrying } } as LinkStatus.Retrying
        assertTrue(st.lastError, st.lastError.contains("No data"))
        fake.silent = false
        pc.reconnectNow()
        pc.awaitConnected()
    }

    @Test
    fun clockSyncUsesOfficialEightByteFormat() = dumpOnFailure {
        val (fake, pc) = setup(session = SessionConfig(clockSyncDelayMs = 200))
        pc.start()
        withTimeout(8_000) { while (fake.acceptedWrites.none { it.first == 0x05 }) delay(50) }
        val data = fake.acceptedWrites.first { it.first == 0x05 }.second
        assertEquals(16, data.length) // 8 bytes
        assertTrue(data.endsWith("00"))
    }

    @Test
    fun registrationFlow() = dumpOnFailure {
        val (fake, pc) = setup()
        pc.start()
        pc.awaitState { it.registrations == 1 }
        fake.registrationMode = true
        assertTrue(pc.execute(CarCommand.RegisterPhone) is CommandResult.Success)
        pc.awaitState { it.registrations == 2 }
        assertTrue(pc.execute(CarCommand.UnregisterPhone) is CommandResult.Success)
        pc.awaitState { it.registrations == 1 }
    }
}
