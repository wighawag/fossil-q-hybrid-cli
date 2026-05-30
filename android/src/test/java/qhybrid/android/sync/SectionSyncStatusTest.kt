package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-SYNCSTATUS — the pure synced-marker core. No Android, no Room, no BLE; the truth-table for
 * "is this row on the watch?" lives here and is exhaustively tested.
 */
class SectionSyncStatusTest {

    // ---- isOnWatch -----------------------------------------------------------

    @Test
    fun neverSynced_everyRowIsPending() {
        // sectionSyncedAt == 0 → the section has never been pushed → nothing is on the watch,
        // regardless of when the row was edited (incl. a row edited at "0").
        assertFalse(SectionSyncStatus.isOnWatch(rowUpdatedAt = 0, sectionSyncedAt = 0))
        assertFalse(SectionSyncStatus.isOnWatch(rowUpdatedAt = 100, sectionSyncedAt = 0))
    }

    @Test
    fun editedBeforeSync_isOnWatch() {
        // The section was pushed AFTER the row's last edit → the file the watch holds includes it.
        assertTrue(SectionSyncStatus.isOnWatch(rowUpdatedAt = 100, sectionSyncedAt = 200))
    }

    @Test
    fun editedAfterSync_isPending() {
        // The row was edited AFTER the last push → the watch's file is stale for this row.
        assertFalse(SectionSyncStatus.isOnWatch(rowUpdatedAt = 300, sectionSyncedAt = 200))
    }

    @Test
    fun equalTimestamps_countAsOnWatch_seedThenSyncCase() {
        // A provision/seed writes the row (updatedAt = T) then the SAME connect's sync stamps
        // sectionSyncedAt at a time >= T. With `<=`, an exactly-equal stamp is on-watch — and the
        // real seed-then-sync case (sync stamp captured AFTER the row write) is strictly >=, so it
        // is always on-watch.
        assertTrue(SectionSyncStatus.isOnWatch(rowUpdatedAt = 200, sectionSyncedAt = 200))
        // The realistic case: row written first, section stamped a moment later.
        assertTrue(SectionSyncStatus.isOnWatch(rowUpdatedAt = 200, sectionSyncedAt = 201))
    }

    // ---- pendingCount --------------------------------------------------------

    @Test
    fun pendingCount_emptyRows_isZero() {
        assertEquals(0, SectionSyncStatus.pendingCount(emptyList(), sectionSyncedAt = 0))
        assertEquals(0, SectionSyncStatus.pendingCount(emptyList(), sectionSyncedAt = 500))
    }

    @Test
    fun pendingCount_neverSynced_allRowsPending() {
        assertEquals(3, SectionSyncStatus.pendingCount(listOf(10, 20, 30), sectionSyncedAt = 0))
    }

    @Test
    fun pendingCount_mixedRows_countsOnlyThoseEditedAfterSync() {
        // synced at 200: rows at 100/200 are on-watch; rows at 250/300 are pending.
        assertEquals(2, SectionSyncStatus.pendingCount(listOf(100, 200, 250, 300), sectionSyncedAt = 200))
    }

    @Test
    fun pendingCount_allOnWatch_isZero() {
        assertEquals(0, SectionSyncStatus.pendingCount(listOf(100, 150, 200), sectionSyncedAt = 200))
    }

    // ---- banner text (pure, pluralised) --------------------------------------

    @Test
    fun pendingMessage_pluralises() {
        assertEquals("1 change not on the watch — Save to watch.", pendingMessage(1))
        assertEquals("3 changes not on the watch — Save to watch.", pendingMessage(3))
    }
}
