package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ChargeTimerCodec
import io.github.anwo91.phevlink.core.model.ChargeTimerProgram
import io.github.anwo91.phevlink.core.model.ChargeWindow
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.RawRegister
import io.github.anwo91.phevlink.core.model.ScheduleDecoder
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.core.protocol.toHex
import io.github.anwo91.phevlink.core.session.ConnectionConfig
import io.github.anwo91.phevlink.core.session.PhevConnection
import io.github.anwo91.phevlink.core.session.TcpConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** The car's charge timer: packing as the official app writes it (2014–2017 cars), reading it back, the command. */
class ChargeTimerTest {
    private fun t(h: Int, m: Int): LocalTime = LocalTime.of(h, m)

    /** Mon–Fri 23:00–07:00 with Timer 1, weekend off, full charge on Saturday. */
    private val weekdays = ChargeTimerProgram(
        windows = listOf(ChargeWindow(t(23, 0), t(7, 0)), null, null),
        dayTimer = listOf(0, 1, 1, 1, 1, 1, 0),
        fullDays = setOf(6),
    )

    private fun state(vararg regs: Pair<Int, String>, my: ModelYear = ModelYear.MY14) = CarState(
        modelYear = my,
        registers = regs.associate { (r, hex) -> r to RawRegister(r, hex, 0L, 1) },
    )

    @Test
    fun packsLikeTheOfficialApp() {
        // Worked example from the official app's packing (data bytes of register 0x01).
        val expected = "54 05 40 F7 7F F8 01 E7 7F F8 01 02 FF DE 7B EF 07 07 00 1C FF 9C 73 CE 07 07 00 1C".replace(" ", "").lowercase()
        assertEquals(expected, ChargeTimerCodec.encodeMy14(weekdays, 2).toHex().replace(" ", "").lowercase())
    }

    @Test
    fun readsWhatTheCarEchoes() {
        val data = ChargeTimerCodec.encodeMy14(weekdays, 2)
        val presets = data.copyOfRange(0, 2) + data.copyOfRange(3, 11)
        val s = state(
            Reg.READ_CHARGE_PRESETS to presets.toHex(),
            Reg.READ_FULL_CHARGE_DAYS to "40",
            Reg.READ_CHARGE_TIMER_PROG to data.copyOfRange(12, 28).toHex(),
            Reg.READ_CHARGE_TIMER_STATE_B to "02",
        )
        assertEquals(weekdays, ChargeTimerProgram.read(s))
        // The per-day view the home screen shows agrees.
        val days = ScheduleDecoder.charge(s)
        assertEquals(t(23, 0), days[1].start)
        assertEquals(t(7, 0), days[5].end)
        assertNull(days[0].start)
        assertTrue(days[6].fullCharge)
        assertTrue(ScheduleDecoder.chargeTimerOn(s))
    }

    @Test
    fun rebuildsTimersFromPerDayWindowsWhenPresetsAreMissing() {
        val data = ChargeTimerCodec.encodeMy14(weekdays, 2)
        val s = state(Reg.READ_CHARGE_TIMER_PROG to data.copyOfRange(12, 28).toHex(), Reg.READ_FULL_CHARGE_DAYS to "40")
        assertEquals(weekdays, ChargeTimerProgram.read(s))
        assertNull(ChargeTimerProgram.read(state()))
    }

    @Test
    fun emptyTimerAndNormalising() {
        val empty = ChargeTimerCodec.encodeMy14(ChargeTimerProgram.EMPTY, 1)
        val s = state(Reg.READ_CHARGE_PRESETS to (empty.copyOfRange(0, 2) + empty.copyOfRange(3, 11)).toHex())
        assertEquals(ChargeTimerProgram.EMPTY, ChargeTimerProgram.read(s))
        assertFalse(ChargeTimerProgram.EMPTY.active)
        // A day pointing at an empty timer is dropped.
        val odd = weekdays.copy(dayTimer = listOf(2, 1, 1, 1, 1, 1, 0))
        assertEquals(weekdays, odd.normalized())
        assertEquals("T1 23:00–07:00 Mon,Tue,Wed,Thu,Fri; full Sat", weekdays.summary())
    }

    @Test
    fun stateByte() {
        assertEquals(1, ChargeTimerCodec.stateByte(ChargeTimerProgram.EMPTY, 2))
        assertEquals(2, ChargeTimerCodec.stateByte(weekdays, 1))
        assertEquals(2, ChargeTimerCodec.stateByte(weekdays, null))
        assertEquals(0x13, ChargeTimerCodec.stateByte(weekdays, 0x13))
    }

    @Test
    fun commandWritesTheTimerAndSwitchesItOn() {
        val off = state(Reg.READ_CHARGE_TIMER_STATE_B to "01")
        val w = CarCommand.SetChargeTimer(weekdays).toWrites(ModelYear.MY14, off)
        assertEquals(listOf(Reg.WRITE_CHARGE_TIMER_MY14, Reg.WRITE_CHARGE_TIMER_CANCEL), w.map { it.register })
        assertEquals(28, w[0].data.size)
        assertEquals("02", w[1].data.toHex())

        val on = state(Reg.READ_CHARGE_TIMER_STATE_B to "02")
        assertEquals(1, CarCommand.SetChargeTimer(weekdays).toWrites(ModelYear.MY14, on).size)
        val clear = CarCommand.SetChargeTimer(ChargeTimerProgram.EMPTY).toWrites(ModelYear.MY14, on)
        assertEquals("01", clear[0].data[11].toInt().let { "%02x".format(it) })
        assertEquals("01", clear[1].data.toHex())

        assertEquals(CarCommand.SetChargeTimer.NOT_MY14, CarCommand.SetChargeTimer(weekdays).refusal(state(my = ModelYear.MY18), emptySet()))
        assertNull(CarCommand.SetChargeTimer(weekdays).refusal(off, emptySet()))
    }

    @Test
    fun travelsToTheBridgeAsJson() {
        val c = CarCommand.SetChargeTimer(weekdays)
        val back = Codecs.commandFromMap(io.github.anwo91.phevlink.core.json.Json.parseObject(io.github.anwo91.phevlink.core.json.Json.write(Codecs.commandToMap(c))))
        assertEquals(c, back)
    }

    // ------------------------------------------------------------------------------------- end to end

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

    @Test
    fun setsTheTimerOnTheEmulator() = runBlocking {
        val fake = FakeCar(ModelYear.MY14).start()
        val pc = PhevConnection(TcpConnector("127.0.0.1", fake.port), log, scope, ConnectionConfig(backoffMs = listOf(200, 400, 800)))
        car = fake; conn = pc
        pc.start()
        val first = withTimeout(8_000) { pc.car.first { it.vin != null && ChargeTimerProgram.read(it) != null } }
        assertEquals(ChargeTimerProgram.EMPTY, ChargeTimerProgram.read(first))

        val r = pc.execute(CarCommand.SetChargeTimer(weekdays))
        assertTrue("$r\n${log.dump()}", r is CommandResult.Success)
        val after = withTimeout(8_000) { pc.car.first { ChargeTimerProgram.read(it) == weekdays && ScheduleDecoder.chargeTimerOn(it) } }
        assertNotNull(after)
        assertEquals(listOf("02"), fake.acceptedWrites.filter { it.first == 0x17 }.map { it.second })
        assertEquals("0x01 written once", 1, fake.acceptedWrites.count { it.first == Reg.WRITE_CHARGE_TIMER_MY14 })

        // Clearing it: no timer, state 1.
        assertTrue(pc.execute(CarCommand.SetChargeTimer(ChargeTimerProgram.EMPTY)) is CommandResult.Success)
        withTimeout(8_000) { pc.car.first { ChargeTimerProgram.read(it) == ChargeTimerProgram.EMPTY && !ScheduleDecoder.chargeTimerOn(it) } }
        assertEquals("01", "%02x".format(fake.register(Reg.READ_CHARGE_TIMER_STATE_B)!![0].toInt()))
        Unit
    }
}
