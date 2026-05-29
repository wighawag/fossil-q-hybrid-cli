@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.buttons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import qhybrid.android.db.ButtonMappingEntity

/**
 * WP16d — the Buttons screen (per-button mapping + dial-mode toggles). State comes from
 * [ButtonsViewModel] (WP4 active watch + its mappings, sorted by buttonId); intents delegate to
 * the VM, which persists via [qhybrid.android.db.WatchRepository] (WP4) and pushes via the
 * injectable [ButtonSync] seam.
 *
 * **MODEL-AGNOSTIC by design:** the UI allows ANY buttonId / mode / action / count and does NOT
 * gate behind a watch-model lookup table. Validating that a given buttonId actually exists on the
 * connected hardware is out of scope (on-device-pending / WP14).
 *
 * **On-device verification pending:** the LazyColumn rendering, the mode dropdown, the action
 * picker, the dial-mode toggles, reset, and the Save effect can only be confirmed on a device.
 * The actual button-config upload to the watch is **WP14** ([ButtonSync] reports it as
 * not-yet-wired and the UI flags it).
 */
@Composable
fun ButtonsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: ButtonsViewModel = viewModel(factory = ButtonsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    ButtonsContent(
        state = state,
        onAdd = { id, mode, json -> vm.addMapping(id, mode, json) },
        onUpdate = vm::updateMapping,
        onReset = vm::resetButton,
        onSave = { vm.saveToWatch() },
        modifier = modifier,
    )
}

/**
 * Stateless Buttons body — pure function of [ButtonsUiState] + intent lambdas, so it is
 * preview-/UI-testable with fake state and no VM/Room/BLE.
 */
@Composable
fun ButtonsContent(
    state: ButtonsUiState,
    onAdd: (buttonId: Int, modeType: String, actionsJson: String) -> Boolean,
    onUpdate: (ButtonMappingEntity) -> Unit,
    onReset: (buttonId: Int) -> Unit,
    onSave: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    // Editor dialog state: null = closed; addingNew distinguishes add vs edit.
    var editing by remember { mutableStateOf<ButtonMappingEntity?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var saveNote by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            if (state.hasActiveWatch) {
                ExtendedFloatingActionButton(
                    onClick = {
                        addingNew = true
                        editing = ButtonMappingEntity(
                            watchMac = state.activeMac ?: "",
                            buttonId = nextSuggestedButtonId(state.buttonIds),
                            modeType = ButtonModes.DEFAULT,
                            actionsJson = ButtonActionsJson.encode(listOf(ButtonActions.DEFAULT)),
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add button mapping") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.width(0.dp))
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
                        Text(
                            "Button mappings (${state.mappings.size})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(onClick = {
                            val wired = onSave()
                            saveNote = if (wired) "Saved to watch."
                            else "Saved locally. Button-config upload to the watch is pending (WP14)."
                        }) { Text("Save to watch") }
                    }
                    saveNote?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    Text(
                        "Add any button (e.g. 0x10/0x20/0x30, or more on 5-position dials). Any " +
                            "buttonId/mode/count is allowed — hardware validation is on-device-pending.",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    if (state.mappings.isEmpty()) {
                        Text(
                            "No button mappings yet — tap “Add button mapping”.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.mappings, key = { it.buttonId }) { m ->
                                MappingRow(
                                    mapping = m,
                                    onClick = { addingNew = false; editing = m },
                                    onReset = { onReset(m.buttonId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { template ->
        MappingEditorDialog(
            initial = template,
            isNew = addingNew,
            existingButtonIds = state.buttonIds,
            onDismiss = { editing = null },
            onConfirm = { result ->
                if (addingNew) {
                    onAdd(result.buttonId, result.modeType, result.actionsJson)
                } else {
                    onUpdate(result)
                }
                editing = null
            },
        )
    }
}

/** A friendly buttonId hex label, e.g. "Button 0x10". */
private fun buttonLabel(buttonId: Int): String = "Button 0x%02X".format(buttonId)

/** Suggest the next unused 0x10-increment buttonId (model-agnostic; user can override). */
private fun nextSuggestedButtonId(existing: Set<Int>): Int {
    var id = 0x10
    while (id in existing) id += 0x10
    return id
}

@Composable
private fun MappingRow(
    mapping: ButtonMappingEntity,
    onClick: () -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = onClick,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(buttonLabel(mapping.buttonId), style = MaterialTheme.typography.titleMedium)
                }
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
            IconButton(onClick = onReset) {
                Icon(Icons.Filled.Delete, contentDescription = "Reset button mapping")
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
private fun MappingEditorDialog(
    initial: ButtonMappingEntity,
    isNew: Boolean,
    existingButtonIds: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (ButtonMappingEntity) -> Unit,
) {
    var buttonIdText by remember { mutableStateOf("0x%02X".format(initial.buttonId)) }
    var mode by remember { mutableStateOf(ButtonModes.normalize(initial.modeType)) }
    // Selected ids: actions when SINGLE_ACTION/MUSIC_MULTIMODE, dial modes when CUSTOM_TOGGLE.
    var selected by remember { mutableStateOf(ButtonActionsJson.decode(initial.actionsJson)) }

    val parsedId = parseButtonId(buttonIdText)
    val duplicate = isNew && parsedId != null && parsedId in existingButtonIds
    val valid = parsedId != null && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        initial.copy(
                            buttonId = parsedId ?: initial.buttonId,
                            modeType = mode,
                            actionsJson = ButtonActionsJson.encode(selected),
                        )
                    )
                },
            ) { Text(if (isNew) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (isNew) "New button mapping" else "Edit button mapping") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isNew) {
                    OutlinedTextField(
                        value = buttonIdText,
                        onValueChange = { buttonIdText = it.take(6) },
                        label = { Text("Button id (e.g. 0x10, 0x20, 16, 32…)") },
                        singleLine = true,
                        isError = duplicate || parsedId == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (duplicate) {
                        Text(
                            "A mapping for this button already exists.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (parsedId == null) {
                        Text(
                            "Enter a hex (0x10) or decimal button id.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(buttonLabel(initial.buttonId), style = MaterialTheme.typography.titleMedium)
                }

                HorizontalDivider()

                Text("Mode", style = MaterialTheme.typography.labelLarge)
                ModeDropdown(selected = mode, onSelect = { mode = it })

                HorizontalDivider()

                if (ButtonModes.usesDialModes(mode)) {
                    // Dial-mode toggles (sub-eye positions). Stored in the same id list.
                    Text("Dial modes to cycle", style = MaterialTheme.typography.labelLarge)
                    DialModeToggles(
                        selected = selected,
                        onToggle = { id -> selected = toggle(selected, id) },
                    )
                } else {
                    Text("Actions", style = MaterialTheme.typography.labelLarge)
                    ActionCheckboxes(
                        selected = selected,
                        onToggle = { id -> selected = toggle(selected, id) },
                    )
                }
            }
        },
    )
}

private fun toggle(list: List<String>, id: String): List<String> =
    if (id in list) list - id else list + id

/**
 * Parse a button id from hex ("0x10", "10h"), bare hex ("10" when it has hex letters), or
 * decimal. We accept "0x.." as hex and a plain number as decimal so both conventions work.
 */
private fun parseButtonId(text: String): Int? {
    val t = text.trim().lowercase()
    if (t.isEmpty()) return null
    return when {
        t.startsWith("0x") -> t.removePrefix("0x").toIntOrNull(16)
        t.endsWith("h") -> t.removeSuffix("h").toIntOrNull(16)
        else -> t.toIntOrNull(10)
    }?.takeIf { it in 0..0xFF }
}

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

@Composable
private fun ActionCheckboxes(selected: List<String>, onToggle: (String) -> Unit) {
    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
        items(ButtonActions.ALL, key = { it }) { id ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = id in selected, onValueChange = { onToggle(id) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = id in selected, onCheckedChange = { onToggle(id) })
                Text(ButtonActions.label(id), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DialModeToggles(selected: List<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Pick the dial positions a press cycles through.",
            style = MaterialTheme.typography.labelSmall,
        )
        // FlowRow would be nicer; a simple wrapping list of chips keeps deps minimal.
        ButtonDialModes.ALL.forEach { id ->
            FilterChip(
                selected = id in selected,
                onClick = { onToggle(id) },
                label = { Text(ButtonDialModes.label(id)) },
            )
        }
    }
}
