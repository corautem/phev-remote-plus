package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PairingTest {
    private val dir: File = Files.createTempDirectory("pairing").toFile()
    private val log = DiagLog()
    private var uptime: Double? = 60.0
    private var boot: String? = "boot-1"

    private fun pairing(minutes: Int = 10) = Pairing(minutes, dir, log, { uptime }, { boot })

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun openOnlySoonAfterPowerOn() {
        val p = pairing()
        assertTrue(p.window().open)
        assertEquals(540, p.window().secondsLeft)
        uptime = 601.0
        assertEquals(Pairing.Window(false, 0, "expired"), p.window())
    }

    @Test
    fun oncePerBootEvenAcrossRestarts() {
        assertTrue(pairing().claim("phone A"))
        assertFalse("second claim in the same boot", pairing().claim("phone B"))
        // A new process (the bridge restarted itself) within the window: still closed.
        assertEquals("used", pairing().window().reason)
        // Power-cycled: new boot id, fresh window.
        boot = "boot-2"; uptime = 30.0
        assertTrue(pairing().window().open)
        assertTrue(pairing().claim("phone B"))
    }

    @Test
    fun closedWhenDisabledOrUnknown() {
        assertEquals("disabled", pairing(minutes = 0).window().reason)
        uptime = null
        assertEquals("unknown", pairing().window().reason)
        uptime = 1.0; boot = null
        assertFalse(pairing().claim("x"))
    }

    @Test
    fun staysClosedInThisProcessWhenTheMarkerCantBeWritten() {
        val p = Pairing(10, File("/proc/definitely/not/writable"), log, { uptime }, { boot })
        assertTrue(p.claim("phone A"))
        assertFalse(p.claim("phone B"))
    }
}
