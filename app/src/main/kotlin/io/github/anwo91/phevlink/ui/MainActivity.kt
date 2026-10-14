package io.github.anwo91.phevlink.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.link.DemoMode
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.ui.diag.DiagnosticsScreen
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.setup.BridgeSetupScreen
import io.github.anwo91.phevlink.ui.bridge.BridgeScreen
import io.github.anwo91.phevlink.ui.schedule.ChargeTimerScreen
import io.github.anwo91.phevlink.ui.home.HomeScreen
import io.github.anwo91.phevlink.ui.obd.ObdScreen
import io.github.anwo91.phevlink.ui.obd.ObdViewModel
import io.github.anwo91.phevlink.ui.tips.Faq
import io.github.anwo91.phevlink.ui.tips.TipsScreen
import io.github.anwo91.phevlink.ui.bridge.SmartHomeScreen
import io.github.anwo91.phevlink.ui.vehicle.VehicleSettingsScreen
import io.github.anwo91.phevlink.ui.schedule.ScheduleScreen
import io.github.anwo91.phevlink.ui.settings.SettingsScreen
import io.github.anwo91.phevlink.ui.setup.SetupScreen
import io.github.anwo91.phevlink.ui.theme.PhevTheme
import io.github.anwo91.phevlink.util.AppLocale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val obdVm: ObdViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.requestPage(intent?.getStringExtra(EXTRA_OPEN))
        setContent {
            val settings by vm.settings.collectAsState()
            PhevTheme(dynamicColor = settings.dynamicColor) { AppRoot(vm, obdVm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.requestPage(intent.getStringExtra(EXTRA_OPEN))
    }

    override fun onStart() {
        super.onStart()
        vm.onVisible()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's "install unknown apps" setting: carry on with the app update.
        vm.appUpdater.onResume(this)
        // Departures or settings changed on another phone.
        vm.sharedSync.syncIfStale()
    }

    companion object {
        /** Intent extra: a page to open on launch (from a notification). */
        const val EXTRA_OPEN = "io.github.anwo91.phevlink.OPEN"
        const val OPEN_BRIDGE_SETUP = "bridge"
    }

    override fun onStop() {
        vm.onHidden()
        super.onStop()
    }
}

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CAR(R.string.tab_car, PhevIcons.Car),
    SCHEDULE(R.string.tab_schedule, PhevIcons.Clock),
    OBD(R.string.tab_obd, PhevIcons.Obd),
    SETTINGS(R.string.tab_settings, PhevIcons.Settings),
}

/** Full-screen pages opened from a tab. */
private object Page {
    const val VEHICLE = "vehicle"
    const val TIPS = "tips"
    const val BRIDGE = "bridge"
    const val BRIDGE_STATUS = "bridge-status"
    const val CHARGE_TIMER = "charge-timer"
    const val ACTIVITY = "activity"
    /** The app's own log, the car's registers and tools (was a tab; now under Settings → Help and support). */
    const val DIAGNOSTICS = "diagnostics"
    /** Google Home ("smart-home:matter") or Home Assistant ("smart-home:ha"). */
    const val SMART_HOME = "smart-home"
}

/** The always-visible EMULATION bar, shown while demo mode is on so simulated data is never mistaken for the real car. */
@Composable
private fun EmulationBanner(onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.emulation_banner),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStop) { Text(stringResource(R.string.emulation_stop)) }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel, obdVm: ObdViewModel) {
    val settings by vm.settings.collectAsState()
    val demo by DemoMode.active.collectAsState()
    val setupRequested by vm.setupRequested.collectAsState()
    // The first-run setup is for phones that can't reach the car yet (or when the owner asks for it again). A phone
    // whose car Wi-Fi or bridge is already saved when the app starts never lands there by itself — e.g. after an app
    // update. Checked once per start, so saving the Wi-Fi during the setup doesn't end the setup early.
    val strayAtStart = remember { !settings.setupDone && settings.canConnect && !setupRequested }
    val stray = strayAtStart && !settings.setupDone && !setupRequested
    LaunchedEffect(stray) {
        if (stray) {
            vm.log.warn("Setup: marked unfinished although the car Wi-Fi/bridge is saved — opening the app, not the setup")
            vm.updateSettings { it.copy(setupDone = true) }
        }
    }
    if (!settings.setupDone && !demo && !stray) {
        SetupScreen(vm)
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A tab index saved by an older version (it had five tabs) may not exist any more.
    val current = Tab.entries.getOrElse(tab) { Tab.CAR }
    // An open page ("vehicle", "tips" or "tips:<id>") is drawn over the current tab. [previous]: the page it was
    // opened from (one step), so Back returns there — e.g. Bridge → Google Home → back to the bridge page.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var previous by rememberSaveable { mutableStateOf<String?>(null) }
    fun show(p: String) { previous = page; page = p }
    fun back() { page = previous?.takeIf { it != page }; previous = null }
    fun openTab(t: Tab) { tab = t.ordinal; page = null; previous = null }
    // Which page shows (also one restored after the app was restarted, e.g. by an update) — logged for support.
    LaunchedEffect(page) { vm.log.link("UI: ${page?.let { "page $it" } ?: "tabs"}") }
    // A page asked for from outside (a notification tap): open it once.
    val requested by vm.openPage.collectAsState()
    LaunchedEffect(requested) {
        requested?.let {
            page = it
            previous = null
            vm.pageOpened()
        }
    }
    val appUpd by vm.appUpdater.state.collectAsState()
    val bridgeAdm by vm.bridgeAdmin.state.collectAsState()
    val updates = appUpd.available || (bridgeAdm.update?.available == true && bridgeAdm.canUpdate)
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snack.showSnackbar(it) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { if (demo) EmulationBanner(onStop = { vm.setDemo(false) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = current == t,
                        onClick = { openTab(t) },
                        icon = {
                            // A dot on Settings when the app or the bridge has an update.
                            if (t == Tab.SETTINGS && updates) BadgedBox(badge = { Badge() }) { Icon(t.icon, contentDescription = null) }
                            else Icon(t.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        val p = page
        when {
            p == Page.VEHICLE -> VehicleSettingsScreen(vm, m, onBack = { back() })
            p == Page.BRIDGE -> BridgeSetupScreen(vm, m, onBack = { back() }, onFinished = { page = null; previous = null })
            p == Page.BRIDGE_STATUS -> BridgeScreen(
                vm, m, onBack = { back() }, onOpenSetup = { show(Page.BRIDGE) }, onOpenActivity = { show(Page.ACTIVITY) },
                onOpenSmartHome = { show(Page.SMART_HOME + ":" + it) },
            )
            p == Page.CHARGE_TIMER -> ChargeTimerScreen(vm, m, onBack = { back() })
            p == Page.ACTIVITY -> io.github.anwo91.phevlink.ui.activity.ActivityScreen(vm, m, onBack = { back() })
            p == Page.DIAGNOSTICS -> DiagnosticsScreen(vm, m, onBack = { back() })
            p != null && p.startsWith(Page.SMART_HOME) -> SmartHomeScreen(
                vm, m, which = p.substringAfter(':', "matter"), onBack = { back() }, onOpenSetup = { show(Page.BRIDGE) },
            )
            p != null && p.startsWith(Page.TIPS) -> TipsScreen(
                m, initialTip = p.substringAfter(':', "").ifEmpty { null },
                onBack = { back() },
                onOpenTests = { openTab(Tab.OBD) },
                onOpenVehicleSettings = { show(Page.VEHICLE) },
            )
            else -> when (current) {
                Tab.CAR -> HomeScreen(
                    vm, m,
                    onOpenSchedule = { openTab(Tab.SCHEDULE) },
                    onOpenDiagnostics = { show(Page.DIAGNOSTICS) },
                    onOpenVehicleSettings = { show(Page.VEHICLE) },
                    onOpenHelp = { id -> show(if (id == null) Page.TIPS else Page.TIPS + ":" + Faq.PREFIX + id) },
                    onOpenBridgeSetup = { show(Page.BRIDGE) },
                    onOpenChargeTimer = { show(Page.CHARGE_TIMER) },
                    onOpenActivity = { show(Page.ACTIVITY) },
                )
                Tab.SCHEDULE -> ScheduleScreen(vm, m, onOpenChargeTimer = { show(Page.CHARGE_TIMER) })
                Tab.OBD -> ObdScreen(obdVm, m, onOpenTip = { show(Page.TIPS + ":" + it) })
                Tab.SETTINGS -> SettingsScreen(
                    vm, m,
                    onOpenBridgeSetup = { show(Page.BRIDGE) },
                    onOpenBridge = { show(Page.BRIDGE_STATUS) },
                    onOpenSmartHome = { show(Page.SMART_HOME + ":" + it) },
                    onOpenHelp = { show(Page.TIPS) },
                    onOpenDiagnostics = { show(Page.DIAGNOSTICS) },
                )
            }
        }
    }
}
