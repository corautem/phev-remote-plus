package io.github.anwo91.phevlink.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.link.BridgeSetupController
import io.github.anwo91.phevlink.link.BridgeSetupController.PairState
import io.github.anwo91.phevlink.link.BridgeSetupController.RegState
import io.github.anwo91.phevlink.link.BridgeSetupController.Step
import io.github.anwo91.phevlink.link.BridgeSetupController.VerifyState
import io.github.anwo91.phevlink.link.BridgeSetupController.WifiState
import io.github.anwo91.phevlink.link.FoundBridge
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.data.passwordProblemRes
import io.github.anwo91.phevlink.ui.settings.ExpandableHelp

/**
 * "Set up bridge": finds the bridge on the home network, pairs by re-plugging it, connects it to the car's Wi-Fi and
 * walks through the car's registration — no terminal, no typed addresses or tokens. The steps live in the app-wide
 * [BridgeSetupController], so turning the phone or leaving to unplug the bridge doesn't lose the place.
 */
@Composable
fun BridgeSetupScreen(vm: MainViewModel, modifier: Modifier, onBack: () -> Unit, onFinished: () -> Unit) {
    val c = vm.bridgeSetup
    val st by c.state.collectAsState()
    val s by vm.settings.collectAsState()
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            c.start()
        }
    }
    // While setup is open the app keeps off the car's Wi-Fi: joining it would take the phone off the home network
    // (where the bridge is) and occupy the car while the bridge tries to connect.
    DisposableEffect(Unit) {
        vm.repo.pauseCarWifi(BridgeSetupController.PAUSE_TAG, "the bridge setup is open")
        onDispose { vm.repo.resumeCarWifi(BridgeSetupController.PAUSE_TAG) }
    }
    val car by vm.car.collectAsState()
    val close = { c.cancel(); onBack() }
    BackHandler(onBack = close)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.bs_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val n = st.step.ordinal + 1
            val total = Step.entries.size
            LinearProgressIndicator(progress = { n.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.bs_step_of, n, total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st.waitingForHome) Busy(stringResource(R.string.bs_waiting_home))
            when (st.step) {
                Step.FIND -> FindStep(c, st)
                Step.PAIR -> PairStep(st)
                Step.CAR_WIFI -> WifiStep(vm, c, st, s.ssid, onFinished)
                Step.REGISTER -> RegisterStep(c, st, car.registrations)
                Step.VERIFY -> VerifyStep(c, st, onFinished = { c.finish(); onFinished() })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UpdateNeeded() {
    Note(stringResource(R.string.bs_needs_update), error = true)
    ExpandableHelp(stringResource(R.string.bs_update_help_title), stringResource(R.string.bs_update_help))
}

// ------------------------------------------------------------------ 1. find

@Composable
private fun FindStep(c: BridgeSetupController, st: BridgeSetupController.State) {
    SectionCard(title = stringResource(R.string.bs_find_title)) {
        Text(stringResource(R.string.bs_find_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        when {
            st.searching -> Busy(stringResource(R.string.bs_searching))
            st.found.isNotEmpty() -> st.found.forEach { b -> BridgeRow(b) { c.choose(b) } }
            st.searched && !st.onLan -> Note(stringResource(R.string.bs_not_on_wifi), error = true)
            st.searched -> {
                Note(stringResource(R.string.bs_none_found), error = true)
                Spacer(Modifier.height(4.dp))
                Note(stringResource(R.string.bs_none_tips))
                st.searchFrom?.let { from ->
                    Spacer(Modifier.height(4.dp))
                    Note(stringResource(R.string.bs_search_info, st.searchCount, from, st.searchSeconds))
                }
            }
        }
        if (st.bridge?.needsUpdate == true || st.found.any { it.needsUpdate }) {
            Spacer(Modifier.height(8.dp))
            UpdateNeeded()
        }
        if (!st.searching && st.searched) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { c.search() }) { Text(stringResource(R.string.bs_search_again)) }
        }
    }
    if (!st.searching && st.searched) {
        SectionCard {
            var open by rememberSaveable { mutableStateOf(false) }
            Text(
                stringResource(R.string.bs_manual_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            )
            if (open) {
                var address by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    address, { address = it }, label = { Text(stringResource(R.string.bs_manual_label)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { c.useAddress(address) }, enabled = address.isNotBlank()) { Text(stringResource(R.string.bs_manual_go)) }
            }
        }
    }
}

@Composable
private fun BridgeRow(b: FoundBridge, onUse: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(b.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(b.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!b.needsUpdate) Button(onClick = onUse) { Text(stringResource(R.string.bs_use_this)) }
    }
}

// ------------------------------------------------------------------ 2. pair

@Composable
private fun PairStep(st: BridgeSetupController.State) {
    SectionCard(title = stringResource(R.string.bs_pair_title)) {
        when (st.pair) {
            PairState.IDLE, PairState.TRYING -> Busy(stringResource(R.string.bs_pairing))
            PairState.NEED_REPLUG -> {
                if (st.pairReason == "used") {
                    Note(stringResource(R.string.bs_pair_used))
                    Spacer(Modifier.height(6.dp))
                }
                Text(stringResource(R.string.bs_replug), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Note(stringResource(R.string.bs_replug_why))
                Spacer(Modifier.height(12.dp))
                Busy(stringResource(R.string.bs_waiting_bridge))
            }
            PairState.BRIDGE_OFF -> Busy(stringResource(R.string.bs_bridge_off))
        }
    }
}

// ------------------------------------------------------------------ 3. car Wi-Fi

@Composable
private fun WifiStep(vm: MainViewModel, c: BridgeSetupController, st: BridgeSetupController.State, carSsid: String, onFinished: () -> Unit) {
    val s by vm.settings.collectAsState()
    SectionCard(title = stringResource(R.string.bs_wifi_title)) {
        when (st.wifi) {
            WifiState.IDLE, WifiState.CHECKING -> Busy(stringResource(R.string.bs_wifi_checking))
            WifiState.NEED_CREDENTIALS -> {
                Note(stringResource(R.string.bs_wifi_need_creds))
            }
            WifiState.LEAVING_TEST_MODE -> Busy(stringResource(R.string.bs_wifi_test_mode))
            WifiState.SEARCHING -> Busy(stringResource(R.string.bs_wifi_searching, carSsid))
            WifiState.JOINING -> Busy(stringResource(R.string.bs_wifi_joining, carSsid))
            WifiState.CONNECTED -> {
                Text(stringResource(R.string.bs_wifi_connected, carSsid), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                st.carSignal?.let { sig ->
                    Note(stringResource(R.string.bs_wifi_signal, sig))
                    if (sig < WEAK_SIGNAL) Note(stringResource(R.string.bs_wifi_weak), error = true)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { c.toRegister() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_next)) }
            }
            WifiState.CAR_NOT_SEEN -> {
                Note(stringResource(R.string.bs_wifi_not_seen, carSsid), error = true)
                Spacer(Modifier.height(4.dp))
                Note(stringResource(R.string.bs_wifi_not_seen_tips))
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { c.checkWifi() }) { Text(stringResource(R.string.bs_try_again)) }
                FinishLater(c, onFinished)
            }
            WifiState.SAVED_NOT_CONNECTED -> {
                Note(stringResource(R.string.bs_wifi_saved, carSsid))
                st.wifiDetail?.let { Note(stringResource(R.string.bs_details, it)) }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { c.checkWifi() }) { Text(stringResource(R.string.bs_try_again)) }
                FinishLater(c, onFinished)
            }
            WifiState.PROBLEM -> {
                when (st.wifiDetail) {
                    PROBLEM_SINGLE_RADIO -> Note(stringResource(R.string.bs_problem_single_radio), error = true)
                    PROBLEM_NO_WIFI -> Note(stringResource(R.string.bs_problem_no_wifi), error = true)
                    PROBLEM_NO_NM -> Note(stringResource(R.string.bs_problem_no_nm), error = true)
                    PROBLEM_HELPER -> UpdateNeeded()
                    else -> {
                        Note(stringResource(R.string.bs_wifi_failed), error = true)
                        st.wifiDetail?.let { Note(stringResource(R.string.bs_details, it)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { c.checkWifi() }) { Text(stringResource(R.string.bs_try_again)) }
            }
            WifiState.FAILED -> {
                if (st.wifiDetail == BridgeSetupController.NOT_HOME) {
                    Note(stringResource(R.string.bs_not_home), error = true)
                } else {
                    Note(stringResource(R.string.bs_wifi_failed), error = true)
                    st.wifiDetail?.let { Note(stringResource(R.string.bs_details, it)) }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { c.checkWifi() }) { Text(stringResource(R.string.bs_try_again)) }
            }
        }
    }
    if (st.wifi == WifiState.NEED_CREDENTIALS) {
        // Just the two things the bridge needs, saved with one button (the full Wi-Fi card wants a separate Save).
        SectionCard {
            var ssid by rememberSaveable { mutableStateOf(s.ssid) }
            var pw by rememberSaveable { mutableStateOf(s.password) }
            val pwProblem = passwordProblemRes(pw)
            OutlinedTextField(
                ssid, { ssid = it }, label = { Text(stringResource(R.string.bs_car_wifi_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                pw, { pw = it }, label = { Text(stringResource(R.string.settings_password)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = pw.isNotEmpty() && pwProblem != null,
                supportingText = if (pw.isNotEmpty() && pwProblem != null) ({ Text(stringResource(pwProblem)) }) else null,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { c.useCarWifi(ssid, pw) },
                enabled = ssid.isNotBlank() && pwProblem == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.bs_continue)) }
        }
    }
}

/** The car isn't home: save its network on the bridge and finish now; registration waits for the car. */
@Composable
private fun FinishLater(c: BridgeSetupController, onFinished: () -> Unit) {
    var finishing by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    Note(stringResource(R.string.bs_finish_later_hint))
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = { finishing = true; c.finishWithoutCar { onFinished() } },
        enabled = !finishing, modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.bs_finish_later)) }
}

// ------------------------------------------------------------------ 4. register

@Composable
private fun RegisterStep(c: BridgeSetupController, st: BridgeSetupController.State, carRegistrations: Int?) {
    SectionCard(title = stringResource(R.string.bs_reg_title)) {
        when (st.reg) {
            RegState.INSTRUCTIONS -> {
                Text(stringResource(R.string.bs_reg_intro), style = MaterialTheme.typography.bodyMedium)
                // The car told the app how many devices it remembers: when that's already the maximum, say so first.
                if (carRegistrations != null && carRegistrations >= MAX_REGISTRATIONS) {
                    Spacer(Modifier.height(8.dp))
                    Note(stringResource(R.string.bs_reg_full_ahead, carRegistrations), error = true)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.bs_reg_steps), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.bs_reg_question), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.beeps(0) }) { Text(stringResource(R.string.bs_beeps_none)) }
                    OutlinedButton(onClick = { c.beeps(1) }) { Text("1") }
                    OutlinedButton(onClick = { c.beeps(2) }) { Text("2") }
                    OutlinedButton(onClick = { c.beeps(3) }) { Text("3") }
                }
            }
            RegState.NO_BEEP -> {
                Note(stringResource(R.string.bs_reg_no_beep), error = true)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { c.backToInstructions() }) { Text(stringResource(R.string.bs_start_over)) }
            }
            RegState.MEMORY_FULL -> {
                Note(stringResource(R.string.bs_reg_full), error = true)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.registration_full), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { c.backToInstructions() }) { Text(stringResource(R.string.bs_start_over)) }
            }
            RegState.READY -> {
                Text(stringResource(R.string.bs_reg_ready), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { c.register() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bs_reg_button)) }
            }
            RegState.REGISTERING -> Busy(stringResource(R.string.bs_registering))
            RegState.REGISTERED -> {
                Text(stringResource(R.string.bs_reg_done), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { c.verify() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bs_power_off_done)) }
            }
            RegState.FAILED -> {
                if (st.regDetail == BridgeSetupController.NOT_HOME) {
                    Note(stringResource(R.string.bs_not_home), error = true)
                } else {
                    Note(stringResource(R.string.bs_reg_failed), error = true)
                    st.regDetail?.let { Note(stringResource(R.string.bs_details, it)) }
                }
                Spacer(Modifier.height(4.dp))
                Note(stringResource(R.string.bs_reg_failed_hint))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.register() }) { Text(stringResource(R.string.bs_try_again)) }
                    TextButton(onClick = { c.backToInstructions() }) { Text(stringResource(R.string.bs_start_over)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 5. verify

@Composable
private fun VerifyStep(c: BridgeSetupController, st: BridgeSetupController.State, onFinished: () -> Unit) {
    SectionCard(title = stringResource(R.string.bs_verify_title)) {
        when (st.verify) {
            VerifyState.WAITING -> Busy(stringResource(R.string.bs_verify_waiting))
            VerifyState.OK -> {
                Text(
                    stringResource(R.string.bs_verify_ok, st.battery?.toString() ?: "?"),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Note(stringResource(R.string.bs_verify_ok_hint))
                Spacer(Modifier.height(12.dp))
                Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bs_finish)) }
            }
            VerifyState.SLOW -> {
                Note(stringResource(R.string.bs_verify_slow))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onFinished) { Text(stringResource(R.string.bs_finish)) }
                    TextButton(onClick = { c.verify() }) { Text(stringResource(R.string.bs_try_again)) }
                }
            }
        }
    }
}

private const val WEAK_SIGNAL = 40
/** The car remembers at most two devices (Mitsubishi's operation guide); a third gets three beeps. */
private const val MAX_REGISTRATIONS = 2
private const val PROBLEM_SINGLE_RADIO = "single-radio"
private const val PROBLEM_NO_WIFI = "no-wifi"
private const val PROBLEM_NO_NM = "no-networkmanager"
private const val PROBLEM_HELPER = "helper-missing"
