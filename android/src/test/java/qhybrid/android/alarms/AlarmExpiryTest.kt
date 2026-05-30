package qhybrid.android.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.db.WatchAlarmEntity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure unit tests for [AlarmExpiry] — the display-only "has this one-off already fired?" derivation
 * that lets the Alarms screen auto-deactivate a passed one-off (plain one-off OR single-weekday
 * one-off) without mutating Room. Uses a fixed UTC zone + explicit millis so it is deterministic.
 */
class AlarmExpiryTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** Wall-clock millis for a UTC date-time. */
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun alarm(
        hour: Int,
        minute: Int,
        daysMask: Int = 0,
        repeating: Boolean = false,
        setAt: Long,
    ) = WatchAlarmEntity(
        watchMac = "AA:00:00:00:00:01",
        slotId = 0,
        hour = hour,
        minute = minute,
        isEnabled = true,
        daysMask = daysMask,
        isRepeating = repeating,
        label = null,
        updatedAt = setAt,
    )

    // ---- plain one-off (no days) ---------------------------------------------

    @Test
    fun plainOneOff_sameDayLaterTime_firesToday_notPassedBefore_passedAfter() {
        // Set Mon 2024-01-01 08:00; alarm at 09:00 → fires today 09:00.
        val setAt = millis(2024, 1, 1, 8, 0)
        val a = alarm(hour = 9, minute = 0, setAt = setAt)

        assertFalse("before occurrence", AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 8, 59), zone))
        assertTrue("at occurrence", AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 9, 0), zone))
        assertTrue("after occurrence", AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 9, 1), zone))
    }

    @Test
    fun plainOneOff_timeAlreadyPastWhenSet_rollsToTomorrow() {
        // Set Mon 08:00; alarm at 07:00 (already past today) → fires tomorrow 07:00.
        val setAt = millis(2024, 1, 1, 8, 0)
        val a = alarm(hour = 7, minute = 0, setAt = setAt)

        // Same day, even later, is still BEFORE tomorrow's occurrence.
        assertFalse(AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 23, 0), zone))
        // Tomorrow at 07:00 → fired.
        assertTrue(AlarmExpiry.hasPassed(a, millis(2024, 1, 2, 7, 0), zone))
    }

    // ---- single-weekday one-off ----------------------------------------------

    @Test
    fun singleWeekdayOneOff_firesNextMatchingWeekday() {
        // Set Mon 2024-01-01 08:00; alarm WED 09:00 → next Wed is 2024-01-03.
        val setAt = millis(2024, 1, 1, 8, 0)
        val a = alarm(hour = 9, minute = 0, daysMask = AlarmDays.WED, setAt = setAt)

        // Tuesday: not yet.
        assertFalse(AlarmExpiry.hasPassed(a, millis(2024, 1, 2, 23, 0), zone))
        // Wednesday before 09:00: not yet.
        assertFalse(AlarmExpiry.hasPassed(a, millis(2024, 1, 3, 8, 59), zone))
        // Wednesday at 09:00: fired.
        assertTrue(AlarmExpiry.hasPassed(a, millis(2024, 1, 3, 9, 0), zone))
    }

    @Test
    fun singleWeekdayOneOff_sameWeekdayLaterTime_firesSameDay() {
        // Set Mon 2024-01-01 08:00; alarm MON 09:00 → fires SAME day (Mon 09:00).
        val setAt = millis(2024, 1, 1, 8, 0)
        val a = alarm(hour = 9, minute = 0, daysMask = AlarmDays.MON, setAt = setAt)

        assertFalse(AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 8, 59), zone))
        assertTrue(AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 9, 0), zone))
    }

    @Test
    fun singleWeekdayOneOff_sameWeekdayPastTime_rollsToNextWeek() {
        // Set Mon 2024-01-01 08:00; alarm MON 07:00 (past today) → next Mon 2024-01-08.
        val setAt = millis(2024, 1, 1, 8, 0)
        val a = alarm(hour = 7, minute = 0, daysMask = AlarmDays.MON, setAt = setAt)

        // The following Sunday: still before next Monday's occurrence.
        assertFalse(AlarmExpiry.hasPassed(a, millis(2024, 1, 7, 23, 0), zone))
        // Next Monday 07:00: fired.
        assertTrue(AlarmExpiry.hasPassed(a, millis(2024, 1, 8, 7, 0), zone))
    }

    // ---- never-expiring shapes -----------------------------------------------

    @Test
    fun repeatingAlarmsNeverExpire() {
        val setAt = millis(2024, 1, 1, 8, 0)
        val weekly = alarm(hour = 7, minute = 0, daysMask = AlarmDays.WEEKDAY, repeating = true, setAt = setAt)
        val singleDayRepeating = alarm(hour = 7, minute = 0, daysMask = AlarmDays.MON, repeating = true, setAt = setAt)

        // Far in the future — still not "passed" because they recur.
        assertFalse(AlarmExpiry.hasPassed(weekly, millis(2030, 1, 1, 12, 0), zone))
        assertFalse(AlarmExpiry.hasPassed(singleDayRepeating, millis(2030, 1, 1, 12, 0), zone))
    }

    // ---- grace margin (upload-suppression clock-skew safety) -----------------

    @Test
    fun graceMargin_doesNotSuppressUntilConfidentlyPast() {
        // Set Mon 08:00; one-off at 09:00. With a 10-min grace, it's only "passed" once now is
        // >= 09:10 — so an alarm the watch may not have fired yet (phone clock ahead) is NOT killed.
        val setAt = millis(2024, 1, 1, 8, 0)
        val a = alarm(hour = 9, minute = 0, setAt = setAt)
        val grace = 10 * 60 * 1000L

        // Exactly at the occurrence: NOT yet suppressed (within grace).
        assertFalse(AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 9, 0), zone, grace))
        // 9 minutes past: still within grace.
        assertFalse(AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 9, 9), zone, grace))
        // 10 minutes past: confidently fired.
        assertTrue(AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 9, 10), zone, grace))

        // The DEFAULT (no grace) the UI uses is exact — "passed" the instant the time arrives.
        assertTrue(AlarmExpiry.hasPassed(a, millis(2024, 1, 1, 9, 0), zone))
    }

    @Test
    fun uploadGraceConstantIsPositive() {
        // The upload path uses a non-trivial margin to absorb phone↔watch clock skew.
        assertTrue(AlarmExpiry.UPLOAD_SUPPRESS_GRACE_MS >= 5 * 60 * 1000L)
    }

    @Test
    fun multiDayNonRepeatingIsTreatedAsWeekly_neverExpires() {
        // 2+ day bits is inherently weekly even if isRepeating somehow false — no single occurrence.
        val setAt = millis(2024, 1, 1, 8, 0)
        val a = alarm(hour = 7, minute = 0, daysMask = AlarmDays.WEEKEND, repeating = false, setAt = setAt)
        assertFalse(AlarmExpiry.hasPassed(a, millis(2030, 1, 1, 12, 0), zone))
        assertEquals(null, AlarmExpiry.nextOccurrenceMillis(a, zone))
    }
}
