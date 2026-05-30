@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.defaults

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonDialModes
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots

/**
 * WP-DEFAULTS (sub-part 4) — the "Defaults for new watches" editor sub-screen, reachable from
 * Settings. It binds the SAME button vocabulary as the per-watch Buttons screen
 * ([ButtonModes] / [ButtonActions] / [ButtonDialModes]) but to the app-level
 * [DefaultsProfileStore] (via [DefaultsViewModel]) instead of a watch row.
 *
 * Covers the UNREADABLE sections only: the three default buttons (editable), plus read-only
 * summaries of the default alarms/rules (both empty by default — "no surprises"). Includes
 * reset-to-factory and the "Apply defaults to this watch" action (surfaced here too).
 *
 * **On-device-pending:** the rendering is verified on a device; the VM/state mapping is unit-tested.
 */
@Composable
fun DefaultsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: DefaultsViewModel = viewModel(factory = DefaultsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    DefaultsContent(
        state = state,
        onSetButtonSlot = vm::setButtonSlot,
        onClearButtonSlot = vm::clearButtonSlot,
        onResetToFactory = vm::resetToFactory,
        onApplyToWatch = vm::applyToActiveWatch,
        modifier = modifier,
    )
}

/**
 * Stateless body — a pure function of [DefaultsUiState] + intents, so it is preview-/UI-testable
 * with fake state and no VM/store.
 */
@Composable
fun DefaultsContent(
    state: DefaultsUiState,
    onSetButtonSlot: (Int, String, List<String>) -> Unit,
    onClearButtonSlot: (Int) -> Unit,
    onResetToFactory: () -> Unit,
    onApplyToWatch: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var note by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Defaults for new watches", style = MaterialTheme.typography.titleLarge)
            Text(
                "These defaults are applied when you ADD a new watch, for the sections the watch " +
                    "can't tell us about: alarms, notification rules, and button mappings. " +
                    "(Vibration, step goal, nudge and 2nd timezone are read FROM the watch, so " +
                    "they're not here.) You can also push these to the current watch below.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Buttons (the non-empty factory section) — one card per physical button.
            ButtonSlots.ALL.forEach { id ->
                DefaultButtonCard(
                    buttonId = id,
                    mapping = state.buttonFor(id),
                    onSet = onSetButtonSlot,
                    onClear = { onClearButtonSlot(id) },
                )
            }

            HorizontalDivider()
            DefaultSectionSummary("Default alarms", state.alarms.size,
                "New watches start with these standard alarms (empty by default — no surprises).")
            DefaultSectionSummary("Default notification rules", state.rules.size,
                "New watches start with these notification rules (empty by default).")

            HorizontalDivider()
            ApplyToWatchRow(onApplyToWatch) { note = it }
            ResetToFactoryRow(onResetToFactory) { note = it }

            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun DefaultButtonCard(
    buttonId: Int,
    mapping: DefaultButton?,
    onSet: (Int, String, List<String>) -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(ButtonSlots.label(buttonId), style = MaterialTheme.typography.titleMedium)

            val mode = ButtonModes.normalize(mapping?.modeType)
            val ids = mapping?.actions ?: emptyList()

            // Mode selector (Single action vs Dial-mode toggle).
            ButtonModes.ALL.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == m, onClick = {
                        // Switching mode resets the ids to a sensible default for that mode.
                        val seed = if (ButtonModes.usesDialModes(m)) listOf(ButtonDialModes.ALERT)
                        else listOf(ButtonActions.DEFAULT)
                        onSet(buttonId, m, if (mode == m) ids else seed)
                    })) {
                    RadioButton(selected = mode == m, onClick = null)
                    Text(ButtonModes.label(m), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (ButtonModes.usesDialModes(mode)) {
                // CUSTOM_TOGGLE: multi-select dial modes (chips); canonical order enforced in VM.
                Text("Dial modes to cycle:", style = MaterialTheme.typography.labelSmall)
                ButtonDialModes.ALL.forEach { dial ->
                    val selected = dial in ids
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) ids - dial else ids + dial
                            onSet(buttonId, mode, next)
                        },
                        label = { Text(ButtonDialModes.label(dial)) },
                    )
                }
            } else {
                // SINGLE_ACTION: single-select action.
                Text("Action:", style = MaterialTheme.typography.labelSmall)
                ButtonActions.ALL.forEach { action ->
                    val selected = ids.firstOrNull() == action
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, onClick = {
                            onSet(buttonId, mode, listOf(action))
                        })) {
                        RadioButton(selected = selected, onClick = null)
                        Text(ButtonActions.label(action), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (mapping != null) {
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear this button default")
                }
            }
        }
    }
}

@Composable
private fun DefaultSectionSummary(title: String, count: Int, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (count == 0) "None (empty)." else "$count configured.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ApplyToWatchRow(onApplyToWatch: () -> Boolean, onNote: (String) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Apply defaults to this watch", style = MaterialTheme.typography.titleMedium)
            Text(
                "Overwrite the current watch's buttons and notification filter with these defaults " +
                    "(full replace, not a merge).",
                style = MaterialTheme.typography.labelSmall,
            )
            Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Apply to this watch")
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Apply defaults to this watch?") },
            text = {
                Text(
                    "This REPLACES the current watch's buttons and notification filter with these " +
                        "defaults (full overwrite). Your per-watch button / notification setup will " +
                        "be lost. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val ok = onApplyToWatch()
                    onNote(if (ok) "Applying defaults to the watch…" else "No active watch to apply defaults to.")
                }) { Text("Apply defaults") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ResetToFactoryRow(onResetToFactory: () -> Unit, onNote: (String) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Reset to factory defaults", style = MaterialTheme.typography.titleMedium)
            Text(
                "Restore the built-in defaults: TOP = Stopwatch, MIDDLE = dial-mode toggle " +
                    "(2nd timezone, alarm, date), BOTTOM = multi-function; no default alarms or " +
                    "notification rules.",
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to factory")
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset defaults to factory?") },
            text = { Text("This discards your edits to the defaults profile and restores the built-in defaults.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onResetToFactory()
                    onNote("Defaults reset to factory.")
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
