package qhybrid.android.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import qhybrid.android.sync.SectionSyncStatus

/**
 * WP-SYNCSTATUS — proves the Room layer stamps `updatedAt` on EVERY write path (single-row +
 * bulk replace/seed/transfer) and that the per-watch `…SyncedAt` write + the pure
 * [SectionSyncStatus] helper agree on "is this row on the watch?", including the seed-then-sync case.
 */
@RunWith(RobolectricTestRunner::class)
class SyncStatusStampTest : DbTestBase() {

    private val mac = "AA:00:00:00:00:01"

    /** A repository over the same in-memory DB but driven by a controllable clock. */
    private fun repoAt(clock: () -> Long) = WatchRepository(db, now = clock)

    private fun seedWatch() = runBlocking { watchDao.upsert(watch(mac, active = true)) }

    // ---- updatedAt is stamped on the single-row upsert paths -----------------

    @Test
    fun upsertAlarm_stampsUpdatedAt() {
        seedWatch()
        val r = repoAt { 1_000L }
        runBlocking {
            r.upsertAlarm(alarm(mac, slot = 0)) // fixture leaves updatedAt = 0
            assertEquals(1_000L, alarmDao.getForWatch(mac).single().updatedAt)
        }
    }

    @Test
    fun upsertRule_stampsUpdatedAt() {
        seedWatch()
        val r = repoAt { 2_000L }
        runBlocking {
            r.upsertRule(rule(mac, "com.whatsapp"))
            assertEquals(2_000L, ruleDao.getForWatch(mac).single().updatedAt)
        }
    }

    @Test
    fun upsertButton_stampsUpdatedAt() {
        seedWatch()
        val r = repoAt { 3_000L }
        runBlocking {
            r.upsertButton(button(mac, buttonId = 0x10))
            assertEquals(3_000L, buttonDao.getForWatch(mac).single().updatedAt)
        }
    }

    // ---- updatedAt is stamped on the bulk replace / transfer paths -----------

    @Test
    fun replaceDefaultsSections_stampsUpdatedAtOnSeededRows() {
        seedWatch()
        val r = repoAt { 4_000L }
        runBlocking {
            r.replaceDefaultsSections(
                mac,
                alarms = listOf(alarm(mac, slot = 1)),
                rules = listOf(rule(mac, "com.x")),
                buttons = listOf(button(mac, buttonId = 0x20)),
            )
            assertEquals(4_000L, alarmDao.getForWatch(mac).single().updatedAt)
            assertEquals(4_000L, ruleDao.getForWatch(mac).single().updatedAt)
            assertEquals(4_000L, buttonDao.getForWatch(mac).single().updatedAt)
        }
    }

    @Test
    fun transferSettings_reStampsUpdatedAtOnClonedRows() {
        val toMac = "BB:00:00:00:00:02"
        runBlocking {
            watchDao.upsert(watch(mac, active = true))
            watchDao.upsert(watch(toMac))
        }
        // Source rows stamped at 100; the clone at 5_000.
        runBlocking { repoAt { 100L }.upsertAlarm(alarm(mac, slot = 0)) }
        runBlocking { repoAt { 5_000L }.transferSettings(mac, toMac) }
        runBlocking {
            assertEquals(5_000L, alarmDao.getForWatch(toMac).single().updatedAt)
        }
    }

    // ---- the seed-then-sync ORDERING CAVEAT ----------------------------------

    @Test
    fun seedThenSync_seededRowsReadAsOnWatchAfterTheSectionIsStamped() {
        seedWatch()
        // 1) a provision/seed writes the rows at T=10_000.
        val r = repoAt { 10_000L }
        runBlocking {
            r.upsertAlarm(alarm(mac, slot = 0))
            // BEFORE the sync stamps the section, the row is pending (section never synced).
            val before = watchDao.getByMac(mac)!!
            assertEquals(0L, before.alarmsSyncedAt)
            assertTrue(
                "row pending before its section is synced",
                !SectionSyncStatus.isOnWatch(
                    alarmDao.getForWatch(mac).single().updatedAt, before.alarmsSyncedAt,
                ),
            )
            // 2) the SAME connect's sync completes and stamps the section AFTER the row write (>= T).
            r.setAlarmsSyncedAt(mac, 10_050L)
            val after = watchDao.getByMac(mac)!!
            assertTrue(
                "seeded row is on-watch once its section is synced",
                SectionSyncStatus.isOnWatch(
                    alarmDao.getForWatch(mac).single().updatedAt, after.alarmsSyncedAt,
                ),
            )
        }
    }

    @Test
    fun editAfterSync_flipsRowBackToPending() {
        seedWatch()
        runBlocking {
            repoAt { 1_000L }.upsertAlarm(alarm(mac, slot = 0))
            repoAt { 1_000L }.setAlarmsSyncedAt(mac, 1_000L) // on-watch
            // user edits the alarm AFTER the sync → pending again until the next push.
            repoAt { 2_000L }.upsertAlarm(alarm(mac, slot = 0).copy(hour = 9))
            val syncedAt = watchDao.getByMac(mac)!!.alarmsSyncedAt
            val rowUpdated = alarmDao.getForWatch(mac).single().updatedAt
            assertEquals(2_000L, rowUpdated)
            assertEquals(1_000L, syncedAt)
            assertTrue(!SectionSyncStatus.isOnWatch(rowUpdated, syncedAt))
        }
    }
}
