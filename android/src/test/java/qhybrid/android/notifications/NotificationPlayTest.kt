package qhybrid.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP11 — unit tests for the notification play seam contract (mirrors the VibrationSync seam tests).
 * The production [ServiceNotificationPlay] pokes the WP3 service (verified on-device); here we lock
 * the seam contract: a fake records the forwarded package, the Noop is inert, and the wired flag.
 */
class NotificationPlayTest {

    /** A fake [NotificationPlay] that records every package it was asked to play. */
    private class FakeNotificationPlay(private val wired: Boolean = true) : NotificationPlay {
        val played = mutableListOf<String>()
        override fun play(packageName: String): Boolean {
            played += packageName
            return wired
        }
    }

    @Test
    fun fake_recordsForwardedPackage() {
        val fake = FakeNotificationPlay()
        assertTrue(fake.play("com.whatsapp"))
        assertTrue(fake.play("com.google.android.gm"))
        assertEquals(listOf("com.whatsapp", "com.google.android.gm"), fake.played)
    }

    @Test
    fun fake_canReportNotWired() {
        val fake = FakeNotificationPlay(wired = false)
        assertFalse(fake.play("com.whatsapp"))
        assertEquals(listOf("com.whatsapp"), fake.played)
    }

    @Test
    fun noop_isInertAndReturnsFalse() {
        assertFalse(NoopNotificationPlay.play("com.whatsapp"))
    }

    @Test
    fun playWired_isTrue() {
        assertTrue(NotificationPlay.PLAY_WIRED)
    }
}
