package io.github.anwo91.phevlink.link

import android.app.Activity
import android.widget.Toast
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.graph

/**
 * Invisible trampoline for home-screen shortcuts, the widget and the quick
 * settings tile: being an activity, it may start the foreground service from
 * anywhere, then it disappears immediately.
 */
class ActionActivity : Activity() {
    private var handled = false

    override fun onResume() {
        super.onResume()
        // Resumed = the app is in the foreground, so starting the service is allowed.
        if (handled) return
        handled = true
        val ctx = applicationContext
        val (svcIntent, toast) = when (intent?.action) {
            QUICK_CLIMATE -> LinkService.intent(ctx, LinkService.ACTION_QUICK_CLIMATE) to "Starting climate…"
            TOGGLE_CLIMATE -> LinkService.intent(ctx, LinkService.ACTION_TOGGLE_CLIMATE) to "Contacting the car…"
            STOP_CLIMATE -> LinkService.commandIntent(ctx, CarCommand.ClimateOff) to "Stopping climate…"
            LOCATE -> LinkService.commandIntent(ctx, CarCommand.Locate()) to "Flashing headlights…"
            REFRESH -> LinkService.intent(ctx, LinkService.ACTION_REFRESH) to "Refreshing…"
            else -> null to null
        }
        if (svcIntent != null) {
            if (!ctx.graph.settings.current.canConnect) {
                Toast.makeText(ctx, "Open PHEV Link and set up your car first", Toast.LENGTH_LONG).show()
            } else {
                LinkService.start(ctx, svcIntent)
                toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val QUICK_CLIMATE = "io.github.anwo91.phevlink.action.QUICK_CLIMATE"
        const val TOGGLE_CLIMATE = "io.github.anwo91.phevlink.action.TOGGLE_CLIMATE"
        const val STOP_CLIMATE = "io.github.anwo91.phevlink.action.STOP_CLIMATE"
        const val LOCATE = "io.github.anwo91.phevlink.action.LOCATE"
        const val REFRESH = "io.github.anwo91.phevlink.action.REFRESH"
    }
}
