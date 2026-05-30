package qhybrid.android.sync

/**
 * WP-PULLSYNC — the **pure** decision for what (if anything) a successful connect should sync.
 *
 * Connecting NO LONGER auto-pushes the full config. The watch keeps its config across disconnects,
 * so re-pushing everything on every reconnect was redundant AND flooded the single BLE control
 * channel. Sync is now user-initiated (per-screen Save-to-watch, or the Settings "Sync all"
 * button). The only automatic case left is **provisioning a brand-new watch** the first time it
 * connects.
 *
 * Kept as a pure function (no Android, no BLE) so the connect-time decision is unit-testable; the
 * actual upload runs behind the WP3 service's ble-worker.
 */
object ConnectSyncDecider {

    /** What a successful connect should do, sync-wise. */
    sealed interface Decision {
        /** Do not sync (a known watch reconnecting — sync is user-initiated). */
        data object None : Decision

        /**
         * Run a sync with these [sections]. Either a user-requested Save-to-watch / "Sync all"
         * that was pending while the link was down, or a one-time full provisioning sync for a
         * brand-new watch.
         */
        data class Sync(val sections: Set<SyncSection>, val reason: String) : Decision
    }

    /**
     * Decide the on-connect sync.
     *
     * @param hadPendingSync    a user explicitly requested a sync while the link was down (a
     *                          Save-to-watch or "Sync all"); honour it now that we're connected.
     * @param requestedSections the sections that pending sync targeted (null = full reconcile).
     * @param isNewWatch        the connecting watch has no Room row yet (a brand-new association).
     */
    fun decide(
        hadPendingSync: Boolean,
        requestedSections: Set<SyncSection>?,
        isNewWatch: Boolean,
    ): Decision = when {
        hadPendingSync ->
            Decision.Sync(requestedSections ?: SyncSection.ALL, "user-requested sync on connect")

        isNewWatch ->
            Decision.Sync(SyncSection.ALL, "new watch — one-time provisioning full sync")

        else -> Decision.None
    }
}
