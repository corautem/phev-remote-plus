package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.diag.LogKind
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.protocol.Packet
import io.github.anwo91.phevlink.core.protocol.PacketType
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.core.session.PhevSession
import io.github.anwo91.phevlink.core.session.SessionClock
import io.github.anwo91.phevlink.core.session.SessionConfig
import io.github.anwo91.phevlink.core.session.SessionListener
import io.github.anwo91.phevlink.core.session.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The protocol engine driven directly with bytes and a fake clock (no sockets). */
class SessionLogicTest {
    private class FakeClock : SessionClock {
        var now = 1_000_000L
        override fun monoMs() = now
        override fun wallMs() = now
    }

    private val log = DiagLog()
    private val clock = FakeClock()
    private val session = PhevSession(
        transmit = {}, listener = object : SessionListener {}, log = log, clock = clock,
        config = SessionConfig(syncClockOnConnect = false, initialRefreshDelayMs = Long.MAX_VALUE / 4),
    )

    private fun txCount() = log.snapshot().count { it.kind == LogKind.TX }
    private fun car(type: Int, ack: Int, register: Int, vararg data: Int) =
        session.onBytes(Packet(type, ack = ack, register = register, data = ByteArray(data.size) { data[it].toByte() }).encode(0))

    private fun startSession() {
        session.start()
        // A real MY14 start packet from a field log.
        session.onBytes("4e0c00013f5072b63f5072b602cb".hexToBytes())
        assertEquals(SessionPhase.READY, session.phase)
    }

    @Test
    fun encodingComplaintsDoNotTriggerAckResends() {
        startSession()
        car(PacketType.RESP, 0, 0x1d, 64, 0, 0, 0)
        car(PacketType.RESP, 0, 0x24, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val before = txCount()
        // Four different complaints in quick succession (the field log had dozens per second): we must not
        // answer them by resending acks, which only produced more complaints.
        for ((i, expected) in listOf(0xae, 0x5e, 0xa2, 0x61).withIndex()) {
            clock.now += 5
            car(PacketType.BAD_ENCODING, 1, 0x40 + i, expected)
        }
        assertEquals("no packets sent in reply to complaints", before, txCount())
        assertEquals(SessionPhase.READY, session.phase)
        // A fifth asks the car for a new key (the official app's recovery), still no ack resends.
        clock.now += 5
        car(PacketType.BAD_ENCODING, 1, 0x50, 0x93)
        assertEquals(before + 1, txCount())
        assertTrue(log.snapshot().last { it.kind == LogKind.TX }.text.contains("START"))
    }

    @Test
    fun pendingWriteIsResentAfterComplaint() {
        startSession()
        var result: CommandResult? = null
        session.execute(CarCommand.Headlights(true)) { result = it }
        val writes = { log.snapshot().count { it.kind == LogKind.TX && it.text.contains("headlights") } }
        assertEquals(1, writes())
        clock.now += 20
        car(PacketType.BAD_ENCODING, 1, 0x4f, 0x77)
        assertEquals("the write goes out again with the corrected key", 2, writes())
        // The car acks it: done.
        car(PacketType.RESP, 1, 0x0a, 0)
        assertNotNull(result)
        assertTrue("$result", result is CommandResult.Success)
    }
}
