package qhybrid.android.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-TRACKER — headless DAO/repository tests for the [WaypointEntity] table, mirroring the WP4
 * [DbTestBase] in-memory Room harness. Verifies insert → ordered reads (newest first / chronological
 * for GPX), count, delete, clear, the observe Flow, and that waypoints are NOT cascade-deleted with
 * a watch (a waypoint outlives its watch — no FK binding).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WaypointDaoTest : DbTestBase() {

    private val waypointDao get() = db.waypointDao()

    private fun wp(kind: String, capturedAt: Long, mac: String? = null) = WaypointEntity(
        watchMac = mac, kind = kind, lat = 1.0 + capturedAt, lon = 2.0 + capturedAt,
        accuracyM = 5f, capturedAt = capturedAt,
    )

    @Test
    fun insertAndReadNewestFirst() = runBlocking {
        repo.insertWaypoint(wp("MINOR", 100))
        repo.insertWaypoint(wp("MAJOR", 300))
        repo.insertWaypoint(wp("MINOR", 200))
        val all = repo.getWaypoints()
        assertEquals(3, all.size)
        assertEquals(listOf(300L, 200L, 100L), all.map { it.capturedAt })
        assertEquals(3, repo.waypointCount())
    }

    @Test
    fun chronologicalForGpx() = runBlocking {
        repo.insertWaypoint(wp("MAJOR", 300))
        repo.insertWaypoint(wp("MINOR", 100))
        val chrono = repo.getWaypointsChronological()
        assertEquals(listOf(100L, 300L), chrono.map { it.capturedAt })
    }

    @Test
    fun insertReturnsAutoIdAndPersistsFields() = runBlocking {
        val id = repo.insertWaypoint(wp("MAJOR", 500, mac = "AA:00:00:00:00:01"))
        assertTrue(id > 0)
        val row = repo.getWaypoints().first()
        assertEquals("MAJOR", row.kind)
        assertEquals("AA:00:00:00:00:01", row.watchMac)
        assertEquals(5f, row.accuracyM)
    }

    @Test
    fun deleteByIdAndClear() = runBlocking {
        val a = repo.insertWaypoint(wp("MINOR", 100))
        repo.insertWaypoint(wp("MINOR", 200))
        repo.deleteWaypoint(a)
        assertEquals(1, repo.waypointCount())
        repo.clearWaypoints()
        assertEquals(0, repo.waypointCount())
    }

    @Test
    fun observeEmitsCurrentRows() = runBlocking {
        repo.insertWaypoint(wp("MINOR", 100))
        val emitted = repo.observeWaypoints().first()
        assertEquals(1, emitted.size)
    }

    @Test
    fun waypointsAreNotCascadeDeletedWithTheirWatch() = runBlocking {
        // A waypoint references a watch only informationally (NOT a foreign key) so it survives the
        // watch's deletion — the user's logged track must outlive the paired watch.
        watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
        repo.insertWaypoint(wp("MINOR", 100, mac = "AA:00:00:00:00:01"))
        watchDao.deleteByMac("AA:00:00:00:00:01")
        assertNull(watchDao.getByMac("AA:00:00:00:00:01"))
        // The waypoint is still there.
        assertEquals(1, repo.waypointCount())
        assertEquals("AA:00:00:00:00:01", repo.getWaypoints().first().watchMac)
    }
}
