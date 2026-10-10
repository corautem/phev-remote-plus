package io.github.anwo91.phevlink.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.diag.LogEntry
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.data.AppSettings
import io.github.anwo91.phevlink.graph
import io.github.anwo91.phevlink.link.CarRepository
import io.github.anwo91.phevlink.link.LinkService
import io.github.anwo91.phevlink.schedule.DepartureScheduler
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Labels
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    val repo: CarRepository = g.repo
    val car = repo.car
    val link = repo.link
    val settings: StateFlow<AppSettings> = g.settings.flow
    val log = g.log

    private val _pending = MutableStateFlow<String?>(null)
    /** Label of the command in flight, for button spinners. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _logVersion = MutableStateFlow(0)
    val logVersion: StateFlow<Int> = _logVersion.asStateFlow()
    private val logListener: (LogEntry) -> Unit = { _logVersion.value++ }

    private var visible = false
    private var watchStarted = false

    init {
        log.addListener(logListener)
        viewModelScope.launch {
            car.collect { maybeStartWatch() }
        }
    }

    override fun onCleared() {
        log.removeListener(logListener)
    }

    /** A string in the app language, resolved now (the language can change while this view model lives). */
    private fun str(@StringRes id: Int, vararg args: Any): String {
        val res = AppLocale.localized(getApplication())
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }

    fun onVisible() {
        visible = true
        // After a language change the activity is recreated: re-render texts built outside the UI.
        repo.relocalize()
        repo.hold(CarRepository.UI)
        maybeStartWatch()
    }

    fun onHidden() {
        visible = false
        repo.release(CarRepository.UI, lingerMs = 30_000)
    }

    /** While the app is open (allowed to start services), hand ongoing charging/climate to the watcher. */
    private fun maybeStartWatch() {
        val s = settings.value
        if (!visible || !s.watchWhileBusy) return
        if (repo.busy() && link.value.connected) {
            if (!watchStarted) {
                watchStarted = true
                // First background check a little later; alarms bring the service back as needed.
                io.github.anwo91.phevlink.link.WatchScheduler.schedule(getApplication(), 10 * 60_000L, 0)
            }
        } else {
            watchStarted = false
        }
    }

    fun run(cmd: CarCommand, successMessage: String? = null) {
        if (_pending.value != null) return
        viewModelScope.launch {
            val label = Labels.command(getApplication(), cmd)
            _pending.value = label
            try {
                when (val r = repo.execute(cmd)) {
                    is CommandResult.Success -> _messages.emit(successMessage ?: str(R.string.msg_cmd_done, label))
                    is CommandResult.Failure -> {
                        _messages.emit(str(R.string.msg_cmd_failed, label, Labels.failureReason(getApplication(), r.reason)))
                    }
                }
            } finally {
                _pending.value = null
            }
        }
    }

    fun quickClimate() {
        viewModelScope.launch { run(repo.quickClimate()) }
    }

    fun refresh() = run(CarCommand.RequestUpdate, str(R.string.msg_refreshed))

    fun reconnect() {
        repo.reconnect()
        viewModelScope.launch { _messages.emit(str(R.string.msg_reconnecting)) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { g.settings.update(transform) }
    }

    fun toast(text: String) {
        viewModelScope.launch { _messages.emit(text) }
    }

    fun nextDeparture() = DepartureScheduler.next(getApplication())

    fun previousSessionLog(): String = g.cache.loadDiag()

    /** Connect for a moment and report the outcome — used by setup and settings. */
    fun testConnection(onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            repo.hold("test")
            try {
                var waited = 0
                while (!link.value.connected && waited < 45_000) {
                    delay(500); waited += 500
                    val l = link.value
                    if (l.phase == io.github.anwo91.phevlink.link.LinkPhase.FAILED) break
                }
                val l = link.value
                if (l.connected) onDone(true, str(R.string.msg_connected_battery, car.value.batteryPercent?.toString() ?: "?"))
                else onDone(false, listOfNotNull(l.text, l.detail).joinToString(" — "))
            } finally {
                repo.release("test", 20_000)
            }
        }
    }
}
