package io.github.anwo91.phevlink.ui.activity

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.activity.ActivityGroup
import io.github.anwo91.phevlink.core.activity.ActivityText
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.VehicleSettingsText
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the activity page shows: the bridge's shared log (when reachable) plus this phone's own direct actions. */
class ActivityFeed(val entries: List<Activity>, val bridgeState: BridgeState, val more: Boolean)

enum class BridgeState { NONE, OK, UNREACHABLE, TOO_OLD }

/** Fetches the bridge's activity (older pages with [beforeMs]); null entries when it can't be reached. */
suspend fun fetchBridgeActivity(url: String, token: String, limit: Int, beforeMs: Long? = null): Pair<BridgeState, List<Activity>> {
    val r = BridgeSetupApi.activity(url, token, limit, beforeMs) ?: return BridgeState.UNREACHABLE to emptyList()
    if (r.code == 404) return BridgeState.TOO_OLD to emptyList()
    if (r.code != 200) return BridgeState.UNREACHABLE to emptyList()
    @Suppress("UNCHECKED_CAST")
    val list = (r.body["entries"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(Activity::fromMap) }
    return BridgeState.OK to list
}

private enum class Filter(val label: Int) {
    ALL(R.string.act_f_all), CHARGING(R.string.act_f_charging), CLIMATE(R.string.act_f_climate),
    CAR(R.string.act_f_car), PEOPLE(R.string.act_f_people), BRIDGE(R.string.act_f_bridge);

    fun keeps(a: Activity): Boolean = when (this) {
        ALL -> true
        CHARGING -> a.group == ActivityGroup.CHARGING
        CLIMATE -> a.group == ActivityGroup.CLIMATE
        CAR -> a.group == ActivityGroup.CAR
        PEOPLE -> a.who != Activity.CAR && a.who != Activity.BRIDGE
        BRIDGE -> a.group == ActivityGroup.BRIDGE || a.who == Activity.BRIDGE
    }
}

/**
 * Activity: who did what with the car (each phone by its name; actions next to the car marked), what the car did —
 * charging in detail with a summary per charge — the bridge's departures and its own events. Newest first, by day,
 * with filters. Reads the bridge's log; this phone's own direct actions show even without a bridge.
 */
@Composable
fun ActivityScreen(vm: MainViewModel, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val local by vm.activityRecorder.entries.collectAsState()
    var remote by remember { mutableStateOf<List<Activity>>(emptyList()) }
    var state by remember { mutableStateOf(if (settings.hasBridge) BridgeState.OK else BridgeState.NONE) }
    var loading by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    suspend fun load(before: Long? = null) {
        val s = vm.settings.value
        if (!s.hasBridge || s.bridgeToken.isBlank()) {
            state = BridgeState.NONE
            return
        }
        loading = true
        val (st, list) = fetchBridgeActivity(s.bridgeUrl, s.bridgeToken, PAGE, before)
        loading = false
        state = st
        if (st == BridgeState.OK) {
            remote = if (before == null) (list + remote.filter { r -> list.none { it.id == r.id } && r.atMs < (list.lastOrNull()?.atMs ?: 0) })
            else remote + list.filter { l -> remote.none { it.id == l.id } }
            more = list.size >= PAGE
        }
        vm.log.link("Activity page: ${list.size} entries from the bridge ($st), ${vm.activityRecorder.entries.value.size} from this phone")
    }

    LaunchedEffect(Unit) {
        // Anything this phone did next to the car goes to the bridge first, so it shows in its place.
        vm.activityRecorder.upload("activity page")
        while (true) {
            load()
            delay(60_000)
        }
    }

    val all = remember(remote, local) {
        (remote + local.filter { l -> remote.none { it.id == l.id } }).sortedByDescending { it.atMs }
    }
    val shown = all.filter { filter.keeps(it) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dayFmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(AppLocale.uiLocale(ctx)) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PhevIcons.Back, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.act_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = { scope.launch { load() } }) { Icon(PhevIcons.Refresh, stringResource(R.string.common_refresh)) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Text(
                    stringResource(if (state == BridgeState.NONE) R.string.act_intro_no_bridge else R.string.act_intro),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    BridgeState.UNREACHABLE -> Text(stringResource(R.string.act_unreachable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    BridgeState.TOO_OLD -> Text(stringResource(R.string.act_bridge_old), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Filter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
                    }
                }
                if (shown.isEmpty() && !loading) Text(stringResource(R.string.act_empty), style = MaterialTheme.typography.bodyMedium)
            }
            var lastDay: LocalDate? = null
            val rows = ArrayList<Pair<LocalDate?, Activity>>()
            for (a in shown) {
                val d = Instant.ofEpochMilli(a.atMs).atZone(zone).toLocalDate()
                rows += (if (d != lastDay) d else null) to a
                lastDay = d
            }
            items(rows, key = { it.second.id }) { (header, a) ->
                Column {
                    if (header != null) {
                        Text(
                            when (header) {
                                today -> stringResource(R.string.act_today)
                                today.minusDays(1) -> stringResource(R.string.act_yesterday)
                                else -> header.format(dayFmt).replaceFirstChar { it.uppercase() }
                            },
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    ActivityRow(a)
                }
            }
            if (more && state == BridgeState.OK) item {
                TextButton(onClick = { scope.launch { load(before = remote.minOfOrNull { it.atMs }) } }) { Text(stringResource(R.string.act_load_more)) }
            }
        }
    }
}

@Composable
fun ActivityRow(a: Activity) {
    val ctx = LocalContext.current
    val status = LocalStatus.current
    val text = remember(a) { ActivityLabels.text(ctx, a) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(Format.clock(a.atMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp).padding(top = 2.dp))
        Icon(
            iconFor(a), null, Modifier.size(18.dp).padding(top = 2.dp),
            tint = when {
                !a.ok || a.code == "car.alarm" -> status.bad
                a.group == ActivityGroup.CHARGING -> status.charge
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (a.ok) MaterialTheme.colorScheme.onSurface else status.bad)
            if (a.via == "direct") Text(stringResource(R.string.act_direct), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun iconFor(a: Activity): ImageVector = when {
    a.group == ActivityGroup.CHARGING -> PhevIcons.Bolt
    a.group == ActivityGroup.CLIMATE -> PhevIcons.Temperature
    a.group == ActivityGroup.BRIDGE -> PhevIcons.Bridge
    a.code.startsWith("car.door") -> PhevIcons.Door
    a.code == "car.locked" -> PhevIcons.Lock
    a.code == "car.unlocked" -> PhevIcons.Unlock
    a.code.contains("light") || a.code.contains("hazard") || a.code == "cmd.locate" -> PhevIcons.Headlights
    a.code == "car.alarm" -> PhevIcons.Warning
    a.code.startsWith("car.") -> PhevIcons.Car
    else -> PhevIcons.Phone
}

private const val PAGE = 150

/** The activity log in the app's language (English from the core for anything it doesn't know yet). */
object ActivityLabels {
    fun text(ctx: Context, a: Activity): String {
        val res = AppLocale.localized(ctx)
        val lang = AppLocale.effectiveLanguage(ctx)
        val who = when (a.who) {
            Activity.CAR -> res.getString(R.string.act_who_car)
            Activity.BRIDGE -> res.getString(R.string.act_who_bridge)
            else -> a.who
        }
        val mode = a.str("mode")?.let { runCatching { ClimateMode.valueOf(it) }.getOrNull() }?.let { res.getString(Labels.modeInline(it)) }
        val pct = a.int("pct")
        val minutes = a.int("minutes")
        fun withPct(id: Int) = pct?.let { res.getString(id, it) }
        val base: String? = when (a.code) {
            "cmd.climate" -> if (mode != null && minutes != null) res.getString(R.string.act_cmd_climate, who, mode, minutes) else null
            "cmd.climate_off" -> res.getString(R.string.act_cmd_climate_off, who)
            "cmd.headlights" -> res.getString(if (a.bool("on") == true) R.string.act_cmd_headlights_on else R.string.act_cmd_headlights_off, who)
            "cmd.parking_lights" -> res.getString(if (a.bool("on") == true) R.string.act_cmd_parking_on else R.string.act_cmd_parking_off, who)
            "cmd.locate" -> res.getString(R.string.act_cmd_locate, who)
            "cmd.charge_now" -> res.getString(R.string.act_cmd_charge_now, who)
            "cmd.resume_charge_timer" -> res.getString(R.string.act_cmd_resume_timer, who)
            "cmd.charge_timer" -> res.getString(R.string.act_cmd_charge_timer, who)
            "cmd.setting" -> {
                val id = a.int("id")
                val value = a.int("value")
                if (id != null && value != null) res.getString(R.string.act_cmd_setting, who, VehicleSettingsText.name(id, lang), VehicleSettingsText.option(id, value, lang)) else null
            }
            "cmd.reset_settings" -> res.getString(R.string.act_cmd_reset_settings, who)
            "cmd.register" -> res.getString(R.string.act_cmd_register, who)
            "cmd.unregister" -> res.getString(R.string.act_cmd_unregister, who)
            "cmd.ack_climate" -> res.getString(R.string.act_cmd_ack_climate, who)
            "cmd.wifi_always_on" -> res.getString(if (a.bool("on") == true) R.string.act_cmd_wifi_on else R.string.act_cmd_wifi_off, who)
            "car.plugged" -> withPct(R.string.act_car_plugged)
            "car.unplugged" -> withPct(R.string.act_car_unplugged)
            "car.charge_started" -> withPct(if (a.str("kind") == "quick") R.string.act_car_quick_started else R.string.act_car_charge_started)
                ?.plus(a.int("minutesLeft")?.takeIf { it in 1..1440 }?.let { " · " + res.getString(R.string.act_to_full, Format.minutes(it, ctx)) } ?: "")
            "car.charge_progress" -> withPct(R.string.act_car_charge_progress)
                ?.plus(a.int("minutesLeft")?.takeIf { it in 1..1440 }?.let { " · " + res.getString(R.string.act_to_full, Format.minutes(it, ctx)) } ?: "")
            "car.charge_stopped" -> when (a.str("why")) {
                "full" -> res.getString(R.string.act_car_charge_full)
                "unplugged" -> withPct(R.string.act_car_charge_unplugged)
                "timer" -> res.getString(R.string.act_car_charge_timer)
                else -> withPct(R.string.act_car_charge_stopped)
            }
            "charge.session" -> {
                val from = a.int("fromPct")
                val to = a.int("toPct")
                val kwh = (a.params["kwh"] as? Number)?.toDouble()
                if (from != null && to != null && minutes != null && kwh != null) {
                    res.getString(R.string.act_charge_session, from, to, Format.minutes(minutes, ctx), String.format(AppLocale.uiLocale(ctx), "%.1f", kwh))
                } else null
            }
            "car.charge_waiting" -> res.getString(R.string.act_car_charge_waiting)
            "car.charge_failed" -> res.getString(R.string.act_car_charge_failed)
            "car.battery_heating" -> res.getString(R.string.act_car_battery_heating)
            "car.climate_on" -> mode?.let { res.getString(R.string.act_car_climate_on, it) } ?: res.getString(R.string.act_car_climate_on_plain)
            "car.climate_off" -> res.getString(R.string.act_car_climate_off)
            "car.climate_cancelled" -> res.getString(R.string.act_car_climate_cancelled)
            "car.locked" -> res.getString(R.string.act_car_locked)
            "car.unlocked" -> res.getString(R.string.act_car_unlocked)
            "car.door_open" -> doorName(res, a.str("door"))?.let { res.getString(R.string.act_car_door_open, it) }
            "car.doors_closed" -> res.getString(R.string.act_car_doors_closed)
            "car.headlights_on" -> res.getString(R.string.act_car_headlights_on)
            "car.headlights_off" -> res.getString(R.string.act_car_headlights_off)
            "car.hazards_on" -> res.getString(R.string.act_car_hazards_on)
            "car.hazards_off" -> res.getString(R.string.act_car_hazards_off)
            "car.switched_on" -> res.getString(R.string.act_car_switched_on)
            "car.switched_off" -> res.getString(R.string.act_car_switched_off)
            "car.alarm" -> res.getString(R.string.act_car_alarm)
            "departure.started" -> if (mode != null && minutes != null) res.getString(R.string.act_dep_started, mode, minutes, a.str("at") ?: "?") else null
            "departure.skipped" -> res.getString(R.string.act_dep_skipped, a.str("at") ?: "?")
            "departure.failed" -> res.getString(R.string.act_dep_failed, a.str("at") ?: "?")
            "departure.away" -> res.getString(R.string.act_dep_away, a.str("at") ?: "?")
            "shared.changed" -> res.getString(if (a.str("keys")?.contains("departures") == true) R.string.act_shared_departures else R.string.act_shared_changed, who)
            "bridge.started" -> res.getString(R.string.act_bridge_started, a.str("build") ?: "?")
            "bridge.update" -> res.getString(R.string.act_bridge_update, who)
            "bridge.restart" -> res.getString(R.string.act_bridge_restart, who)
            "bridge.reboot" -> res.getString(R.string.act_bridge_reboot, who)
            "bridge.carwifi_paused" -> a.int("minutes")?.let { res.getString(R.string.act_bridge_paused, who, it) }
            "bridge.carwifi_resumed" -> res.getString(R.string.act_bridge_resumed)
            "bridge.carwifi_lost" -> res.getString(if (a.bool("inSight") == true) R.string.act_bridge_lost_near else R.string.act_bridge_lost_away)
            "bridge.carwifi_back" -> minutes?.let { res.getString(R.string.act_bridge_back, Format.minutes(it, ctx)) } ?: res.getString(R.string.act_bridge_resumed)
            "bridge.smarthome_on" -> res.getString(R.string.act_bridge_smarthome_on, who)
            "bridge.smarthome_off" -> res.getString(R.string.act_bridge_smarthome_off, who)
            "bridge.paired" -> res.getString(R.string.act_bridge_paired, who)
            "bridge.invite" -> res.getString(R.string.act_bridge_invite, who)
            else -> null
        }
        val text = base ?: ActivityText.english(a).removeSuffix(failedSuffix(a))
        return if (a.ok) text else text + " — " + res.getString(R.string.act_failed, Labels.failureReason(ctx, a.detail ?: "?"))
    }

    private fun failedSuffix(a: Activity) = if (a.ok) "" else " — failed" + (a.detail?.let { ": $it" } ?: "")

    private fun doorName(res: Context, d: String?): String? = when (d) {
        "driver" -> res.getString(R.string.door_driver)
        "passenger" -> res.getString(R.string.door_passenger)
        "rear_left" -> res.getString(R.string.door_rear_left)
        "rear_right" -> res.getString(R.string.door_rear_right)
        "boot" -> res.getString(R.string.door_boot)
        "bonnet" -> res.getString(R.string.door_bonnet)
        else -> null
    }?.replaceFirstChar { it.uppercase() }
}
