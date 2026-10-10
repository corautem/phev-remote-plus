package io.github.anwo91.phevlink.ui.obd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.knowledge.Tips
import io.github.anwo91.phevlink.core.obd.ObdPhase
import io.github.anwo91.phevlink.core.obd.ObdState
import io.github.anwo91.phevlink.core.obd.dtc.DtcDatabase
import io.github.anwo91.phevlink.core.obd.livetest.LiveTestText
import io.github.anwo91.phevlink.core.obd.livetest.LiveTests
import io.github.anwo91.phevlink.core.obd.livetest.Msg
import io.github.anwo91.phevlink.core.obd.livetest.TestResult
import io.github.anwo91.phevlink.core.obd.livetest.TestUiState
import io.github.anwo91.phevlink.core.obd.livetest.Verdict
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.Pill
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.Format

@Composable
fun verdictColor(v: Verdict): Color {
    val st = LocalStatus.current
    return when (v) {
        Verdict.PASS -> st.good
        Verdict.WARN -> st.warn
        Verdict.FAIL -> st.bad
        Verdict.INFO -> st.charge
        Verdict.INCONCLUSIVE, Verdict.CANCELLED -> st.idle
    }
}

fun verdictLabel(v: Verdict): Int = when (v) {
    Verdict.PASS -> R.string.verdict_pass
    Verdict.WARN -> R.string.verdict_warn
    Verdict.FAIL -> R.string.verdict_fail
    Verdict.INFO -> R.string.verdict_info
    Verdict.INCONCLUSIVE -> R.string.verdict_inconclusive
    Verdict.CANCELLED -> R.string.verdict_cancelled
}

private fun verdictIcon(v: Verdict): ImageVector = when (v) {
    Verdict.PASS -> Icons.Outlined.CheckCircle
    Verdict.WARN -> Icons.Outlined.WarningAmber
    Verdict.FAIL -> Icons.Outlined.ErrorOutline
    Verdict.INFO -> Icons.Outlined.Info
    Verdict.INCONCLUSIVE, Verdict.CANCELLED -> Icons.Outlined.HelpOutline
}

@Composable
fun TestsTab(vm: ObdViewModel, s: ObdState) {
    val lang = vm.lang()
    var showEngineHelp by rememberSaveable { mutableStateOf(false) }
    val connected = s.phase == ObdPhase.READY || s.phase == ObdPhase.POLLING
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column {
                Text(stringResource(R.string.tests_intro), style = MaterialTheme.typography.bodyMedium)
                if (!connected) {
                    Text(stringResource(R.string.obd_not_connected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = { showEngineHelp = !showEngineHelp }) { Text(stringResource(R.string.tests_engine_help_title)) }
                if (showEngineHelp) Text(LiveTestText.text("eng.start", lang), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(LiveTests.all, key = { it.id }) { t ->
            val last = remember(t.id) { vm.lastRun(t.id) }
            SectionCard(title = LiveTestText.text(t.titleKey, lang)) {
                Text(LiveTestText.text(t.descriptionKey, lang), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(R.string.tests_minutes, t.minutes), MaterialTheme.colorScheme.primary)
                    if (t.needsEngine) Pill(stringResource(R.string.tests_engine), LocalStatus.current.warn)
                    if (t.road) Pill(stringResource(R.string.tests_road), LocalStatus.current.charge)
                }
                if (last != null) {
                    Text(
                        stringResource(R.string.tests_last_run, stringResource(verdictLabel(last.first)), Format.ago(last.second)),
                        style = MaterialTheme.typography.bodySmall, color = verdictColor(last.first), modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Button(onClick = { vm.startTest(t) }, enabled = connected, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.tests_start))
                }
            }
        }
        item {
            Text(stringResource(R.string.tests_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Full-screen view of a running or finished test. */
@Composable
fun TestRunnerScreen(vm: ObdViewModel, state: TestUiState, modifier: Modifier, onOpenTip: (String) -> Unit) {
    val lang = vm.lang()
    val test = LiveTests.byId(state.testId)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(test?.let { LiveTestText.text(it.titleKey, lang) } ?: state.testId, style = MaterialTheme.typography.titleLarge)
        when (state) {
            is TestUiState.Prompt -> {
                SectionCard {
                    Text(stringResource(R.string.tests_step, state.step), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(LiveTestText.text(state.msg, lang), style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The step makes a quick double tap answer only this prompt, not the next one as well.
                    val step = state.step
                    Button(onClick = { vm.answerTest(true, step) }) { Text(LiveTestText.text(state.positive, lang)) }
                    state.negative?.let { neg -> FilledTonalButton(onClick = { vm.answerTest(false, step) }) { Text(LiveTestText.text(neg, lang)) } }
                    TextButton(onClick = { vm.cancelTest() }) { Text(stringResource(R.string.common_cancel)) }
                }
            }
            is TestUiState.Working -> {
                SectionCard {
                    Text(LiveTestText.text(state.msg, lang), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    val p = state.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.secondsLeft?.let { left ->
                        if (left > 0) Text(stringResource(R.string.tests_seconds_left, left), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    state.live.forEach { m -> LabeledValue(LiveTestText.text(m.label, lang), LiveTestText.value(m, lang)) }
                }
                OutlinedButton(onClick = { vm.cancelTest() }) { Text(stringResource(R.string.common_cancel)) }
            }
            is TestUiState.Finished -> ResultView(vm, state.result, lang, onOpenTip)
        }
    }
}

@Composable
private fun ResultView(vm: ObdViewModel, r: TestResult, lang: String, onOpenTip: (String) -> Unit) {
    var codeDetail by remember { mutableStateOf<String?>(null) }
    val color = verdictColor(r.verdict)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(verdictIcon(r.verdict), null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(verdictLabel(r.verdict)), style = MaterialTheme.typography.labelLarge, color = color)
                Text(LiveTestText.text(r.headline, lang), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (r.findings.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.tests_findings)) {
            r.findings.forEach { f ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Icon(verdictIcon(f.verdict), null, tint = verdictColor(f.verdict), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(LiveTestText.text(f.msg, lang), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    val shown = r.measurements.filter { it.value != null || it.text != null }
    if (shown.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.tests_measurements)) {
            shown.forEach { m -> LabeledValue(LiveTestText.text(m.label, lang), LiveTestText.value(m, lang)) }
        }
    }
    if (r.nextSteps.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.tests_next)) {
            r.nextSteps.forEachIndexed { i, m -> Bullet(LiveTestText.text(m, lang), "${i + 1}.") }
        }
    }
    if (r.relatedCodes.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.tests_codes)) {
            r.relatedCodes.forEach { code ->
                TextButton(onClick = { codeDetail = code }) {
                    Text("$code · " + (DtcDatabase.title(code, lang) ?: DtcDatabase.explain(code, lang).title), fontWeight = FontWeight.Normal)
                }
            }
        }
    }
    val tips = remember(r.testId, lang) {
        val book = Tips.load(lang)
        r.relatedTips.mapNotNull { book.tip(it) }
    }
    if (tips.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.tests_tips)) {
            tips.forEach { t -> TextButton(onClick = { onOpenTip(t.id) }) { Text(t.title) } }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.dismissTest() }) { Text(stringResource(R.string.tests_done)) }
        LiveTests.byId(r.testId)?.let { t -> OutlinedButton(onClick = { vm.startTest(t) }) { Text(stringResource(R.string.tests_again)) } }
    }
    codeDetail?.let { code ->
        DtcDetailDialog(code, lang, emptyList(), onDismiss = { codeDetail = null }, onOpenTip = { codeDetail = null; onOpenTip(it) })
    }
}

/** Text of a [Msg] in the app language (helper for other screens). */
@Composable
fun msgText(vm: ObdViewModel, msg: Msg): String = LiveTestText.text(msg, vm.lang())
