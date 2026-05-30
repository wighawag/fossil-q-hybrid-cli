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
        onSetNudge = vm::setNudge,
        onSetTimezone = vm::setSecondTimezoneOffset,
        onSetMusicApp = vm::setPreferredMusicApp,
        onTransfer = vm::transferSettings,
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
            NudgeCard(state, onSetNudge) { note = it }
            TimezoneCard(state, onSetTimezone) { note = it }
            MusicAppCard(state, onSetMusicApp)
            HorizontalDivider()
            TransferCard(state, onTransfer) { note = it }
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
