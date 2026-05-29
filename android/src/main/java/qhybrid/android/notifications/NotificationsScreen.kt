@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package qhybrid.android.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import qhybrid.android.db.NotificationRuleEntity

/**
 * WP16c — the Notifications screen (per-app rules: vibe pattern + hand position). State comes
 * from [NotificationsViewModel] (WP4 active watch + its rules, sorted by packageName); intents
 * delegate to the VM, which persists via [qhybrid.android.db.WatchRepository] (WP4) and pushes
 * via the injectable [NotificationSync] seam.
 *
 * **On-device verification pending:** the LazyColumn rendering, the vibe-pattern dropdown +
 * hand-degree inputs, and the Save effect can only be confirmed on a device. The actual
 * filter-byte upload to the watch is **WP14** ([NotificationSync] reports it as not-yet-wired
 * and the UI flags it).
 *
 * **Deferred (later WP):** populating the *installed-app list* (querying `PackageManager` /
 * `NotificationListenerService` plumbing) is its own later WP. For WP16c the package is entered
 * via a free-text field (a small sample list is offered as a convenience); see [SAMPLE_PACKAGES].
 */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: NotificationsViewModel = viewModel(factory = NotificationsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    NotificationsContent(
        state = state,
        onAdd = { pkg, vibe, hourDeg, minDeg -> vm.addRule(pkg, vibe, hourDeg, minDeg) },
        onUpdate = vm::updateRule,
        onDelete = vm::deleteRule,
        onSave = { vm.saveToWatch() },
        modifier = modifier,
    )
}

/** A few common packages offered as a convenience until the real app-list WP lands. */
val SAMPLE_PACKAGES = listOf(
    "com.whatsapp",
    "com.google.android.apps.messaging",
    "com.android.email",
    "com.google.android.gm",
    "com.google.android.calendar",
    "com.slack",
    "org.telegram.messenger",
)

/**
 * Stateless Notifications body — pure function of [NotificationsUiState] + intent lambdas, so
 * it is preview-/UI-testable with fake state and no VM/Room/BLE.
 */
@Composable
fun NotificationsContent(
    state: NotificationsUiState,
    onAdd: (pkg: String, vibe: Int, hourDeg: Int, minDeg: Int) -> Boolean,
    onUpdate: (NotificationRuleEntity) -> Unit,
    onDelete: (pkg: String) -> Unit,
    onSave: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    // Editor dialog state: null = closed; an entity with blank packageName = adding,
    // an entity with a real packageName already present = editing.
    var editing by remember { mutableStateOf<NotificationRuleEntity?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var saveNote by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            if (state.hasActiveWatch) {
                ExtendedFloatingActionButton(
                    onClick = {
                        addingNew = true
                        editing = NotificationRuleEntity(
                            watchMac = state.activeMac ?: "",
                            packageName = "",
                            vibePattern = VibePatterns.DEFAULT,
                            hourHandDegrees = 0,
                            minuteHandDegrees = 0,
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add app rule") },
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
                    "No active watch — associate one to manage notification rules.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Per-app rules (${state.rules.size})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(onClick = {
                            val wired = onSave()
                            saveNote = if (wired) "Saved to watch."
                            else "Saved locally. Filter-byte upload to the watch is pending (WP14)."
                        }) { Text("Save to watch") }
                    }
                    saveNote?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "App-list picker (installed apps / notification access) is a later WP — " +
                            "enter a package name for now.",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    if (state.rules.isEmpty()) {
                        Text(
                            "No app rules yet — tap “Add app rule”.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.rules, key = { it.packageName }) { r ->
                                RuleRow(
                                    rule = r,
                                    onClick = { addingNew = false; editing = r },
                                    onDelete = { onDelete(r.packageName) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { template ->
        RuleEditorDialog(
            initial = template,
            isNew = addingNew,
            existingPackages = state.packageNames,
            onDismiss = { editing = null },
            onConfirm = { result ->
                if (addingNew) {
                    onAdd(result.packageName, result.vibePattern,
                        result.hourHandDegrees, result.minuteHandDegrees)
                } else {
                    onUpdate(result)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: NotificationRuleEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
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
                    Text(rule.packageName, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Vibe: ${VibePatterns.label(rule.vibePattern)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    VibePatterns.handSummary(rule.hourHandDegrees, rule.minuteHandDegrees),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete rule")
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    initial: NotificationRuleEntity,
    isNew: Boolean,
    existingPackages: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (NotificationRuleEntity) -> Unit,
) {
    var pkg by remember { mutableStateOf(initial.packageName) }
    var vibe by remember { mutableStateOf(initial.vibePattern) }
    var hourDeg by remember { mutableStateOf(initial.hourHandDegrees.toString()) }
    var minDeg by remember { mutableStateOf(initial.minuteHandDegrees.toString()) }

    val pkgTrim = pkg.trim()
    val duplicate = isNew && pkgTrim.isNotEmpty() && pkgTrim in existingPackages
    val valid = pkgTrim.isNotEmpty() && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        initial.copy(
                            packageName = pkgTrim,
                            vibePattern = VibePatterns.clamp(vibe),
                            hourHandDegrees = VibePatterns.clampDegrees(hourDeg.toIntOrNull() ?: 0),
                            minuteHandDegrees = VibePatterns.clampDegrees(minDeg.toIntOrNull() ?: 0),
                        )
                    )
                },
            ) { Text(if (isNew) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (isNew) "New app rule" else "Edit app rule") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isNew) {
                    PackageField(
                        value = pkg,
                        onValueChange = { pkg = it },
                        isError = duplicate,
                    )
                    if (duplicate) {
                        Text(
                            "A rule for this package already exists.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(pkg, style = MaterialTheme.typography.titleMedium)
                }

                HorizontalDivider()

                Text("Vibe pattern", style = MaterialTheme.typography.labelLarge)
                VibePatternDropdown(selected = vibe, onSelect = { vibe = it })

                HorizontalDivider()

                Text("Hand position (degrees, 0–359)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hourDeg,
                        onValueChange = { hourDeg = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Hour°") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minDeg,
                        onValueChange = { minDeg = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Minute°") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
private fun PackageField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("App package name") },
            singleLine = true,
            isError = isError,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SAMPLE_PACKAGES.forEach { sample ->
                DropdownMenuItem(
                    text = { Text(sample) },
                    onClick = { onValueChange(sample); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun VibePatternDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) {
            Text("${VibePatterns.label(selected)}  (${selected})")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VibePatterns.ALL.forEach { p ->
                DropdownMenuItem(
                    text = { Text("$p — ${VibePatterns.label(p)}") },
                    onClick = { onSelect(p); expanded = false },
                )
            }
        }
    }
}
