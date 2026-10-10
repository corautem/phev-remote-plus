package io.github.anwo91.phevlink.data

import android.content.Context
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarState
import java.io.File

/**
 * Last known car state on disk, so the app, widget and tile show something
 * useful immediately — even when the car is out of reach.
 */
class StateCache(context: Context) {
    private val file = File(context.filesDir, "car_state.json")
    private val diagFile = File(context.filesDir, "diag_last_session.txt")

    @Synchronized
    fun load(): CarState = runCatching {
        if (!file.exists()) CarState() else Codecs.carStateFromMap(Json.parseObject(file.readText()))
    }.getOrElse { CarState() }

    @Synchronized
    fun save(state: CarState) {
        if (!state.hasData) return
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(Codecs.carStateToMap(state)))
            tmp.renameTo(file)
        }
    }

    /** The previous session's log survives an app restart, for "it failed this morning" debugging. */
    fun saveDiag(text: String) = runCatching { diagFile.writeText(text) }

    fun loadDiag(): String = runCatching { if (diagFile.exists()) diagFile.readText() else "" }.getOrDefault("")
}
