package io.github.anwo91.phevlink.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
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
import io.github.anwo91.phevlink.ui.setup.BridgeSetupScreen
import io.github.anwo91.phevlink.ui.home.HomeScreen
import io.github.anwo91.phevlink.ui.obd.ObdScreen
import io.github.anwo91.phevlink.ui.obd.ObdViewModel
import io.github.anwo91.phevlink.ui.tips.TipsScreen
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
        setContent {
            val settings by vm.settings.collectAsState()
            PhevTheme(dynamicColor = settings.dynamicColor) { AppRoot(vm, obdVm) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onVisible()
    }

    override fun onStop() {
        vm.onHidden()
        super.onStop()
    }
}

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CAR(R.string.tab_car, Icons.Outlined.DirectionsCar),
    SCHEDULE(R.string.tab_schedule, Icons.Outlined.Schedule),
    OBD(R.string.tab_obd, Icons.Outlined.Build),
    DIAG(R.string.tab_log, Icons.Outlined.BugReport),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings),
}

/** Full-screen pages opened from a tab. */
private object Page {
    const val VEHICLE = "vehicle"
    const val TIPS = "tips"
    const val BRIDGE = "bridge"
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
    if (!settings.setupDone && !demo) {
        SetupScreen(vm)
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // An open page ("vehicle", "tips" or "tips:<id>") is drawn over the current tab.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
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
                        selected = tab == i,
                        onClick = { tab = i; page = null },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        val p = page
        when {
            p == Page.VEHICLE -> VehicleSettingsScreen(vm, m, onBack = { page = null })
            p == Page.BRIDGE -> BridgeSetupScreen(vm, m, onBack = { page = null }, onFinished = { page = null })
            p != null && p.startsWith(Page.TIPS) -> TipsScreen(
                m, initialTip = p.substringAfter(':', "").ifEmpty { null },
                onBack = { page = null },
                onOpenTests = { page = null; tab = Tab.OBD.ordinal },
                onOpenVehicleSettings = { page = Page.VEHICLE },
            )
            else -> when (Tab.entries[tab]) {
                Tab.CAR -> HomeScreen(
                    vm, m,
                    onOpenSchedule = { tab = Tab.SCHEDULE.ordinal },
                    onOpenDiagnostics = { tab = Tab.DIAG.ordinal },
                    onOpenVehicleSettings = { page = Page.VEHICLE },
                    onOpenTips = { page = Page.TIPS },
                    onOpenObd = { tab = Tab.OBD.ordinal },
                )
                Tab.SCHEDULE -> ScheduleScreen(vm, m)
                Tab.OBD -> ObdScreen(obdVm, m, onOpenTip = { page = Page.TIPS + ":" + it })
                Tab.DIAG -> DiagnosticsScreen(vm, m)
                Tab.SETTINGS -> SettingsScreen(vm, m, onOpenBridgeSetup = { page = Page.BRIDGE })
            }
        }
    }
}
