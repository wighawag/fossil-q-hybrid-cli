package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP13 (Step 1) — [WatchRepository.replaceCalendarAlarms] full-replaces only the calendar slots
 * (16..31), leaves the standard user alarm slots (0..15) untouched, re-keys/normalizes the mac, and
 * stamps `updatedAt` (the WP-SYNCSTATUS chokepoint).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplaceCalendarAlarmsTest : DbTestBase() {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun calAlarm(slot: Int, hour: Int = 9) = WatchAlarmEntity(
        watchMac = mac,
        slotId = slot,
        hour = hour,
        minute = 0,
        isEnabled = true,
        daysMask = 0x20,
        isRepeating = false,
        label = "Cal $slot",
    )

    @Test
    fun replace_setsCalendarSlots_leavesStandardUntouched() = runTest {
        watchDao.upsert(watch(mac))
        // pre-existing standard alarms 0,1 + stale calendar alarms 16,17
        alarmDao.upsert(alarm(mac, 0))
        alarmDao.upsert(alarm(mac, 1))
        alarmDao.upsert(calAlarm(16, hour = 6))
        alarmDao.upsert(calAlarm(17, hour = 6))

        // replace with exactly two NEW calendar rows (16, 18)
        repo.replaceCalendarAlarms(mac, listOf(calAlarm(16, hour = 11), calAlarm(18, hour = 12)))

        val all = alarmDao.getForWatch(mac)
        // standard 0,1 survive; calendar is EXACTLY the supplied {16,18} (old 17 gone)
        assertEquals(listOf(0, 1, 16, 18), all.map { it.slotId })
        assertEquals(11, all.first { it.slotId == 16 }.hour) // overwritten with new value
    }

    @Test
    fun replace_withEmpty_clearsAllCalendarSlots() = runTest {
        watchDao.upsert(watch(mac))
        alarmDao.upsert(alarm(mac, 3))
        alarmDao.upsert(calAlarm(20))

        repo.replaceCalendarAlarms(mac, emptyList())

        val all = alarmDao.getForWatch(mac)
        assertEquals(listOf(3), all.map { it.slotId }) // only the standard alarm remains
    }

    @Test
    fun replace_stampsUpdatedAt_andNormalizesMac() = runTest {
        watchDao.upsert(watch(mac.uppercase()))
        val fixedClock = WatchRepository(db, now = { 123_456L })

        fixedClock.replaceCalendarAlarms(mac.lowercase(), listOf(calAlarm(16)))

        val row = alarmDao.getForWatch(mac.uppercase()).single()
        assertEquals(mac.uppercase(), row.watchMac)
        assertEquals(123_456L, row.updatedAt)
    }

    @Test
    fun replace_onEmptyDb_isNoOp() = runTest {
        watchDao.upsert(watch(mac))
        repo.replaceCalendarAlarms(mac, emptyList())
        assertTrue(alarmDao.getForWatch(mac).isEmpty())
    }
}
