package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP4 — active-watch selection always yields exactly one active watch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActiveWatchTest : DbTestBase() {

    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"
    private val c = "CC:CC:CC:CC:CC:CC"

    @Test
    fun setActive_selectsExactlyOne() = runTest {
        watchDao.upsert(watch(a))
        watchDao.upsert(watch(b))
        watchDao.upsert(watch(c))

        repo.setActiveWatch(b)
        assertEquals(b, repo.getActiveWatch()?.macAddress)
        assertEquals(1, watchDao.getAll().count { it.isActive })

        // Switching active clears the previous one — still exactly one active.
        repo.setActiveWatch(c)
        assertEquals(c, repo.getActiveWatch()?.macAddress)
        assertEquals(1, watchDao.getAll().count { it.isActive })
    }

    @Test
    fun registerWatch_isIdempotentAndActivates() = runTest {
        repo.registerWatch(a, "First")
        repo.registerWatch(b, "Second")
        // Re-registering must not duplicate or wipe details, just re-activate.
        repo.registerWatch(a, "ignored-rename")

        assertEquals(2, watchDao.getAll().size)
        assertEquals(a, repo.getActiveWatch()?.macAddress)
        assertEquals(1, watchDao.getAll().count { it.isActive })
        assertEquals("First", watchDao.getByMac(a)?.name)
    }
}
