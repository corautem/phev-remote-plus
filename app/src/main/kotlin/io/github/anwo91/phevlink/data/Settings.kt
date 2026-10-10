package io.github.anwo91.phevlink.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.anwo91.phevlink.core.alerts.AlertPrefs
import io.github.anwo91.phevlink.core.json.Codecs
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.planner.Departure
import io.github.anwo91.phevlink.core.protocol.ModelYear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "settings")

/** How the phone reaches the car. */
enum class Route(val label: String) {
    AUTO("Automatic"),
    DIRECT("Car Wi-Fi only"),
    BRIDGE("Bridge only"),
}

data class AppSettings(
    val setupDone: Boolean = false,
    val ssid: String = "",
    val password: String = "",
    /** Car access point MAC, from companion pairing; enables silent background connects. */
    val bssid: String = "",
    /** "host:port" to talk to an emulator instead of the car (diagnostics). */
    val hostOverride: String = "",
    val modelYearOverride: ModelYear = ModelYear.UNKNOWN,
    val route: Route = Route.AUTO,
    val bridgeUrl: String = "",
    val bridgeToken: String = "",
    val quickMode: ClimateMode = ClimateMode.HEAT,
    val quickDuration: ClimateDuration = ClimateDuration.MIN_20,
    val quickSmart: Boolean = true,
    val departures: List<Departure> = emptyList(),
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    val alerts: AlertPrefs = AlertPrefs(),
    val phoneAlerts: Boolean = true,
    /** Keep checking in the background while the car is charging or preconditioning. */
    val watchWhileBusy: Boolean = true,
    val watchIntervalMinutes: Int = 10,
    val fullRangeKm: Int = 45,
    val syncClock: Boolean = true,
    val recordPings: Boolean = false,
) {
    val hasCarWifi: Boolean get() = ssid.isNotBlank() && passwordProblem(password) == null
    val hasBridge: Boolean get() = bridgeUrl.isNotBlank()
    val canConnect: Boolean get() = hasCarWifi || hasBridge || hostOverride.isNotBlank()
}

/** Why a WPA2 password would be rejected by Android, or null if it is fine. */
fun passwordProblem(pw: String): String? = when {
    pw.length < 8 -> "At least 8 characters"
    pw.length > 63 -> "At most 63 characters"
    pw.any { it.code !in 0x20..0x7e } -> "Only plain letters, digits and symbols (no æøå or emoji)"
    else -> null
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {

    private object K {
        val setupDone = booleanPreferencesKey("setup_done")
        val ssid = stringPreferencesKey("ssid")
        val password = stringPreferencesKey("password")
        val bssid = stringPreferencesKey("bssid")
        val hostOverride = stringPreferencesKey("host_override")
        val modelYear = stringPreferencesKey("model_year")
        val route = stringPreferencesKey("route")
        val bridgeUrl = stringPreferencesKey("bridge_url")
        val bridgeToken = stringPreferencesKey("bridge_token")
        val quickMode = stringPreferencesKey("quick_mode")
        val quickDuration = intPreferencesKey("quick_duration")
        val quickSmart = booleanPreferencesKey("quick_smart")
        val departures = stringPreferencesKey("departures")
        val homeLat = doublePreferencesKey("home_lat")
        val homeLon = doublePreferencesKey("home_lon")
        val phoneAlerts = booleanPreferencesKey("phone_alerts")
        val aChargeComplete = booleanPreferencesKey("a_charge_complete")
        val aChargeStopped = booleanPreferencesKey("a_charge_stopped")
        val aClimateFinished = booleanPreferencesKey("a_climate_finished")
        val aClimateCancelled = booleanPreferencesKey("a_climate_cancelled")
        val aLeftUnlocked = booleanPreferencesKey("a_left_unlocked")
        val aDoorOpen = booleanPreferencesKey("a_door_open")
        val aLightsOn = booleanPreferencesKey("a_lights_on")
        val watch = booleanPreferencesKey("watch")
        val watchInterval = intPreferencesKey("watch_interval")
        val fullRange = intPreferencesKey("full_range_km")
        val syncClock = booleanPreferencesKey("sync_clock")
        val recordPings = booleanPreferencesKey("record_pings")
    }

    val flow: StateFlow<AppSettings> = context.dataStore.data
        .map(::fromPrefs)
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { fromPrefs(context.dataStore.data.first()) })

    val current: AppSettings get() = flow.value

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        val a = d.alerts
        return AppSettings(
            setupDone = p[K.setupDone] ?: false,
            ssid = p[K.ssid] ?: "",
            password = p[K.password] ?: "",
            bssid = p[K.bssid] ?: "",
            hostOverride = p[K.hostOverride] ?: "",
            modelYearOverride = p[K.modelYear]?.let { runCatching { ModelYear.valueOf(it) }.getOrNull() } ?: ModelYear.UNKNOWN,
            route = p[K.route]?.let { runCatching { Route.valueOf(it) }.getOrNull() } ?: Route.AUTO,
            bridgeUrl = p[K.bridgeUrl] ?: "",
            bridgeToken = p[K.bridgeToken] ?: "",
            quickMode = p[K.quickMode]?.let { runCatching { ClimateMode.valueOf(it) }.getOrNull() } ?: d.quickMode,
            quickDuration = p[K.quickDuration]?.let { ClimateDuration.fromMinutes(it) } ?: d.quickDuration,
            quickSmart = p[K.quickSmart] ?: d.quickSmart,
            departures = Codecs.departuresFromJson(p[K.departures]),
            homeLat = p[K.homeLat],
            homeLon = p[K.homeLon],
            phoneAlerts = p[K.phoneAlerts] ?: true,
            alerts = a.copy(
                chargeComplete = p[K.aChargeComplete] ?: a.chargeComplete,
                chargeStopped = p[K.aChargeStopped] ?: a.chargeStopped,
                climateFinished = p[K.aClimateFinished] ?: a.climateFinished,
                climateCancelled = p[K.aClimateCancelled] ?: a.climateCancelled,
                leftUnlocked = p[K.aLeftUnlocked] ?: a.leftUnlocked,
                doorOpen = p[K.aDoorOpen] ?: a.doorOpen,
                lightsOn = p[K.aLightsOn] ?: a.lightsOn,
            ),
            watchWhileBusy = p[K.watch] ?: true,
            watchIntervalMinutes = p[K.watchInterval] ?: 10,
            fullRangeKm = p[K.fullRange] ?: 45,
            syncClock = p[K.syncClock] ?: true,
            recordPings = p[K.recordPings] ?: false,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(fromPrefs(p))
            p[K.setupDone] = s.setupDone
            p[K.ssid] = s.ssid.trim()
            p[K.password] = s.password
            p[K.bssid] = s.bssid.trim()
            p[K.hostOverride] = s.hostOverride.trim()
            p[K.modelYear] = s.modelYearOverride.name
            p[K.route] = s.route.name
            p[K.bridgeUrl] = s.bridgeUrl.trim().trimEnd('/')
            p[K.bridgeToken] = s.bridgeToken.trim()
            p[K.quickMode] = s.quickMode.name
            p[K.quickDuration] = s.quickDuration.minutes
            p[K.quickSmart] = s.quickSmart
            p[K.departures] = Codecs.departuresToJson(s.departures)
            if (s.homeLat != null && s.homeLon != null) {
                p[K.homeLat] = s.homeLat; p[K.homeLon] = s.homeLon
            } else {
                p.remove(K.homeLat); p.remove(K.homeLon)
            }
            p[K.phoneAlerts] = s.phoneAlerts
            p[K.aChargeComplete] = s.alerts.chargeComplete
            p[K.aChargeStopped] = s.alerts.chargeStopped
            p[K.aClimateFinished] = s.alerts.climateFinished
            p[K.aClimateCancelled] = s.alerts.climateCancelled
            p[K.aLeftUnlocked] = s.alerts.leftUnlocked
            p[K.aDoorOpen] = s.alerts.doorOpen
            p[K.aLightsOn] = s.alerts.lightsOn
            p[K.watch] = s.watchWhileBusy
            p[K.watchInterval] = s.watchIntervalMinutes
            p[K.fullRange] = s.fullRangeKm
            p[K.syncClock] = s.syncClock
            p[K.recordPings] = s.recordPings
        }
    }
}
