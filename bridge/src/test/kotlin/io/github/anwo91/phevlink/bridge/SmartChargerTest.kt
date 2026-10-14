package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ChargeTimerCodec
import io.github.anwo91.phevlink.core.model.ChargeTimerProgram
import io.github.anwo91.phevlink.core.model.ChargeWindow
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.RawRegister
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SmartChargerTest {
    private val log = DiagLog()
    private val zone = ZoneId.of("Europe/Oslo")
    private val dir: File = Files.createTempDirectory("smart").toFile()
    private var t = ZonedDateTime.of(2026, 10, 14, 18, 0, 0, 0, zone) // Wednesday evening
    private val written = ArrayList<ChargeTimerProgram>()
    private var accept = true
    private var reachButFail = false

    /** The owner's timer: 23:00–07:00 on weekdays. */
    private val ownerTimer = ChargeTimerProgram(
        listOf(ChargeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)), null, null), listOf(0, 1, 1, 1, 1, 1, 0), emptySet(),
    )
    private var program: ChargeTimerProgram = ownerTimer
    private var car = carWith(ownerTimer, 40, PlugState.PLUGGED)

    /** A car state that reports [p] as its timer (registers 0x20 and 0x0e), like the car does. */
    private fun carWith(p: ChargeTimerProgram, pct: Int, plug: PlugState): CarState {
        val raw = ChargeTimerCodec.encodeMy14(p, 2)
        // 0x20 echoes bytes 0..1 (day choices) and 3..10 (timers).
        val presets = byteArrayOf(raw[0], raw[1]) + raw.copyOfRange(3, 11)
        return CarState(
            batteryPercent = pct, plug = plug, modelYear = ModelYear.MY14, updatedAtMs = t.toInstant().toEpochMilli(),
            registers = mapOf(
                Reg.READ_CHARGE_PRESETS to RawRegister(Reg.READ_CHARGE_PRESETS, presets.toHex(), t.toInstant().toEpochMilli(), 0),
                Reg.READ_FULL_CHARGE_DAYS to RawRegister(Reg.READ_FULL_CHARGE_DAYS, byteArrayOf(raw[2]).toHex(), t.toInstant().toEpochMilli(), 0),
            ),
        )
    }

    /** Hourly prices: evening 2.00, 01–05 at 0.50, otherwise 1.00, for today and tomorrow. */
    private fun fakeDay(url: String): String? {
        val date = Regex("""(\d{4})/(\d{2})-(\d{2})_""").find(url)!!.groupValues.let { java.time.LocalDate.of(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
        val items = (0 until 24).map { h ->
            val s = date.atTime(h, 0).atZone(zone)
            val p = when (h) { in 17..21 -> 2.0; in 1..4 -> 0.5; else -> 1.0 }
            """{"NOK_per_kWh":$p,"time_start":"${s.toOffsetDateTime()}","time_end":"${s.plusHours(1).toOffsetDateTime()}"}"""
        }
        return items.joinToString(",", "[", "]")
    }

    private fun rig(shared: Map<String, Any?> = mapOf("smartCharge" to true)): SmartCharger {
        val store = SharedStore(File(dir, "shared.json"), log)
        store.update(mapOf("timeZone" to "Europe/Oslo", "homeLat" to 59.91, "homeLon" to 10.75) + shared, "test")
        val activity = ActivityLog(File(dir, "activity.jsonl"), log)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prices = SpotPrices(File(dir, "prices"), log, fetch = ::fakeDay, now = { t.toInstant().toEpochMilli() })
        val history = EnergyHistory(dir, store, prices, activity, log, scope, now = { t.toInstant().toEpochMilli() })
        return SmartCharger(
            store, history, prices, activity, car = { car }, carHome = { true },
            execute = { cmd ->
                val p = (cmd as CarCommand.SetChargeTimer).program
                val onCar = ChargeTimerProgram.read(car)?.normalized()
                when {
                    // Like the car session's compare-and-swap check.
                    cmd.expected != null && cmd.expected!!.none { it.sameTimes(onCar) } -> CommandResult.Failure(cmd, CarCommand.SetChargeTimer.TIMER_CHANGED)
                    reachButFail -> {
                        program = p
                        car = carWith(p, car.batteryPercent ?: 0, car.plug)
                        CommandResult.Failure(cmd, "the car didn't confirm")
                    }
                    accept -> {
                        written += p
                        program = p
                        car = carWith(p, car.batteryPercent ?: 0, car.plug)
                        CommandResult.Success(cmd, 900)
                    }
                    else -> CommandResult.Failure(cmd, "no answer")
                }
            },
            log = log, file = File(dir, "smart_charge.json"), now = { t.toInstant().toEpochMilli() },
        )
    }

    @Test
    fun movesTonightsChargingToTheCheapHoursAndPutsTheOwnersTimerBack() = runBlocking {
        val s = rig()
        s.tick()
        val p = written.single()
        // 40 → 100 % at 18 %/h with margin: 230 min, cheapest from 01:00 Thursday (day 4), with full charge.
        assertEquals(ChargeWindow(LocalTime.of(1, 0), LocalTime.of(4, 50)), p.window(4))
        assertEquals("tonight (Wednesday) too", ChargeWindow(LocalTime.of(1, 0), LocalTime.of(4, 50)), p.window(3))
        assertTrue(4 in p.fullDays)
        assertEquals("other weekdays keep the owner's timer", ownerTimer.windows[0], p.window(1))
        assertEquals("planned", s.describe()["status"])
        // Overnight: charged full, charging stops → the owner's timer goes back.
        t = t.plusHours(11)
        car = carWith(program, 100, PlugState.PLUGGED)
        s.tick()
        assertEquals(ownerTimer.normalized(), written.last())
        assertTrue(log.dump(), log.dump().contains("putting the owner's timer back"))
        s.tick()
        assertEquals("once only", 2, written.size)
    }

    @Test
    fun unpluggingEarlyAlsoPutsItBack() = runBlocking {
        val s = rig()
        s.tick()
        t = t.plusHours(1)
        car = carWith(program, 40, PlugState.UNPLUGGED)
        s.tick()
        assertEquals(ownerTimer.normalized(), written.last())
    }

    @Test
    fun aRestartKeepsTheOwnersTimer() = runBlocking {
        rig().tick()
        val again = rig()
        t = t.plusHours(1)
        car = carWith(program, 45, PlugState.UNPLUGGED)
        again.tick()
        assertEquals(ownerTimer.normalized(), written.last())
    }

    @Test
    fun aTimerChangedByAPhoneIsLeftAlone() = runBlocking {
        val s = rig()
        s.tick()
        t = t.plusMinutes(10)
        val mine = ChargeTimerProgram(listOf(ChargeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)), null, null), List(7) { 1 }, emptySet())
        car = carWith(mine, 40, PlugState.PLUGGED)
        s.tick()
        t = t.plusHours(12)
        car = carWith(mine, 100, PlugState.UNPLUGGED)
        s.tick()
        assertEquals("nothing written after the phone's change", 1, written.size)
        assertTrue(log.dump().contains("changed by someone else"))
    }

    @Test
    fun offByDefaultAndNothingToDoWhenNearlyFull() = runBlocking {
        rig(shared = emptyMap()).tick()
        assertTrue(written.isEmpty())
        car = carWith(ownerTimer, 97, PlugState.PLUGGED)
        val s = rig()
        s.tick()
        assertTrue(written.isEmpty())
        assertEquals("full", s.describe()["status"])
    }

    @Test
    fun aFlatPriceLeavesTheTimerAlone() = runBlocking {
        val s = rig(mapOf("smartCharge" to true, "priceModel" to "norgespris"))
        s.tick()
        assertTrue(written.isEmpty())
        assertEquals("flat_price", s.describe()["status"])
    }

    @Test
    fun aWriteThatFailedButReachedTheCarIsStillPutBack() = runBlocking {
        reachButFail = true
        val s = rig()
        s.tick()
        assertEquals("writing", s.describe()["status"])
        // The car does carry the bridge's program: next look confirms it instead of taking it for the owner's.
        reachButFail = false
        t = t.plusMinutes(1)
        s.tick()
        assertEquals("planned", s.describe()["status"])
        t = t.plusHours(1)
        car = carWith(program, 40, PlugState.UNPLUGGED)
        s.tick()
        assertEquals(ownerTimer.normalized(), written.last())
    }

    @Test
    fun aPhoneChangingTheTimerWhileUnpluggedIsKept() = runBlocking {
        val s = rig()
        s.tick()
        // Unplugged and away (the bridge can't put it back yet); a phone sets a new timer next to the car.
        t = t.plusHours(1)
        val mine = ChargeTimerProgram(listOf(ChargeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)), null, null), List(7) { 1 }, emptySet())
        car = carWith(mine, 40, PlugState.UNPLUGGED)
        s.tick()
        assertEquals("nothing put back over the phone's timer", 1, written.size)
        assertEquals("changed", s.describe()["status"])
        // The phones can still show what the timer was before tonight.
        assertTrue(s.describe()["ownerBefore"] != null)
    }

    @Test
    fun theCarRefusingGetsTheOwnersTimerWrittenBack() = runBlocking {
        accept = false
        val s = rig()
        // Three tries (the car keeps reporting the owner's timer), then the owner's timer is written back — harmless
        // if it's there, and it covers a write that reached the car unconfirmed.
        repeat(4) {
            s.tick()
            t = t.plusMinutes(1)
            car = carWith(ownerTimer, 40, PlugState.PLUGGED)
        }
        assertEquals("restoring", s.describe()["status"])
        accept = true
        t = t.plusMinutes(5)
        s.tick()
        assertEquals(ownerTimer.normalized(), written.single())
        assertEquals("failed", s.describe()["status"])
    }

    @Test
    fun aPhoneChangeJustBeforeTheRestoreStays() = runBlocking {
        val s = rig()
        s.tick()
        // The car comes back unplugged with a timer a phone set — but its report is older than the write (cached),
        // so only the compare-and-swap at write time catches it.
        val mine = ChargeTimerProgram(listOf(ChargeWindow(LocalTime.of(21, 0), LocalTime.of(5, 0)), null, null), List(7) { 1 }, emptySet())
        val stale = carWith(mine, 40, PlugState.UNPLUGGED)
        car = stale.copy(registers = stale.registers.mapValues { (_, r) -> r.copy(updatedAtMs = 0) })
        t = t.plusHours(1)
        s.tick()
        assertEquals("nothing written over the phone's timer", 1, written.size)
        assertEquals("changed", s.describe()["status"])
    }
}
