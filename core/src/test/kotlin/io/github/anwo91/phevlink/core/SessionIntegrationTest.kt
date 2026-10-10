package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarSetting
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
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

    // ------------------------------------------------------- vehicle settings

    private suspend fun PhevConnection.awaitSettingsDump(): CarState =
        awaitState { it.settings.size == FakeCar.DEFAULT_SETTINGS.size }

    /** The car acks a write before recording it, so poll for effects that are only visible car-side. */
    private suspend fun waitUntil(what: String, timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what")
            delay(20)
        }
    }

    /** Index in the car's accepted writes of the first write to [reg] with [hex], at or after [from]. */
    private fun FakeCar.writeIndex(reg: Int, hex: String, from: Int = 0): Int =
        acceptedWrites.withIndex().indexOfFirst { (i, w) -> i >= from && w.first == reg && w.second == hex }

    @Test
    fun vehicleSettingsDumpAndChange() = dumpOnFailure {
        val (fake, pc) = setup()
        pc.start()
        val s = pc.awaitSettingsDump()
        assertEquals(FakeCar.DEFAULT_SETTINGS, s.settings)
        assertEquals(2, s.settingsStatus)
        val groups = VehicleSettingsCatalog.visibleGroups(s.settings)
        assertTrue(groups.isNotEmpty())
        assertEquals("1 minute", groups.flatMap { it.items }.first { it.id == 7 }.currentLabel)

        val r = pc.execute(CarCommand.ChangeSetting(7, 3))
        assertTrue("$r", r is CommandResult.Success)
        assertEquals(31 to 3, fake.setting(7))
        // The car confirmed before we saved, so our state already has the new value.
        assertEquals(CarSetting(31, 3), pc.car.value.settings[7])
        waitUntil("save") { fake.savedSettingsCount == 1 }
        val set = fake.writeIndex(0x0f, "0703")
        val save = fake.writeIndex(0x0e, "00")
        assertTrue("0x0f then 0x0e: ${fake.acceptedWrites}", set >= 0 && save > set)
        assertTrue(log.snapshot().any { it.text.contains("setting Charging Lid Light Auto cut-out: 5 minutes") })
        assertTrue(log.snapshot().any { it.text.startsWith("Car reported the result of update setting") })

        // Asking for the value it already has: no wait needed, still saved.
        assertTrue(pc.execute(CarCommand.ChangeSetting(7, 3)) is CommandResult.Success)
        waitUntil("second save") { fake.savedSettingsCount == 2 }
    }

    @Test
    fun unconfirmedSettingFailsButIsStillSaved() = dumpOnFailure {
        val (fake, pc) = setup { ignoreSettingChanges = true }
        pc.start()
        pc.awaitSettingsDump()
        val r = pc.execute(CarCommand.ChangeSetting(9, 4))
        assertTrue("$r", r is CommandResult.Failure)
        r as CommandResult.Failure
        assertEquals(CarCommand.ChangeSetting.UNCONFIRMED, r.reason)
        assertEquals(false, r.retryable)
        waitUntil("save") { fake.savedSettingsCount == 1 }
        val set = fake.writeIndex(0x0f, "0904")
        val save = fake.writeIndex(0x0e, "00")
        assertTrue("0x0f then 0x0e: ${fake.acceptedWrites}", set >= 0 && save > set)
        assertEquals(31 to 2, fake.setting(9))
        assertEquals(CarSetting(31, 2), pc.car.value.settings[9])
        assertTrue(log.snapshot().any { it.text.startsWith("The car never reported the result of update setting") })
        // The session carries on normally afterwards.
        assertTrue(pc.execute(CarCommand.Headlights(true)) is CommandResult.Success)
    }

    @Test
    fun resetVehicleSettings() = dumpOnFailure {
        val (fake, pc) = setup()
        pc.start()
        pc.awaitSettingsDump()
        assertTrue(pc.execute(CarCommand.ChangeSetting(9, 4)) is CommandResult.Success)
        assertTrue(pc.execute(CarCommand.ChangeSetting(50, 0)) is CommandResult.Success)
        assertEquals(31 to 4, fake.setting(9))
        val before = fake.acceptedWrites.size

        val r = pc.execute(CarCommand.ResetSettings)
        assertTrue("$r", r is CommandResult.Success)
        assertEquals(31 to 2, fake.setting(9))
        assertEquals(3 to 1, fake.setting(50))
        waitUntil("save after reset") { fake.savedSettingsCount == 3 }
        val set = fake.writeIndex(0x0f, "fe00", before)
        val save = fake.writeIndex(0x0e, "00", before)
        assertTrue("0x0f then 0x0e: ${fake.acceptedWrites}", set >= 0 && save > set)
        val s = pc.awaitState { it.settings == FakeCar.DEFAULT_SETTINGS }
        assertEquals(CarSetting(31, 2), s.settings[9])
    }

    @Test
    fun vehicleSettingsMy18() = dumpOnFailure {
        val (fake, pc) = setup(ModelYear.MY18)
        pc.start()
        val s = pc.awaitSettingsDump()
        assertEquals(ModelYear.MY18, s.modelYear)
        val r = pc.execute(CarCommand.ChangeSetting(50, 0))
        assertTrue("$r", r is CommandResult.Success)
        assertEquals(3 to 0, fake.setting(50))
        assertEquals(0, pc.car.value.settings[50]?.value)
        waitUntil("save") { fake.savedSettingsCount == 1 }
        assertTrue(fake.writeIndex(0x0e, "00") > fake.writeIndex(0x0f, "3200"))
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

    @Test
    fun climateRefusedWhilePowerIsOn() = dumpOnFailure {
        val (fake, pc) = setup()
        fake.setIgnition(3) // ACC
        pc.start()
        pc.awaitState { it.ignition == Ignition.ACC }
        val r = pc.execute(CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_10))
        assertTrue("$r", r is CommandResult.Failure && r.reason == CarCommand.ClimateOn.POWER_ON && !r.retryable)
        assertTrue("nothing sent", fake.acceptedWrites.none { it.first == 0x02 || it.first == 0x04 })
        assertTrue(log.snapshot().any { it.text.startsWith("Not sending") && it.text.contains("ignition ACC") })
        // Power off: works again.
        fake.setIgnition(0)
        pc.awaitState { it.ignition == Ignition.OFF }
        val ok = pc.execute(CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_10))
        assertTrue("$ok", ok is CommandResult.Success)
        assertTrue(pc.car.value.climate.isRunning)
    }

    @Test
    fun climateThatNeverStartsIsReported() = dumpOnFailure {
        val (fake, pc) = setup(session = SessionConfig(maxAwaitMs = 1_500)) { ignoreClimate = true }
        val r = pc.execute(CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_20))
        assertTrue("$r", r is CommandResult.Failure && r.reason == CarCommand.ClimateOn.NOT_STARTED)
        // Both writes went out (the car acked them) and the log says what the car reported.
        assertTrue(fake.acceptedWrites.any { it.first == 0x04 && it.second == "02" })
        assertTrue(log.snapshot().any { it.text.contains("never reported the result") && it.text.contains("Car state: ignition OFF") })
    }

    @Test
    fun delayedClimateStartIsNotAwaited() = dumpOnFailure {
        val (_, pc) = setup(ModelYear.MY18, session = SessionConfig(maxAwaitMs = 60_000)) { ignoreClimate = true }
        val started = System.currentTimeMillis()
        val r = pc.execute(CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_10, delayMinutes = 10))
        assertTrue("$r", r is CommandResult.Success)
        assertTrue(System.currentTimeMillis() - started < 15_000)
    }

    @Test
    fun wifiAlwaysOnMy18AndRefusedOnMy14() = dumpOnFailure {
        val (fake, pc) = setup(ModelYear.MY18)
        pc.start()
        pc.awaitState { it.wifiAlwaysOn == false }
        val r = pc.execute(CarCommand.WifiAlwaysOn(true))
        assertTrue("$r", r is CommandResult.Success)
        assertEquals(true, pc.awaitState { it.wifiAlwaysOn == true }.wifiAlwaysOn)
        assertEquals("01", fake.acceptedWrites.last { it.first == 0x18 }.second)
        pc.stop("next car")
        fake.stop()

        val (fake14, pc14) = setup(ModelYear.MY14)
        pc14.start()
        pc14.awaitState { it.modelYear == ModelYear.MY14 && it.vin != null }
        val r14 = pc14.execute(CarCommand.WifiAlwaysOn(true))
        assertTrue("$r14", r14 is CommandResult.Failure && r14.reason == CarCommand.WifiAlwaysOn.NOT_ON_MY14)
        assertTrue(fake14.acceptedWrites.none { it.first == 0x18 })
        assertEquals(null, pc14.car.value.wifiAlwaysOn)
    }
}
