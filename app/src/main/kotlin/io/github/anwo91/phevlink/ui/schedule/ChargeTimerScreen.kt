package io.github.anwo91.phevlink.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.anwo91.phevlink.core.diag.LogKind
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ChargeTimerProgram
import io.github.anwo91.phevlink.core.model.ChargeWindow
import io.github.anwo91.phevlink.core.model.ScheduleDecoder
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.AppLocale
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle

private const val HOLD = "charge-timer"

/**
 * The car's own charge timer (2014–2017 cars), edited like in Mitsubishi's app: up to three timers, each a window
 * (start–end, 10-minute steps) used on the days you pick (one timer per day), plus "Full charge" days. Starts from what
 * the car reports; "Send to the car" writes it and waits until the car reports it back. Every step is logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargeTimerScreen(vm: MainViewModel, modifier: Modifier, onBack: () -> Unit) {
    val car by vm.car.collectAsState()
    val link by vm.link.collectAsState()
    val pending by vm.pending.collectAsState()
    val status = LocalStatus.current
    val ctx = LocalContext.current
    val locale = remember { AppLocale.uiLocale(ctx) }
    val fromCar = remember(car.registers) { ChargeTimerProgram.read(car) }
    var draft by remember { mutableStateOf<ChargeTimerProgram?>(null) }
    var editing by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // timer index, start?
    val unsupported = car.modelYear == ModelYear.MY18 || car.modelYear == ModelYear.MY24

    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        vm.repo.hold(HOLD)
        vm.log.add(LogKind.CMD, "Charge timer: opened — car reports ${fromCar?.summary() ?: "nothing yet"}")
        onDispose { vm.repo.release(HOLD, lingerMs = 30_000) }
    }
    // Start from the car's timer as soon as it is known.
    LaunchedEffect(fromCar) { if (fromCar != null && draft == null) draft = fromCar }
    LaunchedEffect(Unit) { if (fromCar == null && link.connected) vm.refresh() }

    val d = draft
    val changed = d != null && fromCar != null && d.normalized() != fromCar.normalized()
    // Days Monday first, as people read a week; the car counts Sunday as day 0.
    val week = listOf(1, 2, 3, 4, 5, 6, 0)
    fun dayName(day: Int) = DayOfWeek.of(if (day == 0) 7 else day).getDisplayName(TextStyle.SHORT, locale).take(3)
    fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PhevIcons.Back, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.ct_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (pending != null) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ct_intro), style = MaterialTheme.typography.bodyMedium)
            // What the car does right now.
            val now = when {
                fromCar == null -> null
                !fromCar.active -> stringResource(R.string.ct_now_none)
                ScheduleDecoder.chargeTimerOn(car) -> stringResource(R.string.ct_now_on)
                else -> stringResource(R.string.ct_now_paused)
            }
            now?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when {
                unsupported -> Text(stringResource(R.string.ct_my18), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                d == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (link.connected) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (link.connected) stringResource(R.string.ct_reading) else link.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (link.connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (d != null && !unsupported) {
                for (t in 1..3) {
                    val w = d.windows[t - 1]
                    SectionCard(title = stringResource(R.string.ct_timer, t), trailing = {
                        if (w != null) IconButton(onClick = {
                            draft = d.copy(windows = d.windows.toMutableList().also { it[t - 1] = null }, dayTimer = d.dayTimer.map { if (it == t) 0 else it })
                        }) { Icon(PhevIcons.Delete, stringResource(R.string.ct_clear)) }
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = t to true }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ct_from, w?.let { hhmm(it.start) } ?: "––:––"))
                            }
                            OutlinedButton(onClick = { editing = t to false }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ct_to, w?.let { hhmm(it.end) } ?: "––:––"))
                            }
                        }
                        if (w == null) {
                            Text(stringResource(R.string.ct_not_set), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                week.forEach { day ->
                                    FilterChip(
                                        selected = d.dayTimer[day] == t,
                                        onClick = {
                                            draft = d.copy(dayTimer = d.dayTimer.toMutableList().also { it[day] = if (it[day] == t) 0 else t })
                                        },
                                        label = { Text(dayName(day)) },
                                    )
                                }
                            }
                            if (w.end < w.start) Text(stringResource(R.string.ct_overnight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SectionCard(title = stringResource(R.string.ct_full_title)) {
                    Text(stringResource(R.string.ct_full_explain), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        week.forEach { day ->
                            FilterChip(
                                selected = day in d.fullDays,
                                onClick = { draft = d.copy(fullDays = if (day in d.fullDays) d.fullDays - day else d.fullDays + day) },
                                label = { Text(dayName(day)) },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.ct_charge_now_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sent = stringResource(R.string.ct_sent)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.log.add(LogKind.CMD, "Charge timer: sending ${d.normalized().summary()} (car had ${fromCar?.summary() ?: "?"})")
                            vm.run(CarCommand.SetChargeTimer(d.normalized()), sent)
                        },
                        enabled = changed && pending == null && link.connected,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.ct_send)) }
                    if (changed) OutlinedButton(onClick = { draft = fromCar }) { Text(stringResource(R.string.ct_undo)) }
                }
                if (!changed && fromCar != null) {
                    Text(stringResource(R.string.ct_in_step), style = MaterialTheme.typography.bodySmall, color = status.good)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editing?.let { (t, start) ->
        val cur = d?.windows?.get(t - 1)
        val initial = (if (start) cur?.start else cur?.end) ?: if (start) LocalTime.of(22, 0) else LocalTime.of(6, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (start) R.string.ct_pick_start else R.string.ct_pick_end, t)) },
            text = {
                Column {
                    TimeInput(state = state)
                    Text(stringResource(R.string.ct_ten_minutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val picked = LocalTime.of(state.hour, state.minute / 10 * 10)
                    val p = draft ?: ChargeTimerProgram.EMPTY
                    val old = p.windows[t - 1]
                    val s = if (start) picked else old?.start ?: LocalTime.of(22, 0)
                    var e = if (start) old?.end ?: LocalTime.of(6, 0) else picked
                    if (s == e) e = e.plusMinutes(10)
                    draft = p.copy(windows = p.windows.toMutableList().also { it[t - 1] = ChargeWindow(s, e) })
                    editing = null
                }) { Text(stringResource(R.string.ct_ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
