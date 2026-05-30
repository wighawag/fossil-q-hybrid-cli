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
import qhybrid.android.alarms.AlarmDays
import qhybrid.android.db.DbTestBase
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.settings.AppSettings
import qhybrid.android.settings.SettingsPrefs
import java.time.LocalDateTime
import java.time.ZoneId

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
        override fun setCalendarAlarmOffset(minutes: Int) {}
    }

    private fun userAlarm(mac: String, slot: Int) =
        WatchAlarmEntity(mac, slot, 7, slot, true, 0x3E, true, "A$slot")

    private val utc: ZoneId = ZoneId.of("UTC")
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(utc).toInstant().toEpochMilli()

    /** A one-off (or repeating) alarm with an explicit set-time for the expiry derivation. */
    private fun oneOff(
        mac: String, slot: Int, hour: Int, minute: Int,
        days: Int = 0, repeating: Boolean = false, setAt: Long,
    ) = WatchAlarmEntity(mac, slot, hour, minute, true, days, repeating, "A$slot", setAt)

    private fun loaderAt(nowMillis: Long) =
        SyncDataLoader(repo, FakePrefs(AppSettings()), now = { nowMillis }, zone = { utc })

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

    // ---- passed one-off suppression (don't re-arm a fired one-off) -----------

    @Test
    fun passedPlainOneOff_isDisabledInTheInput_soItIsNotReUploaded() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        // Set Mon 08:00, alarm at 09:00 → occurrence Mon 09:00. "Now" is well past it.
        alarmDao.upsert(oneOff(mac, slot = 0, hour = 9, minute = 0, setAt = millis(2024, 1, 1, 8, 0)))

        val input = loaderAt(millis(2024, 1, 1, 10, 0)).load()
        // The row is still loaded into the standard list, but flagged DISABLED so the compiler
        // drops it from the wire bytes (the Room row itself is untouched).
        val a = input.alarms.single { it.slotId == 0 }
        assertFalse("a passed one-off must be suppressed from the upload", a.isEnabled)
    }

    @Test
    fun oneOffWithinGraceWindow_isNOTsuppressed_clockSkewSafety() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        // Occurrence Mon 09:00; "now" is 09:05 (5 min past). Within the upload grace margin we must
        // NOT disable it — the watch (clock possibly behind the phone) may not have fired it yet.
        alarmDao.upsert(oneOff(mac, slot = 0, hour = 9, minute = 0, setAt = millis(2024, 1, 1, 8, 0)))

        val input = loaderAt(millis(2024, 1, 1, 9, 5)).load()
        assertTrue(
            "a just-fired one-off (within grace) must stay armed to survive clock skew",
            input.alarms.single { it.slotId == 0 }.isEnabled,
        )
    }

    @Test
    fun futureOneOff_staysEnabled() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        alarmDao.upsert(oneOff(mac, slot = 0, hour = 9, minute = 0, setAt = millis(2024, 1, 1, 8, 0)))

        // "Now" is before the 09:00 occurrence — the alarm is still armed.
        val input = loaderAt(millis(2024, 1, 1, 8, 30)).load()
        assertTrue(input.alarms.single { it.slotId == 0 }.isEnabled)
    }

    @Test
    fun repeatingAlarmIsNeverSuppressed() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        alarmDao.upsert(
            oneOff(mac, slot = 0, hour = 9, minute = 0,
                days = AlarmDays.WEEKDAY, repeating = true, setAt = millis(2024, 1, 1, 8, 0))
        )
        // Far in the future — a repeating alarm must remain enabled (it recurs).
        val input = loaderAt(millis(2030, 1, 1, 12, 0)).load()
        assertTrue(input.alarms.single { it.slotId == 0 }.isEnabled)
    }

    @Test
    fun passedSingleWeekdayCalendarAlarm_isDisabledInTheInput() = runBlocking {
        val mac = "AA:00:00:00:00:01"
        watchDao.upsert(watch(mac, active = true))
        // Calendar-style one-off in slot 16: single weekday (Wed), refreshed Mon, event Wed 09:00.
        alarmDao.upsert(
            oneOff(mac, slot = 16, hour = 9, minute = 0,
                days = AlarmDays.WED, repeating = false, setAt = millis(2024, 1, 1, 8, 0))
        )
        // Now is the following Thursday — the Wed occurrence has passed.
        val input = loaderAt(millis(2024, 1, 4, 10, 0)).load()
        assertFalse(input.calendarAlarms.single { it.slotId == 16 }.isEnabled)
    }
}
