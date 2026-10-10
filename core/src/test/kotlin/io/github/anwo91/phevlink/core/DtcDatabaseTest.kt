package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.obd.dtc.DtcDatabase
import io.github.anwo91.phevlink.core.obd.dtc.DtcExplanation
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity
import io.github.anwo91.phevlink.core.obd.dtc.DtcSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DtcDatabaseTest {

    private val resourceCodes: List<String> by lazy {
        val stream = DtcDatabase::class.java.getResourceAsStream("/io/github/anwo91/phevlink/core/obd/dtc/codes.tsv")
        assertNotNull("codes.tsv must be on the classpath", stream)
        stream!!.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toList()
        }
    }

    private fun assertComplete(e: DtcExplanation) {
        val c = e.code
        assertTrue("$c title", e.title.isNotBlank())
        assertTrue("$c summary", e.summary.isNotBlank())
        assertTrue("$c needs >= 2 causes: ${e.causes}", e.causes.size >= 2)
        assertTrue("$c needs >= 2 checks: ${e.checks}", e.checks.size >= 2)
        assertTrue("$c driveAdvice", e.driveAdvice.isNotBlank())
        assertTrue("$c blank cause", e.causes.none { it.isBlank() })
        assertTrue("$c blank check", e.checks.none { it.isBlank() })
        assertEquals("$c duplicate causes", e.causes.size, e.causes.toSet().size)
        assertEquals("$c duplicate checks", e.checks.size, e.checks.toSet().size)
    }

    @Test
    fun databaseLoads() {
        assertNull(DtcDatabase.loadError)
        assertTrue("size=${DtcDatabase.size}", DtcDatabase.size > 3000)
        assertEquals(resourceCodes.size, DtcDatabase.size)
    }

    @Test
    fun knownTitles() {
        assertEquals("Catalyst System Efficiency Below Threshold Bank 1", DtcDatabase.title("P0420"))
        assertEquals("Replace Hybrid Battery Pack", DtcDatabase.title("P0A80"))
        assertEquals("Lost Communication With ECM/PCM A", DtcDatabase.title("U0100"))
        assertNull(DtcDatabase.title("P1A23"))
        assertNull("reserved entries are not imported", DtcDatabase.title("P000E"))
    }

    @Test
    fun curatedEntries() {
        val expected = mapOf(
            "P0300" to DtcSeverity.CHECK_NOW,
            "P0A80" to DtcSeverity.CHECK_NOW,
            "P0AA6" to DtcSeverity.CHECK_NOW,
            "U0100" to DtcSeverity.CHECK_NOW,
            "P0420" to DtcSeverity.CHECK_SOON,
            "P0524" to DtcSeverity.STOP,
        )
        for ((code, severity) in expected) {
            val e = DtcDatabase.explain(code)
            assertTrue("$code curated", e.curated)
            assertTrue("$code known", e.known)
            assertFalse("$code generic", e.manufacturerSpecific)
            assertEquals("$code severity", severity, e.severity)
            assertEquals("$code title from database", DtcDatabase.title(code), e.title)
            assertNotNull(e.subsystem)
            assertComplete(e)
        }
        assertEquals(DtcSystem.HYBRID_EV, DtcDatabase.explain("P0A80").system)
        assertEquals(DtcSystem.IGNITION_MISFIRE, DtcDatabase.explain("P0300").system)
        assertEquals(DtcSystem.NETWORK, DtcDatabase.explain("U0100").system)
        // High-voltage entries always carry the safety note.
        for (code in listOf("P0A80", "P0AA6", "P0A94", "P0A0A")) {
            assertTrue(code, DtcDatabase.explain(code).checks.any { it.contains("high-voltage", ignoreCase = true) })
        }
        // Misfire advice covers the flashing check-engine light.
        assertTrue(DtcDatabase.explain("P0302").driveAdvice.contains("flashing"))
    }

    @Test
    fun everyCuratedCodeExistsInTsv() {
        val curated = DtcDatabase.curatedCodes
        assertTrue("curated count ${curated.size}", curated.size in 80..130)
        val inTsv = resourceCodes.toSet()
        for (code in curated) {
            assertTrue("curated $code missing from codes.tsv", code in inTsv)
            assertTrue(DtcDatabase.explain(code).curated)
        }
    }

    @Test
    fun unknownManufacturerCode() {
        val e = DtcDatabase.explain("P1A23")
        assertTrue(e.manufacturerSpecific)
        assertFalse(e.known)
        assertFalse(e.curated)
        assertEquals(DtcSystem.MANUFACTURER, e.system)
        assertTrue(e.title.contains("Manufacturer-specific"))
        assertComplete(e)

        assertEquals(DtcSystem.NETWORK, DtcDatabase.explain("U1100").system)
        assertEquals(DtcSystem.BODY, DtcDatabase.explain("B1234").system)
        assertEquals(DtcSystem.CHASSIS, DtcDatabase.explain("C2100").system)
        assertTrue(DtcDatabase.explain("U1100").manufacturerSpecific)

        // Generic range but not in the list: not manufacturer-specific, still explained.
        val unlisted = DtcDatabase.explain("P000E")
        assertFalse(unlisted.manufacturerSpecific)
        assertFalse(unlisted.known)
        assertComplete(unlisted)
    }

    @Test
    fun ruleBasedExplanations() {
        val ect = DtcDatabase.explain("P0115")
        assertFalse(ect.curated)
        assertTrue(ect.known)
        assertEquals(DtcSystem.POWERTRAIN_ENGINE, ect.system)

        val hvSensor = DtcDatabase.explain("P0A9D")
        assertFalse(hvSensor.curated)
        assertEquals(DtcSystem.HYBRID_EV, hvSensor.system)
        assertTrue(hvSensor.checks.first().contains("high-voltage"))
        assertTrue(hvSensor.severity >= DtcSeverity.CHECK_NOW)

        val airbag = DtcDatabase.explain("B0100")
        assertEquals(DtcSystem.BODY, airbag.system)
        assertEquals(DtcSeverity.CHECK_NOW, airbag.severity)
        assertTrue(airbag.checks.first().contains("airbag"))

        val abs = DtcDatabase.explain("C0035")
        assertEquals(DtcSystem.CHASSIS, abs.system)
        assertEquals(DtcSeverity.CHECK_NOW, abs.severity)

        assertEquals(DtcSystem.NETWORK, DtcDatabase.explain("U0102").system)
        assertTrue(DtcDatabase.explain("P0305").summary.contains("four cylinders"))
        assertTrue(DtcDatabase.explain("P0234").summary.contains("turbo"))
    }

    @Test
    fun explainNeverThrowsForEveryDatabaseCode() {
        for (code in resourceCodes) {
            val e = DtcDatabase.explain(code)
            assertEquals(code, e.code)
            assertTrue("$code known", e.known)
            assertComplete(e)
        }
    }

    @Test
    fun explainNeverThrowsForRandomValidCodes() {
        val rnd = Random(20261010)
        val letters = "PCBU"
        val hex = "0123456789ABCDEF"
        val codes = (1..20_000).map {
            buildString {
                append(letters[rnd.nextInt(4)])
                repeat(4) { append(hex[rnd.nextInt(16)]) }
            }
        } + listOf("P0000", "PFFFF", "U3FFF", "C0000", "B3FFF", "P4000", "U1FFF", "P3263", "P3400")
        for (code in codes) {
            assertTrue(DtcDatabase.isValid(code))
            val e = DtcDatabase.explain(code)
            assertEquals(code, e.code)
            assertComplete(e)
        }
    }

    @Test
    fun invalidSyntaxThrows() {
        for (bad in listOf("", "   ", "P", "P042", "P04200", "X0420", "P04G0", "P-0420", "0420", "P 0420")) {
            assertFalse("'$bad' should be invalid", DtcDatabase.isValid(bad))
            assertThrows(IllegalArgumentException::class.java) { DtcDatabase.explain(bad) }
        }
    }

    @Test
    fun normalisation() {
        assertTrue(DtcDatabase.isValid(" p0a80 "))
        assertEquals("P0A80", DtcDatabase.normalize(" p0a80 "))
        assertEquals("P0A80", DtcDatabase.explain(" p0a80 ").code)
        assertEquals(DtcDatabase.title("P0A80"), DtcDatabase.title(" p0a80 "))
        assertEquals(DtcDatabase.explain("P0A80"), DtcDatabase.explain("p0A80\n"))
    }

    @Test
    fun search() {
        val prefix = DtcDatabase.search("P0A8")
        assertTrue(prefix.isNotEmpty())
        val firstSix = prefix.take(6).map { it.first }
        assertTrue(firstSix.toString(), firstSix.all { it.startsWith("P0A8") })
        assertEquals("P0A80", DtcDatabase.search("p0a80").first().first)
        assertEquals("P0A80", DtcDatabase.search("P0A80").first().first)

        assertTrue(DtcDatabase.search("catalyst").any { it.first == "P0420" })
        assertTrue(DtcDatabase.search("CATALYST efficiency").any { it.first == "P0420" })
        assertEquals("curated codes rank first", "P0420", DtcDatabase.search("catalyst").first().first)

        assertTrue(DtcDatabase.search("catalyst", limit = 3).size <= 3)
        assertTrue(DtcDatabase.search("").isEmpty())
        assertTrue(DtcDatabase.search("   ").isEmpty())
        assertTrue(DtcDatabase.search("catalyst", limit = 0).isEmpty())

        // A valid code that is not in the list is still found, with its explanation title.
        val unknown = DtcDatabase.search("P1A23")
        assertEquals("P1A23", unknown.first().first)
        assertTrue(unknown.first().second.contains("Manufacturer-specific"))
    }
}
