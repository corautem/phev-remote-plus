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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.settings.CarWifiCard

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
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.setup_intro),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.setup_intro_2),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        page = 1
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_start)) }
                }
                1 -> {
                    Text(stringResource(R.string.setup_step1_title), style = MaterialTheme.typography.titleLarge)
                    CarWifiCard(vm, s, showTest = false)
                    Text(
                        stringResource(R.string.setup_pairing_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = 0 }) { Text(stringResource(R.string.common_back)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { page = 2 }, enabled = s.hasCarWifi) { Text(stringResource(R.string.common_next)) }
                    }
                    TextButton(onClick = { page = 3 }) { Text(stringResource(R.string.setup_bridge_only)) }
                }
                2 -> {
                    Text(stringResource(R.string.setup_step2_title), style = MaterialTheme.typography.titleLarge)
                    SectionCard(title = stringResource(R.string.setup_connect)) {
                        Text(stringResource(R.string.setup_status, link.text), style = MaterialTheme.typography.bodyMedium)
                        link.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (car.vin != null) {
                            Text(
                                stringResource(R.string.setup_car_line, car.vin ?: "", car.batteryPercent?.toString() ?: "?"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        var testing by remember { mutableStateOf(false) }
                        Button(
                            onClick = { testing = true; vm.testConnection { _, msg -> testing = false; vm.toast(msg) } },
                            enabled = !testing,
                        ) {
                            if (testing) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.setup_connect))
                        }
                    }
                    SectionCard(title = stringResource(R.string.setup_not_registered_title)) {
                        Text(
                            stringResource(R.string.setup_not_registered_explain),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.registration_steps), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        val msgRegistered = stringResource(R.string.msg_phone_registered)
                        OutlinedButton(onClick = { vm.run(CarCommand.RegisterPhone, msgRegistered) }) { Text(stringResource(R.string.setup_register_phone)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = 1 }) { Text(stringResource(R.string.common_back)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { page = 3 }) { Text(stringResource(if (link.connected) R.string.common_next else R.string.setup_skip_for_now)) }
                    }
                }
                else -> {
                    Text(stringResource(R.string.setup_step3_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.setup_good_to_know),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = 2 }) { Text(stringResource(R.string.common_back)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { vm.updateSettings { it.copy(setupDone = true) } }) { Text(stringResource(R.string.setup_open_app)) }
                    }
                }
            }
        }
    }
}
