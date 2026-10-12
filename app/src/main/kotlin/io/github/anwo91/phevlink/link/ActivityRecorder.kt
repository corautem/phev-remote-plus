package io.github.anwo91.phevlink.link

import android.content.Context
import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * What this phone did straight over the car's Wi-Fi, for the shared activity log: kept on the phone (so the activity
 * page has it without a bridge) and handed to the bridge the next time it can be reached. Through the bridge, the
 * bridge writes it down itself. Uploads are logged.
 */
class ActivityRecorder(
    context: Context,
    private val settings: SettingsStore,
    private val log: DiagLog,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "activity-local.json")
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<Activity>>(emptyList())
    /** This phone's own direct actions, newest last. */
    val entries: StateFlow<List<Activity>> = _entries.asStateFlow()
    private val pending = LinkedHashSet<String>()

    init {
        load()
    }

    fun direct(cmd: CarCommand, r: CommandResult) {
        val m = Codecs.commandToMap(cmd)
        val action = m["action"]?.toString() ?: return
        if (action in ActivityText.QUIET_COMMANDS) return
        val params = if (cmd is CarCommand.SetChargeTimer) mapOf("summary" to cmd.program.summary()) else m - "action"
        val a = Activity(
            System.currentTimeMillis(), settings.current.deviceName, "cmd.$action", params,
            ok = r is CommandResult.Success, detail = (r as? CommandResult.Failure)?.reason, via = "direct",
        )
        synchronized(pending) {
            _entries.value = (_entries.value + a).takeLast(MAX)
            pending += a.id
        }
        save()
        if (settings.current.hasBridge) scope.launch { upload("after a command") }
    }

    /** Hands what the bridge hasn't seen yet to the bridge. True when nothing is left to send. */
    suspend fun upload(why: String): Boolean = mutex.withLock {
        val s = settings.current
        if (!s.hasBridge || s.bridgeToken.isBlank()) return@withLock false
        val todo = synchronized(pending) { _entries.value.filter { it.id in pending } }
        if (todo.isEmpty()) return@withLock true
        val r = BridgeSetupApi.postActivity(s.bridgeUrl, s.bridgeToken, todo.map { it.toMap() })
        if (r == null || r.code != 200) {
            log.link("Activity: ${todo.size} direct action(s) not handed to the bridge yet ($why: ${r?.code ?: "not reachable"})")
            return@withLock false
        }
        synchronized(pending) { pending.removeAll(todo.map { it.id }.toSet()) }
        save()
        log.link("Activity: handed ${todo.size} direct action(s) to the bridge ($why)")
        true
    }

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        val m = Json.parseObject(file.readText())
        @Suppress("UNCHECKED_CAST")
        _entries.value = (m["entries"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(Activity::fromMap) }
        pending += (m["pending"] as? List<*>).orEmpty().map { it.toString() }
    }.onFailure { log.warn("Activity: couldn't read the phone's own log: ${it.message}") }

    private fun save() = runCatching {
        val text = synchronized(pending) {
            Json.write(mapOf("entries" to _entries.value.map { it.toMap() }, "pending" to pending.toList()))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }.onFailure { log.warn("Activity: couldn't save the phone's own log: ${it.message}") }

    companion object {
        private const val MAX = 300
    }
}
