package qhybrid.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP16g — constants sanity + range/normalization tolerance (clamp out-of-range, tolerate unknown).
 * Pure JVM (no Android deps), so no Robolectric needed here.
 */
class SettingsVocabularyTest {

    // ---- vibration strength --------------------------------------------------

    @Test
    fun vibrationConstantsAndClamp() {
        assertEquals(0, SettingsVocabulary.VIBE_MIN)
        assertEquals(100, SettingsVocabulary.VIBE_MAX)
        assertEquals(50, SettingsVocabulary.VIBE_DEFAULT)
        // In-range passes through.
        assertEquals(73, SettingsVocabulary.normalizeVibration(73))
        // Out-of-range clamps.
        assertEquals(0, SettingsVocabulary.normalizeVibration(-20))
        assertEquals(100, SettingsVocabulary.normalizeVibration(250))
    }

    // ---- inactivity nudge ----------------------------------------------------

    @Test
    fun nudgeConstantsAndClamp() {
        assertEquals(1, SettingsVocabulary.NUDGE_MIN_MINUTES)
        assertEquals(255, SettingsVocabulary.NUDGE_MAX_MINUTES)
        assertEquals(30, SettingsVocabulary.NUDGE_DEFAULT_MINUTES)
        assertEquals(15, SettingsVocabulary.NUDGE_STEP_MINUTES)
        assertFalse(SettingsVocabulary.NUDGE_DEFAULT_ENABLED)
        // In-range passes through; out-of-range clamps to the 1-byte range.
        assertEquals(60, SettingsVocabulary.normalizeNudgeMinutes(60))
        assertEquals(1, SettingsVocabulary.normalizeNudgeMinutes(0))
        assertEquals(1, SettingsVocabulary.normalizeNudgeMinutes(-5))
        assertEquals(255, SettingsVocabulary.normalizeNudgeMinutes(9999))
    }

    // ---- second timezone -----------------------------------------------------

    @Test
    fun timezoneRangeAndClamp() {
        assertEquals(-12 * 60, SettingsVocabulary.TZ_MIN_OFFSET_MINUTES)
        assertEquals(14 * 60, SettingsVocabulary.TZ_MAX_OFFSET_MINUTES)
        assertEquals(0, SettingsVocabulary.TZ_DEFAULT_OFFSET_MINUTES)
        // Clamp out-of-range to the valid UTC-12..UTC+14 window.
        assertEquals(-720, SettingsVocabulary.normalizeTzOffset(-9999))
        assertEquals(840, SettingsVocabulary.normalizeTzOffset(9999))
        assertEquals(330, SettingsVocabulary.normalizeTzOffset(330)) // India +5:30 passes through
    }

    @Test
    fun timezoneOptionsSpanFullRange() {
        val opts = SettingsVocabulary.TZ_OFFSET_OPTIONS_MINUTES
        assertEquals(SettingsVocabulary.TZ_MIN_OFFSET_MINUTES, opts.first())
        assertEquals(SettingsVocabulary.TZ_MAX_OFFSET_MINUTES, opts.last())
        // Monotonically increasing, 30-minute steps.
        for (i in 1 until opts.size) {
            assertEquals(30, opts[i] - opts[i - 1])
        }
        assertTrue(opts.contains(0)) // UTC present
    }

    @Test
    fun timezoneLabels() {
        assertEquals("UTC", SettingsVocabulary.tzOffsetLabel(0))
        assertEquals("UTC+05:30", SettingsVocabulary.tzOffsetLabel(330))
        assertEquals("UTC-08:00", SettingsVocabulary.tzOffsetLabel(-480))
        assertEquals("UTC+14:00", SettingsVocabulary.tzOffsetLabel(840))
        // Out-of-range still formats (clamped first).
        assertEquals("UTC+14:00", SettingsVocabulary.tzOffsetLabel(9999))
    }

    // ---- preferred music app -------------------------------------------------

    @Test
    fun musicAppNormalization() {
        assertEquals(SettingsVocabulary.MUSIC_APP_NONE, SettingsVocabulary.normalizeMusicApp(null))
        assertEquals(SettingsVocabulary.MUSIC_APP_NONE, SettingsVocabulary.normalizeMusicApp(""))
        assertEquals(SettingsVocabulary.MUSIC_APP_NONE, SettingsVocabulary.normalizeMusicApp("   "))
        assertEquals("com.spotify.music", SettingsVocabulary.normalizeMusicApp("com.spotify.music"))
        assertEquals("com.spotify.music", SettingsVocabulary.normalizeMusicApp("  com.spotify.music  "))
    }

    // ---- multi-function role (WP-TRACKER; GLOBAL app pref) -------------------

    @Test
    fun multiFunctionRoleConstantsAndDefault() {
        assertEquals("MUSIC", SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC)
        assertEquals("TRACKER", SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER)
        // Default preserves the WP12 music-control behaviour.
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC,
            SettingsVocabulary.MULTI_FUNCTION_ROLE_DEFAULT,
        )
        assertEquals(
            listOf("MUSIC", "TRACKER"),
            SettingsVocabulary.MULTI_FUNCTION_ROLES,
        )
    }

    @Test
    fun multiFunctionRoleNormalization() {
        // Blank/unknown → default MUSIC (never throws).
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC,
            SettingsVocabulary.normalizeMultiFunctionRole(null),
        )
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC,
            SettingsVocabulary.normalizeMultiFunctionRole("   "),
        )
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC,
            SettingsVocabulary.normalizeMultiFunctionRole("BOGUS"),
        )
        // Known values round-trip; case-insensitive + trimmed.
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER,
            SettingsVocabulary.normalizeMultiFunctionRole("  tracker "),
        )
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC,
            SettingsVocabulary.normalizeMultiFunctionRole("music"),
        )
    }

    @Test
    fun multiFunctionRoleLabels() {
        assertEquals("Music control", SettingsVocabulary.multiFunctionRoleLabel("MUSIC"))
        assertEquals("GPS waypoint tracker", SettingsVocabulary.multiFunctionRoleLabel("TRACKER"))
        // Unknown role falls back to the default label.
        assertEquals("Music control", SettingsVocabulary.multiFunctionRoleLabel("BOGUS"))
    }

    // ---- WP-TRACKER: find-my-phone ring duration -----------------------------

    @Test
    fun ringDurationConstantsAndClamp() {
        assertEquals(5, SettingsVocabulary.RING_DURATION_MIN_SECONDS)
        assertEquals(300, SettingsVocabulary.RING_DURATION_MAX_SECONDS)
        // Default is 1 minute, as requested.
        assertEquals(60, SettingsVocabulary.RING_DURATION_DEFAULT_SECONDS)
        // Clamp below min / above max; in-range passes through.
        assertEquals(5, SettingsVocabulary.normalizeRingDuration(0))
        assertEquals(5, SettingsVocabulary.normalizeRingDuration(-99))
        assertEquals(300, SettingsVocabulary.normalizeRingDuration(99_999))
        assertEquals(90, SettingsVocabulary.normalizeRingDuration(90))
    }

    @Test
    fun ringDurationLabels() {
        assertEquals("30 sec", SettingsVocabulary.ringDurationLabel(30))
        assertEquals("1 min", SettingsVocabulary.ringDurationLabel(60))
        assertEquals("1 min 30 sec", SettingsVocabulary.ringDurationLabel(90))
        assertEquals("5 min", SettingsVocabulary.ringDurationLabel(300))
    }
}
