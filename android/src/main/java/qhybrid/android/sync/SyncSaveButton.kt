package qhybrid.android.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * WP-PROGRESS (sub-part 3) — the shared "Save to watch" button + sync-progress note used by every
 * screen (Buttons / Alarms / Notifications / Settings). It observes the screen's [SyncProgressUi]
 * (mapped from the process-wide [SyncState]) so the live sync state is VISIBLE:
 *
 * - while SYNCING: shows a [CircularProgressIndicator] inside the button and **disables** it;
 * - after a pass: shows a transient success/warning/error note honestly (a partial-failure pass
 *   reads as a WARNING, not a blanket success — see [SyncProgressUi.from]).
 *
 * The button is also disabled when there is no active watch ([hasActiveWatch] = false). The actual
 * `CircularProgressIndicator` rendering is on-device-pending; the state mapping it consumes is
 * unit-tested ([SyncProgressUiTest]) and each ViewModel's `syncProgress` flow is unit-tested.
 */
@Composable
fun SyncSaveButton(
    progress: SyncProgressUi,
    hasActiveWatch: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Save to watch",
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = onSave,
            enabled = progress.saveEnabled(hasActiveWatch),
        ) {
            if (progress.syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(label)
        }
        progress.note?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = noteColor(progress.tone),
            )
        }
    }
}

/**
 * WP-PROGRESS (sub-part 3) — an inline sync-progress row for screens WITHOUT a single "Save to
 * watch" button (the Settings screen applies each control independently, each poking a sync). It
 * shows a small [CircularProgressIndicator] + "Saving to watch…" while SYNCING, and the transient
 * success/warning/error note afterwards. Renders nothing when IDLE. Visual rendering is
 * on-device-pending; the [SyncProgressUi] mapping it consumes is unit-tested.
 */
@Composable
fun SyncStatusRow(
    progress: SyncProgressUi,
    modifier: Modifier = Modifier,
) {
    val note = progress.note ?: return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (progress.syncing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(note, style = MaterialTheme.typography.labelSmall, color = noteColor(progress.tone))
    }
}

/**
 * WP-SYNCFIX — a **blocking** "Saving to watch…" modal shown while a Save is in flight. Rendered
 * by each config screen; it appears the instant the user taps Save (SyncState SYNCING, published
 * synchronously by [ServiceSaveToWatch]) and stays until the service resolves the pass to
 * SUCCESS/ERROR. Non-dismissible (no outside-tap / back) so the user waits for the watch write
 * rather than racing further edits against an in-flight sync — the chosen UX for now.
 *
 * Renders nothing unless [SyncProgressUi.syncing]. Visual rendering is on-device-pending; the
 * syncing flag it keys on is unit-tested ([SyncProgressUiTest]).
 */
@Composable
fun SyncSavingDialog(progress: SyncProgressUi) {
    if (!progress.syncing) return
    AlertDialog(
        onDismissRequest = { /* blocking: ignore outside-tap / back while saving */ },
        confirmButton = {},
        title = { Text("Saving to watch…") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    "Sending your changes to the watch. This can take a few seconds while it " +
                        "connects.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun noteColor(tone: SyncProgressUi.Tone): Color = when (tone) {
    SyncProgressUi.Tone.ERROR -> MaterialTheme.colorScheme.error
    SyncProgressUi.Tone.WARNING -> MaterialTheme.colorScheme.error
    SyncProgressUi.Tone.SUCCESS -> MaterialTheme.colorScheme.primary
    SyncProgressUi.Tone.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
}
