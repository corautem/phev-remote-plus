package io.github.anwo91.phevlink.ui.vehicle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.VehicleSettingsCatalog
import io.github.anwo91.phevlink.core.model.VehicleSettingsText
import io.github.anwo91.phevlink.core.model.VisibleSetting
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.util.AppLocale

/**
 * The car's own customisable functions (lights, locks, wipers, A/C…), read and changed over the car's Wi-Fi like
 * the official app does. Keeps the connection open while visible.
 */
@Composable
fun VehicleSettingsScreen(vm: MainViewModel, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lang = remember { AppLocale.effectiveLanguage(ctx) }
    val car by vm.car.collectAsState()
    val link by vm.link.collectAsState()
    val pending by vm.pending.collectAsState()
    var editing by remember { mutableStateOf<VisibleSetting?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val groups = remember(car.settings, lang) { VehicleSettingsText.visibleGroups(car.settings, lang) }
    val allowed = VehicleSettingsCatalog.statusAllowsChanges(car.settingsStatus)
    val canChange = link.connected && allowed && pending == null

    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        vm.repo.hold(HOLD)
        onDispose { vm.repo.release(HOLD, lingerMs = 30_000) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.vs_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (pending != null) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    Text(stringResource(R.string.vs_intro), style = MaterialTheme.typography.bodyMedium)
                    when {
                        !link.connected -> Text(link.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                        !allowed -> Text(stringResource(R.string.vs_not_allowed, car.settingsStatus ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                        groups.isEmpty() -> Text(stringResource(R.string.vs_waiting), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            items(groups, key = { it.title }) { g ->
                SectionCard(title = g.title) {
                    g.items.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = canChange) { editing = item }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text(item.currentLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            val shownIds = groups.flatMap { g -> g.items.map { it.id } }.toSet()
            val unnamed = car.settings.filter { (id, st) -> st.mask != 0 && id !in shownIds }.toSortedMap()
            if (unnamed.isNotEmpty()) {
                item {
                    SectionCard(title = stringResource(R.string.vs_unnamed_title)) {
                        Text(stringResource(R.string.vs_unnamed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        unnamed.forEach { (id, st) ->
                            val options = (0..6).filter { ((st.mask shr it) and 1) == 1 }
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = canChange && options.isNotEmpty()) {
                                    editing = VisibleSetting(
                                        io.github.anwo91.phevlink.core.model.SettingDef(id, ctx.getString(R.string.vs_unnamed_item, id), emptyList(), ""),
                                        st.value, ctx.getString(R.string.vs_option_n, st.value + 1),
                                        options.map { it to ctx.getString(R.string.vs_option_n, it + 1) },
                                    )
                                }.padding(vertical = 10.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.vs_unnamed_item, id), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(R.string.vs_unnamed_value, st.value + 1, options.joinToString(", ") { (it + 1).toString() }),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) {
                item {
                    Column {
                        Text(stringResource(R.string.vs_beeps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { confirmReset = true }, enabled = canChange, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.vs_reset))
                        }
                    }
                }
            }
        }
    }

    editing?.let { item ->
        var choice by remember(item.id) { mutableStateOf(item.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(item.name) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    item.options.forEach { (option, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = choice == option, onClick = { choice = option }, role = Role.RadioButton).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == option, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editing = null
                        if (choice != item.value) vm.run(CarCommand.ChangeSetting(item.id, choice))
                    },
                ) { Text(stringResource(R.string.vs_apply)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.vs_reset)) },
            text = { Text(stringResource(R.string.vs_reset_confirm)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.run(CarCommand.ResetSettings) }) { Text(stringResource(R.string.vs_reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private const val HOLD = "vehicle-settings"
