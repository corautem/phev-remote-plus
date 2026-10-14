package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.PlugState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class RemindersTest {
    private val log = DiagLog()
    private val zone = ZoneId.of("Europe/Oslo")
    private var t = ZonedDateTime.of(2026, 10, 14, 20, 50, 0, 0, zone)
    private var car = CarState(batteryPercent = 18, plug = PlugState.UNPLUGGED)
    private var home: Boolean? = true
    private val raised = ArrayList<Alert>()

    private fun rig(): Reminders {
        val store = SharedStore(File(Files.createTempDirectory("shared").toFile(), "shared.json"), log)
        store.update(mapOf("timeZone" to "Europe/Oslo"), "test")
        return Reminders(store, { car }, { home }, { raised += it }, log, now = { t })
    }

    @Test
    fun remindsOnceAtNineWhenHomeLowAndNotPluggedIn() {
        val r = rig()
        r.tick()
        assertTrue("not before 21:00", raised.isEmpty())
        t = t.withHour(21).withMinute(0)
        r.tick()
        assertEquals(listOf(AlertType.PLUG_IN_REMINDER), raised.map { it.type })
        assertEquals("Battery 18 %, not plugged in.", raised.single().message)
        t = t.plusMinutes(5)
        r.tick()
        assertEquals("once a day", 1, raised.size)
    }

    @Test
    fun saysNothingWhenPluggedInHighOrAway() {
        val r1 = rig()
        car = car.copy(plug = PlugState.PLUGGED)
        t = t.withHour(21).withMinute(1)
        r1.tick()
        car = CarState(batteryPercent = 95, plug = PlugState.UNPLUGGED)
        rig().tick()
        car = CarState(batteryPercent = 18, plug = PlugState.UNPLUGGED)
        home = false
        rig().tick()
        assertTrue(raised.isEmpty())
        val text = log.dump()
        assertTrue(text, text.contains("already plugged in") && text.contains("not below 80 %") && text.contains("isn't within reach"))
    }

    @Test
    fun aSharedTimeAndLevelAreUsed() {
        val store = SharedStore(File(Files.createTempDirectory("shared").toFile(), "shared.json"), log)
        store.update(mapOf("timeZone" to "Europe/Oslo", "plugReminderAt" to "22:30", "plugReminderBelow" to 20), "test")
        val r = Reminders(store, { car }, { home }, { raised += it }, log, now = { t })
        t = t.withHour(21).withMinute(0)
        r.tick()
        assertTrue(raised.isEmpty())
        t = t.withHour(22).withMinute(31)
        r.tick()
        assertEquals(1, raised.size)
        store.update(mapOf("plugReminder" to false), "test")
        assertEquals(false, store.plugReminder().enabled)
    }
}
