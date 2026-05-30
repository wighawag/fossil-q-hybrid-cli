package qhybrid.android.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper.CalendarEvent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * WP13 (Step 1) — the PURE calendar→rows core. These mostly re-assert WP9 (golden-tested in
 * `:protocol`) wired through the [AlarmSlot]→[WatchAlarmEntity] mapping, plus the slot/mac/field
 * mapping itself. No Room, no Android, no provider.
 */
class CalendarAlarmSyncTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Epoch millis for a UTC local date-time (the test zone is UTC for determinism). */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val now = at(2025, 6, 2, 0, 0) // Mon 2025-06-02 00:00 UTC

    @Test
    fun emptyEvents_emptyRows() {
        assertTrue(CalendarAlarmSync.mapEventsToRows(mac, emptyList(), now, zone).isEmpty())
    }

    @Test
    fun nEvents_nRowsInSlots16Onward() {
        val events = listOf(
            CalendarEvent("A", at(2025, 6, 2, 9, 0)),
            CalendarEvent("B", at(2025, 6, 3, 10, 0)),
            CalendarEvent("C", at(2025, 6, 4, 11, 0)),
        )
        val rows = CalendarAlarmSync.mapEventsToRows(mac, events, now, zone)
        assertEquals(3, rows.size)
        assertEquals(listOf(16, 17, 18), rows.map { it.slotId })
        // All non-repeating, enabled, calendar-owned, re-keyed to the (upper-case) mac.
        rows.forEach {
            assertFalse(it.isRepeating)
            assertTrue(it.isEnabled)
            assertEquals(mac.uppercase(), it.watchMac)
            assertEquals(0L, it.updatedAt) // left to the repo to stamp
        }
        assertEquals(listOf("A", "B", "C"), rows.map { it.label })
    }

    @Test
    fun fridayEvent_mapsToRightDaysMaskHourMinute() {
        // 2025-06-06 is a Friday. bit5 = 0x20.
        val events = listOf(CalendarEvent("Fri", at(2025, 6, 6, 10, 15)))
        val row = CalendarAlarmSync.mapEventsToRows(mac, events, now, zone).single()
        assertEquals(16, row.slotId)
        assertEquals(10, row.hour)
        assertEquals(15, row.minute)
        assertEquals(0x20, row.daysMask)
    }

    @Test
    fun moreThan16Events_keptTo16() {
        // 20 distinct-wire-identity events all inside the window → only the nearest 16 kept.
        val events = (0 until 20).map { i ->
            // spread minutes so each maps to a distinct (daysMask,hour,minute) wire identity
            CalendarEvent("E$i", at(2025, 6, 2, 8, i))
        }
        val rows = CalendarAlarmSync.mapEventsToRows(mac, events, now, zone)
        assertEquals(16, rows.size)
        assertEquals((16..31).toList(), rows.map { it.slotId })
    }

    @Test
    fun eventsBeyond7Days_excluded() {
        val events = listOf(
            CalendarEvent("soon", at(2025, 6, 3, 9, 0)),   // +1d → in window
            CalendarEvent("far", at(2025, 6, 20, 9, 0)),   // +18d → excluded
        )
        val rows = CalendarAlarmSync.mapEventsToRows(mac, events, now, zone)
        assertEquals(1, rows.size)
        assertEquals("soon", rows.single().label)
    }

    @Test
    fun lowerCaseMac_normalizedOntoRows() {
        val events = listOf(CalendarEvent("A", at(2025, 6, 2, 9, 0)))
        val row = CalendarAlarmSync.mapEventsToRows(mac.lowercase(), events, now, zone).single()
        assertEquals(mac.uppercase(), row.watchMac)
    }

    @Test
    fun offset_ringsBeforeEvent() {
        // Event at 10:15 with a 30-min offset -> alarm at 09:45.
        val events = listOf(CalendarEvent("Standup", at(2025, 6, 3, 10, 15)))
        val row = CalendarAlarmSync.mapEventsToRows(mac, events, now, zone, offsetMinutes = 30).single()
        assertEquals(9, row.hour)
        assertEquals(45, row.minute)
    }

    @Test
    fun offset_crossesMidnight_shiftsDayAndDaysMask() {
        // Event Tue 2025-06-03 00:10 with a 30-min offset -> alarm Mon 2025-06-02 23:40.
        // Monday bit1 = 0x02.
        val events = listOf(CalendarEvent("Late", at(2025, 6, 3, 0, 10)))
        val row = CalendarAlarmSync.mapEventsToRows(mac, events, now, zone, offsetMinutes = 30).single()
        assertEquals(23, row.hour)
        assertEquals(40, row.minute)
        assertEquals(0x02, row.daysMask) // Monday (shifted back across midnight)
    }

    @Test
    fun offset_zero_ringsAtEventTime() {
        val events = listOf(CalendarEvent("A", at(2025, 6, 3, 10, 15)))
        val row = CalendarAlarmSync.mapEventsToRows(mac, events, now, zone, offsetMinutes = 0).single()
        assertEquals(10, row.hour)
        assertEquals(15, row.minute)
    }
}
