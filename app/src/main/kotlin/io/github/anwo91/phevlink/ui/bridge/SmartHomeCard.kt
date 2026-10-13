package io.github.anwo91.phevlink.ui.bridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.settings.ExpandableHelp
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.AppLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the bridge says about its smart-home link (GET /api/mqtt). */
private data class ShInfo(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val hasPassword: Boolean,
    val localBroker: Boolean,
    val localUser: String?,
    val localPassword: String?,
    val localPhase: String,
    val localMessage: String?,
    val lan: List<String>,
    val phase: String,
    val error: String?,
    val broker: String?,
    val base: String,
) {
    companion object {
        fun from(m: Map<String, Any?>): ShInfo {
            val st = m["status"] as? Map<*, *>
            val local = m["local"] as? Map<*, *>
            val login = m["localLogin"] as? Map<*, *>
            return ShInfo(
                enabled = m["enabled"] == true,
                host = m["host"]?.toString().orEmpty(),
                port = (m["port"] as? Number)?.toInt() ?: 1883,
                username = m["username"]?.toString().orEmpty(),
                hasPassword = m["hasPassword"] == true,
                localBroker = m["localBroker"] == true,
                localUser = login?.get("username")?.toString(),
                localPassword = login?.get("password")?.toString(),
                localPhase = local?.get("phase")?.toString() ?: "idle",
                localMessage = local?.get("message")?.toString(),
                lan = (m["lanAddresses"] as? List<*>).orEmpty().map { it.toString() },
                phase = st?.get("phase")?.toString() ?: "off",
                error = st?.get("error")?.toString(),
                broker = st?.get("broker")?.toString(),
                base = m["baseTopic"]?.toString() ?: "phevlink",
            )
        }
    }
}

private data class Found(val host: String, val port: Int, val name: String?, val homeAssistant: Boolean, val thisBridge: Boolean)

/**
 * Bridge page → Smart home: connect the car to Home Assistant (MQTT). Finds brokers on the bridge's network, or sets
 * one up on the bridge, and says what to do in Home Assistant. Everything is logged ("Smart home: …").
 */
@Composable
fun SmartHomeCard(vm: MainViewModel, url: String, token: String, supported: Boolean, carName: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val good = LocalStatus.current.good
    val bad = MaterialTheme.colorScheme.error
    var info by remember { mutableStateOf<ShInfo?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var found by remember { mutableStateOf<List<Found>?>(null) }
    var finding by remember { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("1883") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }

    fun errorText(e: String?): String = when {
        e == null -> "?"
        e.contains("user name or password") || e.contains("not allowed") -> ctx.getString(R.string.sh_err_login)
        e.contains("nothing answers") -> ctx.getString(R.string.sh_err_no_broker)
        e.contains("no answer") -> ctx.getString(R.string.sh_err_no_answer)
        e.contains("unknown address") -> ctx.getString(R.string.sh_err_unknown_host)
        else -> e
    }

    suspend fun reload() {
        val r = BridgeSetupApi.mqtt(url, token)
        if (r?.code == 200) {
            info = ShInfo.from(r.body)
            loadFailed = false
        } else loadFailed = true
    }

    fun find() {
        finding = true
        scope.launch {
            val r = BridgeSetupApi.findMqtt(url, token)
            val list = (r?.body?.get("brokers") as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>) }.map {
                Found(
                    it["host"].toString(), (it["port"] as? Number)?.toInt() ?: 1883, it["name"]?.toString(),
                    it["homeAssistant"] == true, it["thisBridge"] == true,
                )
            }
            found = list
            finding = false
            vm.log.link("Smart home: broker search — " + if (list.isEmpty()) "none found" else list.joinToString { it.host + if (it.homeAssistant) " (Home Assistant)" else "" })
            if (host.isBlank()) (list.firstOrNull { it.homeAssistant } ?: list.firstOrNull { !it.thisBridge })?.let { host = it.host; port = it.port.toString() }
        }
    }

    fun startEditing() {
        val i = info
        if (i != null && !i.localBroker) {
            host = i.host; port = i.port.toString(); user = i.username
        }
        pass = ""
        message = null
        editing = true
        find()
    }

    fun save(changes: Map<String, Any?>) {
        saving = true
        message = null
        scope.launch {
            vm.log.link("Smart home: saving " + changes.filterKeys { it != "password" } + if ("password" in changes) " (+ password)" else "")
            val r = BridgeSetupApi.putMqtt(url, token, changes + ("language" to AppLocale.effectiveLanguage(ctx)))
            saving = false
            when {
                r == null -> message = ctx.getString(R.string.sh_err_bridge) to false
                r.code == 400 -> {
                    val e = r.body["error"]?.toString()
                    message = when {
                        e?.contains("address") == true -> ctx.getString(R.string.sh_err_address)
                        e?.contains("port") == true -> ctx.getString(R.string.sh_err_port)
                        else -> e ?: "?"
                    } to false
                }
                r.code == 200 -> {
                    val i = ShInfo.from(r.body)
                    info = i
                    message = when {
                        !i.enabled -> { editing = false; ctx.getString(R.string.sh_off_done) to true }
                        i.phase == "connected" -> { editing = false; ctx.getString(R.string.sh_connected_done) to true }
                        else -> ctx.getString(R.string.sh_cant_connect, errorText(i.error)) to false
                    }
                }
                else -> message = (r.body["error"]?.toString() ?: "HTTP ${r.code}") to false
            }
            vm.log.link("Smart home: " + (message?.let { (t, ok) -> (if (ok) "ok — " else "failed — ") + t } ?: "?"))
        }
    }

    LaunchedEffect(url, supported) {
        if (!supported) return@LaunchedEffect
        while (true) {
            reload()
            val installing = info?.localPhase == "installing"
            delay(if (installing) 3_000 else 15_000)
        }
    }
    // The broker on the bridge is ready: the bridge switched the link on to it.
    LaunchedEffect(info?.localPhase, info?.localBroker) {
        if (info?.localPhase == "ok" && info?.localBroker == true && editing) {
            editing = false
            message = ctx.getString(R.string.sh_connected_done) to true
        }
    }

    SectionCard(title = stringResource(R.string.sh_title)) {
        val i = info
        when {
            !supported -> Text(stringResource(R.string.sh_needs_update), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            i == null -> if (loadFailed) Text(stringResource(R.string.sh_err_bridge), style = MaterialTheme.typography.bodySmall) else Busy(stringResource(R.string.sh_loading))
            editing -> {
                Text(stringResource(R.string.sh_find_title), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                val list = found
                when {
                    finding -> Busy(stringResource(R.string.sh_finding))
                    list != null && list.isEmpty() -> Text(stringResource(R.string.sh_none_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list != null -> list.filter { !it.thisBridge || i.localBroker }.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { host = f.host; port = f.port.toString() }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = host == f.host, onClick = { host = f.host; port = f.port.toString() })
                            Column(Modifier.weight(1f)) {
                                Text(f.host + (f.name?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(
                                        when {
                                            f.homeAssistant -> R.string.sh_found_ha
                                            f.thisBridge -> R.string.sh_found_bridge
                                            else -> R.string.sh_found_broker
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (!finding) TextButton(onClick = { find() }) { Text(stringResource(R.string.sh_search_again)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.sh_address)) }, singleLine = true, modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.sh_port)) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(user, { user = it.trim() }, label = { Text(stringResource(R.string.sh_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text(stringResource(R.string.sh_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    placeholder = if (i.hasPassword && !i.localBroker) { { Text(stringResource(R.string.sh_password_keep)) } } else null,
                )
                Text(stringResource(R.string.sh_login_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val changes = linkedMapOf<String, Any?>("enabled" to true, "host" to host, "port" to (port.toIntOrNull() ?: 1883), "username" to user)
                            if (pass.isNotEmpty() || !i.hasPassword || i.localBroker) changes["password"] = pass
                            save(changes)
                        },
                        enabled = !saving && host.isNotBlank(),
                    ) { Text(stringResource(R.string.sh_connect)) }
                    TextButton(onClick = { editing = false; message = null }) { Text(stringResource(R.string.common_cancel)) }
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.sh_local_title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.sh_local_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (i.localPhase == "installing") {
                    Busy(stringResource(R.string.sh_local_installing))
                } else {
                    OutlinedButton(onClick = {
                        message = null
                        scope.launch {
                            vm.log.link("Smart home: asking the bridge to run its own broker")
                            val r = BridgeSetupApi.localMqtt(url, token)
                            if (r?.code == 200) info = ShInfo.from(r.body) else message = ctx.getString(R.string.sh_err_bridge) to false
                        }
                    }) { Text(stringResource(R.string.sh_local_button)) }
                }
                if (i.localPhase == "failed") {
                    Text(stringResource(R.string.sh_local_failed, i.localMessage ?: "?"), style = MaterialTheme.typography.bodySmall, color = bad)
                }
                ExpandableHelp(stringResource(R.string.sh_ha_broker_title), stringResource(R.string.sh_ha_broker))
            }
            i.enabled -> {
                when (i.phase) {
                    "connected" -> Text(stringResource(R.string.sh_connected, i.broker ?: i.host), style = MaterialTheme.typography.bodyMedium, color = good)
                    "connecting" -> Busy(stringResource(R.string.sh_connecting))
                    else -> Text(stringResource(R.string.sh_cant_connect, errorText(i.error)), style = MaterialTheme.typography.bodyMedium, color = bad)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sh_in_ha, carName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i.localBroker && i.localPassword != null) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    LocalBrokerLogin(vm, i)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { startEditing() }, enabled = !saving) { Text(stringResource(R.string.sh_change)) }
                    TextButton(onClick = { save(mapOf("enabled" to false)) }, enabled = !saving) { Text(stringResource(R.string.sh_turn_off)) }
                }
                ExpandableHelp(stringResource(R.string.sh_voice_title), stringResource(R.string.sh_voice, carName))
                ExpandableHelp(stringResource(R.string.sh_auto_title), stringResource(R.string.sh_auto, i.base))
            }
            else -> {
                Text(stringResource(R.string.sh_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { startEditing() }) { Text(stringResource(R.string.sh_set_up)) }
            }
        }
        message?.let { (text, ok) ->
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) good else bad)
        }
    }
}

/** With the broker on the bridge: what to type into Home Assistant's MQTT setup. */
@Composable
private fun LocalBrokerLogin(vm: MainViewModel, i: ShInfo) {
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.sh_copied)
    Text(stringResource(R.string.sh_local_ha_title), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.sh_local_ha_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    LabeledValue(stringResource(R.string.sh_address), i.lan.firstOrNull() ?: "?")
    LabeledValue(stringResource(R.string.sh_port), "1883")
    LabeledValue(stringResource(R.string.sh_user), i.localUser ?: "phev")
    LabeledValue(stringResource(R.string.sh_password), i.localPassword ?: "?")
    TextButton(onClick = {
        clipboard.setText(AnnotatedString(i.localPassword.orEmpty()))
        vm.toast(copied)
    }) { Text(stringResource(R.string.sh_copy_password)) }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
