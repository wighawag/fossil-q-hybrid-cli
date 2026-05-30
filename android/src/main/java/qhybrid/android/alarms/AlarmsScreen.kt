@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package qhybrid.android.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.sync.ConnectionBanner
import qhybrid.android.sync.LeaveGuardState
import qhybrid.android.sync.PendingSyncBanner
import qhybrid.android.sync.PublishLeaveGuard
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncRowBadge
import qhybrid.android.sync.SyncSavingDialog
import qhybrid.android.sync.SyncStatusRow

/**
 * WP16b — the Alarms screen (user slots 0–15 only). State comes from [AlarmsViewModel]
 * (WP4 active watch + its alarm rows, filtered to 0–15); intents delegate to the VM, which
 * persists via [qhybrid.android.db.WatchRepository] (WP4) and pushes via the injectable
 * [AlarmSync] seam.
 *
 * **On-device verification pending:** the LazyColumn rendering, the time picker + day chips,
 * and the Save effect can only be confirmed on a device. The actual alarm-byte upload to the
 * watch is **WP14** ([AlarmSync] reports it as not-yet-wired and the UI flags it).
 */
@Composable
fun AlarmsScreen(modifier: Modifier = Modifier, leaveGuard: LeaveGuardState? = null) {
    val context = LocalContext.current
    val vm: AlarmsViewModel = viewModel(factory = AlarmsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val progress by vm.syncProgress.collectAsStateWithLifecycle()

    // WP-SYNCSTATUS (Step 3): publish pending state + Save action so the host can prompt on leave.
    PublishLeaveGuard(leaveGuard, state.pendingCount) { vm.saveToWatch() }

    AlarmsContent(
        progress = progress,
        state = state,
        onAdd = { hour, minute, days, repeating ->
            vm.addAlarm(hour = hour, minute = minute, daysMask = days, isRepeating = repeating)
        },
        onUpdate = vm::updateAlarm,
        onDelete = vm::deleteAlarm,
        onToggleEnabled = vm::toggleEnabled,
        onSave = { vm.saveToWatch() },
        modifier = modifier,
    )
}

/**
 * Stateless Alarms body — pure function of [AlarmsUiState] + intent lambdas, so it is
 * preview-/UI-testable with fake state and no VM/Room/BLE.
 */
@Composable
fun AlarmsContent(
    state: AlarmsUiState,
    onAdd: (hour: Int, minute: Int, daysMask: Int, repeating: Boolean) -> Unit,
    onUpdate: (WatchAlarmEntity) -> Unit,
    onDelete: (slotId: Int) -> Unit,
    onToggleEnabled: (slotId: Int) -> Unit,
    onSave: () -> Boolean,
    modifier: Modifier = Modifier,
    progress: SyncProgressUi = SyncProgressUi.IDLE,
) {
    // Editor dialog state: null = closed; a WatchAlarmEntity = editing (slot is fixed) or
    // a fresh template = adding (slotId == -1 marks "new").
    var editing by remember { mutableStateOf<WatchAlarmEntity?>(null) }

    // WP-SYNCFIX: blocking "Saving to watch…" modal while a save is in flight.
    SyncSavingDialog(progress)
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            if (state.hasActiveWatch && !state.isFull) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editing = WatchAlarmEntity(
                            watchMac = state.activeMac ?: "",
                            slotId = -1, // marker: new alarm; VM assigns the lowest free slot
                            hour = 7, minute = 0,
                            isEnabled = true,
                            // Default a NEW alarm to a one-off ("Once"): no days selected, not
                            // repeating. The user picks days (which flips it to repeating) or a
                            // shortcut if they want a recurring alarm.
                            daysMask = 0,
                            isRepeating = false,
                            label = null,
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add alarm") },
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
            // WP-SYNCFIX: honest link state — alarms stay editable offline; banner says when synced.
            ConnectionBanner()
            // WP-SYNCSTATUS: "N change(s) not on the watch" when any alarm is edited-since-push.
            PendingSyncBanner(state.pendingCount)
            when {
                !state.hasActiveWatch -> Text(
                    "No active watch — associate one to manage alarms.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    Text(
                        "Alarms (${state.alarms.size}/${AlarmsUiState.USER_SLOT_COUNT})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // WP-SYNCSTATUS (Step 4): alarm edits AUTO-SAVE to the watch (debounced), so
                    // there's no manual "Save to watch" button here — the blocking modal +
                    // SYNCING/result note appear automatically when a coalesced save fires.
                    SyncStatusRow(progress = progress)
                    Text(
                        "Changes save to the watch automatically. Slots 0–15 (user alarms); " +
                            "calendar slots 16–31 are managed automatically.",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.alarms.isEmpty()) {
                            item(key = "no-user-alarms") {
                                Text(
                                    "No alarms yet — tap “Add alarm”.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            items(state.alarms, key = { it.slotId }) { alarm ->
                                // A one-off whose single occurrence has already passed shows as
                                // auto-deactivated (switch off + dimmed) — the watch drops it after
                                // it fires; we mirror that in the UI without mutating the row.
                                val expired = remember(alarm.updatedAt, alarm.hour, alarm.minute, alarm.daysMask, alarm.isRepeating) {
                                    AlarmExpiry.hasPassed(alarm, System.currentTimeMillis())
                                }
                                AlarmRow(
                                    alarm = alarm,
                                    onWatch = state.isOnWatch(alarm.slotId),
                                    expired = expired,
                                    onClick = { editing = alarm },
                                    onToggleEnabled = { onToggleEnabled(alarm.slotId) },
                                    onDelete = { onDelete(alarm.slotId) },
                                )
                            }
                        }

                        // WP13 — the read-only calendar alarms (slots 16–31), mirrored from the
                        // user's system calendar. Shown only when there are any. NOT editable here.
                        if (state.calendarAlarms.isNotEmpty()) {
                            item(key = "calendar-header") {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.padding(top = 12.dp),
                                ) {
                                    HorizontalDivider()
                                    Text(
                                        "From your calendar (${state.calendarAlarms.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    Text(
                                        "Upcoming events (next 7 days) synced automatically as " +
                                            "one-off alarms. Read-only — edit them in your calendar app.",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            items(state.calendarAlarms, key = { it.slotId }) { alarm ->
                                CalendarAlarmRow(
                                    alarm = alarm,
                                    onWatch = state.isOnWatch(alarm.slotId),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { template ->
        AlarmEditorDialog(
            initial = template,
            isNew = template.slotId < 0,
            onDismiss = { editing = null },
            onConfirm = { result ->
                if (template.slotId < 0) {
                    onAdd(result.hour, result.minute, result.daysMask, result.isRepeating)
                } else {
                    onUpdate(result)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: WatchAlarmEntity,
    onWatch: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    expired: Boolean = false,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The clickable time/summary opens the editor.
                TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        formatTime(alarm.hour, alarm.minute),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text(
                    AlarmDays.summary(alarm.daysMask, alarm.isRepeating),
                    style = MaterialTheme.typography.bodySmall,
                )
                alarm.label?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
                if (expired) {
                    // A passed one-off: the watch has already fired and dropped it. Show as
                    // auto-deactivated so the UI doesn't imply it's still armed.
                    Text(
                        "Passed · auto-deactivated",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // WP-SYNCSTATUS: ✓ on-watch vs ⏳ not-synced for THIS alarm.
                SyncRowBadge(onWatch = onWatch)
            }
            // A passed one-off renders OFF regardless of its stored flag; re-enabling means
            // editing the alarm (which re-stamps its set-time / next occurrence).
            Switch(
                checked = alarm.isEnabled && !expired,
                enabled = !expired,
                onCheckedChange = { onToggleEnabled() },
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete alarm")
            }
        }
    }
}

/**
 * WP13 — a READ-ONLY calendar-alarm row (slots 16–31). No switch, no delete, no edit click: these
 * are mirrored from the user's system calendar and managed automatically. Shows the time, the day
 * summary, the event title (label), and the on-watch ✓ badge (shared `alarmsSyncedAt` marker).
 */
@Composable
private fun CalendarAlarmRow(
    alarm: WatchAlarmEntity,
    onWatch: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    formatTime(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    AlarmDays.summary(alarm.daysMask, alarm.isRepeating),
                    style = MaterialTheme.typography.bodySmall,
                )
                alarm.label?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
                // WP-SYNCSTATUS: ✓ on-watch vs ⏳ not-synced (shared alarms section marker).
                SyncRowBadge(onWatch = onWatch)
            }
        }
    }
}

@Composable
private fun AlarmEditorDialog(
    initial: WatchAlarmEntity,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (WatchAlarmEntity) -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    var daysMask by remember { mutableStateOf(initial.daysMask and AlarmDays.EVERYDAY) }
    var label by remember { mutableStateOf(initial.label ?: "") }
    val dayCount = AlarmDays.dayCount(daysMask)
    // The repeating SWITCH only makes sense for exactly ONE selected day (0 days = always one-off;
    // 2+ days = always weekly). For the single-day case the toggle always (re-)defaults to ON every
    // time you ENTER that regime: keying the remembered state on `dayCount` re-initializes it to
    // true whenever the count returns to 1, so leaving and coming back to one day snaps back to
    // "Repeats weekly" (the user can still turn it off while staying at one day).
    var oneDayRepeating by remember(dayCount) { mutableStateOf(true) }
    // Derived effective repeating: 0 days → one-off; 1 day → the (re-defaulting) toggle; 2+ → weekly.
    val effectiveRepeating = when {
        dayCount == 0 -> false
        dayCount == 1 -> oneDayRepeating
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    initial.copy(
                        hour = timeState.hour,
                        minute = timeState.minute,
                        daysMask = daysMask,
                        isRepeating = effectiveRepeating,
                        label = label.ifBlank { null },
                    )
                )
            }) { Text(if (isNew) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (isNew) "New alarm" else "Edit alarm") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimePicker(state = timeState)
                HorizontalDivider()

                // Day-of-week chips (Sun-first, matching the wire bit order).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlarmDays.ALL_BITS.forEachIndexed { i, bit ->
                        FilterChip(
                            selected = (daysMask and bit) != 0,
                            onClick = { daysMask = AlarmDays.toggle(daysMask, bit) },
                            label = { Text(AlarmDays.SHORT_LABELS[i]) },
                        )
                    }
                }

                // Shortcuts (each selects 2+ days, which is inherently a weekly repeat).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { daysMask = AlarmDays.WEEKDAY }) { Text("Weekdays") }
                    OutlinedButton(onClick = { daysMask = AlarmDays.WEEKEND }) { Text("Weekend") }
                    OutlinedButton(onClick = { daysMask = AlarmDays.EVERYDAY }) { Text("Every day") }
                }

                // One-shot vs repeating — only meaningful for EXACTLY ONE day. With no days it's
                // always a one-off; with 2+ days it's always weekly, so we hide the toggle and show
                // the implied state instead (the saved isRepeating is derived the same way).
                when (dayCount) {
                    0 -> Text(
                        "One-shot (fires once at the next occurrence of this time).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = oneDayRepeating,
                            onCheckedChange = { oneDayRepeating = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (oneDayRepeating) "Repeats weekly" else "One-shot (fires once)")
                    }
                    else -> Text("Repeats weekly", style = MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

private fun formatTime(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour, minute)
