package io.github.anwo91.phevlink.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.graph
import io.github.anwo91.phevlink.link.ActionActivity
import io.github.anwo91.phevlink.ui.MainActivity
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen widget: battery, charging, climate, and one-tap preheat / refresh. */
class CarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repo
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val toggle = Intent(context, ActionActivity::class.java).setAction(ActionActivity.TOGGLE_CLIMATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val refresh = Intent(context, ActionActivity::class.java).setAction(ActionActivity.REFRESH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        provideContent {
            // Collected while the widget session is alive, so updates show without a full redraw.
            val car by repo.car.collectAsState()
            val link by repo.link.collectAsState()
            // Resolved on every redraw, so a language change shows with the next update.
            val res = AppLocale.localized(context)
            GlanceTheme { Content(res, car, link.connected, open, toggle, refresh) }
        }
    }

    @Composable
    private fun Content(res: Context, car: CarState, connected: Boolean, open: Intent, toggle: Intent, refresh: Intent) {
        val running = car.climate.state == PreAcState.RUNNING
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(22.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(open)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    car.batteryPercent?.let { "$it%" } ?: "–",
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                )
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Text(chargeLine(res, car), style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface))
                    Text(
                        if (running) res.getString(R.string.climate_on_status) + (car.climate.mode?.let { " · ${res.getString(Labels.mode(it))}" } ?: "")
                        else res.getString(R.string.climate_off_status),
                        style = TextStyle(fontSize = 12.sp, color = if (running) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant),
                    )
                }
            }
            Spacer(GlanceModifier.height(4.dp))
            val updated = car.updatedAtMs?.let { res.getString(R.string.widget_updated, Format.ago(it, context = res)) }
                ?: res.getString(R.string.widget_no_data)
            Text(
                if (connected) res.getString(R.string.widget_live, updated) else updated,
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Button(
                    text = res.getString(if (running) R.string.common_stop else R.string.sc_preheat_short),
                    onClick = actionStartActivity(toggle),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(8.dp))
                Button(text = res.getString(R.string.common_refresh), onClick = actionStartActivity(refresh), modifier = GlanceModifier.defaultWeight())
            }
        }
    }

    private fun chargeLine(res: Context, car: CarState): String = when {
        car.charging == true -> car.chargeMinutesRemaining?.let { res.getString(R.string.widget_charging_left, Format.minutes(it, res)) }
            ?: res.getString(R.string.home_charging)
        car.plug == PlugState.PLUGGED -> res.getString(R.string.home_plugged_in)
        car.plug == PlugState.UNPLUGGED -> res.getString(R.string.home_unplugged)
        else -> ""
    }
}

class CarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CarWidget()
}

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var last = 0L
    private var pending: kotlinx.coroutines.Job? = null

    /** At most one redraw per 10 s, but the last request is never dropped. */
    @Synchronized
    fun request(context: Context) {
        if (pending?.isActive == true) return
        val wait = (last + 10_000 - System.currentTimeMillis()).coerceAtLeast(0)
        pending = scope.launch {
            kotlinx.coroutines.delay(wait)
            synchronized(this@WidgetUpdater) { last = System.currentTimeMillis() }
            runCatching { CarWidget().updateAll(context.applicationContext) }
        }
    }
}
