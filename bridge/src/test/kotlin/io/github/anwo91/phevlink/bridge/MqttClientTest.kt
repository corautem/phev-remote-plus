package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.CopyOnWriteArrayList

class MqttClientTest {
    private fun opts(port: Int, user: String? = "phev", pass: String? = "s3cret") = MqttClient.Options(
        host = "127.0.0.1", port = port, clientId = "test", username = user, password = pass, keepAliveSec = 5,
        will = MqttClient.Will("phevlink/x/availability", "offline"),
    )

    @Test
    fun logsInPublishesSubscribesAndReceives() {
        FakeBroker("phev", "s3cret").use { b ->
            val s = MqttClient.connect(opts(b.port))
            val got = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
            s.onMessage = { t, p, r -> got += Triple(t, p, r) }
            s.start()
            s.publish("phevlink/x/state", """{"battery":64}""", retain = true)
            s.subscribe(listOf("phevlink/x/cmd/+"))
            assertTrue(b.waitFor { b.retained["phevlink/x/state"] == """{"battery":64}""" })
            Thread.sleep(100)
            b.publish("phevlink/x/cmd/climate", "ON")
            b.publish("phevlink/x/other", "no")
            assertTrue(b.waitFor { got.isNotEmpty() })
            Thread.sleep(100)
            assertEquals(listOf(Triple("phevlink/x/cmd/climate", "ON", false)), got.toList())
            s.close()
            Thread.sleep(100)
            assertFalse("a clean goodbye sends no last will", b.published.any { it.payload == "offline" })
        }
    }

    @Test
    fun wrongPasswordIsSaidPlainly() {
        FakeBroker("phev", "s3cret").use { b ->
            try {
                MqttClient.connect(opts(b.port, pass = "nope"))
                fail("should be refused")
            } catch (e: MqttClient.Refused) {
                assertEquals(4, e.code)
                assertEquals("wrong user name or password", e.message)
            }
        }
    }

    @Test
    fun dropRunsOnClosedAndTheBrokerSendsTheLastWill() {
        FakeBroker().use { b ->
            val s = MqttClient.connect(opts(b.port, null, null))
            var why: String? = null
            s.onClosed = { why = it }
            s.start()
            b.dropClients()
            assertTrue(b.waitFor { why != null })
            assertTrue(b.waitFor { b.retained["phevlink/x/availability"] == "offline" })
        }
    }

    @Test
    fun longPacketsRoundTrip() {
        val payload = "x".repeat(20_000)
        val bytes = MqttClient.Packets.publish("a/b", payload.toByteArray(), retain = true)
        val raw = MqttClient.Packets.read(DataInputStream(ByteArrayInputStream(bytes)))
        val p = MqttClient.Packets.parsePublish(raw.flags, raw.body)
        assertEquals("a/b", p.topic)
        assertEquals(payload, p.payload)
        assertTrue(p.retain)
    }

    @Test
    fun linkReconnectsAfterADrop() {
        FakeBroker().use { b ->
            val link = MqttLink(DiagLog())
            var announced = 0
            link.onConnected = { announced++ }
            link.start(opts(b.port, null, null))
            assertEquals(MqttLink.Phase.CONNECTED, link.awaitOutcome(5_000).phase)
            b.dropClients()
            assertTrue("reconnects after 2 s", b.waitFor(8_000) { b.connects >= 2 && link.connected })
            assertTrue(b.waitFor { announced >= 2 })
            link.stop("test")
            assertEquals(MqttLink.Phase.OFF, link.status.phase)
        }
    }

    @Test
    fun linkSaysWhyItCantConnect() {
        val link = MqttLink(DiagLog())
        val port = java.net.ServerSocket(0).use { it.localPort } // nothing listens there now
        link.start(opts(port, null, null))
        val st = link.awaitOutcome(5_000)
        assertEquals(MqttLink.Phase.ERROR, st.phase)
        assertTrue(st.error, st.error!!.contains("nothing answers"))
        link.stop("test")
    }
}
