package qhybrid.android.calendar

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP13 (Step 1) — the pure permission check ([CalendarAccess.isGranted] boolean overload), testable
 * without a Context. The thin [CalendarAccess.isGranted] Context wrapper is the Android shell.
 */
class CalendarAccessTest {

    @Test
    fun granted_true() = assertTrue(CalendarAccess.isGranted(true))

    @Test
    fun notGranted_false() = assertFalse(CalendarAccess.isGranted(false))

    @Test
    fun permissionConstant_isReadCalendar() =
        assertEquals(Manifest.permission.READ_CALENDAR, CalendarAccess.PERMISSION)
}
