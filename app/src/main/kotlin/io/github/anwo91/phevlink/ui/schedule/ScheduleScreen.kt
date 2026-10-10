package io.github.anwo91.phevlink.ui.schedule

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.planner.Departure
import io.github.anwo91.phevlink.schedule.DepartureScheduler
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

@Composable
fun ScheduleScreen(vm: MainViewModel, modifier: Modifier) {
    val settings by vm.settings.collectAsState()
    var editing by remember { mutableStateOf<Departure?>(null) }
    val ctx = LocalContext.current
    var minute by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(30_000); minute = System.currentTimeMillis() / 60_000 }
    }
    val next = remember(settings.departures, minute) { vm.nextDeparture() }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.sched_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.sched_explain),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!DepartureScheduler.exactAlarmsAllowed(ctx)) item {
                SectionCard(title = stringResource(R.string.sched_exact_title)) {
                    Text(stringResource(R.string.sched_exact_explain), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                        }
                    }) { Text(stringResource(R.string.sched_open_settings)) }
                }
            }
            if (settings.homeLat == null) item {
                SectionCard(title = stringResource(R.string.sched_no_location_title)) {
                    Text(
                        stringResource(R.string.sched_no_location_explain),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            next?.let { n ->
                item {
                    Text(
                        stringResource(R.string.sched_next, Format.clockOrDay(n.atMs), Format.clockOrDay(n.planAtMs)),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(settings.departures, key = { it.id }) { d ->
                DepartureRow(
                    d,
                    onToggle = { on -> vm.updateSettings { s -> s.copy(departures = s.departures.map { if (it.id == d.id) it.copy(enabled = on) else it }) } },
                    onEdit = { editing = d },
                    onDelete = { vm.updateSettings { s -> s.copy(departures = s.departures.filterNot { it.id == d.id }) } },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                editing = Departure(
                    id = UUID.randomUUID().toString(), time = LocalTime.of(7, 30),
                    days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                )
            },
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text(stringResource(R.string.sched_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { d ->
        EditDepartureDialog(
            initial = d,
            onDismiss = { editing = null },
            onSave = { saved ->
                vm.updateSettings { s ->
                    val exists = s.departures.any { it.id == saved.id }
                    s.copy(departures = if (exists) s.departures.map { if (it.id == saved.id) saved else it } else s.departures + saved)
                }
                editing = null
            },
        )
    }
}

/** Weekday names in the language the screens are shown in. */
@Composable
private fun uiLocale(): Locale = AppLocale.uiLocale(LocalContext.current)

private fun dayShort(d: DayOfWeek, locale: Locale) = d.getDisplayName(TextStyle.SHORT, locale)

@Composable
private fun describeDays(days: Set<DayOfWeek>): String = when {
    days.size == 7 -> stringResource(R.string.days_every)
    days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> stringResource(R.string.days_weekends)
    days == DayOfWeek.entries.filter { it.value <= 5 }.toSet() -> stringResource(R.string.days_weekdays)
    days.isEmpty() -> stringResource(R.string.days_none)
    else -> {
        val locale = uiLocale()
        DayOfWeek.entries.filter { it in days }.joinToString { dayShort(it, locale) }
    }
}

@Composable
private fun DepartureRow(d: Departure, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    SectionCard(modifier = Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) {
                    Text("%02d:%02d".format(d.time.hour, d.time.minute), style = MaterialTheme.typography.displaySmall)
                }
                Text(describeDays(d.days), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (d.smart) stringResource(R.string.sched_smart_row)
                    else stringResource(R.string.sched_fixed_row, stringResource(Labels.mode(d.mode)), d.duration.minutes),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = d.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.common_delete)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditDepartureDialog(initial: Departure, onDismiss: () -> Unit, onSave: (Departure) -> Unit) {
    val time = rememberTimePickerState(initial.time.hour, initial.time.minute, is24Hour = true)
    var days by remember { mutableStateOf(initial.days) }
    var smart by remember { mutableStateOf(initial.smart) }
    var mode by remember { mutableStateOf(initial.mode) }
    var dur by remember { mutableStateOf(initial.duration) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(time = LocalTime.of(time.hour, time.minute), days = days, smart = smart, mode = mode, duration = dur, enabled = true))
            }, enabled = days.isNotEmpty()) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = { Text(stringResource(R.string.sched_dialog_title)) },
        text = {
            Column {
                val locale = uiLocale()
                TimeInput(state = time)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(dayShort(d, locale).take(2)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sched_smart_switch), modifier = Modifier.weight(1f))
                    Switch(checked = smart, onCheckedChange = { smart = it })
                }
                Text(
                    stringResource(if (smart) R.string.sched_smart_fallback else R.string.sched_always_mode),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ClimateMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(Labels.mode(m))) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClimateDuration.entries.forEach { d ->
                        FilterChip(selected = dur == d, onClick = { dur = d }, label = { Text("${d.minutes}") })
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.unit_min), modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        },
    )
}
