package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.emulator.FakeCar
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.core.session.ConnectionConfig
import io.github.anwo91.phevlink.core.session.PhevConnection
import io.github.anwo91.phevlink.core.session.SocketConnector
import io.github.anwo91.phevlink.core.session.Unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

/** A tapped command gives up soon when the car isn't nearby, instead of waiting out its whole time. */
class NotNearbyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = DiagLog()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun givesUpWhenTheLinkNeverComesUp() = runBlocking {
        val pc = PhevConnection(
            SocketConnector { delay(100); throw IOException("car's network not found") }, log, scope,
            ConnectionConfig(backoffMs = listOf(100, 100, 100)),
        )
        val t0 = System.currentTimeMillis()
        val r = pc.execute(CarCommand.ClimateOn(ClimateMode.HEAT, ClimateDuration.MIN_10), 45_000, connectWithinMs = 600)
        val took = System.currentTimeMillis() - t0
        assertEquals(Unreachable.NOT_NEARBY, (r as CommandResult.Failure).reason)
        assertTrue("took $took ms", took < 3_000)
        assertTrue(log.dump(), log.dump().contains("giving up (not nearby)"))
        pc.stop("test", terminal = true)
    }

    @Test
    fun aLinkThatComesUpInTimeStillRunsTheCommand() = runBlocking {
        val car = FakeCar(ModelYear.MY14).start()
        try {
            var tries = 0
            val pc = PhevConnection(
                SocketConnector {
                    // Not found at first (Wi-Fi still searching), then the car is there.
                    if (tries++ < 2) { delay(100); throw IOException("not yet") }
                    Socket("127.0.0.1", car.port)
                },
                log, scope, ConnectionConfig(backoffMs = listOf(100, 100, 100)),
            )
            val r = pc.execute(CarCommand.RequestUpdate, 20_000, connectWithinMs = 8_000)
            assertTrue(r.toString(), r is CommandResult.Success)
            pc.stop("test", terminal = true)
        } finally {
            car.stop()
        }
    }
}
