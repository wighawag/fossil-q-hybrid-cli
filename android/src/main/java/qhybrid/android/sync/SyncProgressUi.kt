package qhybrid.android.sync

/**
 * WP-PROGRESS (sub-part 3) — the **pure, JVM-testable** mapping from the process-wide
 * [SyncState.SyncStatus] to the small display state each screen's "Save to watch" button renders:
 * whether to show the spinner + disable the button, and a short honest note.
 *
 * Keeping this a pure function (no Compose, no Android) means the state mapping the spinner relies
 * on is unit-tested; the actual `CircularProgressIndicator` rendering is on-device-pending.
 *
 * Honesty (see [SyncState]): a completed pass with per-section errors reports
 * [Tone.WARNING] (NOT a blanket success), and a pass that threw reports [Tone.ERROR]; a clean pass
 * reports [Tone.SUCCESS].
 */
data class SyncProgressUi(
    /** True while a sync is in flight — the Save button shows a spinner and is disabled. */
    val syncing: Boolean,
    /** A short transient note to show under the button, or null when there's nothing to say. */
    val note: String?,
    /** The note's tone (drives color/emphasis in the UI). */
    val tone: Tone,
) {
    enum class Tone { NONE, SUCCESS, WARNING, ERROR }

    /** The Save button is enabled only when not syncing AND there is an active watch. */
    fun saveEnabled(hasActiveWatch: Boolean): Boolean = hasActiveWatch && !syncing

    companion object {
        val IDLE = SyncProgressUi(syncing = false, note = null, tone = Tone.NONE)

        /**
         * Map a [SyncState.SyncStatus] into the Save-button display state.
         *
         * - SYNCING → spinner + "Saving to watch…" (in-flight, button disabled).
         * - SUCCESS with no section errors → "Saved to watch." (SUCCESS tone).
         * - SUCCESS with section errors → a partial-failure WARNING note listing the failed
         *   sections (honest — the BLE write partially failed).
         * - ERROR → the failure message (ERROR tone).
         * - IDLE (pristine / never synced) → no note.
         */
        fun from(status: SyncState.SyncStatus): SyncProgressUi = when (status.phase) {
            SyncState.SyncPhase.SYNCING ->
                SyncProgressUi(syncing = true, note = "Saving to watch…", tone = Tone.NONE)

            SyncState.SyncPhase.SUCCESS -> {
                val errs = status.lastResult?.errors.orEmpty()
                if (errs.isEmpty()) {
                    SyncProgressUi(syncing = false, note = "Saved to watch.", tone = Tone.SUCCESS)
                } else {
                    val sections = errs.joinToString(", ") { it.section.name.lowercase() }
                    SyncProgressUi(
                        syncing = false,
                        note = "Saved, but some sections failed: $sections.",
                        tone = Tone.WARNING,
                    )
                }
            }

            SyncState.SyncPhase.ERROR ->
                SyncProgressUi(
                    syncing = false,
                    note = "Sync failed: ${status.errorMessage ?: "unknown error"}.",
                    tone = Tone.ERROR,
                )

            SyncState.SyncPhase.IDLE -> IDLE
        }
    }
}
