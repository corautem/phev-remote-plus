package io.github.anwo91.phevlink.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.energy.PriceAreas
import io.github.anwo91.phevlink.core.energy.PricePlan
import io.github.anwo91.phevlink.data.AppSettings
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.util.AppLocale
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What electricity costs at home, kept on the bridge for all phones: spot price (area chosen or guessed from the home
 * location, with or without the Norwegian electricity support), Norgespris or a fixed price; the grid fee per kWh;
 * the energy a full charge takes from the wall. Chips save at once, numbers with "Save". Logged ("Price: …").
 */
@Composable
fun PriceSettings(vm: MainViewModel, s: AppSettings, onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loc = AppLocale.uiLocale(ctx)
    var shared by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(s.bridgeUrl) {
        val r = BridgeSetupApi.shared(s.bridgeUrl, s.bridgeToken)
        if (r?.code == 200) shared = r.body else failed = true
    }
    fun save(changes: Map<String, Any?>) {
        shared = (shared ?: emptyMap()) + changes
        scope.launch {
            val r = BridgeSetupApi.putShared(s.bridgeUrl, s.bridgeToken, changes)
            vm.log.link("Price: $changes → " + if (r?.code == 200) "saved on the bridge" else "not saved (${r?.code ?: "no answer"})")
            if (r?.code == 200) onSaved() else failed = true
        }
    }
    if (!s.hasBridge) {
        Note(stringResource(R.string.hist_needs_bridge))
        return
    }
    val m = shared
    if (m == null) {
        Note(stringResource(if (failed) R.string.rem_load_failed else R.string.sh_loading))
        return
    }
    val model = (m["priceModel"] as? String)?.lowercase() ?: "spot"
    val chosenArea = (m["priceArea"] as? String)?.takeIf { PriceAreas.valid(it) }?.uppercase()
    val guess = s.homeLat?.let { la -> s.homeLon?.let { lo -> PriceAreas.guess(la, lo) } }
    val area = chosenArea ?: guess
    // A fixed price is in the phone's own currency (saved with it); spot prices in the area's.
    val phoneCurrency = remember { runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "EUR" }
    val currency = if (model == "fixed") m["priceCurrency"] as? String ?: phoneCurrency else PriceAreas.currency(area)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(stringResource(R.string.price_model))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("spot" to R.string.price_spot, "norgespris" to R.string.price_norgespris, "fixed" to R.string.price_fixed).forEach { (k, label) ->
                FilterChip(
                    selected = model == k,
                    onClick = { save(if (k == "fixed" && m["priceCurrency"] == null) mapOf("priceModel" to k, "priceCurrency" to phoneCurrency) else mapOf("priceModel" to k)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        if (model != "fixed") {
            Label(stringResource(R.string.price_area))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = chosenArea == null, onClick = { save(mapOf("priceArea" to "")) },
                    label = { Text(guess?.let { stringResource(R.string.price_area_auto, it) } ?: stringResource(R.string.price_area_auto_none)) },
                )
                val list = if (model == "norgespris") PriceAreas.NORWAY else PriceAreas.ALL
                list.forEach { a -> FilterChip(selected = chosenArea == a, onClick = { save(mapOf("priceArea" to a)) }, label = { Text(a) }) }
            }
            if (area == null) Note(stringResource(R.string.price_area_missing))
        }
        if (model == "spot" && PriceAreas.hasSupport(area)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.price_support), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = m["priceSupport"] as? Boolean ?: true, onCheckedChange = { save(mapOf("priceSupport" to it)) })
            }
            Note(stringResource(R.string.price_support_note))
        }
        if (model == "norgespris") Note(stringResource(R.string.price_norgespris_note))

        // Numbers: typed, then saved together.
        var fixed by remember(m["priceFixed"]) { mutableStateOf((m["priceFixed"] as? Number)?.let { num(it.toDouble(), loc) } ?: "") }
        var fee by remember(m["priceGridFee"]) { mutableStateOf((m["priceGridFee"] as? Number)?.let { num(it.toDouble(), loc) } ?: "") }
        var full by remember(m["fullChargeKwh"]) {
            mutableStateOf(num((m["fullChargeKwh"] as? Number)?.toDouble() ?: PricePlan.DEFAULT_FULL_CHARGE_KWH, loc, 1))
        }
        if (model == "fixed") {
            OutlinedTextField(
                fixed, { fixed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.price_fixed_field, currencyWord(currency))) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        OutlinedTextField(
            fee, { fee = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.price_fee_field, currencyWord(currency))) },
            supportingText = { Text(stringResource(R.string.price_fee_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            full, { full = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.price_full_field)) },
            supportingText = { Text(stringResource(R.string.price_full_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        val bad = stringResource(R.string.price_bad_number)
        FilledTonalButton(onClick = {
            val f = parse(fixed)
            val g = if (fee.isBlank()) 0.0 else parse(fee)
            val k = parse(full)
            val fixedOk = model != "fixed" || (f != null && f in 0.0..20.0)
            if (!fixedOk || g == null || g !in 0.0..10.0 || k == null || k !in 4.0..30.0) {
                vm.toast(bad)
            } else {
                save(listOfNotNull(("priceFixed" to f).takeIf { model == "fixed" }, "priceGridFee" to g, "fullChargeKwh" to k).toMap())
            }
        }) { Text(stringResource(R.string.common_save)) }
        Spacer(Modifier.height(2.dp))
        Note(stringResource(R.string.price_privacy))
        if (failed) Note(stringResource(R.string.rem_load_failed))
    }
}

/** "1.25" or "1,25" → 1.25. */
private fun parse(text: String): Double? = text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()

private fun num(v: Double, loc: Locale, digits: Int = 2): String = String.format(loc, "%.${digits}f", v)

private fun currencyWord(currency: String): String = if (currency in setOf("NOK", "SEK", "DKK")) "kr" else currency

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
