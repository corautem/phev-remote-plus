package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.obd.dtc.DtcDatabase
import io.github.anwo91.phevlink.core.obd.dtc.DtcExplanation
import io.github.anwo91.phevlink.core.obd.dtc.DtcI18n
import io.github.anwo91.phevlink.core.obd.dtc.DtcRules
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Multilingual DTC texts: every language explains every code cleanly, and translations are complete. */
class DtcI18nTest {

    private val translated = listOf("nb", "sv", "da", "de")
    private val rawKey = Regex("""\b(?:txt|topic|cur|fail|unk|loc|net|note|word|area|notfitted|sev|sys)\.[a-z0-9_.]+""")
    private val slot = Regex("""\{(@?[A-Za-z0-9_.]+)\}""")

    /** Texts made only of codes, letters, numbers and abbreviations may be identical in every language. */
    private val languageNeutral = Regex("""[A-Z0-9 /().,#+-]*""")

    private val codes: List<String> by lazy {
        val stream = DtcDatabase::class.java.getResourceAsStream("/io/github/anwo91/phevlink/core/obd/dtc/codes.tsv")
        assertNotNull("codes.tsv must be on the classpath", stream)
        stream!!.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toList()
        }
    }

    private fun texts(e: DtcExplanation): List<String> =
        listOfNotNull(e.title, e.subsystem, e.summary, e.driveAdvice) + e.causes + e.checks

    private fun assertClean(lang: String, e: DtcExplanation) {
        val c = "$lang ${e.code}"
        assertTrue("$c title", e.title.isNotBlank())
        assertTrue("$c summary", e.summary.isNotBlank())
        assertTrue("$c driveAdvice", e.driveAdvice.isNotBlank())
        assertTrue("$c needs >= 2 causes: ${e.causes}", e.causes.size >= 2)
        assertTrue("$c needs >= 2 checks: ${e.checks}", e.checks.size >= 2)
        assertEquals("$c duplicate causes", e.causes.size, e.causes.toSet().size)
        assertEquals("$c duplicate checks", e.checks.size, e.checks.toSet().size)
        e.subsystem?.let { assertTrue("$c blank subsystem", it.isNotBlank()) }
        for (t in texts(e)) {
            assertTrue("$c blank text", t.isNotBlank())
            assertFalse("$c unfilled slot in: $t", t.contains('{') || t.contains('}'))
            assertNull("$c raw key in: $t", rawKey.find(t)?.value)
            assertFalse("$c reference leaked: $t", t.startsWith("@"))
            assertEquals("$c untrimmed: '$t'", t.trim(), t)
            assertFalse("$c double space in: $t", t.contains("  "))
        }
    }

    @Test
    fun supportedLanguages() {
        assertEquals(listOf("en", "nb", "sv", "da", "de"), DtcDatabase.languages)
    }

    @Test
    fun languageForMapsBcp47Tags() {
        val expected = mapOf(
            "nb" to "nb", "nn" to "nb", "no" to "nb", "nb-NO" to "nb", "nn-NO" to "nb", "no_NO" to "nb", " NB-no " to "nb",
            "sv" to "sv", "sv-SE" to "sv", "sv-FI" to "sv", "SV" to "sv",
            "da" to "da", "da-DK" to "da", "da_DK" to "da",
            "de" to "de", "de-DE" to "de", "de-AT" to "de", "de-CH" to "de", "de_DE.UTF-8" to "de",
            "en" to "en", "en-GB" to "en", "fr" to "en", "fi-FI" to "en", "" to "en", "x" to "en", "denmark" to "en",
        )
        for ((tag, lang) in expected) assertEquals("'$tag'", lang, DtcDatabase.languageFor(tag))
    }

    @Test
    fun everyDatabaseCodeExplainsCleanlyInEveryLanguage() {
        val extra = listOf("P1A23", "U1100", "B1234", "C2100", "P000E", "P3263", "U3FFF", "P0000", "B3FFF", "P4000")
        for (lang in DtcDatabase.languages) {
            for (code in codes + extra) {
                val e = DtcDatabase.explain(code, lang)
                assertEquals(code, e.code)
                assertClean(lang, e)
            }
        }
    }

    @Test
    fun randomValidCodesExplainCleanlyInEveryLanguage() {
        val rnd = Random(20261011)
        val hex = "0123456789ABCDEF"
        val sample = (1..1500).map { "PCBU"[rnd.nextInt(4)] + (1..4).map { hex[rnd.nextInt(16)] }.joinToString("") }
        for (lang in translated) {
            for (code in sample) assertClean(lang, DtcDatabase.explain(code, lang))
        }
    }

    @Test
    fun structureIsTheSameInEveryLanguage() {
        for (code in codes.filterIndexed { i, _ -> i % 7 == 0 } + DtcDatabase.curatedCodes) {
            val en = DtcDatabase.explain(code)
            for (lang in translated) {
                val tr = DtcDatabase.explain(code, lang)
                assertEquals("$lang $code severity", en.severity, tr.severity)
                assertEquals("$lang $code system", en.system, tr.system)
                assertEquals("$lang $code curated", en.curated, tr.curated)
                assertEquals("$lang $code known", en.known, tr.known)
                assertEquals("$lang $code manufacturer", en.manufacturerSpecific, tr.manufacturerSpecific)
                assertEquals("$lang $code subsystem presence", en.subsystem == null, tr.subsystem == null)
            }
        }
    }

    @Test
    fun titlesAvailableForAtLeast99PercentOfCodes() {
        val all = codes.toSet()
        for (lang in translated) {
            val local = DtcI18n.readTable("codes_$lang.tsv")
            assertTrue("$lang has codes not in codes.tsv", all.containsAll(local.keys))
            val covered = codes.count { !local[it].isNullOrBlank() }
            assertTrue("$lang titles $covered/${codes.size}", covered >= codes.size * 0.99)
            for (code in codes.filterIndexed { i, _ -> i % 50 == 0 }) {
                assertEquals("$lang $code", local[code] ?: DtcDatabase.title(code), DtcDatabase.title(code, lang))
            }
        }
        assertNull(DtcDatabase.title("P1A23", "de"))
    }

    @Test
    fun curatedEntriesAreFullyTranslated() {
        for (code in DtcDatabase.curatedCodes) {
            val en = DtcDatabase.explain(code)
            for (lang in translated) {
                val tr = DtcDatabase.explain(code, lang)
                assertTrue("$lang $code curated", tr.curated)
                assertEquals("$lang $code causes", en.causes.size, tr.causes.size)
                assertEquals("$lang $code checks", en.checks.size, tr.checks.size)
                val pairs = listOf(
                    en.title to tr.title,
                    en.subsystem.orEmpty() to tr.subsystem.orEmpty(),
                    en.summary to tr.summary,
                    en.driveAdvice to tr.driveAdvice,
                    en.severity.label to tr.severity.label(lang),
                    en.system.label to tr.system.label(lang),
                ) + en.causes.zip(tr.causes) + en.checks.zip(tr.checks)
                for ((a, b) in pairs) {
                    if (languageNeutral.matches(a) || a == "Information") continue
                    assertNotEquals("$lang $code not translated", a, b)
                }
            }
        }
    }

    @Test
    fun safetyNotesInEveryLanguage() {
        for (lang in DtcDatabase.languages) {
            val hv = DtcI18n.text(lang, "txt.hv_safety")
            val srs = DtcI18n.text(lang, "txt.srs_safety")
            assertTrue(hv.isNotBlank() && srs.isNotBlank())
            for (code in listOf("P0A80", "P0AA6", "P0A94", "P0A0A", "P0A9D", "P0A78")) {
                assertEquals("$lang $code HV safety note first", hv, DtcDatabase.explain(code, lang).checks.first())
            }
            for (code in listOf("B0001", "B0010", "B0100")) {
                assertTrue("$lang $code SRS safety note", DtcDatabase.explain(code, lang).checks.contains(srs))
            }
        }
        assertTrue(DtcI18n.text("nb", "txt.hv_safety").contains("høyspenning"))
        assertTrue(DtcI18n.text("de", "txt.hv_safety").contains("Hochvolt"))
    }

    @Test
    fun localInspectionTerms() {
        val adv = mapOf("nb" to "EU-kontroll", "sv" to "besiktning", "da" to "syn", "de" to "HU/AU")
        for ((lang, term) in adv) {
            assertTrue("$lang P0420", DtcDatabase.explain("P0420", lang).driveAdvice.contains(term))
        }
        assertTrue(DtcDatabase.explain("P0128", "nb").driveAdvice.startsWith("Ja"))
        assertTrue(DtcDatabase.explain("P0302", "de").driveAdvice.contains("Motorkontrollleuchte"))
    }

    @Test
    fun stringTablesMatchEnglishKeysAndSlots() {
        val en = DtcI18n.strings("en")
        assertTrue("strings_en.txt loaded", en.size > 1000)
        fun refs(v: String) = slot.findAll(v).map { it.groupValues[1] }.filter { it.startsWith("@") }.map { it.drop(1) } +
            listOfNotNull(v.takeIf { it.startsWith("@") && !it.contains(' ') }?.drop(1))
        for ((k, v) in en) for (r in refs(v)) assertTrue("en $k references unknown $r", en.containsKey(r))
        for (lang in translated) {
            val tr = DtcI18n.strings(lang)
            for ((k, v) in tr) {
                if (k.startsWith("sev.") || k.startsWith("sys.")) continue
                val ev = en[k]
                assertNotNull("$lang: key $k is not in strings_en.txt", ev)
                assertEquals("$lang $k slots", slot.findAll(ev!!).map { it.value }.toSet(), slot.findAll(v).map { it.value }.toSet())
            }
            val missing = en.filter { (k, v) -> !(v.startsWith("@") && !v.contains(' ')) && k !in tr }.keys
            assertTrue("$lang untranslated: ${missing.take(10)}", missing.isEmpty())
            assertTrue("$lang load problems: ${DtcI18n.loadProblems()}", DtcI18n.loadProblems().isEmpty())
        }
    }

    @Test
    fun severityAndSystemLabels() {
        for (lang in translated) {
            for (s in DtcSeverity.entries) {
                assertNotNull("$lang sev.${s.name}.label", DtcI18n.localOnly(lang, "sev.${s.name}.label"))
                assertNotNull("$lang sev.${s.name}.advice", DtcI18n.localOnly(lang, "sev.${s.name}.advice"))
                assertNotEquals("$lang ${s.name} advice", s.advice, s.advice(lang))
            }
            for (s in DtcSystem.entries) {
                assertNotNull("$lang sys.${s.name}.label", DtcI18n.localOnly(lang, "sys.${s.name}.label"))
                assertTrue(s.label(lang).isNotBlank())
            }
            assertNotEquals(DtcSeverity.STOP.label, DtcSeverity.STOP.label(lang))
            assertNotEquals(DtcSystem.HYBRID_EV.label, DtcSystem.HYBRID_EV.label(lang))
        }
        assertEquals("Stopp bilen", DtcSeverity.STOP.label("nb-NO"))
        for (s in DtcSeverity.entries) {
            assertEquals(s.label, s.label("en"))
            assertEquals(s.advice, s.advice("en"))
            assertEquals(s.label, s.label("fr"))
        }
        for (s in DtcSystem.entries) assertEquals(s.label, s.label("en-US"))
    }

    @Test
    fun componentNamesTranslated() {
        val phrases = codes.flatMap { DtcRules.componentPhrases(DtcDatabase.title(it)!!, it) }.toSet()
        assertTrue(phrases.size > 1000)
        for (lang in translated) {
            // Translations identical to English (e.g. "ABS", "radio") are not listed in the file.
            val covered = phrases.count { DtcI18n.hasComponent(lang, it) }
            assertTrue("$lang components $covered/${phrases.size}", covered >= phrases.size * 0.97)
        }
        assertEquals("lambdasonde", DtcI18n.component("nb", "oxygen sensor"))
        assertEquals("Lambdasonde", DtcI18n.component("de", "oxygen sensor"))
    }

    @Test
    fun localisedSearch() {
        assertTrue(DtcDatabase.search("lambdasonde", lang = "nb").any { it.first == "P0130" })
        assertTrue(DtcDatabase.search("katalysator", lang = "de").any { it.first == "P0420" })
        // English still matches when the app is in another language.
        assertTrue(DtcDatabase.search("catalyst", lang = "nb").any { it.first == "P0420" })
        val hit = DtcDatabase.search("p0a80", lang = "sv").first()
        assertEquals("P0A80", hit.first)
        assertEquals(DtcDatabase.title("P0A80", "sv"), hit.second)
        assertNotEquals(DtcDatabase.title("P0A80"), hit.second)
        assertEquals(DtcDatabase.search("P0A80"), DtcDatabase.search("P0A80", lang = "xx"))
        assertTrue(DtcDatabase.search("P1A23", lang = "da").first().second.isNotBlank())
    }

    @Test
    fun fallbacksNeverCrashOrLeakKeys() {
        assertEquals(DtcDatabase.explain("P0420", "nb"), DtcDatabase.explain("P0420", "nb-NO"))
        assertEquals(DtcDatabase.explain("P0420", "nb"), DtcDatabase.explain("P0420", "nn"))
        assertEquals(DtcDatabase.explain("P0420"), DtcDatabase.explain("P0420", "fr-FR"))
        assertEquals(DtcDatabase.title("P0420"), DtcDatabase.title("P0420", "es"))
        // A missing key renders as empty text, never as the key itself.
        assertEquals("", DtcI18n.text("nb", "no.such.key"))
        // A translation whose slot is not supplied falls back to English with the slot removed.
        val t = DtcI18n.text("nb", "cur.lost.sub")
        assertFalse(t, t.contains('{'))
        assertTrue(t, t.startsWith("Lost communication"))
        // An unknown component phrase is shown in English.
        assertEquals("flux capacitor", DtcI18n.component("de", "flux capacitor"))
        // Unknown language tags get English everywhere.
        assertEquals(DtcSeverity.CHECK_NOW.advice, DtcSeverity.CHECK_NOW.advice("pt-BR"))
    }
}
