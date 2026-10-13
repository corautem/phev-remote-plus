package io.github.anwo91.phevlink.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.ui.icons.PhevIcons

/**
 * Settings are grouped ("Car connection", "Climate and alerts", …): a small heading and one card holding the group's
 * rows. Each row shows its icon, title and a one-line summary of the current choice, and opens in place when tapped,
 * so the page reads as a short list instead of a long wall of fields.
 */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(content = content)
        }
    }
}

/**
 * One setting in a [SettingsGroup]. Tapping the header opens [content] below it (remembered per [key]); with [onClick]
 * the row is a plain link instead (e.g. a picker dialog) and has no content. [first] leaves out the divider above.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    first: Boolean = false,
    key: String = title,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    if (!first) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { if (onClick != null) onClick() else open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (summary != null && !open) {
                    Text(
                        summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                when {
                    onClick != null -> PhevIcons.ChevronRight
                    open -> PhevIcons.ChevronUp
                    else -> PhevIcons.ChevronDown
                },
                null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
        }
        if (open && content != null) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}
