package io.github.anwo91.phevlink.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider as dayNight
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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

/** The colours the widget draws with. */
private class WidgetColors(
    val background: ColorProvider,
    val text: ColorProvider,
    val textDim: ColorProvider,
    val accent: ColorProvider,
    val onAccent: ColorProvider,
    val chip: ColorProvider,
    val onChip: ColorProvider,
    val track: ColorProvider,
    val charge: ColorProvider,
)

/** PHEV Link's own colours, light and dark: the same values as the app theme (ui/theme/Theme.kt). */
private val BrandColors = WidgetColors(
    background = dayNight(day = Color(0xFFFBFDFC), night = Color(0xFF182327)),
    text = dayNight(day = Color(0xFF171D1B), night = Color(0xFFDDE4E1)),
    textDim = dayNight(day = Color(0xFF3C4945), night = Color(0xFFBEC9C4)),
    accent = dayNight(day = Color(0xFF0E7C6D), night = Color(0xFF45D3BB)),
    onAccent = dayNight(day = Color(0xFFFFFFFF), night = Color(0xFF003730)),
    chip = dayNight(day = Color(0xFFC9E9E0), night = Color(0xFF324B45)),
    onChip = dayNight(day = Color(0xFF04201A), night = Color(0xFFCCE8E0)),
    track = dayNight(day = Color(0xFFD9E5E0), night = Color(0xFF3C4945)),
    charge = dayNight(day = Color(0xFF1565C0), night = Color(0xFF90CAF9)),
)

/** The phone's wallpaper colours, for people who turned on "Match my wallpaper" in Settings. */
@Composable
private fun wallpaperColors() = WidgetColors(
    background = GlanceTheme.colors.widgetBackground,
    text = GlanceTheme.colors.onSurface,
    textDim = GlanceTheme.colors.onSurfaceVariant,
    accent = GlanceTheme.colors.primary,
    onAccent = GlanceTheme.colors.onPrimary,
    chip = GlanceTheme.colors.secondaryContainer,
    onChip = GlanceTheme.colors.onSecondaryContainer,
    track = GlanceTheme.colors.surfaceVariant,
    charge = GlanceTheme.colors.primary,
)

/** Home-screen widget: battery, charging, climate, and one-tap preheat / refresh. */
class CarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.graph.repo
        val settingsFlow = context.graph.settings.flow
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val toggle = Intent(context, ActionActivity::class.java).setAction(ActionActivity.TOGGLE_CLIMATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val refresh = Intent(context, ActionActivity::class.java).setAction(ActionActivity.REFRESH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        provideContent {
            // Collected while the widget session is alive, so updates show without a full redraw.
            val car by repo.car.collectAsState()
            val link by repo.link.collectAsState()
            val settings by settingsFlow.collectAsState()
            // Resolved on every redraw, so a language change shows with the next update.
            val res = AppLocale.localized(context)
            GlanceTheme {
                // Same rule as the app: its own teal unless "Match my wallpaper" is on (Android 12+).
                val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wallpaperColors() else BrandColors
                Content(res, car, link.connected, colors, open, toggle, refresh)
            }
        }
    }

    @Composable
    private fun Content(res: Context, car: CarState, connected: Boolean, c: WidgetColors, open: Intent, toggle: Intent, refresh: Intent) {
        val running = car.climate.state == PreAcState.RUNNING
        val charging = car.charging == true
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(c.background)
                .cornerRadius(22.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(open)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    car.batteryPercent?.let { "$it%" } ?: "–",
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.text),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (charging) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_w_bolt),
                                contentDescription = null,
                                modifier = GlanceModifier.size(14.dp),
                                colorFilter = ColorFilter.tint(c.charge),
                            )
                            Spacer(GlanceModifier.width(4.dp))
                        }
                        Text(chargeLine(res, car), style = TextStyle(fontSize = 13.sp, color = c.text), maxLines = 1)
                    }
                    Text(
                        if (running) res.getString(R.string.climate_on_status) + (car.climate.mode?.let { " · ${res.getString(Labels.mode(it))}" } ?: "")
                        else res.getString(R.string.climate_off_status),
                        style = TextStyle(fontSize = 12.sp, color = if (running) c.accent else c.textDim),
                        maxLines = 1,
                    )
                }
            }
            // Battery bar: blue while charging (like the gauge in the app), teal otherwise.
            car.batteryPercent?.let { pct ->
                Spacer(GlanceModifier.height(6.dp))
                LinearProgressIndicator(
                    progress = pct.coerceIn(0, 100) / 100f,
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                    color = if (charging) c.charge else c.accent,
                    backgroundColor = c.track,
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            val updated = car.updatedAtMs?.let { res.getString(R.string.widget_updated, Format.ago(it, context = res)) }
                ?: res.getString(R.string.widget_no_data)
            Text(
                if (connected) res.getString(R.string.widget_live, updated) else updated,
                style = TextStyle(fontSize = 11.sp, color = c.textDim),
                maxLines = 1,
            )
            // Keeps the buttons on the bottom edge when the widget is resized taller.
            Spacer(GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.height(6.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                val stopLabel = res.getString(R.string.common_stop)
                val preheatLabel = res.getString(R.string.sc_preheat_short)
                PillButton(
                    modifier = GlanceModifier.defaultWeight(),
                    background = if (running) c.chip else c.accent,
                    content = if (running) c.onChip else c.onAccent,
                    icon = if (running) R.drawable.ic_w_stop else R.drawable.ic_w_heat,
                    label = if (running) stopLabel else preheatLabel,
                    description = if (running) stopLabel else preheatLabel,
                    onClick = actionStartActivity(toggle),
                )
                Spacer(GlanceModifier.width(8.dp))
                PillButton(
                    modifier = GlanceModifier.width(52.dp),
                    background = c.chip,
                    content = c.onChip,
                    icon = R.drawable.ic_w_refresh,
                    label = null,
                    description = res.getString(R.string.common_refresh),
                    onClick = actionStartActivity(refresh),
                )
            }
        }
    }

    /**
     * A pill button with an icon and an optional [label]. The pill is a tinted shape drawable
     * rather than a corner radius, so it is round on Android versions before 12 as well.
     */
    @Composable
    private fun PillButton(
        modifier: GlanceModifier,
        background: ColorProvider,
        content: ColorProvider,
        icon: Int,
        label: String?,
        description: String,
        onClick: Action,
    ) {
        Box(modifier = modifier.height(36.dp).clickable(onClick), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(R.drawable.widget_pill),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(background),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(icon),
                    // With a label next to it the icon is decoration; alone it needs a name.
                    contentDescription = if (label == null) description else null,
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(content),
                )
                if (label != null) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = content), maxLines = 1)
                }
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
