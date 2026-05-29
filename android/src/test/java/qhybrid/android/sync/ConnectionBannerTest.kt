package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.WatchState

/**
 * WP-SYNCFIX — headless tests for the pure [bannerText] link-state classification the per-screen
 * [ConnectionBanner] renders. Proves the message/busy mapping WITHOUT Compose or a device (the
 * actual banner rendering is on-device-pending).
 */
class ConnectionBannerTest {

    @Test
    fun connected_isNotBusyAndSaysConnected() {
        val (text, busy) = bannerText(WatchState.LinkState.INITIALIZED, liveMessage = "Connected")
        assertEquals("Watch connected", text)
        assertFalse(busy)
    }

    @Test
    fun connecting_isBusyAndPrefersLiveMessage() {
        val (text, busy) = bannerText(WatchState.LinkState.CONNECTING, liveMessage = "Connecting to AA…")
        assertEquals("Connecting to AA…", text)
        assertTrue(busy)
    }

    @Test
    fun connecting_fallsBackWhenNoLiveMessage() {
        val (text, busy) = bannerText(WatchState.LinkState.CONNECTING, liveMessage = null)
        assertTrue(text.isNotBlank())
        assertTrue(busy)
    }

    @Test
    fun initializing_isBusy() {
        assertTrue(bannerText(WatchState.LinkState.INITIALIZING, null).second)
    }

    @Test
    fun authRequired_isBusyAndMentionsTopButton() {
        val (text, busy) = bannerText(WatchState.LinkState.AUTH_REQUIRED, liveMessage = null)
        assertTrue(busy)
        assertTrue(text.contains("TOP", ignoreCase = true))
    }

    @Test
    fun disconnected_isNotBusyAndSaysChangesAreSaved() {
        val (text, busy) = bannerText(WatchState.LinkState.DISCONNECTED, liveMessage = "Disconnected")
        assertFalse(busy)
        // Honest: editing offline is fine; the change is saved and will sync on connect.
        assertTrue(text.contains("saved", ignoreCase = true))
        assertTrue(text.contains("disconnected", ignoreCase = true))
    }

    @Test
    fun idle_treatedAsDisconnected() {
        val (text, busy) = bannerText(WatchState.LinkState.IDLE, liveMessage = null)
        assertFalse(busy)
        assertTrue(text.contains("saved", ignoreCase = true))
    }
}
