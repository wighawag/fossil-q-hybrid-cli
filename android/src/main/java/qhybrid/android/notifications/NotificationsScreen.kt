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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import qhybrid.android.sync.ConnectionBanner
import qhybrid.android.sync.LeaveGuardState
import qhybrid.android.sync.PendingSyncBanner
import qhybrid.android.sync.PublishLeaveGuard
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncRowBadge
import qhybrid.android.sync.SyncSaveButton
import qhybrid.android.sync.SyncSavingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * **App picker:** the add-rule dialog offers a **searchable list of installed, launchable apps**
 * (display name + icon) supplied by the injectable [InstalledAppsProvider] seam, which enumerates
 * launcher apps via `PackageManager` (no special permission; no `QUERY_ALL_PACKAGES`). The user
 * searches by app name OR package id; a free-text fallback is kept for packages the launcher
 * query doesn't surface.
 */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier, leaveGuard: LeaveGuardState? = null) {
    val context = LocalContext.current
    val vm: NotificationsViewModel = viewModel(factory = NotificationsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val progress by vm.syncProgress.collectAsStateWithLifecycle()

    // WP-SYNCSTATUS (Step 3): publish pending state + Save action so the host can prompt on leave.
    PublishLeaveGuard(leaveGuard, state.pendingCount) { vm.saveToWatch() }

    // Load the installed launchable apps off the main thread; empty until ready so the dialog
    // still works (free-text) before the list arrives.
    val provider = remember(context) { SystemInstalledAppsProvider(context) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    LaunchedEffect(provider) {
        installedApps = withContext(Dispatchers.IO) { provider.installedApps() }
    }

    NotificationsContent(
        state = state,
        installedApps = installedApps,
        onAdd = { pkg, vibe, hourDeg, minDeg -> vm.addRule(pkg, vibe, hourDeg, minDeg) },
        onUpdate = vm::updateRule,
        onDelete = vm::deleteRule,
        onPlay = vm::playRule,
        onSave = { vm.saveToWatch() },
        progress = progress,
        modifier = modifier,
    )
}

/**
 * Stateless Notifications body — pure function of [NotificationsUiState] + the installed-app
 * list + intent lambdas, so it is preview-/UI-testable with fake state and no VM/Room/BLE.
 */
@Composable
fun NotificationsContent(
    state: NotificationsUiState,
    installedApps: List<InstalledApp>,
    onAdd: (pkg: String, vibe: Int, hourDeg: Int, minDeg: Int) -> Boolean,
    onUpdate: (NotificationRuleEntity) -> Unit,
    onDelete: (pkg: String) -> Unit,
    onSave: () -> Boolean,
    modifier: Modifier = Modifier,
    progress: SyncProgressUi = SyncProgressUi.IDLE,
    // WP11: test the on-watch play for a saved rule (buzz + hands per the already-uploaded filter).
    onPlay: (pkg: String) -> Boolean = { false },
) {
    // Editor dialog state: null = closed; an entity with blank packageName = adding,
    // an entity with a real packageName already present = editing.
    var editing by remember { mutableStateOf<NotificationRuleEntity?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    // WP-SYNCFIX: blocking "Saving to watch…" modal while a save is in flight.
    SyncSavingDialog(progress)

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
            // WP-SYNCFIX: honest link state — rules stay editable offline; banner says when synced.
            ConnectionBanner()
            // WP-SYNCSTATUS: "N change(s) not on the watch" when any rule is edited-since-push.
            PendingSyncBanner(state.pendingCount)
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
                        // WP-PROGRESS: spinner + disable while SYNCING; transient success/error note.
                        SyncSaveButton(
                            progress = progress,
                            hasActiveWatch = state.hasActiveWatch,
                            onSave = { onSave() },
                        )
                    }
                    Text(
                        "Pick an installed app (searchable by name), or type a package name.",
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
                                    onWatch = state.isOnWatch(r.packageName),
                                    onClick = { addingNew = false; editing = r },
                                    onPlay = { onPlay(r.packageName) },
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
            installedApps = installedApps,
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
    onWatch: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
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
                // WP-SYNCSTATUS: ✓ on-watch vs ⏳ not-synced for THIS rule.
                SyncRowBadge(onWatch = onWatch)
            }
            // WP11: test the on-watch play for THIS app (buzz + hands per the saved rule, which is
            // already on the watch in its NOTIFICATION_FILTER). Connect-then-play if disconnected.
            // WP-SYNCSTATUS: DISABLED until the rule is on the watch — the watch doesn't have this
            // rule's vibe/hands yet, so a play would no-op (or buzz the wrong/stale config).
            IconButton(onClick = onPlay, enabled = onWatch) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play on watch")
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
    installedApps: List<InstalledApp>,
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
                    AppPicker(
                        query = pkg,
                        onQueryChange = { pkg = it },
                        installedApps = installedApps,
                        existingPackages = existingPackages,
                        isError = duplicate,
                        onPick = { pkg = it },
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

/**
 * Searchable installed-app picker: a text field that **filters the installed-app list live by
 * display name OR package id** as the user types (fixing the previous static/non-filtering
 * dropdown), showing each match's icon + friendly name with the package id as a subtitle. The
 * field doubles as a free-text package entry for apps the launcher query doesn't surface.
 */
@Composable
private fun AppPicker(
    query: String,
    onQueryChange: (String) -> Unit,
    installedApps: List<InstalledApp>,
    existingPackages: Set<String>,
    isError: Boolean,
    onPick: (packageName: String) -> Unit,
) {
    val matches = remember(query, installedApps) {
        installedApps.asSequence()
            .filter { it.matches(query) }
            .filter { it.packageName !in existingPackages } // hide already-configured apps
            .take(50)
            .toList()
    }
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search apps or type a package name") },
            singleLine = true,
            isError = isError,
            modifier = Modifier.fillMaxWidth(),
        )
        if (installedApps.isEmpty()) {
            Text(
                "Loading installed apps… you can also type a package name.",
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (matches.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
            ) {
                items(matches, key = { it.packageName }) { app ->
                    AppPickerRow(app = app, onClick = { onPick(app.packageName) })
                }
            }
        } else {
            Text(
                "No matching installed app — the typed package will be used as-is.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AppPickerRow(app: InstalledApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val bmp = remember(app.packageName, app.icon) {
            runCatching { app.icon?.toBitmap()?.asImageBitmap() }.getOrNull()
        }
        if (bmp != null) {
            Icon(
                bitmap = bmp,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Spacer(Modifier.size(32.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            TextButton(
                onClick = onClick,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Column {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                }
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
