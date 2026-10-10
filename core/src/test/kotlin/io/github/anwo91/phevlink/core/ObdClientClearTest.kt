package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.obd.ElmConfig
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.ObdLink
import io.github.anwo91.phevlink.core.obd.emulator.FakeElm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** [ObdClient.clearDtcs] must report adapter errors in its result, never throw them at the caller. */
class ObdClientClearTest {
    private val log = DiagLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** Passes everything to [fake], but answers `?` to `ATSH7DF` while [rejectBroadcastHeader] is set. */
    private class RejectingLink(private val fake: FakeElm) : ObdLink {
        @Volatile var rejectBroadcastHeader = false
        private val own = ConcurrentLinkedQueue<ByteArray>()
        override val description: String get() = "rejecting " + fake.description

        override suspend fun send(bytes: ByteArray) {
            val text = String(bytes, Charsets.ISO_8859_1).trim().replace(" ", "").uppercase(Locale.ROOT)
            if (rejectBroadcastHeader && text == "ATSH7DF") {
                own.add("?\r\r>".toByteArray(Charsets.ISO_8859_1))
                return
            }
            fake.send(bytes)
        }

        override suspend fun receive(timeoutMs: Long): ByteArray? = own.poll() ?: fake.receive(timeoutMs)

        override fun close() = fake.close()
    }

    @Test
    fun rejectedHeaderIsAFailedResultNotAnException() = runBlocking {
        withTimeout(15_000) {
            val link = RejectingLink(FakeElm())
            val c = ObdClient(link, log, scope, elmConfig = ElmConfig.fast())
            assertTrue(c.connect())
            // As after a terminal command: the driver forgets its cached header and must send ATSH7DF again.
            c.terminal("ATSH7E0")
            link.rejectBroadcastHeader = true
            val r = c.clearDtcs()
            assertFalse(r.success)
            assertTrue(r.message, r.message.contains("ATSH7DF"))
            c.disconnect()
        }
    }
}
