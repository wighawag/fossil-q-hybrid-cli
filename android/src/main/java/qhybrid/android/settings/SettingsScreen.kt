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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.sync.ConnectionBanner
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncSavingDialog
import qhybrid.android.sync.SyncStatusRow
import qhybrid.protocol.requests.fossil.notification.BuzzPatterns

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
    onOpenDefaults: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    onOpenNavCueDiag: () -> Unit = {},
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
        onSyncAll = vm::syncAll,
        onApplyDefaults = vm::applyDefaultsToActiveWatch,
        onClearAlarms = vm::clearAlarmsOnActiveWatch,
        onSetNudge = vm::setNudge,
        onSetTimezone = vm::setSecondTimezoneOffset,
        onSetCalendarOffset = vm::setCalendarAlarmOffset,
        onResyncCalendar = vm::resyncCalendar,
        onSetMusicApp = vm::setPreferredMusicApp,
        onSetMultiFunctionRole = vm::setMultiFunctionRole,
        onToggleMode = vm::toggleMultiFunctionMode,
        onSetActiveModeIndex = vm::setMultiFunctionActiveIndex,
        onSetLyrionServer = vm::setLyrionServer,
        onSetLyrionPlayer = vm::setLyrionPlayer,
        onSetLyrionFallback = vm::setLyrionEmptyQueueFallback,
        onSetLyrionFavorite = vm::setLyrionFavoriteId,
        onLoadLyrionPlayers = { host, port -> vm.loadLyrionPlayers(host, port) },
        onLoadLyrionFavorites = { host, port -> vm.loadLyrionFavorites(host, port) },
        onDiscoverLyrionServers = vm::discoverLyrionServers,
        onSetRingDuration = vm::setRingDurationSeconds,
        onSetNavCue = vm::setNavCue,
        onTransfer = vm::transferSettings,
        onRemoveWatch = vm::removeActiveWatch,
        onOpenLogs = onOpenLogs,
        onOpenDefaults = onOpenDefaults,
        onOpenWaypoints = onOpenWaypoints,
        onOpenNavCueDiag = onOpenNavCueDiag,
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
    // WP-TRACKER: set the GLOBAL multi-function role (MUSIC ⇄ TRACKER). No-op default.
    onSetMultiFunctionRole: (String) -> Unit = {},
    // L0: toggle a mode in/out of the configurable rotation. No-op default.
    onToggleMode: (String) -> Unit = {},
    // L0: set the active mode index in the rotation. No-op default.
    onSetActiveModeIndex: (Int) -> Unit = {},
    // L1: set the Lyrion server host + port. No-op default.
    onSetLyrionServer: (String?, Int) -> Unit = { _, _ -> },
    // L1: set the target Lyrion player id + display name. No-op default.
    onSetLyrionPlayer: (String?, String?) -> Unit = { _, _ -> },
    // L1: set the Lyrion empty-queue fallback. No-op default.
    onSetLyrionFallback: (String?) -> Unit = {},
    // L1: set the Lyrion favourite id. No-op default.
    onSetLyrionFavorite: (String?) -> Unit = {},
    // L6: load players from a server (host, port passed live from the fields). No-op default.
    onLoadLyrionPlayers: (String, Int) -> Unit = { _, _ -> },
    // L6: load favourites from a server (host, port passed live from the fields). No-op default.
    onLoadLyrionFavorites: (String, Int) -> Unit = { _, _ -> },
    // L7: discover Lyrion servers on the LAN (UDP). No-op default.
    onDiscoverLyrionServers: () -> Unit = {},
    // WP-TRACKER: set the loud-ring auto-stop duration (seconds). No-op default for previews/tests.
    onSetRingDuration: (Int) -> Boolean = { false },
    // WP-NAV: set the turn-by-turn nav-cue toggle + soon/now trigger distances. No-op default.
    onSetNavCue: (Boolean, Int, Int) -> Boolean = { _, _, _ -> false },
    // WP13: set the calendar-alarm ring offset (minutes before the event). No-op default.
    onSetCalendarOffset: (Int) -> Boolean = { false },
    // WP13: manually re-read the calendar + re-map/push slots 16–31. No-op default.
    onResyncCalendar: () -> Boolean = { false },
    onTransfer: (String, String) -> Boolean,
    onOpenLogs: () -> Unit,
    onOpenDefaults: () -> Unit = {},
    // WP-TRACKER: open the GPS-waypoint viewer (list + Save/Share GPX). No-op default for previews.
    onOpenWaypoints: () -> Unit = {},
    // WP-NAV: open the live navigation-cue diagnostics screen. No-op default for previews.
    onOpenNavCueDiag: () -> Unit = {},
    modifier: Modifier = Modifier,
    progress: SyncProgressUi = SyncProgressUi.IDLE,
    // WP-BUZZTEST: manual "vibrate the watch now" test buttons (pattern byte). No-op default so
    // existing previews/tests that don't exercise the buzz keep compiling.
    onVibrate: (Int) -> Boolean = { false },
    // WP-PULLSYNC: manual "Sync all" (full reconcile). No-op default for previews/tests.
    onSyncAll: () -> Boolean = { false },
    // WP-DEFAULTS: manual "Apply defaults to this watch" (overwrite buttons + filter). No-op default.
    onApplyDefaults: () -> Boolean = { false },
    // WP-CLEARALARMS: manual "Clear all alarms" (delete + blank the watch). No-op default.
    onClearAlarms: () -> Boolean = { false },
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
            TestVibrationCard(state, progress, onVibrate) { note = it }
            NudgeCard(state, onSetNudge) { note = it }
            TimezoneCard(state, onSetTimezone) { note = it }
            CalendarOffsetCard(state, onSetCalendarOffset, onResyncCalendar) { note = it }
            MusicAppCard(state, onSetMusicApp)
            MultiFunctionRotationCard(state, onToggleMode, onSetActiveModeIndex)
            if (state.lyrionInRotation) {
                LyrionCard(
                    state,
                    onSetLyrionServer,
                    onSetLyrionPlayer,
                    onSetLyrionFallback,
                    onSetLyrionFavorite,
                    onLoadLyrionPlayers,
                    onLoadLyrionFavorites,
                    onDiscoverLyrionServers,
                ) { note = it }
            }
            RingDurationCard(state, onSetRingDuration) { note = it }
            NavCueCard(state, onSetNavCue, onOpenNavCueDiag) { note = it }
            WaypointsEntryCard(onOpenWaypoints)
            HorizontalDivider()
            SyncAllCard(state, progress, onSyncAll) { note = it }
            HorizontalDivider()
            DefaultsEntryCard(onOpenDefaults)
            HorizontalDivider()
            ApplyDefaultsCard(state, progress, onApplyDefaults) { note = it }
            HorizontalDivider()
            ClearAlarmsCard(state, progress, onClearAlarms) { note = it }
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
 * WP-SYNCSTATUS: replaced the old fixed two buttons + diagnostic "put filter + send buzz" button
 * with a **vibration-pattern dropdown + a single "Buzz" button** so the user can feel each useful
 * pattern. The dropdown lists EXACTLY the reserved patterns
 * ([qhybrid.protocol.requests.fossil.notification.BuzzPatterns.RESERVED_PATTERNS] = {1,2,3,5,6,7,8}
 * — silent 0/9 and the 4≡3 duplicate are skipped) because those are the ones already on the watch
 * in the reserved filter, so a **play-only** buzz ([onVibrate]/`VibrationSync.buzz` with
 * `forceFilterPlay=false`) applies them. Default selection is 5 ([VibrationSync.PATTERN_SINGLE]).
 *
 * The button is disabled while a buzz/sync is in flight and when there is no active watch (reusing
 * [SyncProgressUi.saveEnabled]). No new wire bytes — reuses the WP3 service's play-only buzz path.
 * UI rendering is on-device-pending.
 */
@Composable
private fun TestVibrationCard(
    state: SettingsUiState,
    progress: SyncProgressUi,
    onVibrate: (Int) -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Test vibration") {
        Text(
            "Pick a vibration pattern and buzz the watch to feel it. Connects first if needed; " +
                "reports an error if the watch is unreachable.",
            style = MaterialTheme.typography.labelSmall,
        )
        val enabled = progress.saveEnabled(state.hasActiveWatch)
        // Only the reserved (useful) patterns are offered — those are on the watch's reserved
        // filter, so the play-only buzz can apply them.
        var selected by remember { mutableStateOf(VibrationSync.PATTERN_SINGLE) }
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = "$selected — ${VibePatterns.label(selected)}",
                onValueChange = {},
                readOnly = true,
                label = { Text("Vibration pattern") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                BuzzPatterns.RESERVED_PATTERNS.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("$p — ${VibePatterns.label(p)}") },
                        onClick = {
                            selected = p
                            expanded = false
                        },
                    )
                }
            }
        }
        Button(
            onClick = {
                // Play-only: the reserved patterns are already on the watch, so a single
                // NOTIFICATION_PLAY put buzzes the selected pattern.
                val wired = onVibrate(selected)
                onNote(buzzNote(VibePatterns.label(selected), wired))
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Buzz") }
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

// ---- calendar alarm ring offset (WP13 — APP PREF, applied in CalendarRefresher) ----

@Composable
private fun CalendarOffsetCard(
    state: SettingsUiState,
    onSetCalendarOffset: (Int) -> Boolean,
    onResyncCalendar: () -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Calendar alarms") {
        Text(
            "How long before each calendar event the watch alarm rings. Changing this re-maps the " +
                "calendar alarm slots (16–31) and pushes them to the watch.",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "Ring: ${SettingsVocabulary.calendarOffsetLabel(state.calendarAlarmOffsetMinutes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeCalendarOffset(
                        state.calendarAlarmOffsetMinutes - SettingsVocabulary.CAL_OFFSET_STEP_MINUTES
                    )
                    onSetCalendarOffset(next)
                    onNote("Calendar alarms: ring " + SettingsVocabulary.calendarOffsetLabel(next))
                },
            ) { Text("− ${SettingsVocabulary.CAL_OFFSET_STEP_MINUTES}m") }
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeCalendarOffset(
                        state.calendarAlarmOffsetMinutes + SettingsVocabulary.CAL_OFFSET_STEP_MINUTES
                    )
                    onSetCalendarOffset(next)
                    onNote("Calendar alarms: ring " + SettingsVocabulary.calendarOffsetLabel(next))
                },
            ) { Text("+ ${SettingsVocabulary.CAL_OFFSET_STEP_MINUTES}m") }
        }
        // WP13: force a re-read of the calendar now (re-map slots 16–31 + silent push if changed).
        OutlinedButton(
            onClick = {
                onResyncCalendar()
                onNote("Resyncing calendar…")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Resync calendar now") }
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

/**
 * L0 — configure the GLOBAL multi-function ROTATION: which modes the switch button cycles, in what
 * order (first = default/active), plus a picker for the live active mode. Generalises the old 2-way
 * role flip. Modes: Music (phone) / Music (Lyrion player) / GPS waypoint tracker. GLOBAL because the
 * watch's gesture stream is button-blind. Pure phone-side state; never sent to the watch.
 */
@Composable
private fun MultiFunctionRotationCard(
    state: SettingsUiState,
    onToggleMode: (String) -> Unit,
    onSetActiveModeIndex: (Int) -> Unit,
) {
    SettingCard("Multi-function button modes") {
        Text(
            "Which modes the multi-function button cycles through (press the SWITCH button to " +
                "advance). The first enabled mode is the default. GLOBAL — applies to every " +
                "multi-function button at once. Phone-side only — never sent to the watch.",
            style = MaterialTheme.typography.labelSmall,
        )
        // Enable/disable each mode (checkbox). The rotation can never be emptied (last stays on).
        SettingsVocabulary.MULTI_FUNCTION_MODES.forEach { mode ->
            val enabled = state.multiFunctionRotation.contains(mode)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enabled, onCheckedChange = { onToggleMode(mode) })
                Text(SettingsVocabulary.modeLabel(mode))
            }
        }

        // Active-mode picker (the live entry the button currently points at).
        var expanded by remember { mutableStateOf(false) }
        val rotation = state.multiFunctionRotation
        val activeLabel = SettingsVocabulary.modeLabel(state.activeMode)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = activeLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Active mode") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                rotation.forEachIndexed { index, mode ->
                    DropdownMenuItem(
                        text = { Text(SettingsVocabulary.modeLabel(mode)) },
                        onClick = {
                            expanded = false
                            onSetActiveModeIndex(index)
                        },
                    )
                }
            }
        }
    }
}

/**
 * L1 — configure the Lyrion (LMS) music backend used by the "Music (Lyrion player)" mode: server
 * host + port, the target player id (MAC), the empty-queue fallback, and a favourite id. Shown only
 * when the Lyrion mode is in the rotation. Pure phone-side state; never sent to the watch.
 *
 * L6/L7: "Discover servers" (UDP) fills a server dropdown; "Load players"/"Load favourites" query
 * the configured server and fill the player/favourite dropdowns. Manual entry stays available.
 */
@Composable
private fun LyrionCard(
    state: SettingsUiState,
    onSetLyrionServer: (String?, Int) -> Unit,
    onSetLyrionPlayer: (String?, String?) -> Unit,
    onSetLyrionFallback: (String?) -> Unit,
    onSetLyrionFavorite: (String?) -> Unit,
    onLoadLyrionPlayers: (String, Int) -> Unit,
    onLoadLyrionFavorites: (String, Int) -> Unit,
    onDiscoverLyrionServers: () -> Unit,
    onNote: (String) -> Unit,
) {
    SettingCard("Lyrion music server") {
        Text(
            "Control a Lyrion (LMS) player over the network. Discover the server on your LAN (or " +
                "enter it), then load + pick the target player. A play gesture starts/controls music " +
                "on that player.",
            style = MaterialTheme.typography.labelSmall,
        )

        // Status line: loading spinner text + last lookup result ("Found N", or an error hint).
        if (state.lyrionLoading) {
            Text("Contacting server…", style = MaterialTheme.typography.labelMedium)
        } else if (state.lyrionLastResult.isNotEmpty()) {
            Text(state.lyrionLastResult, style = MaterialTheme.typography.labelMedium)
        }

        // L7 — discover servers on the LAN (UDP); selecting one fills host+port.
        OutlinedButton(
            onClick = { onDiscoverLyrionServers(); onNote("Discovering Lyrion servers…") },
            enabled = !state.lyrionLoading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Discover servers on network") }
        if (state.lyrionDiscoveredServers.isNotEmpty()) {
            var srvExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = srvExpanded,
                onExpandedChange = { srvExpanded = it },
            ) {
                OutlinedTextField(
                    value = "${state.lyrionDiscoveredServers.size} found — pick one",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Discovered servers") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = srvExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = srvExpanded,
                    onDismissRequest = { srvExpanded = false },
                ) {
                    state.lyrionDiscoveredServers.forEach { srv ->
                        DropdownMenuItem(
                            text = { Text(srv.name) },
                            onClick = {
                                srvExpanded = false
                                onSetLyrionServer(srv.host, srv.jsonPort)
                                onNote("Selected ${srv.name}")
                            },
                        )
                    }
                }
            }
        }

        var host by remember(state.lyrionServerHost) { mutableStateOf(state.lyrionServerHost) }
        var port by remember(state.lyrionServerPort) {
            mutableStateOf(state.lyrionServerPort.toString())
        }
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Server host / IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("Port (default 9000)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            onSetLyrionServer(host, port.toIntOrNull() ?: SettingsVocabulary.LYRION_PORT_DEFAULT)
            onNote("Lyrion server saved")
        }) { Text("Save server") }

        // L6 — load + pick a player. Uses the LIVE host/port fields (auto-saves them), so the user
        // doesn't have to press "Save server" first. Manual id entry still available.
        OutlinedButton(
            onClick = {
                onLoadLyrionPlayers(host, port.toIntOrNull() ?: SettingsVocabulary.LYRION_PORT_DEFAULT)
                onNote("Loading players…")
            },
            enabled = !state.lyrionLoading && host.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Load players") }
        if (state.lyrionPlayers.isNotEmpty()) {
            var plExpanded by remember { mutableStateOf(false) }
            val current = state.lyrionPlayers.firstOrNull { it.id == state.lyrionPlayerId }
            ExposedDropdownMenuBox(
                expanded = plExpanded,
                onExpandedChange = { plExpanded = it },
            ) {
                OutlinedTextField(
                    value = current?.name ?: state.lyrionPlayerName.ifEmpty { "Pick a player" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Player") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = plExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = plExpanded,
                    onDismissRequest = { plExpanded = false },
                ) {
                    state.lyrionPlayers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name} (${p.model})") },
                            onClick = {
                                plExpanded = false
                                onSetLyrionPlayer(p.id, p.name)
                                onNote("Selected ${p.name}")
                            },
                        )
                    }
                }
            }
        }

        var playerId by remember(state.lyrionPlayerId) { mutableStateOf(state.lyrionPlayerId) }
        var playerName by remember(state.lyrionPlayerName) { mutableStateOf(state.lyrionPlayerName) }
        OutlinedTextField(
            value = playerId,
            onValueChange = { playerId = it },
            label = { Text("Player id (MAC) — or pick above") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it },
            label = { Text("Player name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            onSetLyrionPlayer(playerId, playerName)
            onNote("Lyrion player saved")
        }) { Text("Save player") }

        // Empty-queue fallback (what a play gesture starts when the queue is empty).
        var fbExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = fbExpanded,
            onExpandedChange = { fbExpanded = it },
        ) {
            OutlinedTextField(
                value = SettingsVocabulary.lyrionFallbackLabel(state.lyrionEmptyQueueFallback),
                onValueChange = {},
                readOnly = true,
                label = { Text("When queue is empty") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fbExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = fbExpanded,
                onDismissRequest = { fbExpanded = false },
            ) {
                SettingsVocabulary.LYRION_FALLBACKS.forEach { fb ->
                    DropdownMenuItem(
                        text = { Text(SettingsVocabulary.lyrionFallbackLabel(fb)) },
                        onClick = {
                            fbExpanded = false
                            onSetLyrionFallback(fb)
                        },
                    )
                }
            }
        }

        // Favourite (used when the fallback is FAVORITE): load + pick, or enter the id manually.
        if (state.lyrionEmptyQueueFallback == SettingsVocabulary.LYRION_FALLBACK_FAVORITE) {
            OutlinedButton(
                onClick = {
                    onLoadLyrionFavorites(host, port.toIntOrNull() ?: SettingsVocabulary.LYRION_PORT_DEFAULT)
                    onNote("Loading favourites…")
                },
                enabled = !state.lyrionLoading && host.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Load favourites") }
            if (state.lyrionFavorites.isNotEmpty()) {
                var favExpanded by remember { mutableStateOf(false) }
                val currentFav = state.lyrionFavorites.firstOrNull { it.id == state.lyrionFavoriteId }
                ExposedDropdownMenuBox(
                    expanded = favExpanded,
                    onExpandedChange = { favExpanded = it },
                ) {
                    OutlinedTextField(
                        value = currentFav?.name ?: "Pick a favourite",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Favourite") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = favExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = favExpanded,
                        onDismissRequest = { favExpanded = false },
                    ) {
                        state.lyrionFavorites.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name) },
                                onClick = {
                                    favExpanded = false
                                    onSetLyrionFavorite(f.id)
                                    onNote("Selected ${f.name}")
                                },
                            )
                        }
                    }
                }
            }
            var fav by remember(state.lyrionFavoriteId) { mutableStateOf(state.lyrionFavoriteId) }
            OutlinedTextField(
                value = fav,
                onValueChange = { fav = it },
                label = { Text("Favourite id (item_id) — or pick above") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                onSetLyrionFavorite(fav)
                onNote("Lyrion favourite saved")
            }) { Text("Save favourite") }
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

// ---- WP-TRACKER: loud "find my phone" ring duration ------------------------

/**
 * WP-TRACKER — the auto-stop duration for the loud "find my phone" ring (a TRACKER-role long
 * gesture / a RING_PHONE button). A repeated trigger also stops it early; this caps how long it
 * rings if you can't get to it. Phone-side only — never sent to the watch.
 */
@Composable
private fun RingDurationCard(
    state: SettingsUiState,
    onSetRingDuration: (Int) -> Boolean,
    onNote: (String) -> Unit,
) {
    SettingCard("Find-my-phone ring") {
        Text(
            "How long the phone rings loudly when a TRACKER long-press / RING_PHONE button fires " +
                "(press again to stop early). Phone-side only — never sent to the watch.",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "Ring for: ${SettingsVocabulary.ringDurationLabel(state.ringDurationSeconds)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeRingDuration(
                        state.ringDurationSeconds - SettingsVocabulary.RING_DURATION_STEP_SECONDS
                    )
                    onSetRingDuration(next)
                    onNote("Find-my-phone ring: " + SettingsVocabulary.ringDurationLabel(next))
                },
            ) { Text("− ${SettingsVocabulary.RING_DURATION_STEP_SECONDS}s") }
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeRingDuration(
                        state.ringDurationSeconds + SettingsVocabulary.RING_DURATION_STEP_SECONDS
                    )
                    onSetRingDuration(next)
                    onNote("Find-my-phone ring: " + SettingsVocabulary.ringDurationLabel(next))
                },
            ) { Text("+ ${SettingsVocabulary.RING_DURATION_STEP_SECONDS}s") }
        }
    }
}

// ---- GPS waypoints entry (WP-TRACKER) --------------------------------------

/** WP-TRACKER — a card that opens the GPS-waypoint viewer (list + Save/Share GPX). */
@Composable
private fun WaypointsEntryCard(onOpenWaypoints: () -> Unit) {
    SettingCard("GPS waypoints") {
        Text(
            "View the GPS waypoints logged by TRACKER-role gestures / the \"Log GPS waypoint\" " +
                "button, and save or share them as a GPX file.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(onClick = onOpenWaypoints, modifier = Modifier.fillMaxWidth()) {
            Text("View GPS waypoints")
        }
    }
}

// ---- defaults editor entry (WP-DEFAULTS sub-part 4) -------------------------

/** WP-DEFAULTS — a card that navigates to the app-level "Defaults for new watches" editor. */
@Composable
private fun DefaultsEntryCard(onOpenDefaults: () -> Unit) {
    SettingCard("Defaults for new watches") {
        Text(
            "Edit the buttons / alarms / notification rules applied when you ADD a new watch (the " +
                "sections the watch can't report back). Readable settings (vibration, step goal, " +
                "nudge, 2nd timezone) are read FROM the watch, so they're not here.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(onClick = onOpenDefaults, modifier = Modifier.fillMaxWidth()) {
            Text("Edit defaults")
        }
    }
}

// ---- apply defaults to this watch (WP-DEFAULTS sub-part 3) -------------------

/**
 * WP-DEFAULTS — "Apply defaults to this watch": push the app-level defaults profile's UNREADABLE
 * sections (buttons + the notification filter/rules) onto the already-added active watch on demand,
 * a FULL-OVERWRITE of those per-watch sections (the watch ends up with exactly the profile's
 * buttons + filter, same as provisioning). Because this WIPES the user's per-watch button /
 * notification setup, it is gated behind a confirm dialog (mirrors [RemoveWatchCard]). Disabled
 * while a sync/buzz is in flight and when there is no active watch ([SyncProgressUi.saveEnabled]).
 */
@Composable
private fun ApplyDefaultsCard(
    state: SettingsUiState,
    progress: SyncProgressUi,
    onApplyDefaults: () -> Boolean,
    onNote: (String) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    SettingCard("Apply defaults to this watch") {
        Text(
            "Overwrite this watch's buttons and notification filter with your \"Defaults for new " +
                "watches\" profile (a full replace, not a merge — the same sections provisioning " +
                "pushes when you add a watch). Your per-watch button / notification setup is " +
                "replaced. Connects first if needed.",
            style = MaterialTheme.typography.labelSmall,
        )
        Button(
            onClick = { confirming = true },
            enabled = progress.saveEnabled(state.hasActiveWatch),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply defaults to this watch") }
    }

    if (confirming) {
        val mac = state.activeMac ?: ""
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Apply defaults to this watch?") },
            text = {
                Text(
                    "This REPLACES $mac's buttons and notification filter with your defaults " +
                        "profile (full overwrite, not a merge). Your current per-watch buttons and " +
                        "notification rules for this watch will be lost. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val ok = onApplyDefaults()
                    onNote(
                        if (ok) "Applying the defaults profile to the watch…"
                        else "No active watch to apply defaults to.",
                    )
                }) { Text("Apply defaults") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

// ---- clear all alarms (WP-CLEARALARMS) --------------------------------------

/**
 * WP-CLEARALARMS — "Clear all alarms": delete the active watch's standard alarms (slots 0–15) and
 * push the BLANKED alarm file to the watch (force-write, so the watch is actively cleared — a normal
 * save skip-empties an empty alarm set and would leave the watch's alarms in place). Calendar slots
 * (16–31) are left untouched. Gated behind a confirm dialog (mirrors [RemoveWatchCard]) because it
 * removes ALL the watch's alarms. Disabled while a sync/buzz is in flight and when there is no
 * active watch.
 */
@Composable
private fun ClearAlarmsCard(
    state: SettingsUiState,
    progress: SyncProgressUi,
    onClearAlarms: () -> Boolean,
    onNote: (String) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    SettingCard("Clear all alarms") {
        Text(
            "Remove ALL alarms from this watch (the app's alarm list AND the watch itself). " +
                "Calendar-synced alarms are not affected. Connects first if needed.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(
            onClick = { confirming = true },
            enabled = progress.saveEnabled(state.hasActiveWatch),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Clear all alarms") }
    }

    if (confirming) {
        val mac = state.activeMac ?: ""
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear all alarms?") },
            text = {
                Text(
                    "This deletes every alarm for $mac — both in the app and on the watch. " +
                        "Calendar-synced alarms are kept. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val ok = onClearAlarms()
                    onNote(
                        if (ok) "Clearing all alarms from the watch…"
                        else "No active watch to clear alarms on.",
                    )
                }) { Text("Clear alarms") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
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

/**
 * WP-NAV — turn-by-turn navigation cues: a global toggle + the soon/now trigger distances. The
 * watch buzzes + points BOTH hands in the turn direction (left/right/straight/U-turn), sourced from
 * OsmAnd / OsmAnd+ via its AIDL navigation-updates API (no Google, no API key).
 */
@Composable
private fun NavCueCard(
    state: SettingsUiState,
    onSetNavCue: (Boolean, Int, Int) -> Boolean,
    onOpenDiag: () -> Unit,
    onNote: (String) -> Unit,
) {
    SettingCard("Navigation turn cues") {
        Text(
            "While OsmAnd (or OsmAnd+) is navigating, the watch buzzes and points BOTH hands in " +
                "the turn direction so you know when/where to turn without looking at the phone. " +
                "Requires OsmAnd installed and navigating.",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (state.navCueEnabled) "Enabled" else "Disabled")
            Switch(
                checked = state.navCueEnabled,
                onCheckedChange = {
                    onSetNavCue(it, state.navCueSoonMeters, state.navCueNowMeters)
                    onNote(navCueNote(it))
                },
            )
        }
        Text(
            "Turn soon: ${state.navCueSoonMeters} m   ·   Turn now: ${state.navCueNowMeters} m",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeNavCueSoonMeters(state.navCueSoonMeters - 10)
                    onSetNavCue(state.navCueEnabled, next, state.navCueNowMeters)
                },
            ) { Text("Soon − 10m") }
            OutlinedButton(
                onClick = {
                    val next = SettingsVocabulary.normalizeNavCueSoonMeters(state.navCueSoonMeters + 10)
                    onSetNavCue(state.navCueEnabled, next, state.navCueNowMeters)
                },
            ) { Text("Soon + 10m") }
        }
        OutlinedButton(onClick = onOpenDiag, modifier = Modifier.fillMaxWidth()) {
            Text("Live diagnostics + test cue")
        }
    }
}

private fun navCueNote(enabled: Boolean): String =
    if (enabled) "Navigation turn cues enabled (the watch points its hands at each turn)."
    else "Navigation turn cues disabled."

private fun applyNote(name: String, wired: Boolean): String =
    if (wired) "$name applied to the watch."
    else "$name saved. Applying it to the watch is on-device-pending (WP14)."

private fun buzzNote(kind: String, wired: Boolean): String =
    if (wired) "Buzzing the watch ($kind)…"
    else "No active watch to vibrate."
