package qhybrid.android.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * WP-SYNCSTATUS — the leave-with-pending prompt's decision + shared state.
 *
 * The model (see WP-SYNCSTATUS): a screen's rows are ALWAYS persisted to Room immediately, so
 * "leaving without saving" never loses data — it just means the WATCH is out of sync with the DB.
 * Hence the leave UX is a **prompt** ("Save to watch? / Leave / Cancel"), NOT a forced auto-save and
 * NOT a background-save (sync-on-connect already re-pushes a pending targeted save when the watch
 * reconnects, so backgrounding is safe).
 *
 * approach (a): a SINGLE guard lifted into `MainActivity` gates BOTH the bottom-nav tab switches and
 * the system back press. Each editable screen publishes its current pending state (count + a
 * "save to watch" action) into the guard while it is on screen; the host consults [shouldPrompt]
 * before navigating away and, when true, shows the dialog instead of leaving.
 */
object LeaveGuardLogic {
    /** Pure: prompt before leaving iff the current screen has unsaved-to-watch changes. */
    fun shouldPrompt(pendingCount: Int): Boolean = pendingCount > 0
}

/**
 * A tiny Compose state holder the active editable screen publishes into and the host consults. Kept
 * deliberately small: the screen sets [pendingCount] + the [save] action when it has pending
 * changes, and clears them (count 0) otherwise; the host reads [shouldPrompt] / triggers [save].
 *
 * The decision logic is the pure [LeaveGuardLogic]; this is only the wiring so the host has one
 * place to check across all three screens.
 */
class LeaveGuardState {
    /** The currently-on-screen editable section's count of changes not yet on the watch. */
    var pendingCount by mutableStateOf(0)
        private set

    /** Pushes the active screen's "Save to watch" action (the manual save the prompt invokes). */
    var save: (() -> Unit)? = null
        private set

    /** True iff leaving the current screen should prompt to save. */
    val shouldPrompt: Boolean get() = LeaveGuardLogic.shouldPrompt(pendingCount)

    /** A screen publishes its current pending state (call when it goes on-screen / on change). */
    fun publish(pendingCount: Int, save: () -> Unit) {
        this.pendingCount = pendingCount
        this.save = save
    }

    /** A screen clears its pending state (call when it leaves the composition). */
    fun clear() {
        pendingCount = 0
        save = null
    }
}
