package io.github.anwo91.phevlink.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.anwo91.phevlink.ui.diag.DiagnosticsScreen
import io.github.anwo91.phevlink.ui.home.HomeScreen
import io.github.anwo91.phevlink.ui.schedule.ScheduleScreen
import io.github.anwo91.phevlink.ui.settings.SettingsScreen
import io.github.anwo91.phevlink.ui.setup.SetupScreen
import io.github.anwo91.phevlink.ui.theme.PhevTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PhevTheme { AppRoot(vm) } }
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

private enum class Tab(val label: String, val icon: ImageVector) {
    CAR("Car", Icons.Outlined.DirectionsCar),
    SCHEDULE("Schedule", Icons.Outlined.Schedule),
    DIAG("Diagnostics", Icons.Outlined.BugReport),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    if (!settings.setupDone) {
        SetupScreen(vm)
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snack.showSnackbar(it) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (Tab.entries[tab]) {
            Tab.CAR -> HomeScreen(vm, m, onOpenSchedule = { tab = Tab.SCHEDULE.ordinal }, onOpenDiagnostics = { tab = Tab.DIAG.ordinal })
            Tab.SCHEDULE -> ScheduleScreen(vm, m)
            Tab.DIAG -> DiagnosticsScreen(vm, m)
            Tab.SETTINGS -> SettingsScreen(vm, m)
        }
    }
}
