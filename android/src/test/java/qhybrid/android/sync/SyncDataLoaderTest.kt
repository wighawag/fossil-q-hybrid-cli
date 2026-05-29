package qhybrid.android.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.db.DbTestBase
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.settings.AppSettings
import qhybrid.android.settings.SettingsPrefs

/**
 * WP14 sub-part 1 — headless tests for [SyncDataLoader] over the WP4 in-memory Room
 * ([DbTestBase]) + a fake [SettingsPrefs]. Verifies the 16/16 alarm-slot split, the per-watch
 * + app settings assembly, and no-active-watch tolerance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncDataLoaderTest : DbTestBase() {

    private class FakePrefs(private val s: AppSettings) : SettingsPrefs {
        override fun get(): AppSettings = s
        override fun setNudge(enabled: Boolean, minutes: Int) {}
        override fun setSecondTimezoneOffset(minutes: Int) {}
        override fun setPreferredMusicApp(pkg: String?) {}
    }

    private fun userAlarm(mac: String, slot: Int) =
        WatchAlarmEntity(mac, slot, 7, slot, true, 0x3E, true, "A$slot")

    @Test
    fun noActiveWatchYieldsNoWatchInput() = runBlocking {
        watchDao.upsert(watch("AA:00:00:00:00:01", active = false))
        val input = SyncDataLoader(repo, FakePrefs(AppSettings())).load()
        assertFalse(input.hasWatch)
        assertNull(input.mac)
    }

    @Test
    fun splitsAlarmsIntoStandardAndCalendar() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        alarmDao.upsert(userAlarm(mac, 0))
        alarmDao.upsert(userAlarm(mac, 15))
        alarmDao.upsert(userAlarm(mac, 16)) // calendar
        alarmDao.upsert(userAlarm(mac, 31)) // calendar

        val input = SyncDataLoader(repo, FakePrefs(AppSettings())).load()
        assertTrue(input.hasWatch)
        assertEquals(listOf(0, 15), input.alarms.map { it.slotId })
        assertEquals(listOf(16, 31), input.calendarAlarms.map { it.slotId })
    }

    @Test
    fun assemblesRulesAndButtons() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        ruleDao.upsert(rule(mac, "com.whatsapp"))
        buttonDao.upsert(button(mac, 0x10))

        val input = SyncDataLoader(repo, FakePrefs(AppSettings())).load()
        assertEquals(1, input.rules.size)
        assertEquals(1, input.buttons.size)
    }

    @Test
    fun pullsVibrationFromWatchRow() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true).copy(vibrationStrength = 77))
        val input = SyncDataLoader(repo, FakePrefs(AppSettings())).load()
        assertEquals(77, input.settings.vibrationStrength)
    }

    @Test
    fun nudgeOnlyIncludedWhenEnabled() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))

        val off = SyncDataLoader(repo, FakePrefs(AppSettings(nudgeEnabled = false, nudgeMinutes = 30)))
            .load()
        assertNull(off.settings.nudgeMinutes)

        val on = SyncDataLoader(repo, FakePrefs(AppSettings(nudgeEnabled = true, nudgeMinutes = 45)))
            .load()
        assertEquals(45, on.settings.nudgeMinutes)
        assertTrue(on.settings.nudgeEnabled)
    }

    @Test
    fun secondTimezoneFromPrefs() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        val input = SyncDataLoader(repo, FakePrefs(AppSettings(secondTimezoneOffsetMinutes = -300)))
            .load()
        assertEquals(-300, input.settings.secondTimezoneOffsetMinutes)
    }
}
