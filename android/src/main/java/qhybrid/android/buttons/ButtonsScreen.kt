@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.buttons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import qhybrid.android.db.ButtonMappingEntity

/**
 * WP16d — the Buttons screen. Every Fossil Q Hybrid watch has exactly **three physical buttons**
 * (TOP/MIDDLE/BOTTOM = 0x10/0x20/0x30, see
 * [qhybrid.protocol.requests.fossil.button.ButtonCompiler]), so the UI is a fixed **three-slot**
 * layout — one card per button — rather than a free-form add/remove-by-buttonId flow. Each slot
 * has its own mode + action set, edited in place; "Clear" resets a slot to unconfigured.
 *
 * State comes from [ButtonsViewModel] (WP4 active watch + its mappings); a slot's existing
 * [ButtonMappingEntity] (or null when unconfigured) is surfaced via [ButtonsUiState.slots].
 * Writes go through the VM ([ButtonsViewModel.setSlot] / [ButtonsViewModel.resetButton]).
 *
 * **On-device verification pending:** the slot cards, the mode dropdown, the action picker, the
 * dial-mode toggles, clear, and the Save effect can only be confirmed on a device. The actual
 * button-config upload to the watch is **WP14** ([ButtonSync] reports it not-yet-wired; the UI
 * flags it).
 */
@Composable
fun ButtonsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: ButtonsViewModel = viewModel(factory = ButtonsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    ButtonsContent(
        state = state,
        onSetSlot = { id, mode, ids -> vm.setSlot(id, mode, ids) },
        onClear = vm::resetButton,
        onSave = { vm.saveToWatch() },
        modifier = modifier,
    )
}

/**
 * Stateless Buttons body — pure function of [ButtonsUiState] + intent lambdas, so it is
 * preview-/UI-testable with fake state and no VM/Room/BLE. Renders the three fixed slots.
 */
@Composable
fun ButtonsContent(
    state: ButtonsUiState,
    onSetSlot: (buttonId: Int, modeType: String, ids: List<String>) -> Unit,
    onClear: (buttonId: Int) -> Unit,
    onSave: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    // The slot currently being edited (buttonId), or null when no dialog is open.
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var saveNote by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !state.hasActiveWatch -> Text(
                    "No active watch — associate one to manage button mappings.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Buttons", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = {
                            val wired = onSave()
                            saveNote = if (wired) "Saved to watch."
                            else "Saved locally. Button-config upload to the watch is pending (WP14)."
                        }) { Text("Save to watch") }
                    }
                    saveNote?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    Text(
                        "Your watch has three buttons. Tap one to set what it does.",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    state.slots.forEach { (buttonId, mapping) ->
                        SlotCard(
                            buttonId = buttonId,
                            mapping = mapping,
                            onClick = { editingSlot = buttonId },
                            onClear = { onClear(buttonId) },
                        )
                    }
                }
            }
        }
    }

    editingSlot?.let { buttonId ->
        val existing = state.mappingFor(buttonId)
        SlotEditorDialog(
            buttonId = buttonId,
            initialMode = ButtonModes.normalize(existing?.modeType),
            initialIds = ButtonActionsJson.decode(existing?.actionsJson),
            onDismiss = { editingSlot = null },
            onConfirm = { mode, ids ->
                onSetSlot(buttonId, mode, ids)
                editingSlot = null
            },
        )
    }
}

@Composable
private fun SlotCard(
    buttonId: Int,
    mapping: ButtonMappingEntity?,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ButtonSlots.label(buttonId), style = MaterialTheme.typography.titleMedium)
                if (mapping == null) {
                    Text(
                        "Not set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Mode: ${ButtonModes.label(mapping.modeType)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (ButtonModes.usesDialModes(mapping.modeType))
                            "Dial: ${dialSummary(mapping.actionsJson)}"
                        else
                            ButtonActionsJson.summary(mapping.actionsJson),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            TextButton(onClick = onClick) { Text(if (mapping == null) "Set" else "Edit") }
            if (mapping != null) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

/** Dial-mode summary for a CUSTOM_TOGGLE mapping (stored as the same actionsJson id list). */
private fun dialSummary(actionsJson: String?): String {
    val ids = ButtonActionsJson.decode(actionsJson)
    if (ids.isEmpty()) return "No modes"
    return ids.joinToString(", ") { ButtonDialModes.label(it) }
}

@Composable
private fun SlotEditorDialog(
    buttonId: Int,
    initialMode: String,
    initialIds: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (modeType: String, ids: List<String>) -> Unit,
) {
    var mode by remember { mutableStateOf(ButtonModes.normalize(initialMode)) }
    // Selected ids: actions when SINGLE_ACTION/MUSIC_MULTIMODE (single-select, ≤1),
    // dial modes when CUSTOM_TOGGLE (multi-select, the cycle). We always keep [selected]
    // already normalized for the current [mode] so an invalid combination cannot be stored.
    var selected by remember { mutableStateOf(ButtonMappingRules.normalizeIds(mode, initialIds)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // [selected] is kept normalized for [mode]; normalize once more defensively so the
            // editor can NEVER hand back an invalid combination (WP-BTN cardinality contract).
            TextButton(onClick = { onConfirm(mode, ButtonMappingRules.normalizeIds(mode, selected)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(ButtonSlots.label(buttonId)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Mode", style = MaterialTheme.typography.labelLarge)
                ModeDropdown(
                    selected = mode,
                    onSelect = { newMode ->
                        // Re-normalize the current selection for the new mode so switching
                        // (e.g. CUSTOM_TOGGLE → SINGLE_ACTION) drops now-invalid extra ids.
                        selected = ButtonMappingRules.normalizeIds(newMode, selected)
                        mode = newMode
                    },
                )

                HorizontalDivider()

                if (ButtonModes.usesDialModes(mode)) {
                    Text("Cycle dial modes", style = MaterialTheme.typography.labelLarge)
                    DialModeToggles(
                        selected = selected,
                        onToggle = { id -> selected = toggle(selected, id) },
                    )
                } else {
                    // Single-select: MUSIC_MULTIMODE offers only music-capable actions.
                    val options =
                        if (mode == ButtonModes.MUSIC_MULTIMODE) ButtonMappingRules.MUSIC_ACTIONS
                        else ButtonActions.ALL
                    Text("Action", style = MaterialTheme.typography.labelLarge)
                    ActionRadioGroup(
                        options = options,
                        selected = selected.firstOrNull(),
                        onSelect = { id -> selected = listOf(id) },
                    )
                }
            }
        },
    )
}

/** Multi-select toggle (CUSTOM_TOGGLE only): add/remove a dial-mode id from the cycle. */
private fun toggle(list: List<String>, id: String): List<String> =
    if (id in list) list - id else list + id

@Composable
private fun ModeDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) {
            Text(ButtonModes.label(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ButtonModes.ALL.forEach { m ->
                DropdownMenuItem(
                    text = { Text(ButtonModes.label(m)) },
                    onClick = { onSelect(m); expanded = false },
                )
            }
        }
    }
}

/**
 * WP-BTN single-select action picker (SINGLE_ACTION / MUSIC_MULTIMODE). A radio group enforces
 * the cardinality contract in the UI itself: exactly one action can be chosen, so an invalid
 * multi-action combination can never be stored. [options] is the music-only subset for
 * MUSIC_MULTIMODE, the full catalog otherwise.
 */
@Composable
private fun ActionRadioGroup(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
        items(options, key = { it }) { id ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = id == selected, onClick = { onSelect(id) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = id == selected, onClick = { onSelect(id) })
                Text(ButtonActions.label(id), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DialModeToggles(selected: List<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "This button cycles through the dial positions you pick, one per press.",
            style = MaterialTheme.typography.labelSmall,
        )
        ButtonDialModes.ALL.forEach { id ->
            FilterChip(
                selected = id in selected,
                onClick = { onToggle(id) },
                label = { Text(ButtonDialModes.label(id)) },
            )
        }
    }
}
