package io.github.anwo91.phevlink.ui.tips

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.util.AppLocale
import java.util.Locale

/** One question in Help & FAQ. [id] is what in-app links use ("faq.reach"). */
data class FaqEntry(val id: String, @StringRes val group: Int, @StringRes val q: Int, @StringRes val a: Int)

/** The questions, grouped, in reading order. Answers describe how the app really works. */
object Faq {
    val entries: List<FaqEntry> = listOf(
        FaqEntry("route", R.string.faq_group_connect, R.string.faq_route_q, R.string.faq_route_a),
        FaqEntry("one", R.string.faq_group_connect, R.string.faq_one_q, R.string.faq_one_a),
        FaqEntry("register", R.string.faq_group_connect, R.string.faq_register_q, R.string.faq_register_a),
        FaqEntry("full", R.string.faq_group_connect, R.string.faq_full_q, R.string.faq_full_a),
        FaqEntry("reach", R.string.faq_group_connect, R.string.faq_reach_q, R.string.faq_reach_a),
        FaqEntry("drive", R.string.faq_group_connect, R.string.faq_drive_q, R.string.faq_drive_a),
        FaqEntry("asleep", R.string.faq_group_connect, R.string.faq_asleep_q, R.string.faq_asleep_a),
        FaqEntry("away", R.string.faq_group_connect, R.string.faq_away_q, R.string.faq_away_a),
        FaqEntry("dep", R.string.faq_group_use, R.string.faq_dep_q, R.string.faq_dep_a),
        FaqEntry("late", R.string.faq_group_use, R.string.faq_late_q, R.string.faq_late_a),
        FaqEntry("timer", R.string.faq_group_use, R.string.faq_timer_q, R.string.faq_timer_a),
        FaqEntry("12v", R.string.faq_group_battery, R.string.faq_12v_q, R.string.faq_12v_a),
        FaqEntry("health", R.string.faq_group_battery, R.string.faq_health_q, R.string.faq_health_a),
        FaqEntry("obd", R.string.faq_group_battery, R.string.faq_obd_q, R.string.faq_obd_a),
        FaqEntry("bridge", R.string.faq_group_bridge, R.string.faq_bridge_q, R.string.faq_bridge_a),
        FaqEntry("alerts", R.string.faq_group_bridge, R.string.faq_alerts_q, R.string.faq_alerts_a),
        FaqEntry("google", R.string.faq_group_bridge, R.string.faq_google_q, R.string.faq_google_a),
        FaqEntry("ha", R.string.faq_group_bridge, R.string.faq_ha_q, R.string.faq_ha_a),
        FaqEntry("privacy", R.string.faq_group_app, R.string.faq_privacy_q, R.string.faq_privacy_a),
        FaqEntry("update", R.string.faq_group_app, R.string.faq_update_q, R.string.faq_update_a),
    )

    /** Link prefix for a question: "faq.reach" (as the tips page's initial item). */
    const val PREFIX = "faq."
}

/**
 * The questions: a search field (matches question and answer, any case), grouped and expandable. [initial] opens and
 * scrolls to one question.
 */
@Composable
fun FaqList(initial: String?) {
    val ctx = LocalContext.current
    val res = remember { AppLocale.localized(ctx) }
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(initial) }
    val listState = rememberLazyListState()
    val q = query.trim().lowercase(Locale.ROOT)
    val shown = Faq.entries.filter { e ->
        q.isEmpty() || res.getString(e.q).lowercase(Locale.ROOT).contains(q) || res.getString(e.a).lowercase(Locale.ROOT).contains(q)
    }
    LaunchedEffect(initial) {
        val i = Faq.entries.indexOfFirst { it.id == initial }
        if (i >= 0) listState.scrollToItem(i + 1)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(res.getString(R.string.faq_search)) },
            )
            if (shown.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(res.getString(R.string.faq_none), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(shown, key = { it.id }) { e ->
            val first = shown.firstOrNull { it.group == e.group } === e
            Column {
                if (first) {
                    Text(
                        res.getString(e.group), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                SectionCard(modifier = Modifier.animateContentSize()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (open == e.id) null else e.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(res.getString(e.q), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (open == e.id) PhevIcons.ChevronUp else PhevIcons.ChevronDown, null)
                    }
                    if (open == e.id || q.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(res.getString(e.a), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
