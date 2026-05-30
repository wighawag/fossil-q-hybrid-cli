package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-CLEARALARMS — [WatchRepository.clearStandardAlarms] deletes the standard user alarm slots
 * (0..15) but leaves the calendar-sync slots (16..31) intact, and normalizes the mac.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClearStandardAlarmsTest : DbTestBase() {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun clearsStandardSlots_keepsCalendarSlots() = runTest {
        watchDao.upsert(watch(mac))
        // Standard alarms 0..2 + a calendar alarm at slot 16.
        alarmDao.upsert(alarm(mac, 0))
        alarmDao.upsert(alarm(mac, 2))
        alarmDao.upsert(alarm(mac, 16)) // calendar slot (WP9/WP13)

        repo.clearStandardAlarms(mac)

        val remaining = alarmDao.getForWatch(mac)
        assertEquals(1, remaining.size)
        assertEquals(16, remaining.single().slotId) // calendar alarm preserved
    }

    @Test
    fun clearingWhenEmpty_isNoOp() = runTest {
        watchDao.upsert(watch(mac))
        repo.clearStandardAlarms(mac)
        assertTrue(alarmDao.getForWatch(mac).isEmpty())
    }

    @Test
    fun lowerCaseMac_normalizedToRowKey() = runTest {
        watchDao.upsert(watch(mac.uppercase()))
        alarmDao.upsert(alarm(mac.uppercase(), 0))
        repo.clearStandardAlarms(mac.lowercase())
        assertTrue(alarmDao.getForWatch(mac.uppercase()).isEmpty())
    }
}
