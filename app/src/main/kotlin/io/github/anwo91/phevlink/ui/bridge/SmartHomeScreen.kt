package io.github.anwo91.phevlink.ui.bridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import kotlinx.coroutines.delay

/**
 * Settings → Smart home → Google Home ([which] = "matter") or Home Assistant ("ha"): the bridge's Matter or MQTT card on
 * a page of its own. Both run on the bridge, so without one (or while it doesn't answer) the page says so.
 */
@Composable
fun SmartHomeScreen(vm: MainViewModel, modifier: Modifier, which: String, onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val s by vm.settings.collectAsState()
    BackHandler(onBack = onBack)
    val ready = s.hasBridge && s.bridgeToken.isNotBlank()
    // What the bridge can do (an old bridge has neither): asked once the page opens, again every 10 s until it answers.
    var features by remember { mutableStateOf<List<String>?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(s.bridgeUrl, ready) {
        if (!ready) {
            vm.log.link("Smart home page ($which): no bridge set up")
            return@LaunchedEffect
        }
        while (features == null) {
            val h = BridgeSetupApi.hello(s.bridgeUrl)
            if (h != null) {
                features = h.features
                unreachable = false
                vm.log.link("Smart home page ($which): bridge ${h.version} — " + if (if (which == "ha") "smarthome" in h.features else "matter" in h.features) "supported" else "needs an update")
            } else {
                if (!unreachable) vm.log.link("Smart home page ($which): the bridge didn't answer (${BridgeSetupApi.lastFailure ?: "no answer"})")
                unreachable = true
                delay(10_000)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PhevIcons.Back, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.set_group_smarthome), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val f = features
            when {
                !ready -> SectionCard(title = stringResource(if (which == "ha") R.string.sh_title else R.string.mt_title)) {
                    Text(stringResource(R.string.smart_needs_bridge), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenSetup) { Text(stringResource(R.string.settings_bridge_setup)) }
                }
                f == null -> SectionCard(title = stringResource(if (which == "ha") R.string.sh_title else R.string.mt_title)) {
                    Text(
                        stringResource(if (unreachable) R.string.smart_unreachable else R.string.sh_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                which == "ha" -> SmartHomeCard(vm, s.bridgeUrl, s.bridgeToken, supported = "smarthome" in f, carName = s.carName.ifBlank { "Outlander PHEV" })
                else -> MatterCard(vm, s.bridgeUrl, s.bridgeToken, supported = "matter" in f)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
