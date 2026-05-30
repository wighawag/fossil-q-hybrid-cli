package qhybrid.android.sync

/**
 * WP-SYNCSTATUS — the **pure, JVM-testable** core that answers "is this configured row actually on
 * the watch?".
 *
 * Every watch section uploads as ONE whole file (alarms = 32-slot file, notification filter =
 * concatenated entries, buttons = whole config). There is NO per-row upload. So "is row X on the
 * watch?" is really **"was this section's file re-pushed AFTER row X's last edit?"** — a comparison
 * of a per-row `updatedAt` against a per-watch per-section `…SyncedAt` timestamp.
 *
 * A per-row boolean would be WRONG: it can't represent "the file was re-pushed so everything is
 * current now", and it mishandles deletes (the deleted row is simply gone from the next file). The
 * timestamp model handles both: a re-push bumps `…SyncedAt` past every surviving row's `updatedAt`,
 * and deletes don't matter because only surviving rows are queried.
 *
 * Honesty: the BLE upload is on-device-pending, so the truest "it's on the watch" signal we have is
 * that the orchestrator reported the section in [SyncResult.performed] (it ran the put). There is no
 * watch read-back for these unreadable sections (see WP-DEFAULTS), so `performed` is as truthful as
 * we can be — and this helper is unit-tested independently of the BLE effect.
 */
object SectionSyncStatus {

    /**
     * True iff the row (last written to the DB at [rowUpdatedAt]) is on the watch: the section was
     * pushed ([sectionSyncedAt] > 0) AT OR AFTER the row's last edit ([rowUpdatedAt] <=
     * [sectionSyncedAt]).
     *
     * - [sectionSyncedAt] == 0 → the section has NEVER been synced to this watch → not on watch
     *   (every row is pending), regardless of [rowUpdatedAt].
     * - equal timestamps count as on-watch (`<=`): a provision/seed writes the rows then the SAME
     *   connect's sync stamps `…SyncedAt` AFTER the row writes, so a freshly seeded+synced row reads
     *   as on-watch (its `updatedAt` is <= the later sync stamp).
     */
    fun isOnWatch(rowUpdatedAt: Long, sectionSyncedAt: Long): Boolean =
        sectionSyncedAt > 0 && rowUpdatedAt <= sectionSyncedAt

    /**
     * How many of [rowUpdatedAts] are NOT yet on the watch (pending) given the section's
     * [sectionSyncedAt]. An empty list → 0. A never-synced section ([sectionSyncedAt] == 0) → every
     * row pending (== `rowUpdatedAts.size`).
     */
    fun pendingCount(rowUpdatedAts: List<Long>, sectionSyncedAt: Long): Int =
        rowUpdatedAts.count { !isOnWatch(it, sectionSyncedAt) }
}
