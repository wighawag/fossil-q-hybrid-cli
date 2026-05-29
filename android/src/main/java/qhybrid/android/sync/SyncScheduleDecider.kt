package qhybrid.android.sync

/**
 * WP14 — the **pure** decision logic for the periodic safety-sync job (kept separate from the
 * WorkManager glue so it is JVM/Robolectric-unit-testable with no Android scheduler).
 *
 * The periodic job is a *safety net* on top of the primary triggers (sync-on-connect in the WP3
 * service, and \u2014 later \u2014 the WP13 calendar ContentObserver push). It must respect the WP3
 * foreground/CDM model: **NO continuous scanning, NO forced connect.** Reconnection is the
 * CompanionDeviceManager's job (event-driven `onDeviceAppeared`); the periodic job only nudges a
 * `syncNow` when the link is ALREADY up, so a config change made while connected eventually
 * reconciles even if the explicit "Save to watch" was missed.
 *
 * This mirrors the WP16 provable-core discipline: the *what/when* lives in this pure object; the
 * *how* (WorkManager scheduling, calling the service) lives in [SyncSafetyWorker] /
 * [SyncScheduler].
 */
object SyncScheduleDecider {

    /** The minimum sensible WorkManager periodic interval (its hard floor is 15 minutes). */
    const val MIN_PERIOD_MINUTES = 15L

    /** The default periodic safety-sync interval. */
    const val DEFAULT_PERIOD_MINUTES = 360L // every 6 hours — a safety net, not a poll loop

    /**
     * The decision a periodic run makes.
     *
     * @property shouldSync trigger a [qhybrid.android.WatchConnectionService.syncNow].
     * @property reason human-readable explanation (for logging).
     */
    data class Decision(val shouldSync: Boolean, val reason: String) {
        companion object {
            fun sync(reason: String) = Decision(true, reason)
            fun skip(reason: String) = Decision(false, reason)
        }
    }

    /**
     * The observable inputs a periodic run sees.
     *
     * @property hasAssociatedWatch a CDM association / active watch exists (else nothing to sync).
     * @property linkUp the BLE link is currently INITIALIZED (we never force a connect / scan).
     */
    data class State(
        val hasAssociatedWatch: Boolean,
        val linkUp: Boolean,
    )

    /**
     * Decide whether a periodic run should trigger a sync.
     *
     * Rules (conservative, no scanning):
     * - no associated watch → skip (nothing to do);
     * - associated but link down → skip (CDM will reconnect + sync-on-connect handles it; we do
     *   NOT scan or force-connect from a background job);
     * - associated and link up → sync (reconcile any drift).
     */
    fun decide(state: State): Decision = when {
        !state.hasAssociatedWatch -> Decision.skip("no associated watch")
        !state.linkUp -> Decision.skip("link down (CDM reconnect + sync-on-connect owns this)")
        else -> Decision.sync("link up — reconcile config")
    }

    /** Clamp a requested period onto WorkManager's 15-minute floor. */
    fun normalizePeriodMinutes(minutes: Long): Long =
        if (minutes < MIN_PERIOD_MINUTES) MIN_PERIOD_MINUTES else minutes
}
