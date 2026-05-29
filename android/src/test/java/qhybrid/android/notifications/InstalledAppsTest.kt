package qhybrid.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP16c — pure tests for the installed-app search/filter logic ([InstalledApp.matches]) that
 * backs the searchable app picker. No Android / PackageManager needed.
 */
class InstalledAppsTest {

    private val calendar = InstalledApp("com.google.android.calendar", "Calendar")
    private val whatsapp = InstalledApp("com.whatsapp", "WhatsApp")
    private val gmail = InstalledApp("com.google.android.gm", "Gmail")

    @Test
    fun matchesByDisplayNameCaseInsensitive() {
        assertTrue(calendar.matches("calendar"))
        assertTrue(calendar.matches("Calendar"))
        assertTrue(calendar.matches("CAL"))
        assertTrue(calendar.matches("end")) // substring of "Calendar"
    }

    @Test
    fun matchesByPackageId() {
        assertTrue(gmail.matches("com.google.android.gm"))
        assertTrue(gmail.matches("android.gm"))
        assertTrue(whatsapp.matches("whatsapp")) // also matches the package id substring
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(calendar.matches(""))
        assertTrue(calendar.matches("   "))
    }

    @Test
    fun nonMatchReturnsFalse() {
        assertFalse(calendar.matches("whatsapp"))
        assertFalse(whatsapp.matches("calendar"))
    }

    @Test
    fun filteringAListByQueryNarrowsResults() {
        val apps = listOf(calendar, whatsapp, gmail)
        // Typing "g" should surface the two Google apps by name/package, not WhatsApp by name.
        val byG = apps.filter { it.matches("Gmail") }
        assertEquals(listOf(gmail), byG)

        // Typing "calendar" surfaces only Calendar (the previous bug: list never changed).
        assertEquals(listOf(calendar), apps.filter { it.matches("calendar") })
    }
}
