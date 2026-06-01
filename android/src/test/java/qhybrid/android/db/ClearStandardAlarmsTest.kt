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
 * (0..14) but leaves the reserved TIMER slot (15) and the calendar-sync slots (16..31) intact, and
 * normalizes the mac.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClearStandardAlarmsTest : DbTestBase() {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun clearsUserSlots_keepsTimerAndCalendarSlots() = runTest {
        watchDao.upsert(watch(mac))
        // User alarms 0..2 + the TIMER slot (15) + a calendar alarm at slot 16.
        alarmDao.upsert(alarm(mac, 0))
        alarmDao.upsert(alarm(mac, 2))
        alarmDao.upsert(alarm(mac, 14)) // highest user slot
        alarmDao.upsert(alarm(mac, 15)) // TIMER slot
        alarmDao.upsert(alarm(mac, 16)) // calendar slot (WP9/WP13)

        repo.clearStandardAlarms(mac)

        val remaining = alarmDao.getForWatch(mac).map { it.slotId }.sorted()
        // Timer (15) + calendar (16) preserved; user slots 0,2,14 deleted.
        assertEquals(listOf(15, 16), remaining)
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
