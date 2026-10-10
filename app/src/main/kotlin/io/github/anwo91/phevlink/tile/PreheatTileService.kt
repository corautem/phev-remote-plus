package io.github.anwo91.phevlink.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.graph
import io.github.anwo91.phevlink.link.ActionActivity
import io.github.anwo91.phevlink.util.Format

/** Quick-settings tile: tap to start (smart) preheating, tap again to stop. */
class PreheatTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ActionActivity::class.java)
            .setAction(ActionActivity.TOGGLE_CLIMATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val car = graph.repo.car.value
        val running = car.climate.state == PreAcState.RUNNING
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running) "Climate on" else "Preheat"
        tile.subtitle = when {
            running -> car.climate.mode?.label ?: "Running"
            car.batteryPercent != null -> "${car.batteryPercent}% · ${car.updatedAtMs?.let { Format.ago(it) } ?: ""}"
            else -> "PHEV Link"
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, PreheatTileService::class.java)) }
        }
    }
}
