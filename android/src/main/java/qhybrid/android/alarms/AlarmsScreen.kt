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
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncSaveButton
import qhybrid.android.sync.SyncSavingDialog

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
fun AlarmsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: AlarmsViewModel = viewModel(factory = AlarmsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val progress by vm.syncProgress.collectAsStateWithLifecycle()

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
            when {
                !state.hasActiveWatch -> Text(
                    "No active watch — associate one to manage alarms.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Alarms (${state.alarms.size}/${AlarmsUiState.USER_SLOT_COUNT})",
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
                        "Slots 0–15 (user alarms). Calendar slots 16–31 are managed automatically.",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    if (state.alarms.isEmpty()) {
                        Text(
                            "No alarms yet — tap “Add alarm”.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.alarms, key = { it.slotId }) { alarm ->
                                AlarmRow(
                                    alarm = alarm,
                                    onClick = { editing = alarm },
                                    onToggleEnabled = { onToggleEnabled(alarm.slotId) },
                                    onDelete = { onDelete(alarm.slotId) },
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
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
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
            }
            Switch(checked = alarm.isEnabled, onCheckedChange = { onToggleEnabled() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete alarm")
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
