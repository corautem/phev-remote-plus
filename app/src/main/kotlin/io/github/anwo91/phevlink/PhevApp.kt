package io.github.anwo91.phevlink

import android.app.Application
import android.content.Context
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.data.SettingsStore
import io.github.anwo91.phevlink.data.StateCache
import io.github.anwo91.phevlink.link.BridgeSetupController
import io.github.anwo91.phevlink.link.CarRepository
import io.github.anwo91.phevlink.link.DemoMode
import io.github.anwo91.phevlink.notify.Notifier
import io.github.anwo91.phevlink.obd.ObdRepository
import io.github.anwo91.phevlink.obd.PrefsTestStore
import io.github.anwo91.phevlink.core.obd.livetest.LiveTestRunner
import io.github.anwo91.phevlink.core.obd.emulator.FakeElm
import io.github.anwo91.phevlink.core.obd.livetest.TestStore
import io.github.anwo91.phevlink.schedule.DepartureScheduler
import io.github.anwo91.phevlink.tile.PreheatTileService
import io.github.anwo91.phevlink.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PhevApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        instance = this
    }

    companion object {
        lateinit var instance: PhevApp
            private set
    }
}

/** App-wide singletons. */
class Graph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, scope)
    val log = DiagLog(capacity = 6000)
    val cache = StateCache(app)
    val notifier = Notifier(app)
    val repo = CarRepository(app, settings, log, cache, notifier)
    /** OBD-II adapter connection (ELM327 over Bluetooth Classic/LE or Wi-Fi). */
    private val obdLazy = lazy {
        ObdRepository(app, scope, log, demoLink = {
            // The demo adapter reads the demo car: its battery % and charging follow the simulated car's.
            FakeElm.demo(
                socPercent = { repo.car.value.batteryPercent?.toDouble() },
                charging = { repo.car.value.charging },
            )
        })
    }
    val obd: ObdRepository by obdLazy
    /** Guided live tests; app-wide so a test keeps running when the screen is left. */
    val liveTests: LiveTestRunner by lazy { LiveTestRunner(scope, log) }
    val testStore: TestStore by lazy { PrefsTestStore(app) }
    /** The "set up bridge" wizard; app-wide so it survives rotation and waits while the user re-plugs the bridge. */
    val bridgeSetup: BridgeSetupController by lazy { BridgeSetupController(app, settings, log, scope) }
    val bridgeAdmin: io.github.anwo91.phevlink.link.BridgeAdminController by lazy {
        io.github.anwo91.phevlink.link.BridgeAdminController(app, settings, log, scope) { repo }
    }

    init {
        // Demo mode drives the OBD side too: the simulated adapter connects with the simulated car and leaves with it.
        // A live test running across the switch is stopped — it would mix real and simulated readings, and its
        // result would land in the wrong store.
        scope.launch {
            DemoMode.active.collect { on ->
                if (liveTests.running) {
                    log.link("OBD: demo mode ${if (on) "on" else "off"} — stopping the running live test")
                    liveTests.cancel()
                }
                if (on) {
                    obd.startDemo()
                } else if (obdLazy.isInitialized()) {
                    obd.stopDemo()
                }
            }
        }
        // The car's own outside temperature (from an OBD session) beats the forecast for "now" decisions.
        repo.carTemperature = { if (obdLazy.isInitialized()) obdLazy.value.lastAmbient else null }
        repo.onStateSaved = {
            WidgetUpdater.request(app)
            PreheatTileService.requestUpdate(app)
        }
        // Keep the departure alarms in step with the schedule.
        scope.launch {
            // drop(1): only react to edits. A cold start (often caused by one of these very
            // alarms) must not re-plan the departure that is being handled.
            settings.flow.map { it.departures }.distinctUntilChanged().drop(1).collect {
                DepartureScheduler.reschedule(app)
            }
        }
    }
}

val Context.graph: Graph get() = (applicationContext as PhevApp).graph
