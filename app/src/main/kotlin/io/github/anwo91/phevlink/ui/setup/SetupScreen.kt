package io.github.anwo91.phevlink.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.settings.CarWifiCard
import io.github.anwo91.phevlink.ui.settings.REGISTRATION_STEPS

private const val PAGES = 4

@Composable
fun SetupScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val car by vm.car.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LinearProgressIndicator(progress = { (page + 1f) / PAGES }, modifier = Modifier.fillMaxWidth())
            when (page) {
                0 -> {
                    Text("PHEV Link", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Control your Outlander PHEV over its own Wi-Fi: battery and charging, climate (heat, cool, defrost), " +
                            "lights, doors — plus departure timers that read the weather, a home-screen widget, a quick-settings tile, " +
                            "and alerts. Optionally through a small bridge at home, so it works from anywhere.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Setup takes a few minutes. Have the car nearby with its key, and the Wi-Fi card that came with the keys.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        page = 1
                    }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
                }
                1 -> {
                    Text("1 · The car's Wi-Fi", style = MaterialTheme.typography.titleLarge)
                    CarWifiCard(vm, s, showTest = false)
                    Text(
                        "Pairing is optional but recommended: without it Android asks \"Connect to device?\" now and then, " +
                            "and background features (widget, departures) can't connect on their own.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = 0 }) { Text("Back") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { page = 2 }, enabled = s.hasCarWifi) { Text("Next") }
                    }
                    TextButton(onClick = { page = 3 }) { Text("I'll only use a bridge — skip") }
                }
                2 -> {
                    Text("2 · Connect and register", style = MaterialTheme.typography.titleLarge)
                    SectionCard(title = "Connect") {
                        Text("Status: ${link.text}", style = MaterialTheme.typography.bodyMedium)
                        link.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (car.vin != null) Text("Car: ${car.vin} · ${car.batteryPercent ?: "?"}%", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        var testing by remember { mutableStateOf(false) }
                        Button(
                            onClick = { testing = true; vm.testConnection { _, msg -> testing = false; vm.toast(msg) } },
                            enabled = !testing,
                        ) {
                            if (testing) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Connect")
                        }
                    }
                    SectionCard(title = "Not registered yet?") {
                        Text(
                            "If the connection stops at \"Saying hello\" and then fails, this phone isn't registered with the car yet. " +
                                "Phones that used the official app are usually already registered.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(REGISTRATION_STEPS, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.run(CarCommand.RegisterPhone, "Phone registered") }) { Text("Register this phone") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = 1 }) { Text("Back") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { page = 3 }) { Text(if (link.connected) "Next" else "Skip for now") }
                    }
                }
                else -> {
                    Text("3 · All set", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Good to know:\n" +
                            "• Long-press the app icon for shortcuts (preheat, defrost, flash lights).\n" +
                            "• Add the widget and the \"Preheat\" quick-settings tile.\n" +
                            "• Schedule your usual departures in the Schedule tab.\n" +
                            "• Every connection attempt is logged in Diagnostics — share the log if something misbehaves.\n" +
                            "• For control from anywhere, set up the bridge (see the README) and enter its address in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = 2 }) { Text("Back") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { vm.updateSettings { it.copy(setupDone = true) } }) { Text("Open the app") }
                    }
                }
            }
        }
    }
}
