package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.knowledge.Beep
import io.github.anwo91.phevlink.core.knowledge.Tip
import io.github.anwo91.phevlink.core.knowledge.TipBook
import io.github.anwo91.phevlink.core.knowledge.Tips
import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
import io.github.anwo91.phevlink.core.obd.livetest.LiveTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tips knowledge base: English source and the nb/sv/da/de translations must have the same structure. */
class TipsTest {
    private val en: TipBook = Tips.load("en")
    private val translations = Tips.LANGUAGES.drop(1)

    /** Translated texts that may legitimately equal the English (none at the moment), as "lang:id.field". */
    private val identicalByNature = emptySet<String>()

    @Test
    fun englishBookIsComplete() {
        assertEquals(26, en.tips.size)
        assertEquals(24, en.beeps.size)
        assertEquals("tip ids unique", en.tips.size, en.tips.map { it.id }.toSet().size)
        assertEquals("beep ids unique", en.beeps.size, en.beeps.map { it.id }.toSet().size)
        val testIds = LiveTests.all.map { it.id }.toSet()
        for (t in en.tips) {
            for (id in t.relatedTests) assertTrue("${t.id}: unknown test $id", id in testIds)
            for (id in t.relatedSettings) assertNotNull("${t.id}: unknown setting $id", VehicleSettingsCatalog.def(id))
            assertTrue("${t.id}: diy ${t.diy}", t.diy in setOf("yes", "partly", "no"))
            assertTrue("${t.id}: confidence ${t.confidence}", t.confidence in setOf("high", "medium", "low"))
        }
        for (b in en.beeps) {
            for (id in b.settingIds) assertNotNull("${b.id}: unknown setting $id", VehicleSettingsCatalog.def(id))
            assertTrue("${b.id}: adjust ${b.adjust}", b.adjust in setOf("app", "car-menu", "switch", "dealer", "no"))
            assertTrue("${b.id}: confidence ${b.confidence}", b.confidence in setOf("high", "medium", "low"))
        }
    }

    @Test
    fun everyLanguageHasItsOwnFile() {
        for (lang in Tips.LANGUAGES) {
            assertNotNull("tips_$lang.json missing", Tips::class.java.getResource("/io/github/anwo91/phevlink/core/knowledge/tips_$lang.json"))
        }
    }

    @Test
    fun translationsHaveSameStructure() {
        for (lang in Tips.LANGUAGES) {
            val book = Tips.load(lang)
            assertEquals("$lang tips", 26, book.tips.size)
            assertEquals("$lang beeps", 24, book.beeps.size)
            assertEquals("$lang tip ids", en.tips.map { it.id }, book.tips.map { it.id })
            assertEquals("$lang beep ids", en.beeps.map { it.id }, book.beeps.map { it.id })
            for ((e, t) in en.tips.zip(book.tips)) {
                val w = "$lang:${e.id}"
                assertEquals("$w relatedTests", e.relatedTests, t.relatedTests)
                assertEquals("$w relatedCodes", e.relatedCodes, t.relatedCodes)
                assertEquals("$w relatedSettings", e.relatedSettings, t.relatedSettings)
                assertEquals("$w diy", e.diy, t.diy)
                assertEquals("$w confidence", e.confidence, t.confidence)
                assertEquals("$w sources", e.sources, t.sources)
                assertEquals("$w symptoms count", e.symptoms.size, t.symptoms.size)
                assertEquals("$w whatToDo count", e.whatToDo.size, t.whatToDo.size)
            }
            for ((e, b) in en.beeps.zip(book.beeps)) {
                val w = "$lang:${e.id}"
                assertEquals("$w adjust", e.adjust, b.adjust)
                assertEquals("$w settingIds", e.settingIds, b.settingIds)
                assertEquals("$w confidence", e.confidence, b.confidence)
                assertEquals("$w sources", e.sources, b.sources)
            }
        }
    }

    @Test
    fun textsAreNotEmpty() {
        for (lang in Tips.LANGUAGES) {
            val book = Tips.load(lang)
            for ((e, t) in en.tips.zip(book.tips)) {
                val w = "$lang:${t.id}"
                for ((field, value) in tipTexts(t)) assertTrue("$w.$field empty", value.isNotBlank())
                assertTrue("$w symptoms", t.symptoms.isNotEmpty() && t.symptoms.all { it.isNotBlank() })
                assertTrue("$w whatToDo", t.whatToDo.isNotEmpty() && t.whatToDo.all { it.isNotBlank() })
                assertEquals("$w appHelp present", e.appHelp.isNotBlank(), t.appHelp.isNotBlank())
            }
            for ((e, b) in en.beeps.zip(book.beeps)) {
                val w = "$lang:${b.id}"
                for ((field, value) in beepTexts(b)) assertTrue("$w.$field empty", value.isNotBlank())
                assertEquals("$w notes present", e.notes.isNotBlank(), b.notes.isNotBlank())
            }
        }
    }

    @Test
    fun textsAreTranslated() {
        for (lang in translations) {
            val book = Tips.load(lang)
            for ((e, t) in en.tips.zip(book.tips)) {
                for (field in listOf("title", "why")) {
                    val key = "$lang:${t.id}.$field"
                    if (key in identicalByNature) continue
                    assertNotEquals("$key not translated", tipTexts(e).getValue(field), tipTexts(t).getValue(field))
                }
            }
            for ((e, b) in en.beeps.zip(book.beeps)) {
                for (field in listOf("sound", "how")) {
                    val key = "$lang:${b.id}.$field"
                    if (key in identicalByNature) continue
                    assertNotEquals("$key not translated", beepTexts(e).getValue(field), beepTexts(b).getValue(field))
                }
            }
        }
    }

    @Test
    fun categoriesAreTranslatedConsistently() {
        for (lang in translations) {
            val book = Tips.load(lang)
            val map = HashMap<String, String>()
            for ((e, t) in en.tips.zip(book.tips)) {
                val previous = map.putIfAbsent(e.category, t.category)
                assertTrue("$lang: '${e.category}' translated as both '$previous' and '${t.category}'", previous == null || previous == t.category)
            }
            assertEquals("$lang: two English categories share one translation: $map", map.size, map.values.toSet().size)
            assertEquals("$lang categories", en.categories.size, book.categories.size)
        }
    }

    @Test
    fun unknownLanguageFallsBackToEnglish() {
        assertEquals(en.tips.map { it.title }, Tips.load("fr").tips.map { it.title })
    }

    private fun tipTexts(t: Tip) = mapOf("title" to t.title, "category" to t.category, "why" to t.why)

    private fun beepTexts(b: Beep) = mapOf("sound" to b.sound, "when" to b.whenText, "how" to b.how)
}
