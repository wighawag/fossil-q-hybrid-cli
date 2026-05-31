@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package qhybrid.android.navcue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import qhybrid.android.WatchConnectionService
import qhybrid.android.navcue.TurnCueMapper.Maneuver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WP-NAV — a live diagnostic screen for the navigation-cue pipeline. Observes the process-wide
 * [NavCueDiagnostics] flows (same process as the service) and shows, in real time:
 *   - status cards (toggle on?, OsmAnd installed/bound?, registered?, counters, last raw turn,
 *     last cue + whether it reached the watch),
 *   - "Send test cue" buttons (fire one cue per direction, bypassing OsmAnd, to verify the
 *     watch-side path independently),
 *   - a colour-coded scrolling event log (newest last) with an errors-included tail.
 *
 * This is the tool to answer "why no buzz on turning": you can see whether OsmAnd callbacks arrive
 * at all, what they map to, what the dispatcher decides, and whether the watch send happened or was
 * dropped (link down).
 */
@Composable
fun NavCueDiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by NavCueDiagnostics.status.collectAsStateWithLifecycle()
    val log by NavCueDiagnostics.log.collectAsStateWithLifecycle()

    val prefs = remember { qhybrid.android.settings.SharedPreferencesSettingsPrefs(context) }
    var backend by remember {
        mutableStateOf(NavCueBackend.parse(prefs.get().navCueBackend))
    }

    // The ENTIRE screen is one scrollable LazyColumn (cards as header items, then the log lines),
    // so on a small screen everything scrolls and the log is always reachable. The log text is
    // wrapped in a SelectionContainer so it can be copy-pasted (no OCR).
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { StatusCard(status) }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OsmAnd API backend", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Different OsmAnd builds expose a different AIDL namespace. Auto tries both. " +
                            "Change this if Auto can't bind (watch the log's bind-try lines).",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavCueBackend.entries.forEach { b ->
                            val selected = b == backend
                            OutlinedButton(onClick = {
                                backend = b
                                prefs.setNavCueBackend(b.name)
                                WatchConnectionService.refreshNavCues(context)
                            }) {
                                Text((if (selected) "● " else "○ ") + b.label())
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Send a test cue (bypasses OsmAnd)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Verifies the watch path: the watch must be connected. Each button fires one " +
                            "buzz + points both hands. If these work but real navigation doesn't, the " +
                            "problem is the OsmAnd feed (see the log below).",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TestButton(context, "Left", Maneuver.LEFT)
                        TestButton(context, "Right", Maneuver.RIGHT)
                        TestButton(context, "Straight", Maneuver.STRAIGHT)
                        TestButton(context, "U-turn", Maneuver.U_TURN)
                        TestButton(context, "Arrive", Maneuver.ARRIVE)
                        TestButton(context, "Off-route", Maneuver.OFF_ROUTE)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Event log (${log.size})", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { NavCueDiagnostics.clear() }) { Text("Clear") }
            }
        }

        // Newest last (reads like a console). Selectable text for easy copy-paste.
        items(log) { e ->
            SelectionContainer { LogRow(e) }
        }
    }
}

@Composable
private fun StatusCard(s: NavCueDiagnostics.Status) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Navigation cue diagnostics", style = MaterialTheme.typography.titleSmall)
            KV("Toggle enabled", yn(s.enabled))
            KV("OsmAnd installed", yn(s.osmAndInstalled))
            KV("OsmAnd bound", "${yn(s.bound)}${s.boundPackage?.let { " ($it)" } ?: ""}")
            KV("Registered", s.registeredCallbackId?.let { "yes (id=$it)" } ?: "no")
            KV("Raw callbacks", s.totalRawCallbacks.toString())
            KV(
                "Last raw turn",
                if (s.lastRawTurnType != null)
                    "type=${s.lastRawTurnType} dist=${s.lastRawDistanceM}m → ${s.lastManeuver ?: "?"} (${ago(s.lastRawAtMs)})"
                else "— none yet —",
            )
            KV("Cues sent to watch", s.totalCuesSent.toString())
            KV(
                "Last cue",
                if (s.lastCueText != null)
                    "${s.lastCueText} ${if (s.linkUpAtLastCue == true) "✓sent" else "✗dropped(link down)"} (${ago(s.lastCueAtMs)})"
                else "— none yet —",
            )
        }
    }
}

@Composable
private fun TestButton(context: android.content.Context, label: String, maneuver: Maneuver) {
    OutlinedButton(onClick = { WatchConnectionService.testNavCue(context, maneuver.name) }) {
        Text(label)
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.labelMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogRow(e: NavCueDiagnostics.Entry) {
    val color = when (e.level) {
        NavCueDiagnostics.Level.ERROR -> MaterialTheme.colorScheme.error
        NavCueDiagnostics.Level.WARN -> Color(0xFFB26A00)
        NavCueDiagnostics.Level.CUE -> Color(0xFF1B7A1B)
        NavCueDiagnostics.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "${time(e.atMs)} [${e.stage}] ${e.message}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}

private fun yn(b: Boolean) = if (b) "yes" else "no"

private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private fun time(ms: Long) = TIME_FMT.format(Date(ms))
private fun ago(ms: Long?): String {
    if (ms == null) return "—"
    val secs = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
    return if (secs < 60) "${secs}s ago" else "${secs / 60}m ago"
}
