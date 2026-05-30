package qhybrid.android.notifications

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP10/WP11 — unit tests for the Notification-Access helper: the pure membership grant check and
 * the deep-link Intent. (The context overload reads Settings.Secure and is exercised on-device.)
 * Robolectric provides the Android framework for the Intent/Settings constants.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationAccessTest {

    private val ME = "qhybrid.android"

    @Test
    fun granted_whenPackageInEnabledSet() {
        assertTrue(NotificationAccess.isGranted(setOf("other.app", ME), ME))
    }

    @Test
    fun notGranted_whenPackageAbsent() {
        assertFalse(NotificationAccess.isGranted(setOf("other.app"), ME))
    }

    @Test
    fun notGranted_whenEmpty() {
        assertFalse(NotificationAccess.isGranted(emptySet(), ME))
    }

    @Test
    fun settingsIntent_targetsNotificationListenerSettings() {
        val intent = NotificationAccess.settingsIntent()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)
    }

    @Test
    fun settingsIntent_isNewTask() {
        val intent = NotificationAccess.settingsIntent()
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
