package io.github.anwo91.phevlink.ui.tips

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.knowledge.Beep
import io.github.anwo91.phevlink.core.knowledge.Tip
import io.github.anwo91.phevlink.core.knowledge.TipSource
import io.github.anwo91.phevlink.core.knowledge.Tips
import io.github.anwo91.phevlink.core.model.VehicleSettingsText
import io.github.anwo91.phevlink.core.obd.dtc.DtcDatabase
import io.github.anwo91.phevlink.core.obd.livetest.LiveTestText
import io.github.anwo91.phevlink.core.obd.livetest.LiveTests
import io.github.anwo91.phevlink.ui.common.Pill
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.obd.Bullet
import io.github.anwo91.phevlink.ui.obd.DtcDetailDialog
import io.github.anwo91.phevlink.ui.obd.Heading
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.AppLocale

/**
 * Known complaints and problems of the 2014–2017 Outlander PHEV, plus the warning sounds and how to change them.
 * [initialTip] opens and scrolls to one tip.
 */
@Composable
fun TipsScreen(
    modifier: Modifier,
    initialTip: String?,
    onBack: () -> Unit,
    onOpenTests: () -> Unit,
    onOpenVehicleSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val lang = remember { AppLocale.effectiveLanguage(ctx) }
    val book = remember(lang) { Tips.load(lang) }
    var section by rememberSaveable { mutableStateOf(if (initialTip == null) 0 else 0) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(initialTip) }
    var codeDetail by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    BackHandler(onBack = onBack)

    val tips = book.tips.filter { category == null || it.category == category }
    LaunchedEffect(initialTip) {
        val i = tips.indexOfFirst { it.id == initialTip }
        if (i >= 0) listState.scrollToItem(i + 1)
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PhevIcons.Back, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.tips_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text(stringResource(R.string.tips_problems)) })
            FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text(stringResource(R.string.tips_beeps)) })
        }
        if (section == 0) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = category == null, onClick = { category = null }, label = { Text(stringResource(R.string.tips_all)) })
                        book.categories.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                    }
                }
                items(tips, key = { it.id }) { t ->
                    TipCard(
                        t, lang, open = expanded == t.id,
                        onToggle = { expanded = if (expanded == t.id) null else t.id },
                        onCode = { codeDetail = it }, onOpenTests = onOpenTests, onOpenVehicleSettings = onOpenVehicleSettings,
                    )
                }
                item {
                    Text(stringResource(R.string.tips_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(stringResource(R.string.tips_beeps_intro), style = MaterialTheme.typography.bodyMedium)
                }
                items(book.beeps, key = { it.id }) { b -> BeepCard(b, lang, onOpenVehicleSettings) }
            }
        }
    }
    codeDetail?.let { code ->
        DtcDetailDialog(code, lang, emptyList(), onDismiss = { codeDetail = null }, onOpenTip = { codeDetail = null; expanded = it })
    }
}

@Composable
private fun TipCard(
    t: Tip,
    lang: String,
    open: Boolean,
    onToggle: () -> Unit,
    onCode: (String) -> Unit,
    onOpenTests: () -> Unit,
    onOpenVehicleSettings: () -> Unit,
) {
    SectionCard(modifier = Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(t.title, style = MaterialTheme.typography.titleMedium)
            }
            Icon(if (open) PhevIcons.ChevronUp else PhevIcons.ChevronDown, null)
        }
        if (!open) return@SectionCard
        if (t.symptoms.isNotEmpty()) {
            Heading(stringResource(R.string.tips_symptoms))
            t.symptoms.forEach { Bullet(it) }
        }
        Heading(stringResource(R.string.tips_why))
        Text(t.why, style = MaterialTheme.typography.bodyMedium)
        Heading(stringResource(R.string.tips_what_to_do))
        t.whatToDo.forEachIndexed { i, s -> Bullet(s, "${i + 1}.") }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(stringResource(diyLabel(t.diy)), diyColor(t.diy))
            if (t.confidence.isNotBlank()) Pill(stringResource(confidenceLabel(t.confidence)), MaterialTheme.colorScheme.secondary)
        }
        if (t.appHelp.isNotBlank()) {
            Heading(stringResource(R.string.tips_app_help))
            Text(t.appHelp, style = MaterialTheme.typography.bodyMedium)
        }
        val tests = t.relatedTests.mapNotNull { LiveTests.byId(it) }
        if (tests.isNotEmpty()) {
            Text(
                tests.joinToString(" · ") { LiveTestText.text(it.titleKey, lang) },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(onClick = onOpenTests, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.tips_open_tests)) }
        }
        if (t.relatedSettings.isNotEmpty()) {
            Text(
                t.relatedSettings.joinToString(" · ") { VehicleSettingsText.name(it, lang) },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(onClick = onOpenVehicleSettings, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.tips_open_settings)) }
        }
        if (t.relatedCodes.isNotEmpty()) {
            Heading(stringResource(R.string.tips_codes))
            t.relatedCodes.forEach { code ->
                TextButton(onClick = { onCode(code) }) {
                    Text("$code · " + (DtcDatabase.title(code, lang) ?: stringResource(R.string.tips_code_manufacturer)))
                }
            }
        }
        Sources(t.sources)
    }
}

@Composable
private fun BeepCard(b: Beep, lang: String, onOpenVehicleSettings: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.sound, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(stringResource(adjustLabel(b.adjust)), adjustColor(b.adjust))
        }
        if (b.whenText.isNotBlank()) Text(b.whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Heading(stringResource(R.string.tips_how))
        Text(b.how, style = MaterialTheme.typography.bodyMedium)
        if (b.notes.isNotBlank()) Text(b.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (b.settingIds.isNotEmpty()) {
            Text(
                b.settingIds.joinToString(" · ") { VehicleSettingsText.name(it, lang) },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(onClick = onOpenVehicleSettings, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.tips_open_settings)) }
        }
        Sources(b.sources)
    }
}

@Composable
private fun Sources(sources: List<TipSource>) {
    if (sources.isEmpty()) return
    val ctx = LocalContext.current
    Heading(stringResource(R.string.tips_sources))
    sources.forEach { s ->
        Text(
            s.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.padding(vertical = 3.dp).clickable {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
                } catch (e: Exception) {
                    // No browser: nothing to do.
                }
            },
        )
    }
}

private fun diyLabel(d: String): Int = when (d) {
    "yes" -> R.string.tips_diy_yes
    "partly" -> R.string.tips_diy_partly
    else -> R.string.tips_diy_no
}

@Composable
private fun diyColor(d: String): Color = when (d) {
    "yes" -> LocalStatus.current.good
    "partly" -> LocalStatus.current.charge
    else -> LocalStatus.current.warn
}

private fun confidenceLabel(c: String): Int = when (c) {
    "high" -> R.string.tips_conf_high
    "medium" -> R.string.tips_conf_medium
    else -> R.string.tips_conf_low
}

private fun adjustLabel(a: String): Int = when (a) {
    "app" -> R.string.beep_adjust_app
    "car-menu" -> R.string.beep_adjust_menu
    "switch" -> R.string.beep_adjust_switch
    "dealer" -> R.string.beep_adjust_dealer
    else -> R.string.beep_adjust_no
}

@Composable
private fun adjustColor(a: String): Color = when (a) {
    "app" -> LocalStatus.current.good
    "car-menu", "switch" -> LocalStatus.current.charge
    "dealer" -> LocalStatus.current.warn
    else -> LocalStatus.current.idle
}
