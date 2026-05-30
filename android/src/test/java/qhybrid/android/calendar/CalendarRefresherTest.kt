package qhybrid.android.calendar

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.db.AppDatabase
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository
import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper.CalendarEvent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * WP13 (Step 2) — [CalendarRefresher] read→map→replace glue, with a [FakeCalendarSource] + an
 * in-memory [WatchRepository]. Asserts Room ends with exactly the mapped slots 16–31, that 0–15 are
 * never touched, and that the changed/no-op result drives the Step-3 silent-push decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarRefresherTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: WatchRepository
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = at(2025, 6, 2, 0, 0) // Mon

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = WatchRepository(db)
        db.watchDao().upsert(WatchEntity(macAddress = mac, name = "W", model = null, firmwareVersion = null, batteryLevel = 0, isActive = true))
    }

    @After
    fun tearDown() = db.close()

    private fun refresher(events: List<CalendarEvent>) =
        CalendarRefresher(repo, FakeCalendarSource(events), zone = { zone }, now = { now })

    @Test
    fun refresh_writesMappedSlots16Onward() = runTest {
        val events = listOf(
            CalendarEvent("A", at(2025, 6, 2, 9, 0)),
            CalendarEvent("B", at(2025, 6, 3, 10, 0)),
        )
        val result = refresher(events).refresh()

        assertTrue(result.changed)
        assertEquals(2, result.rowCount)
        val rows = repo.getAlarms(mac)
        assertEquals(listOf(16, 17), rows.map { it.slotId })
        assertTrue(rows.all { it.updatedAt > 0 }) // repo stamped it
    }

    @Test
    fun refresh_leavesStandardSlotsUntouched() = runTest {
        // user-owned standard alarm at slot 0
        db.watchAlarmDao().upsert(
            WatchAlarmEntity(mac, 0, 7, 0, true, 0x02, true, "wake")
        )
        refresher(listOf(CalendarEvent("A", at(2025, 6, 2, 9, 0)))).refresh()

        val rows = repo.getAlarms(mac)
        assertEquals(listOf(0, 16), rows.map { it.slotId })
        assertEquals("wake", rows.first { it.slotId == 0 }.label)
    }

    @Test
    fun refresh_noChange_reportsUnchanged_andNoRewrite() = runTest {
        val events = listOf(CalendarEvent("A", at(2025, 6, 2, 9, 0)))
        // first refresh writes the row
        assertTrue(refresher(events).refresh().changed)
        val firstUpdatedAt = repo.getAlarms(mac).single().updatedAt

        // second refresh with the SAME events → no change → no rewrite (updatedAt unchanged)
        val second = refresher(events).refresh()
        assertFalse(second.changed)
        assertEquals(firstUpdatedAt, repo.getAlarms(mac).single().updatedAt)
    }

    @Test
    fun refresh_emptyEvents_clearsCalendarSlots() = runTest {
        db.watchAlarmDao().upsert(WatchAlarmEntity(mac, 20, 9, 0, true, 0x20, false, "stale"))
        val result = refresher(emptyList()).refresh()
        assertTrue(result.changed) // had a stale row, now empty
        assertTrue(repo.getAlarms(mac).none { it.slotId in 16..31 })
    }

    @Test
    fun refresh_noActiveWatch_isNone() = runTest {
        db.watchDao().deleteByMac(mac)
        val result = refresher(listOf(CalendarEvent("A", at(2025, 6, 2, 9, 0)))).refresh()
        assertEquals(CalendarRefresher.Result.NONE, result)
    }

    /** Recording fake push so we can assert the silent-push fires exactly once on a CHANGED refresh. */
    private class RecordingPush : CalendarPush {
        var pushes = 0
            private set
        override fun pushAlarmsSilently(): Boolean { pushes++; return true }
    }

    @Test
    fun refreshAndMaybePush_changedRefresh_pushesOnce() = runTest {
        val push = RecordingPush()
        val result = refresher(listOf(CalendarEvent("A", at(2025, 6, 2, 9, 0)))).refreshAndMaybePush(push)
        assertTrue(result.changed)
        assertEquals(1, push.pushes)
    }

    @Test
    fun refreshAndMaybePush_noOpRefresh_doesNotPush() = runTest {
        val events = listOf(CalendarEvent("A", at(2025, 6, 2, 9, 0)))
        // seed the rows first so the second refresh is a no-op
        refresher(events).refresh()
        val push = RecordingPush()
        val result = refresher(events).refreshAndMaybePush(push)
        assertFalse(result.changed)
        assertEquals(0, push.pushes)
    }
}
