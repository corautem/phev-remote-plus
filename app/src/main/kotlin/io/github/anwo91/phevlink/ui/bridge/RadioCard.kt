package io.github.anwo91.phevlink.ui.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import kotlinx.coroutines.launch

/** One Wi-Fi radio of the bridge, as GET /api/carwifi/radios describes it. */
private data class BridgeRadio(
    val name: String, val usb: Boolean, val driver: String?, val forCar: Boolean, val home: Boolean, val carSignal: Int?,
)

/**
 * Bridge page → which Wi-Fi radio talks to the car: the built-in one or a USB adapter (often with a better antenna).
 * Lists each radio — built in or USB, its driver, what it does, how well it sees the car — and moves the car's Wi-Fi
 * to another one (never the one carrying the home network). The bridge restarts to use it. Logged ("Car radio: …").
 */
@Composable
fun RadioCard(vm: MainViewModel, url: String, token: String) {
    val scope = rememberCoroutineScope()
    val good = LocalStatus.current.good
    var radios by remember { mutableStateOf<List<BridgeRadio>?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<BridgeRadio?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url, loads) {
        val r = BridgeSetupApi.radios(url, token)
        if (r?.code == 200) {
            val list = (r.body["radios"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>) }.map { m ->
                BridgeRadio(
                    name = m["name"]?.toString() ?: "?", usb = m["usb"] == true, driver = m["driver"] as? String,
                    forCar = m["forCar"] == true, home = m["home"] == true, carSignal = (m["carSignal"] as? Number)?.toInt(),
                )
            }
            radios = list
            vm.log.link("Car radio: " + list.joinToString { "${it.name}${if (it.usb) " (USB" else " (built in"}${it.driver?.let { d -> ", $d" } ?: ""})" + (if (it.forCar) " car" else "") + (if (it.home) " home" else "") + (it.carSignal?.let { s -> " $s %" } ?: "") })
        } else {
            vm.log.link("Car radio: the bridge didn't list its radios (${r?.code ?: BridgeSetupApi.lastFailure ?: "no answer"})")
        }
    }
    val list = radios ?: return
    SectionCard(title = stringResource(R.string.radio_title)) {
        Text(stringResource(R.string.radio_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (list.size < 2) {
            Text(stringResource(R.string.radio_single), style = MaterialTheme.typography.bodyMedium)
        }
        list.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name + " · " + stringResource(if (r.usb) R.string.radio_usb else R.string.radio_builtin) + (r.driver?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val what = listOfNotNull(
                        stringResource(R.string.radio_car).takeIf { r.forCar },
                        stringResource(R.string.radio_home).takeIf { r.home },
                        r.carSignal?.let { stringResource(R.string.radio_signal, it) } ?: stringResource(R.string.radio_not_seen),
                    ).joinToString(" · ")
                    Text(what, style = MaterialTheme.typography.bodySmall, color = if (r.forCar) good else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!r.forCar && !r.home && list.size >= 2) {
                    TextButton(onClick = { confirm = r }, enabled = !busy) { Text(stringResource(R.string.radio_use)) }
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { loads++ }, enabled = !busy) { Text(stringResource(R.string.common_refresh)) }
        }
    }
    confirm?.let { r ->
        val failedText = stringResource(R.string.radio_failed, "%s")
        val oldText = stringResource(R.string.radio_old_helper)
        val movingText = stringResource(R.string.radio_moving)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.radio_confirm_title, r.name)) },
            text = { Text(stringResource(R.string.radio_confirm_text, r.name)) },
            confirmButton = {
                FilledTonalButton(onClick = {
                    confirm = null
                    busy = true
                    message = movingText
                    vm.log.link("Car radio: moving the car's Wi-Fi to ${r.name}")
                    scope.launch {
                        val res = BridgeSetupApi.setRadio(url, token, r.name)
                        busy = false
                        if (res?.code == 200) {
                            vm.log.link("Car radio: ${r.name} saved — the bridge restarts")
                            vm.bridgeAdmin.expectRestart()
                        } else {
                            val why = res?.body?.get("error") as? String ?: BridgeSetupApi.lastFailure ?: "no answer"
                            vm.log.link("Car radio: moving to ${r.name} failed ($why)")
                            message = if (why == "helper-old") oldText else failedText.replace("%s", why)
                            loads++
                        }
                    }
                }) { Text(stringResource(R.string.radio_use)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
