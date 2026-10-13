package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.session.LinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Off the car's Wi-Fi the bridge says why at once (field log: 5-second timeouts every 30 s, "timed out reaching the car"). */
class CarAwayTest {
    @Test
    fun saysTheCarIsAwayInsteadOfTimingOutAndConnectsWhenBack() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val car = FakeCar().start()
        val stateFile = File.createTempFile("bridge", ".json").apply { delete() }
        val config = BridgeConfig(carHost = "127.0.0.1", listenHost = "127.0.0.1", token = "t", mode = LinkMode.PERSISTENT, stateFile = stateFile.path)
        var away = true
        var asked = 0
        val svc = BridgeService(config, DiagLog(), scope, car.port, null) {
            asked++
            if (away) "The bridge isn't on the car's Wi-Fi — the car is away, or its Wi-Fi is asleep" else null
        }
        try {
            svc.connection.start()
            val st = withTimeout(5_000) { svc.connection.status.first { it is LinkStatus.Retrying } } as LinkStatus.Retrying
            assertEquals("The bridge isn't on the car's Wi-Fi — the car is away, or its Wi-Fi is asleep", st.lastError)
            assertTrue(asked >= 1)
            away = false
            svc.connection.reconnectNow()
            withTimeout(20_000) { svc.connection.status.first { it is LinkStatus.Connected } }
        } finally {
            svc.connection.stop("test", terminal = true)
            car.stop(); scope.cancel(); stateFile.delete()
        }
    }
}
