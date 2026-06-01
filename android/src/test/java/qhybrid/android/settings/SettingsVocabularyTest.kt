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

    // ---- L0: configurable multi-function ROTATION ----------------------------

    @Test
    fun modeConstantsAndDefaults() {
        assertEquals("MUSIC_PHONE", SettingsVocabulary.MODE_MUSIC_PHONE)
        assertEquals("MUSIC_LYRION", SettingsVocabulary.MODE_MUSIC_LYRION)
        assertEquals("TRACKER", SettingsVocabulary.MODE_TRACKER)
        assertEquals("TIMER", SettingsVocabulary.MODE_TIMER)
        assertEquals(
            listOf("MUSIC_PHONE", "MUSIC_LYRION", "TRACKER", "TIMER"),
            SettingsVocabulary.MULTI_FUNCTION_MODES,
        )
        // TIMER gesture durations (short/double/long minutes).
        assertEquals(3, SettingsVocabulary.TIMER_SHORT_MINUTES)
        assertEquals(5, SettingsVocabulary.TIMER_DOUBLE_MINUTES)
        assertEquals(10, SettingsVocabulary.TIMER_LONG_MINUTES)
        // Default rotation = phone media only (preserves out-of-box behaviour).
        assertEquals(
            listOf(SettingsVocabulary.MODE_MUSIC_PHONE),
            SettingsVocabulary.MULTI_FUNCTION_ROTATION_DEFAULT,
        )
    }

    @Test
    fun normalizeMode_foldsUnknownAndLegacyToPhone() {
        assertEquals(SettingsVocabulary.MODE_MUSIC_PHONE, SettingsVocabulary.normalizeMode(null))
        assertEquals(SettingsVocabulary.MODE_MUSIC_PHONE, SettingsVocabulary.normalizeMode("  "))
        assertEquals(SettingsVocabulary.MODE_MUSIC_PHONE, SettingsVocabulary.normalizeMode("BOGUS"))
        // Legacy single-role "MUSIC" maps to the phone backend.
        assertEquals(SettingsVocabulary.MODE_MUSIC_PHONE, SettingsVocabulary.normalizeMode("MUSIC"))
        // Known modes round-trip; case-insensitive + trimmed.
        assertEquals(SettingsVocabulary.MODE_MUSIC_LYRION, SettingsVocabulary.normalizeMode(" music_lyrion "))
        assertEquals(SettingsVocabulary.MODE_TRACKER, SettingsVocabulary.normalizeMode("tracker"))
        assertEquals(SettingsVocabulary.MODE_TIMER, SettingsVocabulary.normalizeMode(" timer "))
    }

    @Test
    fun normalizeRotation_dedupsPreservesOrderAndFallsBack() {
        // Order preserved, duplicates dropped.
        assertEquals(
            listOf("MUSIC_PHONE", "MUSIC_LYRION", "TRACKER"),
            SettingsVocabulary.normalizeRotation(
                listOf("MUSIC_PHONE", "MUSIC_LYRION", "MUSIC_PHONE", "TRACKER")
            ),
        )
        // Null/empty → default.
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROTATION_DEFAULT,
            SettingsVocabulary.normalizeRotation(null),
        )
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROTATION_DEFAULT,
            SettingsVocabulary.normalizeRotation(emptyList()),
        )
        // Only-unknown entries collapse (via normalizeMode) to a single phone entry, never empty.
        assertEquals(
            listOf(SettingsVocabulary.MODE_MUSIC_PHONE),
            SettingsVocabulary.normalizeRotation(listOf("???", "   ")),
        )
    }

    @Test
    fun rotationCsvRoundTrips() {
        val csv = SettingsVocabulary.rotationToCsv(listOf("MUSIC_LYRION", "TRACKER"))
        assertEquals("MUSIC_LYRION,TRACKER", csv)
        assertEquals(
            listOf("MUSIC_LYRION", "TRACKER"),
            SettingsVocabulary.parseRotation(csv),
        )
        // Blank/garbage CSV → default.
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROTATION_DEFAULT,
            SettingsVocabulary.parseRotation(null),
        )
        assertEquals(
            SettingsVocabulary.MULTI_FUNCTION_ROTATION_DEFAULT,
            SettingsVocabulary.parseRotation("   "),
        )
    }

    @Test
    fun activeModeAndNextIndex_wrapAround() {
        val rot = listOf("MUSIC_PHONE", "MUSIC_LYRION", "TRACKER")
        assertEquals("MUSIC_PHONE", SettingsVocabulary.activeMode(rot, 0))
        assertEquals("MUSIC_LYRION", SettingsVocabulary.activeMode(rot, 1))
        assertEquals("TRACKER", SettingsVocabulary.activeMode(rot, 2))
        // Out-of-range index is clamped, never throws.
        assertEquals("TRACKER", SettingsVocabulary.activeMode(rot, 99))
        assertEquals("MUSIC_PHONE", SettingsVocabulary.activeMode(rot, -5))
        // next iterates with wrap-around.
        assertEquals(1, SettingsVocabulary.nextIndex(rot, 0))
        assertEquals(2, SettingsVocabulary.nextIndex(rot, 1))
        assertEquals(0, SettingsVocabulary.nextIndex(rot, 2)) // wrap
        // Single-entry rotation always stays at 0.
        assertEquals(0, SettingsVocabulary.nextIndex(listOf("MUSIC_PHONE"), 0))
    }

    @Test
    fun modeLabels() {
        assertEquals("Music (phone)", SettingsVocabulary.modeLabel("MUSIC_PHONE"))
        assertEquals("Music (Lyrion player)", SettingsVocabulary.modeLabel("MUSIC_LYRION"))
        assertEquals("GPS waypoint tracker", SettingsVocabulary.modeLabel("TRACKER"))
        assertEquals("Timer (ring in N min)", SettingsVocabulary.modeLabel("TIMER"))
        assertEquals("Music (phone)", SettingsVocabulary.modeLabel("BOGUS"))
    }

    // ---- per-mode SWITCH buzz (stable, configurable, duplicates allowed) ------

    @Test
    fun switchBuzzConstantsAndOptions() {
        assertEquals(5, SettingsVocabulary.SWITCH_BUZZ_SINGLE)
        assertEquals(6, SettingsVocabulary.SWITCH_BUZZ_DOUBLE)
        assertEquals(7, SettingsVocabulary.SWITCH_BUZZ_TRIPLE)
        assertEquals(8, SettingsVocabulary.SWITCH_BUZZ_LONG)
        assertEquals(listOf(5, 6, 7, 8), SettingsVocabulary.SWITCH_BUZZ_OPTIONS)
    }

    @Test
    fun switchBuzzDefaultsAreDistinctPerMode() {
        // Out of the box, all four modes have a different buzz.
        assertEquals(5, SettingsVocabulary.switchBuzzFor("MUSIC_PHONE", emptyMap()))
        assertEquals(6, SettingsVocabulary.switchBuzzFor("MUSIC_LYRION", emptyMap()))
        assertEquals(7, SettingsVocabulary.switchBuzzFor("TRACKER", emptyMap()))
        assertEquals(8, SettingsVocabulary.switchBuzzFor("TIMER", emptyMap()))
    }

    @Test
    fun switchBuzzFor_overrideWinsOverDefault_invalidFallsBack() {
        // A valid override is used.
        assertEquals(8, SettingsVocabulary.switchBuzzFor("MUSIC_PHONE", mapOf("MUSIC_PHONE" to 8)))
        // An invalid override value falls back to the per-mode default (phone=single).
        assertEquals(5, SettingsVocabulary.switchBuzzFor("MUSIC_PHONE", mapOf("MUSIC_PHONE" to 99)))
        // An unknown mode falls back to single.
        assertEquals(5, SettingsVocabulary.switchBuzzFor("BOGUS", emptyMap()))
    }

    @Test
    fun switchBuzz_duplicatesAcrossModesAllowed() {
        // Two modes can share the same buzz (duplicates allowed by design).
        val overrides = mapOf("MUSIC_PHONE" to 6, "TRACKER" to 6)
        assertEquals(6, SettingsVocabulary.switchBuzzFor("MUSIC_PHONE", overrides))
        assertEquals(6, SettingsVocabulary.switchBuzzFor("TRACKER", overrides))
    }

    @Test
    fun switchBuzz_normalizeAndCsvRoundTrip() {
        // normalize drops invalid values + re-keys onto known modes.
        val norm = SettingsVocabulary.normalizeSwitchBuzzMap(
            mapOf("music_phone" to 8, "TRACKER" to 99)
        )
        // TRACKER's value 99 is invalid → dropped; music_phone uppercases + keeps its valid value.
        assertEquals(mapOf("MUSIC_PHONE" to 8), norm)

        // CSV round-trips a valid map.
        val csv = SettingsVocabulary.switchBuzzMapToCsv(mapOf("TIMER" to 6, "TRACKER" to 7))
        val parsed = SettingsVocabulary.parseSwitchBuzzMap(csv)
        assertEquals(6, parsed["TIMER"])
        assertEquals(7, parsed["TRACKER"])

        // Blank / malformed CSV → empty map (no throw).
        assertEquals(emptyMap<String, Int>(), SettingsVocabulary.parseSwitchBuzzMap(null))
        assertEquals(emptyMap<String, Int>(), SettingsVocabulary.parseSwitchBuzzMap("  "))
        assertEquals(emptyMap<String, Int>(), SettingsVocabulary.parseSwitchBuzzMap("garbage,x=y"))
    }

    @Test
    fun switchBuzzLabels() {
        assertEquals("Single buzz", SettingsVocabulary.switchBuzzLabel(5))
        assertEquals("Double buzz", SettingsVocabulary.switchBuzzLabel(6))
        assertEquals("Triple buzz", SettingsVocabulary.switchBuzzLabel(7))
        assertEquals("Long buzz", SettingsVocabulary.switchBuzzLabel(8))
        assertEquals("Single buzz", SettingsVocabulary.switchBuzzLabel(99))
    }

    @Test
    fun switchBuzzDebounce_defaultClampAndLabel() {
        assertEquals(1500, SettingsVocabulary.SWITCH_BUZZ_DEBOUNCE_DEFAULT_MS)
        // Clamp below min / above max; in-range passes through.
        assertEquals(0, SettingsVocabulary.normalizeSwitchBuzzDebounceMs(-100))
        assertEquals(5000, SettingsVocabulary.normalizeSwitchBuzzDebounceMs(99999))
        assertEquals(1500, SettingsVocabulary.normalizeSwitchBuzzDebounceMs(1500))
        // Labels: off / ms / whole seconds / fractional seconds.
        assertEquals("Off (immediate)", SettingsVocabulary.switchBuzzDebounceLabel(0))
        assertEquals("750 ms", SettingsVocabulary.switchBuzzDebounceLabel(750))
        assertEquals("2 s", SettingsVocabulary.switchBuzzDebounceLabel(2000))
        assertEquals("1.5 s", SettingsVocabulary.switchBuzzDebounceLabel(1500))
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

    // ---- L1: Lyrion (LMS) music control --------------------------------------

    @Test
    fun lyrionHostNormalization() {
        assertEquals(SettingsVocabulary.LYRION_HOST_NONE, SettingsVocabulary.normalizeLyrionHost(null))
        assertEquals(SettingsVocabulary.LYRION_HOST_NONE, SettingsVocabulary.normalizeLyrionHost("  "))
        assertEquals("192.168.1.10", SettingsVocabulary.normalizeLyrionHost("  192.168.1.10 "))
    }

    @Test
    fun lyrionPortNormalization() {
        assertEquals(9000, SettingsVocabulary.LYRION_PORT_DEFAULT)
        // Out-of-range / zero / negative → default.
        assertEquals(9000, SettingsVocabulary.normalizeLyrionPort(0))
        assertEquals(9000, SettingsVocabulary.normalizeLyrionPort(-5))
        assertEquals(9000, SettingsVocabulary.normalizeLyrionPort(70_000))
        // In-range passes through.
        assertEquals(9090, SettingsVocabulary.normalizeLyrionPort(9090))
        assertEquals(1, SettingsVocabulary.normalizeLyrionPort(1))
        assertEquals(65535, SettingsVocabulary.normalizeLyrionPort(65535))
    }

    @Test
    fun lyrionPlayerAndFavoriteIdNormalization() {
        assertEquals(SettingsVocabulary.LYRION_PLAYER_NONE, SettingsVocabulary.normalizeLyrionPlayerId(null))
        assertEquals(SettingsVocabulary.LYRION_PLAYER_NONE, SettingsVocabulary.normalizeLyrionPlayerId("  "))
        assertEquals("00:04:20:ab:cd:ef", SettingsVocabulary.normalizeLyrionPlayerId(" 00:04:20:ab:cd:ef "))
        assertEquals(SettingsVocabulary.LYRION_FAVORITE_NONE, SettingsVocabulary.normalizeLyrionFavoriteId(""))
        assertEquals("abc123", SettingsVocabulary.normalizeLyrionFavoriteId(" abc123 "))
    }

    @Test
    fun lyrionFallbackConstantsAndNormalization() {
        assertEquals("FAVORITE", SettingsVocabulary.LYRION_FALLBACK_FAVORITE)
        assertEquals("RANDOM", SettingsVocabulary.LYRION_FALLBACK_RANDOM)
        assertEquals("NONE", SettingsVocabulary.LYRION_FALLBACK_NONE)
        assertEquals(SettingsVocabulary.LYRION_FALLBACK_FAVORITE, SettingsVocabulary.LYRION_FALLBACK_DEFAULT)
        assertEquals(
            listOf("FAVORITE", "RANDOM", "NONE"),
            SettingsVocabulary.LYRION_FALLBACKS,
        )
        // Blank/unknown → default FAVORITE; known values round-trip (case-insensitive, trimmed).
        assertEquals(SettingsVocabulary.LYRION_FALLBACK_FAVORITE, SettingsVocabulary.normalizeLyrionFallback(null))
        assertEquals(SettingsVocabulary.LYRION_FALLBACK_FAVORITE, SettingsVocabulary.normalizeLyrionFallback("BOGUS"))
        assertEquals(SettingsVocabulary.LYRION_FALLBACK_RANDOM, SettingsVocabulary.normalizeLyrionFallback(" random "))
        assertEquals(SettingsVocabulary.LYRION_FALLBACK_NONE, SettingsVocabulary.normalizeLyrionFallback("none"))
    }

    @Test
    fun lyrionFallbackLabels() {
        assertEquals("Favourite", SettingsVocabulary.lyrionFallbackLabel("FAVORITE"))
        assertEquals("Random tracks", SettingsVocabulary.lyrionFallbackLabel("RANDOM"))
        assertEquals("Do nothing", SettingsVocabulary.lyrionFallbackLabel("NONE"))
        assertEquals("Favourite", SettingsVocabulary.lyrionFallbackLabel("BOGUS"))
    }
}
