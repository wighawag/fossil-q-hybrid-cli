package qhybrid.android.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * WP-SYNCSTATUS — shared, screen-agnostic UI for the synced-marker model so the three editable
 * per-watch sections (Notifications / Alarms / Buttons) render "is this on the watch?" identically.
 *
 * The truth is computed by the pure [SectionSyncStatus] helper in each ViewModel's UiState; these
 * composables only render it. Visual rendering is on-device-pending; the derivation is unit-tested.
 */

/**
 * A small per-row marker: ✓ "On watch" when [onWatch], else ⏳ "Not synced" (the row is in the DB
 * but the section's file hasn't been re-pushed since this row's last edit).
 */
@Composable
fun SyncRowBadge(onWatch: Boolean, modifier: Modifier = Modifier) {
    if (onWatch) {
        Text(
            "✓ On watch",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    } else {
        Text(
            "⏳ Not synced",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/**
 * A header banner shown when [pendingCount] > 0: "N change(s) not on the watch — Save to watch."
 * Renders nothing when there is nothing pending. Purely informational (the actual Save button lives
 * elsewhere on the screen / the leave-prompt offers to save on exit).
 */
@Composable
fun PendingSyncBanner(pendingCount: Int, modifier: Modifier = Modifier) {
    if (pendingCount <= 0) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                pendingMessage(pendingCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** The banner text — pluralised. Pulled out as a pure function so it's unit-testable. */
fun pendingMessage(pendingCount: Int): String {
    val noun = if (pendingCount == 1) "change" else "changes"
    return "$pendingCount $noun not on the watch — Save to watch."
}

/**
 * WP-SYNCSTATUS (Step 3) — an editable screen publishes its current pending state + "Save to watch"
 * action into the host's shared [LeaveGuardState] while it is composed, and clears it on dispose.
 * The host (MainActivity) consults the guard to decide whether to prompt before navigating away
 * (tab switch OR system back). No-op when [guard] is null (previews / UI tests).
 */
@Composable
fun PublishLeaveGuard(guard: LeaveGuardState?, pendingCount: Int, onSave: () -> Unit) {
    if (guard == null) return
    // Keep the latest save action without re-running the effect on every recomposition.
    val latestSave by rememberUpdatedState(onSave)
    DisposableEffect(guard, pendingCount) {
        guard.publish(pendingCount) { latestSave() }
        onDispose { guard.clear() }
    }
}
