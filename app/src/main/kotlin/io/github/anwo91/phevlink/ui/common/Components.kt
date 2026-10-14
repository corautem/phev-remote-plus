package io.github.anwo91.phevlink.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.annotation.PluralsRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.link.LinkPhase
import io.github.anwo91.phevlink.link.LinkUi
import io.github.anwo91.phevlink.ui.theme.LocalStatus

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.size(10.dp))
            }
            content()
        }
    }
}

@Composable
fun phaseColor(phase: LinkPhase): Color {
    val s = LocalStatus.current
    return when (phase) {
        LinkPhase.CONNECTED -> s.good
        LinkPhase.SEARCHING, LinkPhase.CONNECTING, LinkPhase.HANDSHAKE -> s.charge
        LinkPhase.RETRYING -> s.warn
        LinkPhase.FAILED -> s.bad
        LinkPhase.OFFLINE -> s.idle
        // The car not being within reach is normal (it's out driving), not a fault.
        LinkPhase.AWAY -> s.idle
    }
}

/** Connection status pill; pulses while connecting. */
@Composable
fun LinkChip(link: LinkUi, onClick: () -> Unit) {
    val color = phaseColor(link.phase)
    val busy = link.phase in setOf(LinkPhase.SEARCHING, LinkPhase.CONNECTING, LinkPhase.HANDSHAKE)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = if (busy) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a",
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
            Spacer(Modifier.width(8.dp))
            Text(link.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Battery ring with an animated fill; a moving highlight while charging. */
@Composable
fun BatteryRing(percent: Int?, charging: Boolean, size: Dp = 132.dp, content: @Composable () -> Unit) {
    val target = (percent ?: 0) / 100f
    val fill by animateFloatAsState(target, tween(900), label = "fill")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val s = LocalStatus.current
    val arc = when {
        percent == null -> s.idle
        charging -> s.charge
        percent < 20 -> s.warn
        else -> MaterialTheme.colorScheme.primary
    }
    val sweep = rememberInfiniteTransition(label = "sweep")
    val glow by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "g")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val sz = Size(this.size.width - stroke, this.size.height - stroke)
            val tl = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fill > 0f) drawArc(arc, 135f, 270f * fill, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            if (charging && fill > 0f) {
                val pos = 135f + 270f * fill * glow
                drawArc(Color.White.copy(alpha = 0.45f), pos - 6f, 12f, false, tl, sz, style = Stroke(stroke * 0.6f, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

/** Plural string in the activity's (app) language; like `stringResource`, for counted things. */
@Composable
@ReadOnlyComposable
fun quantityString(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)
