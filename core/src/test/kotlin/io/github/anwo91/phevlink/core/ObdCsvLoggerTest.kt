package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.obd.Confidence
import io.github.anwo91.phevlink.core.obd.ObdCsvLogger
import io.github.anwo91.phevlink.core.obd.ObdState
import io.github.anwo91.phevlink.core.obd.ObdValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId

class ObdCsvLoggerTest {
    private val utc = ZoneId.of("UTC")
    private val t0 = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()

    private fun v(id: String, value: Double?, text: String? = null) = ObdValue(id, id, "g", value, text, "", Confidence.VERIFIED, t0)

    private val s1 = ObdState(values = mapOf("obd.0C" to v("obd.0C", 1726.0), "bmu.soc" to v("bmu.soc", 60.0)), pollCycle = 1, lastPollMs = t0)
    private val s2 = s1.copy(
        values = s1.values + mapOf(
            "obd.05" to v("obd.05", 83.25),
            "x.raw" to v("x.raw", null, "01 02"),
            "ecm.idle" to v("ecm.idle", 1.0, "on"),
            "x.third" to v("x.third", 1.0 / 3.0),
            "x.neg" to v("x.neg", -0.00001),
            "x.text" to v("x.text", null, "a,\"b\""),
        ),
        pollCycle = 2,
        lastPollMs = t0 + 1_500,
    )

    @Test
    fun inMemoryCsv() {
        val logger = ObdCsvLogger(zone = utc)
        assertTrue(logger.add(s1))
        assertFalse(logger.add(s1))
        assertTrue(logger.add(s2))
        assertEquals(2, logger.rowCount)
        val expected = listOf(
            "time,bmu.soc,obd.0C,ecm.idle,obd.05,x.neg,x.raw,x.text,x.third",
            "2026-10-10T12:00:00.000,60,1726,,,,,,",
            "2026-10-10T12:00:01.500,60,1726,1,83.25,0,01 02,\"a,\"\"b\"\"\",0.3333",
        ).joinToString("\n", postfix = "\n")
        assertEquals(expected, logger.csv())
        val w = StringWriter()
        logger.writeTo(w)
        assertEquals(expected, w.toString())
    }

    @Test
    fun streamingWritesANewHeaderWhenColumnsGrow() {
        val logger = ObdCsvLogger(zone = utc)
        val w = StringWriter()
        assertTrue(logger.append(w, s1))
        assertFalse(logger.append(w, s1))
        assertTrue(logger.append(w, s2.copy(values = s1.values + ("obd.05" to v("obd.05", 83.0)))))
        assertEquals(
            "time,bmu.soc,obd.0C\n" +
                "2026-10-10T12:00:00.000,60,1726\n" +
                "time,bmu.soc,obd.0C,obd.05\n" +
                "2026-10-10T12:00:01.500,60,1726,83\n",
            w.toString(),
        )
    }

    @Test
    fun fixedColumnsKeepTheHeaderStable() {
        val logger = ObdCsvLogger(listOf("obd.0C", "obd.05"), utc)
        val w = StringWriter()
        logger.append(w, s1)
        logger.append(w, s2)
        assertEquals(
            "time,obd.0C,obd.05\n2026-10-10T12:00:00.000,1726,\n2026-10-10T12:00:01.500,1726,83.25\n",
            w.toString(),
        )
        assertEquals(listOf("obd.0C", "obd.05"), logger.columns)
    }

    @Test
    fun emptyStatesAreSkippedAndRowsAreCapped() {
        val logger = ObdCsvLogger(zone = utc, maxRows = 2)
        assertFalse(logger.add(ObdState(pollCycle = 1)))
        for (i in 1..5) logger.add(s1.copy(pollCycle = i.toLong(), lastPollMs = t0 + i))
        assertEquals(2, logger.rowCount)
        assertTrue(logger.csv().lines()[1].startsWith("2026-10-10T12:00:00.004"))
    }
}
