package io.github.anwo91.phevlink

import android.app.Application
import android.content.Context
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.data.SettingsStore
import io.github.anwo91.phevlink.data.StateCache
import io.github.anwo91.phevlink.link.CarRepository
import io.github.anwo91.phevlink.notify.Notifier
import io.github.anwo91.phevlink.obd.ObdRepository
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
    val obd: ObdRepository by lazy { ObdRepository(app, scope, log) }

    init {
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
