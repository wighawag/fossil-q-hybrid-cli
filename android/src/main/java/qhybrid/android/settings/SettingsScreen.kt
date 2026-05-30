@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import qhybrid.android.sync.ConnectionBanner
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncSavingDialog
import qhybrid.android.sync.SyncStatusRow

/**
 * WP16g — the Settings screen (the SEVENTH and LAST user-facing screen). State comes from
 * [SettingsViewModel] (WP4 active watch for the persisted vibration strength + the app-level
 * [SettingsPrefs] for nudge / second timezone / preferred music app).
 *
 * **PER-SETTING DATA SOURCE (clearly surfaced in the UI):**
 *   - **Vibration strength** — PERSISTED per-watch ([qhybrid.android.db.WatchEntity.vibrationStrength],
 *     an existing WP4 field; NO new DB field). The live apply to the watch is on-device-pending
 *     (deferred behind [ServiceSettingsSync.SETTINGS_WIRED] = false → WP14).
 *   - **Inactivity nudge** + **second timezone** — APP PREF (persisted via [SettingsPrefs]) + a
 *     deferred live command (WP14).
 *   - **Preferred music app** — a PURE phone-side pref (reuses the WP16c installed-app picker; never
 *     sent to the watch).
 *   - **Settings transfer** — the WP4 [qhybrid.android.db.WatchRepository.transferSettings] surface
 *     (reused, not reinvented).
 *   - **View logs** — navigates to the existing WP15 log viewer (reused; no second viewer built).
 *
 * The live watch commands are clearly flagged on-device-pending. The persisted prefs + the
 * settings-transfer are real.
 */
@Composable
fun SettingsScreen(
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val progress by vm.syncProgress.collectAsStateWithLifecycle()

    // Load the installed-app list once for the music-app picker (reuses WP16c).
    LaunchedEffect(Unit) { vm.loadInstalledApps() }

    SettingsContent(
        state = state,
        progress = progress,
        onSetVibration = vm::setVibrationStrength,
        onVibrate = vm::vibrateWatch,
        onVibrateWithFilter = vm::vibrateWatchWithFilter,
        onSyncAll = vm::syncAll,
        onSetNudge = vm::setNudge,
        onSetTimezone = vm::setSecondTimezoneOffset,
        onSetMusicApp = vm::setPreferredMusicApp,
        onTransfer = vm::transferSettings,
        onRemoveWatch = vm::removeActiveWatch,
        onOpenLogs = onOpenLogs,
        modifier = modifier,
    )
}

/**
 * Stateless Settings body — a pure function of [SettingsUiState] + the intents, so it is
 * preview-/UI-testable with fake state and no VM/Room/BLE. Standard Material3 controls only
 * (Slider / Switch / dropdown / Button — no new dependency).
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onSetVibration: (Int) -> Boolean,
    onSetNudge: (Boolean, Int) -> Boolean,
    onSetTimezone: (Int) -> Boolean,
    onSetMusicApp: (String?) -> Unit,
    onTransfer: (String, String) -> Boolean,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
    progress: SyncProgressUi = SyncProgressUi.IDLE,
    // WP-BUZZTEST: manual "vibrate the watch now" test buttons (pattern byte). No-op default so
    // existing previews/tests that don't exercise the buzz keep compiling.
    onVibrate: (Int) -> Boolean = { false },
    // Diagnostic: buzz via the self-contained filter+play path (works without the reserved filter).
    onVibrateWithFilter: (Int) -> Boolean = { false },
    // WP-PULLSYNC: manual "Sync all" (full reconcile). No-op default for previews/tests.
    onSyncAll: () -> Boolean = { false },
    // WP-WATCHADMIN: "remove / re-provision this watch". No-op default for previews/tests.
    onRemoveWatch: () -> Boolean = { false },
) {
    var note by remember { mutableStateOf<String?>(null) }

    // WP-SYNCFIX: blocking "Saving to watch…" modal while a settings apply is in flight.
    SyncSavingDialog(progress)

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            // WP-SYNCFIX: honest link state — settings stay editable offline; banner says when synced.
            ConnectionBanner()
            // WP-PROGRESS: each setting apply pokes a sync; surface the live SYNCING/result here.
            SyncStatusRow(progress = progress)
            if (!state.hasActiveWatch) {
                Text(
                    "No active watch — per-watch settings are disabled. App preferences below " +
                        "(music app) are still saved.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            VibrationCard(state, onSetVibration) { note = it }
            TestVibrationCard(state, progress, onVibrate, onVibrateWithFilter) { note = it }
            NudgeCard(state, onSetNudge) { note = it }
            TimezoneCard(state, onSetTimezone) { note = it }
            MusicAppCard(state, onSetMusicApp)
            HorizontalDivider()
            SyncAllCard(state, progress, onSyncAll) { note = it }
            HorizontalDivider()
            TransferCard(state, onTransfer) { note = it }
            HorizontalDivider()
            RemoveWatchCard(state, onRemoveWatch) { note = it }
            HorizontalDivider()
            LogsCard(onOpenLogs)

            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

// ---- vibration strength (PERSISTED per-watch + deferred live command) -------

@Composable
private fun VibrationCard(
    state: SettingsUiState,
    onSetVibration: (Int) -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Vibration strength") {
        Text(
            "Saved per watch. Applying it to the watch is on-device-pending (WP14).",
            style = MaterialTheme.typography.labelSmall,
        )
        // Local slider value mirrors the persisted value but lets the user drag smoothly.
        var value by remember(state.vibrationStrength, state.activeMac) {
            mutableStateOf(state.vibrationStrength.toFloat())
        }
        Text("${value.toInt()}%", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange =
                SettingsVocabulary.VIBE_MIN.toFloat()..SettingsVocabulary.VIBE_MAX.toFloat(),
            enabled = state.canEditWatchSettings,
            onValueChangeFinished = {
                val wired = onSetVibration(value.toInt())
                onNote(applyNote("Vibration strength", wired))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- manual "vibrate the watch now" test buttons (WP-BUZZTEST) ---------------

/**
 * WP-BUZZTEST — two manual "vibrate the watch now" buttons (an on-device test tool). Pressing one
 * makes the watch buzz immediately (connecting first if the link is down; an honest error if
 * unreachable, surfaced via the shared [SyncStatusRow] + the blocking [SyncSavingDialog]).
 *
 *   - "Vibrate (single)" → pattern 5 (ONE_SHORT_VIBE — strong single buzz)
 *   - "Vibrate (triple)" → pattern 1 (CALL — triple buzz)
 *
 * The buttons are disabled while a buzz/sync is in flight and when there is no active watch
 * (reusing [SyncProgressUi.saveEnabled], the same rule the Save-to-watch buttons use). No new wire
 * bytes — reuses [qhybrid.protocol.FossilController.buzz]. UI rendering is on-device-pending.
 */
@Composable
private fun TestVibrationCard(
    state: SettingsUiState,
    progress: SyncProgressUi,
    onVibrate: (Int) -> Boolean,
    onVibrateWithFilter: (Int) -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Test vibration") {
        Text(
            "Make the watch buzz now to test it. Connects first if needed; reports an error if the " +
                "watch is unreachable.",
            style = MaterialTheme.typography.labelSmall,
        )
        val enabled = progress.saveEnabled(state.hasActiveWatch)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val wired = onVibrate(VibrationSync.PATTERN_SINGLE)
                    onNote(buzzNote("single", wired))
                },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Vibrate (single)") }
            Button(
                onClick = {
                    val wired = onVibrate(VibrationSync.PATTERN_TRIPLE)
                    onNote(buzzNote("triple", wired))
                },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Vibrate (triple)") }
        }
        // Diagnostic fallback: forces NOTIFICATION_FILTER + NOTIFICATION_PLAY (the self-contained
        // path) so it buzzes even if the reserved play-only filter isn't on the watch. Use this to
        // tell "reserved filter missing" (this works, the play-only buttons don't) from other issues.
        OutlinedButton(
            onClick = {
                val wired = onVibrateWithFilter(VibrationSync.PATTERN_SINGLE)
                onNote(buzzNote("single, filter+play", wired))
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Put filter + send buzz") }
    }
}

// ---- inactivity nudge (APP PREF + deferred live command) --------------------

@Composable
private fun NudgeCard(
    state: SettingsUiState,
    onSetNudge: (Boolean, Int) -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Inactivity nudge") {
        Text(
            "Reminds you to move after a period of inactivity. Saved app-side; applying it to " +
                "the watch is on-device-pending (WP14).",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (state.nudgeEnabled) "Enabled" else "Disabled")
            Switch(
                checked = state.nudgeEnabled,
                onCheckedChange = {
                    val wired = onSetNudge(it, state.nudgeMinutes)
                    onNote(applyNote("Inactivity nudge", wired))
                },
            )
        }
        Text(
            "Inactivity duration: ${state.nudgeMinutes} min",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeNudgeMinutes(
                        state.nudgeMinutes - SettingsVocabulary.NUDGE_STEP_MINUTES
                    )
                    val wired = onSetNudge(state.nudgeEnabled, next)
                    onNote(applyNote("Inactivity nudge", wired))
                },
            ) { Text("− ${SettingsVocabulary.NUDGE_STEP_MINUTES}m") }
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeNudgeMinutes(
                        state.nudgeMinutes + SettingsVocabulary.NUDGE_STEP_MINUTES
                    )
                    val wired = onSetNudge(state.nudgeEnabled, next)
                    onNote(applyNote("Inactivity nudge", wired))
                },
            ) { Text("+ ${SettingsVocabulary.NUDGE_STEP_MINUTES}m") }
        }
    }
}

// ---- second timezone (APP PREF + deferred live command) ---------------------

@Composable
private fun TimezoneCard(
    state: SettingsUiState,
    onSetTimezone: (Int) -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Second timezone") {
        Text(
            "Shown by a watch button mapped to 'second timezone'. Saved app-side; applying it to " +
                "the watch is on-device-pending (WP14).",
            style = MaterialTheme.typography.labelSmall,
        )
        var expanded by remember { mutableStateOf(false) }
        val selectedLabel = SettingsVocabulary.tzOffsetLabel(state.secondTimezoneOffsetMinutes)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Offset from UTC") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SettingsVocabulary.TZ_OFFSET_OPTIONS_MINUTES.forEach { offset ->
                    DropdownMenuItem(
                        text = { Text(SettingsVocabulary.tzOffsetLabel(offset)) },
                        onClick = {
                            expanded = false
                            val wired = onSetTimezone(offset)
                            onNote(applyNote("Second timezone", wired))
                        },
                    )
                }
            }
        }
    }
}

// ---- preferred music app (PURE app pref; reuses WP16c picker) ----------------

@Composable
private fun MusicAppCard(
    state: SettingsUiState,
    onSetMusicApp: (String?) -> Unit,
) {
    SettingCard("Preferred music app") {
        Text(
            "Launched by music controls when no player is active (phone-side only — never sent " +
                "to the watch).",
            style = MaterialTheme.typography.labelSmall,
        )
        var expanded by remember { mutableStateOf(false) }
        val current = state.installedApps.firstOrNull { it.packageName == state.preferredMusicApp }
        val selectedLabel = when {
            !state.hasPreferredMusicApp -> "None (use generic media key)"
            current != null -> "${current.label} (${current.packageName})"
            else -> state.preferredMusicApp
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Music app") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("None (use generic media key)") },
                    onClick = {
                        expanded = false
                        onSetMusicApp(null)
                    },
                )
                state.installedApps.forEach { app ->
                    DropdownMenuItem(
                        text = { Text("${app.label} (${app.packageName})") },
                        onClick = {
                            expanded = false
                            onSetMusicApp(app.packageName)
                        },
                    )
                }
            }
        }
    }
}

// ---- manual "Sync all" (WP-PULLSYNC) ----------------------------------------

/**
 * WP-PULLSYNC — a manual "Sync all" button. Connecting no longer auto-pushes the full config
 * (the watch keeps its settings across disconnects, and each screen's Save-to-watch already pushes
 * what you change — with a blocking modal so you know it happened). This button is the explicit
 * escape hatch to re-push the ENTIRE saved config (alarms / notification rules / buttons /
 * settings) to the active watch in one pass. Disabled while a sync/buzz is in flight and when
 * there is no active watch (reuses [SyncProgressUi.saveEnabled]). No new wire bytes.
 */
@Composable
private fun SyncAllCard(
    state: SettingsUiState,
    progress: SyncProgressUi,
    onSyncAll: () -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Sync all") {
        Text(
            "Push the entire saved config (alarms, notification rules, buttons, settings) to the " +
                "watch. Normally you don't need this — each screen's Save already syncs what you " +
                "change. Connects first if needed; reports an error if the watch is unreachable.",
            style = MaterialTheme.typography.labelSmall,
        )
        Button(
            onClick = {
                val ok = onSyncAll()
                onNote(if (ok) "Syncing the full config to the watch…" else "No active watch to sync.")
            },
            enabled = progress.saveEnabled(state.hasActiveWatch),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sync all") }
    }
}

// ---- settings transfer (WP4 reuse) ------------------------------------------

@Composable
private fun TransferCard(
    state: SettingsUiState,
    onTransfer: (String, String) -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Settings transfer") {
        Text(
            "Copy all alarms, notification rules, and button mappings from one watch to another " +
                "(WP4 clone). The source watch is never modified.",
            style = MaterialTheme.typography.labelSmall,
        )
        var fromMac by remember(state.activeMac) { mutableStateOf(state.activeMac ?: "") }
        var toMac by remember { mutableStateOf("") }
        OutlinedTextField(
            value = fromMac,
            onValueChange = { fromMac = it },
            label = { Text("From MAC") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = toMac,
            onValueChange = { toMac = it },
            label = { Text("To MAC") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val ok = onTransfer(fromMac, toMac)
                onNote(
                    if (ok) "Transferring settings from $fromMac to $toMac…"
                    else "Enter two different watch MACs to transfer.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Transfer settings") }
    }
}

// ---- remove / re-provision this watch (WP-WATCHADMIN) -----------------------

/**
 * WP-WATCHADMIN — "Remove watch" with a confirmation dialog. Removing the active watch deletes the
 * app's knowledge of it (DB row + alarms/rules/buttons) and clears its CDM association / presence /
 * reconnect pointer, then disconnects. The NEXT connect then looks brand-new and re-runs the
 * one-time provisioning sync (which uploads the notification filter with the reserved buzz entries
 * folded in). This is the user-facing replacement for the old Debug-Menu wipe.
 *
 * It does NOT unpair at the OS level — the dialog advises the user to "Forget" the device in
 * Android Settings if they want a full Bluetooth unpair. Disabled when there is no active watch.
 */
@Composable
private fun RemoveWatchCard(
    state: SettingsUiState,
    onRemoveWatch: () -> Boolean,
    onNote: (String) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    SettingCard("Remove watch") {
        Text(
            "Forget this watch in the app: deletes its alarms, notification rules, and button " +
                "mappings, clears the pairing, and disconnects. Re-adding it later re-provisions it " +
                "from scratch. (To fully unpair Bluetooth, also use Android Settings → Bluetooth → " +
                "Forget.)",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(
            onClick = { confirming = true },
            enabled = state.hasActiveWatch,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Remove watch") }
    }

    if (confirming) {
        val mac = state.activeMac ?: ""
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove this watch?") },
            text = {
                Text(
                    "This deletes the app's alarms, notification rules, and button mappings for " +
                        "$mac, clears the pairing, and disconnects. Your watch keeps its current " +
                        "settings until you re-add and re-sync it. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val ok = onRemoveWatch()
                    onNote(
                        if (ok) "Removed $mac. Re-add it to provision again."
                        else "No active watch to remove.",
                    )
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

// ---- log viewer (WP15 reuse) ------------------------------------------------

@Composable
private fun LogsCard(onOpenLogs: () -> Unit) {
    SettingCard("Logs") {
        Text(
            "View the in-app log console (the same WP15 log viewer used by the Debug Menu).",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
            Text("View logs")
        }
    }
}

// ---- shared scaffolding -----------------------------------------------------

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun applyNote(name: String, wired: Boolean): String =
    if (wired) "$name applied to the watch."
    else "$name saved. Applying it to the watch is on-device-pending (WP14)."

private fun buzzNote(kind: String, wired: Boolean): String =
    if (wired) "Buzzing the watch ($kind)…"
    else "No active watch to vibrate."
