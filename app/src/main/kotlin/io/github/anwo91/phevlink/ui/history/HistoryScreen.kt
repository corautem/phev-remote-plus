package io.github.anwo91.phevlink.ui.history

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.energy.ChargeRecord
import io.github.anwo91.phevlink.core.energy.TripRecord
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Money
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What GET /api/history gave: newest first; [week] and [month] are the bridge's summaries; [plan] the price plan. */
private data class History(
    val charges: List<ChargeRecord>,
    val trips: List<TripRecord>,
    val week: Map<String, Any?>,
    val month: Map<String, Any?>,
    val plan: Map<String, Any?>,
)

private enum class Load { LOADING, OK, NO_BRIDGE, OLD_BRIDGE, FAILED }

/**
 * Charging and trips, kept by the bridge: each charge at home with its energy from the wall and what it cost, monthly
 * totals, and each trip (time away, battery used ≈ electric km). The price plan can be changed at the bottom.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier, onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val s by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val loc = AppLocale.uiLocale(ctx)
    BackHandler(onBack = onBack)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(Load.LOADING) }
    var data by remember { mutableStateOf<History?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.bridgeUrl, reloads) {
        if (!s.hasBridge || s.bridgeToken.isBlank()) {
            state = Load.NO_BRIDGE
            return@LaunchedEffect
        }
        val r = BridgeSetupApi.history(s.bridgeUrl, s.bridgeToken)
        when (r?.code) {
            200 -> {
                val b = r.body
                data = History(
                    charges = (b["charges"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(ChargeRecord::fromMap) },
                    trips = (b["trips"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(TripRecord::fromMap) },
                    week = (b["week"] as? Map<String, Any?>).orEmpty(),
                    month = (b["month"] as? Map<String, Any?>).orEmpty(),
                    plan = (b["plan"] as? Map<String, Any?>).orEmpty(),
                )
                state = Load.OK
                vm.log.link("History: ${data?.charges?.size} charge(s), ${data?.trips?.size} trip(s) from the bridge")
            }
            404 -> {
                state = Load.OLD_BRIDGE
                vm.log.link("History: the bridge doesn't keep a history yet (update it)")
            }
            else -> {
                state = Load.FAILED
                vm.log.link("History: the bridge didn't answer (${r?.code ?: BridgeSetupApi.lastFailure ?: "no answer"})")
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PhevIcons.Back, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.hist_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(R.string.hist_charging)) })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(R.string.hist_trips)) })
        }
        val d = data
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                state == Load.NO_BRIDGE -> item {
                    SectionCard {
                        Text(stringResource(R.string.hist_needs_bridge), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenSetup) { Text(stringResource(R.string.settings_bridge_setup)) }
                    }
                }
                state == Load.OLD_BRIDGE -> item { SectionCard { Text(stringResource(R.string.hist_old_bridge), style = MaterialTheme.typography.bodyMedium) } }
                state == Load.FAILED -> item {
                    SectionCard {
                        Text(stringResource(R.string.hist_failed), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { state = Load.LOADING; reloads++ }) { Text(stringResource(R.string.reach_try_again)) }
                    }
                }
                d == null -> item { Note(stringResource(R.string.sh_loading)) }
                tab == 0 -> charging(d, loc, vm, s) { state = Load.LOADING; reloads++ }
                else -> trips(d, loc)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- charging

private fun androidx.compose.foundation.lazy.LazyListScope.charging(
    d: History, loc: Locale, vm: MainViewModel, s: io.github.anwo91.phevlink.data.AppSettings, reload: () -> Unit,
) {
    item { Totals(d) }
    if (d.charges.isEmpty()) item { SectionCard { Text(stringResource(R.string.hist_no_charges), style = MaterialTheme.typography.bodyMedium) } }
    val zone = ZoneId.systemDefault()
    val byMonth = d.charges.groupBy { Instant.ofEpochMilli(it.endMs).atZone(zone).let { z -> z.year * 100 + z.monthValue } }
    for ((ym, list) in byMonth) {
        item(key = "m$ym") { MonthHeader(ym, list, loc) }
        items(list, key = { it.id }) { c -> ChargeRow(c, loc, priced(d.plan)) }
    }
    item(key = "price") { PriceCard(vm, s, d, reload) }
}

@Composable
private fun Totals(d: History) {
    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            SummaryColumn(stringResource(R.string.hist_week), d.week, Modifier.weight(1f))
            SummaryColumn(stringResource(R.string.hist_month), d.month, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryColumn(title: String, m: Map<String, Any?>, modifier: Modifier) {
    val ctx = LocalContext.current
    val loc = AppLocale.uiLocale(ctx)
    val charges = (m["charges"] as? Number)?.toInt() ?: 0
    val kwh = (m["kwh"] as? Number)?.toDouble() ?: 0.0
    val cost = (m["cost"] as? Number)?.toDouble()
    val complete = m["costComplete"] as? Boolean ?: true
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(cost?.let { "≈ " + Money.format(it, m["currency"] as? String, loc) + if (complete) "" else " +" } ?: "–", style = MaterialTheme.typography.headlineSmall)
        Text(
            ctx.resources.getQuantityString(R.plurals.hist_charges_kwh, charges, charges, String.format(loc, "%.1f", kwh)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonthHeader(ym: Int, list: List<ChargeRecord>, loc: Locale) {
    val ctx = LocalContext.current
    val month = java.time.YearMonth.of(ym / 100, ym % 100).format(DateTimeFormatter.ofPattern("LLLL yyyy", loc)).replaceFirstChar { it.titlecase(loc) }
    val kwh = list.sumOf { it.kwh }
    val costs = list.mapNotNull { it.cost }
    val total = listOfNotNull(
        ctx.resources.getQuantityString(R.plurals.hist_charges_kwh, list.size, list.size, String.format(loc, "%.1f", kwh)),
        costs.takeIf { it.isNotEmpty() }?.let { "≈ " + Money.format(it.sum(), list.first().currency, loc) + if (costs.size < list.size) " +" else "" },
    ).joinToString(" · ")
    Column(Modifier.padding(start = 4.dp, top = 8.dp)) {
        Text(month, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(total, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Whether the plan can price a charge at all (else a charge without a cost says "no price set", not "later"). */
private fun priced(plan: Map<String, Any?>): Boolean = when (plan["model"]) {
    "fixed" -> plan["fixedPerKwh"] != null
    "norgespris" -> true
    else -> plan["areaUsed"] != null
}

@Composable
private fun ChargeRow(c: ChargeRecord, loc: Locale, priced: Boolean) {
    val status = LocalStatus.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PhevIcons.Plug, null, tint = status.charge, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(span(c.startMs, c.endMs, loc), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${c.fromPct} → ${c.toPct} % · " + String.format(loc, "%.1f kWh", c.kwh),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(c.cost?.let { "≈ " + Money.format(it, c.currency, loc) } ?: "–", style = MaterialTheme.typography.titleSmall)
                Text(
                    c.perKwh?.let { Money.perKwh(it, c.currency, loc) } ?: stringResource(if (priced) R.string.hist_cost_later else R.string.hist_no_price),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PriceCard(vm: MainViewModel, s: io.github.anwo91.phevlink.data.AppSettings, d: History, reload: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val p = d.plan
    val model = when (p["model"]) {
        "norgespris" -> stringResource(R.string.price_norgespris)
        "fixed" -> stringResource(R.string.price_fixed)
        else -> stringResource(R.string.price_spot)
    }
    SectionCard(modifier = Modifier.animateContentSize(), title = stringResource(R.string.price_title)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(model, p["areaUsed"] as? String).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            Icon(if (open) PhevIcons.ChevronUp else PhevIcons.ChevronDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Note(stringResource(R.string.hist_estimate, String.format(AppLocale.uiLocale(LocalContext.current), "%.1f", (p["fullChargeKwh"] as? Number)?.toDouble() ?: 12.0)))
        if (open) {
            Spacer(Modifier.height(8.dp))
            // Saved: the bridge prices the charges still without a cost; show them a moment later.
            PriceSettings(vm, s, onSaved = { scope.launch { kotlinx.coroutines.delay(2_500); reload() } })
        }
    }
}

// ---------------------------------------------------------------------------------------------- trips

private fun androidx.compose.foundation.lazy.LazyListScope.trips(d: History, loc: Locale) {
    item { TripTotals(d) }
    if (d.trips.isEmpty()) item { SectionCard { Text(stringResource(R.string.hist_no_trips), style = MaterialTheme.typography.bodyMedium) } }
    items(d.trips, key = { it.id }) { t -> TripRow(t, loc) }
    item { Note(stringResource(R.string.hist_trips_note)) }
}

@Composable
private fun TripTotals(d: History) {
    val ctx = LocalContext.current
    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            for ((title, m) in listOf(stringResource(R.string.hist_week) to d.week, stringResource(R.string.hist_month) to d.month)) {
                val trips = (m["trips"] as? Number)?.toInt() ?: 0
                val minutes = (m["awayMinutes"] as? Number)?.toInt() ?: 0
                val km = (m["electricKm"] as? Number)?.toInt()
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(km?.let { "≈ $it km" } ?: "–", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        ctx.resources.getQuantityString(R.plurals.hist_trips_away, trips, trips, Format.minutes(minutes, ctx)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TripRow(t: TripRecord, loc: Locale) {
    val ctx = LocalContext.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PhevIcons.Car, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(span(t.leftMs, t.backMs, loc), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        Format.minutes(t.minutes, ctx),
                        if (t.fromPct != null && t.toPct != null) "${t.fromPct} → ${t.toPct} %" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val used = t.usedPct
            Text(
                when {
                    used == null -> "–"
                    used >= 0 -> stringResource(R.string.hist_used, used)
                    else -> stringResource(R.string.hist_gained, -used)
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/** "Tue 14 Oct, 22:10–01:40". */
private fun span(fromMs: Long, toMs: Long, loc: Locale): String {
    val z = ZoneId.systemDefault()
    val a = Instant.ofEpochMilli(fromMs).atZone(z)
    val b = Instant.ofEpochMilli(toMs).atZone(z)
    val day = a.format(DateTimeFormatter.ofPattern("EEE d MMM", loc))
    val t = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(loc)
    return "$day, ${a.format(t)}–${b.format(t)}"
}
