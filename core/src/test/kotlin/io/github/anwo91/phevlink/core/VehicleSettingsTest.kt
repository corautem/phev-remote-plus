package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarSetting
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.RegisterDecoder
import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.core.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VehicleSettingsTest {
    private val cat = VehicleSettingsCatalog

    private fun decode(vararg hex: String, start: CarState = CarState()): CarState =
        hex.foldIndexed(start) { i, s, h -> RegisterDecoder.apply(s, 0x16, h.hexToBytes(), 100L + i) }

    @Test
    fun catalogueIsConsistent() {
        val grouped = cat.groups.flatMap { it.second }
        assertEquals("each id in one group", grouped.size, grouped.toSet().size)
        for (d in cat.all) {
            assertEquals("option count of ${d.id}", cat.optionCount(d.id), d.options.size)
            assertTrue("${d.id} grouped", d.id in grouped)
            assertEquals(cat.groups.first { d.id in it.second }.first, d.group)
            assertTrue(d.name.isNotBlank() && d.options.all { it.isNotBlank() })
        }
        for (id in grouped) assertTrue("$id has a def", cat.def(id) != null)
        for (id in cat.HIDDEN_IDS) assertNull(cat.def(id))
        assertEquals(41, cat.all.size)
    }

    @Test
    fun decodesHandComputedNotification() {
        // 02 | 07 3e | 8b 0b | 00 00 | 00
        // pair 1: A=0x07 -> id 7, value low bits 0; B=0x3e -> mask 31, value high bit 0 -> value 0: accepted.
        // pair 2: A=0x8b -> id 11, value low bits 2; B=0x0b -> mask 0b101, high bit 1 -> value 6,
        //         but option 6 is not in the mask -> rejected (like the official app).
        // pair 3: padding.
        val s = decode("02073e8b0b000000")
        assertEquals(mapOf(7 to CarSetting(31, 0)), s.settings)
        assertEquals(2, s.settingsStatus)
        assertTrue(cat.available(7, s.settings[7]))
        assertEquals("Off", cat.optionLabel(7, 0, s.settings[7]))

        // The same id 11 with every option offered: A=0x8b, B=0xff -> mask 127, value 6 ("3 minutes").
        val s2 = decode("028bff")
        assertEquals(mapOf(11 to CarSetting(127, 6)), s2.settings)
        assertEquals("3 minutes", cat.optionLabel(11, 6, s2.settings[11]))
    }

    @Test
    fun encodeDecodeRoundTripsAllDefaults() {
        val payloads = cat.encodeNotifications(FakeCar.DEFAULT_SETTINGS.toList())
        assertEquals(15, payloads.size)
        for (p in payloads) {
            assertEquals(8, p.size)
            assertEquals(2, p[0].toInt())
            assertEquals(0, p[7].toInt())
        }
        val s = decode(*payloads.map { it.toHex() }.toTypedArray())
        assertEquals(FakeCar.DEFAULT_SETTINGS, s.settings)
        // Formula check on one pair: id 12, mask 63, value 5 -> A = 12 | (1 << 6), B = (63 << 1) | 1
        assertEquals(0x4c to 0x7f, cat.encodePair(12, CarSetting(63, 5)))
        assertEquals(12 to CarSetting(63, 5), cat.decodePair(0x4c, 0x7f))
        // ...and value 5 with a mask that lacks option 5 is rejected.
        assertNull(cat.decodePair(0x4c, 0x1f))
    }

    @Test
    fun accumulatesAcrossNotifications() {
        var s = decode("02073e8bff000000")
        assertEquals(setOf(7, 11), s.settings.keys)
        // A=0x47: id 7 value 1; A=0x0d B=0x1e: id 13 mask 15 value 0
        s = decode("02473e0d1e000000", start = s)
        assertEquals(mapOf(7 to CarSetting(31, 1), 11 to CarSetting(127, 6), 13 to CarSetting(15, 0)), s.settings)
        // A notification touching other registers leaves the settings alone.
        s = RegisterDecoder.apply(s, 0x1d, "48000100".hexToBytes(), 200)
        assertEquals(3, s.settings.size)
    }

    @Test
    fun rejectsInvalidPairs() {
        // id 61 (A=0x3d) is out of range; id 0 is padding.
        assertNull(cat.decodePair(0x3d, 0x02))
        assertNull(cat.decodePair(0x00, 0x02))
        // value 7 is accepted even if not in the mask (but not shown): A=0xc5 B=0x0f -> id 5, mask 7, value 7
        assertEquals(5 to CarSetting(7, 7), cat.decodePair(0xc5, 0x0f))
        assertFalse(cat.available(5, CarSetting(7, 7)))
        // mask 0 is accepted (not equipped), but not shown
        assertEquals(23 to CarSetting(0, 0), cat.decodePair(0x17, 0x00))
        // too short: ignored entirely
        val s = decode("0207")
        assertTrue(s.settings.isEmpty())
        assertNull(s.settingsStatus)
        // partial second pair: only the first is used
        val p = decode("01073e8b")
        assertEquals(mapOf(7 to CarSetting(31, 0)), p.settings)
        assertEquals(1, p.settingsStatus)
        assertFalse(cat.statusAllowsChanges(1))
        assertFalse(cat.statusAllowsChanges(3))
        assertTrue(cat.statusAllowsChanges(2))
    }

    @Test
    fun availabilityRules() {
        assertTrue(cat.available(7, CarSetting(31, 0)))
        assertFalse("mask wider than 5 options", cat.available(7, CarSetting(63, 0)))
        assertFalse("not equipped", cat.available(7, CarSetting(0, 0)))
        assertFalse("value out of range", cat.available(7, CarSetting(31, 5)))
        assertFalse("hidden id", cat.available(23, CarSetting(3, 0)))
        assertFalse("unused id", cat.available(18, CarSetting(1, 0)))
        assertFalse(cat.available(7, null))
        assertEquals(listOf(0, 1, 2), cat.availableOptions(4, CarSetting(0b0111, 2)))
        assertEquals(listOf(1, 3), cat.availableOptions(4, CarSetting(0b1010, 1)))
        assertEquals(emptyList<Int>(), cat.availableOptions(4, CarSetting(0b10000, 1)))
    }

    @Test
    fun disambiguatesRepeatedLabels() {
        val all = CarSetting(63, 2)
        assertEquals(
            listOf("Off", "Tail Lamp/Head Lamp: Cutout always", "On (type 1)", "On (type 2)",
                "Tail Lamp: Cutout once / Head Lamp: Cutout always", "On (type 3)"),
            (0..5).map { cat.optionLabel(12, it, all) },
        )
        // Only one "On" offered: no suffix needed.
        assertEquals("On", cat.optionLabel(12, 2, CarSetting(0b000101, 2)))
        // Suffixes are stable: option 3 is always "type 2".
        val some = CarSetting(0b101001, 3)
        assertEquals(listOf("Off", "On (type 2)", "On (type 3)"), cat.availableOptions(12, some).map { cat.optionLabel(12, it, some) })
        assertEquals("On (type 2)", cat.optionLabel(12, 3))
        assertEquals("Option 10", cat.optionLabel(7, 9))
        assertEquals("Charging Lid Light Auto cut-out: 1 minute", cat.describe(7, 1))
        assertEquals("setting #99 = 1", cat.describe(99, 1))
        assertEquals("Set Headlight Auto-cutout Function → On (type 2)", CarCommand.ChangeSetting(12, 3).label)
    }

    @Test
    fun visibleGroupsFollowDisplayOrder() {
        val groups = cat.visibleGroups(FakeCar.DEFAULT_SETTINGS)
        assertEquals(cat.groups.map { it.first }, groups.map { it.title })
        assertEquals(listOf(3, 20, 28, 26, 21, 32, 19), groups.first().items.map { it.id })
        val shown = groups.flatMap { g -> g.items.map { it.id } }.toSet()
        for (id in listOf(8, 23, 25, 27, 33, 41)) assertFalse("$id hidden", id in shown)
        assertEquals(listOf(36), groups.last().items.map { it.id })
        val wipers = groups.first { it.title == "Wipers" }.items.first { it.id == 4 }
        assertEquals(2, wipers.value)
        assertEquals("Adjustable & Speed Sensitive", wipers.currentLabel)
        assertEquals(listOf(0 to "Fixed (4 seconds)", 1 to "Adjustable", 2 to "Adjustable & Speed Sensitive"), wipers.options)
        val lights = groups.first { it.title == "Lights" }.items.first { it.id == 12 }
        assertEquals("On (type 1)", lights.currentLabel)

        val few = cat.visibleGroups(mapOf(50 to CarSetting(3, 1), 7 to CarSetting(31, 0), 23 to CarSetting(3, 0)))
        assertEquals(listOf("Lights", "Air conditioning"), few.map { it.title })
        assertTrue(cat.visibleGroups(emptyMap()).isEmpty())
    }

    @Test
    fun changeSettingWrites() {
        for (my in listOf(ModelYear.MY14, ModelYear.MY18)) {
            val w = CarCommand.ChangeSetting(7, 2).toWrites(my, CarState())
            assertEquals(listOf(0x0f, 0x0e), w.map { it.register })
            assertEquals(listOf("0702", "00"), w.map { it.data.toHex() })
            val confirm = w[0].awaitState!!
            assertFalse(confirm(CarState(settings = mapOf(7 to CarSetting(31, 1)))))
            assertTrue(confirm(CarState(settings = mapOf(7 to CarSetting(31, 2)))))
            assertEquals(CarCommand.ChangeSetting.UNCONFIRMED, w[0].unconfirmedMessage)
            assertEquals(8_000L, w[0].awaitMs)
            assertNull(w[1].awaitState)
        }
        for ((id, v) in listOf(0 to 0, 61 to 0, 7 to 8, 7 to -1)) {
            try {
                CarCommand.ChangeSetting(id, v); fail("accepted $id=$v")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun resetSettingsWaitsForAnyNewNotification() {
        val st0 = CarState()
        val w = CarCommand.ResetSettings.toWrites(ModelYear.MY14, st0)
        assertEquals(listOf(0x0f, 0x0e), w.map { it.register })
        assertEquals(listOf("fe00", "00"), w.map { it.data.toHex() })
        assertNull(w[0].unconfirmedMessage)
        val done = w[0].awaitState!!
        assertFalse(done(st0))
        assertTrue(done(decode("02073e000000000000")))

        // With a 0x16 already known, even an identical repeat (same bytes, same time) counts...
        val known = decode("02073e000000000000")
        val w2 = CarCommand.ResetSettings.toWrites(ModelYear.MY18, known)[0].awaitState!!
        assertFalse(w2(known))
        assertFalse("other registers don't count", w2(RegisterDecoder.apply(known, 0x1d, "48000100".hexToBytes(), 100)))
        assertTrue(w2(RegisterDecoder.apply(known, 0x16, "02073e000000000000".hexToBytes(), 100)))
    }

    @Test
    fun codecsRoundTrip() {
        val st = decode(*cat.encodeNotifications(FakeCar.DEFAULT_SETTINGS.toList()).map { it.toHex() }.toTypedArray())
        val map = Codecs.carStateToMap(st)
        val back = Codecs.carStateFromMap(Json.parseObject(Json.write(map)))
        assertEquals(st, back)
        assertTrue(Json.write(map).contains("\"7\":{\"mask\":31,\"value\":1}"))
        // Old snapshots without the keys still load.
        val old = Codecs.carStateFromMap(mapOf("batteryPercent" to 50L))
        assertTrue(old.settings.isEmpty())
        assertNull(old.settingsStatus)

        for (c in listOf(CarCommand.ChangeSetting(12, 3), CarCommand.ResetSettings)) {
            assertEquals(c, Codecs.commandFromMap(Json.parseObject(Json.write(Codecs.commandToMap(c)))))
        }
        assertEquals(CarCommand.ChangeSetting(7, 2), Codecs.commandFromMap(mapOf("action" to "setting", "id" to "7", "value" to 2L)))
        try {
            Codecs.commandFromMap(mapOf("action" to "setting", "id" to 99L, "value" to 0L)); fail()
        } catch (_: IllegalArgumentException) {
        }

        val list = Codecs.visibleSettingsToList(mapOf(12 to CarSetting(0b1111, 3)))
        assertEquals(
            """[{"title":"Lights","items":[{"id":12,"name":"Headlight Auto-cutout Function","value":3,"valueLabel":"On (type 2)",""" +
                """"options":[{"value":0,"label":"Off"},{"value":1,"label":"Tail Lamp/Head Lamp: Cutout always"},""" +
                """{"value":2,"label":"On (type 1)"},{"value":3,"label":"On (type 2)"}]}]}]""",
            Json.write(list),
        )
    }
}
