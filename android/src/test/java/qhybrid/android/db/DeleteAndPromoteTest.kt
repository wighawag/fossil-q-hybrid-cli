package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Removing the ACTIVE watch while another watch is still registered must PROMOTE a remaining watch
 * to active (so the Settings screen's per-watch controls — incl. "Remove watch" — stay enabled and
 * can administer the now-active watch right away), via [WatchRepository.deleteWatchAndPromote].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeleteAndPromoteTest : DbTestBase() {

    // getAll() is ordered by name; "Alpha" < "Bravo" so Alpha is the promotion target.
    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"

    @Test
    fun removingActive_promotesAnotherWatch() = runTest {
        watchDao.upsert(watch(a, name = "Alpha"))
        watchDao.upsert(watch(b, name = "Bravo"))
        repo.setActiveWatch(b)
        assertEquals(b, repo.getActiveWatch()?.macAddress)

        repo.deleteWatchAndPromote(b)

        // b is gone; a is promoted to the single active watch.
        assertNull("removed watch row is gone", watchDao.getByMac(b))
        assertEquals("a remaining watch is promoted to active", a, repo.getActiveWatch()?.macAddress)
        assertEquals(1, watchDao.getAll().count { it.isActive })
    }

    @Test
    fun removingActive_lowerCaseMac_stillPromotes() = runTest {
        watchDao.upsert(watch(a, name = "Alpha"))
        watchDao.upsert(watch(b, name = "Bravo"))
        repo.setActiveWatch(b)

        // The UI/seam may hand us a lower-case MAC — it must still match + promote.
        repo.deleteWatchAndPromote(b.lowercase())

        assertNull(watchDao.getByMac(b))
        assertEquals(a, repo.getActiveWatch()?.macAddress)
    }

    @Test
    fun removingLastWatch_leavesNoActive() = runTest {
        watchDao.upsert(watch(a, name = "Alpha", active = true))

        repo.deleteWatchAndPromote(a)

        assertTrue("no watches remain", watchDao.getAll().isEmpty())
        assertNull("no active watch when none remain", repo.getActiveWatch())
    }

    @Test
    fun removingNonActive_keepsExistingActive() = runTest {
        watchDao.upsert(watch(a, name = "Alpha"))
        watchDao.upsert(watch(b, name = "Bravo"))
        repo.setActiveWatch(a)

        // Remove the NON-active watch; the active selection must NOT change.
        repo.deleteWatchAndPromote(b)

        assertNull(watchDao.getByMac(b))
        assertEquals("active watch unchanged when a non-active watch is removed", a, repo.getActiveWatch()?.macAddress)
        assertEquals(1, watchDao.getAll().count { it.isActive })
    }

    @Test
    fun removingActive_cascadesChildrenOfRemovedWatchOnly() = runTest {
        watchDao.upsert(watch(a, name = "Alpha"))
        watchDao.upsert(watch(b, name = "Bravo"))
        repo.setActiveWatch(b)
        alarmDao.upsertAll(listOf(alarm(b, 0)))
        alarmDao.upsertAll(listOf(alarm(a, 0)))

        repo.deleteWatchAndPromote(b)

        assertTrue("removed watch's children cascade away", alarmDao.getForWatch(b).isEmpty())
        assertFalse("the promoted watch keeps its children", alarmDao.getForWatch(a).isEmpty())
    }
}
