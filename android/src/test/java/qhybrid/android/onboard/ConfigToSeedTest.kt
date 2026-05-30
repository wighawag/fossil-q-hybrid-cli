package qhybrid.android.onboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.protocol.FossilQAdapter

/**
 * WP-ONBOARD (sub-part 2) — pure unit tests for [ConfigToSeed].
 *
 * The mapper decodes the watch's READABLE settings from [FossilQAdapter.ConfigEntry.rawData] (not
 * `formattedValue`). Present values map correctly; absent → constant/off/null; malformed/short
 * rawData never throws (safe fallback); out-of-range vibration is clamped.
 */
class ConfigToSeedTest {

    private fun entry(id: Int, name: String, vararg raw: Int): FossilQAdapter.ConfigEntry {
        val bytes = ByteArray(raw.size) { raw[it].toByte() }
        return FossilQAdapter.ConfigEntry(id, name, bytes, "<fmt>")
    }

    private fun vibe(pct: Int) = entry(0x0A, "VIBE_STRENGTH", pct)
    private fun stepGoal(value: Int) = entry(
        0x03, "DAILY_STEP_GOAL",
        value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF, (value shr 24) and 0xFF,
    )

    private fun nudge(fromH: Int, fromM: Int, toH: Int, toM: Int, minutes: Int, enabled: Boolean) =
        entry(0x09, "INACTIVE_NUDGE", fromH, fromM, toH, toM, minutes, if (enabled) 1 else 0)

    private fun secondTz(offset: Int) =
        entry(0x11, "SECOND_TIMEZONE_OFFSET", offset and 0xFF, (offset shr 8) and 0xFF)

    // ---- present values map correctly --------------------------------------------------------

    @Test
    fun present_vibrationStrength_mapsFromRawByte() {
        val s = ConfigToSeed.seed(listOf(vibe(80)))
        assertEquals(80, s.vibrationStrength)
    }

    @Test
    fun present_stepGoal_decodesLittleEndianInt() {
        val s = ConfigToSeed.seed(listOf(stepGoal(12345)))
        assertEquals(12345, s.stepGoal)
    }

    @Test
    fun present_nudgeEnabled_mapsEnabledFlagAndMinutes() {
        val s = ConfigToSeed.seed(listOf(nudge(9, 0, 18, 0, 45, enabled = true)))
        assertTrue(s.nudgeEnabled)
        assertEquals(45, s.nudgeMinutes)
    }

    @Test
    fun present_nudgeDisabled_reportsOffAndNullMinutes() {
        val s = ConfigToSeed.seed(listOf(nudge(9, 0, 18, 0, 45, enabled = false)))
        assertFalse(s.nudgeEnabled)
        assertNull(s.nudgeMinutes)
    }

    @Test
    fun present_secondTimezone_decodesSignedOffset() {
        assertEquals(330, ConfigToSeed.seed(listOf(secondTz(330))).secondTimezoneOffsetMinutes)
        assertEquals(-300, ConfigToSeed.seed(listOf(secondTz(-300 and 0xFFFF))).secondTimezoneOffsetMinutes)
    }

    @Test
    fun present_secondTimezone_disabledSentinelIsUnset() {
        // 1024 is the watch's "DISABLED" sentinel → null (unset).
        assertNull(ConfigToSeed.seed(listOf(secondTz(1024))).secondTimezoneOffsetMinutes)
    }

    @Test
    fun multipleEntries_allMapped() {
        val s = ConfigToSeed.seed(
            listOf(
                vibe(75),
                stepGoal(8000),
                nudge(8, 30, 20, 0, 30, enabled = true),
                secondTz(60),
            )
        )
        assertEquals(75, s.vibrationStrength)
        assertEquals(8000, s.stepGoal)
        assertTrue(s.nudgeEnabled)
        assertEquals(30, s.nudgeMinutes)
        assertEquals(60, s.secondTimezoneOffsetMinutes)
    }

    // ---- absent → constant / off / null ------------------------------------------------------

    @Test
    fun absent_everything_fallsBackToConstantsAndOff() {
        val s = ConfigToSeed.seed(emptyList())
        assertEquals(ConfigToSeed.DEFAULT_VIBRATION_STRENGTH, s.vibrationStrength) // 50
        assertEquals(ConfigToSeed.DEFAULT_STEP_GOAL, s.stepGoal)                    // 10000
        assertFalse(s.nudgeEnabled)
        assertNull(s.nudgeMinutes)
        assertNull(s.secondTimezoneOffsetMinutes)
    }

    @Test
    fun nullEntries_fallsBackSafely() {
        val s = ConfigToSeed.seed(null)
        assertEquals(50, s.vibrationStrength)
        assertEquals(10000, s.stepGoal)
        assertFalse(s.nudgeEnabled)
    }

    @Test
    fun absentVibration_butPresentStepGoal_eachIndependent() {
        val s = ConfigToSeed.seed(listOf(stepGoal(6000)))
        assertEquals(50, s.vibrationStrength)
        assertEquals(6000, s.stepGoal)
    }

    // ---- malformed / short rawData → safe fallback (never throws) ----------------------------

    @Test
    fun shortVibrationRaw_fallsBackToConstant() {
        val s = ConfigToSeed.seed(listOf(entry(0x0A, "VIBE_STRENGTH"))) // empty rawData
        assertEquals(50, s.vibrationStrength)
    }

    @Test
    fun shortStepGoalRaw_fallsBackToConstant() {
        val s = ConfigToSeed.seed(listOf(entry(0x03, "DAILY_STEP_GOAL", 0x10, 0x27))) // only 2 bytes
        assertEquals(10000, s.stepGoal)
    }

    @Test
    fun shortNudgeRaw_reportsOff() {
        val s = ConfigToSeed.seed(listOf(entry(0x09, "INACTIVE_NUDGE", 1, 2, 3))) // only 3 bytes
        assertFalse(s.nudgeEnabled)
        assertNull(s.nudgeMinutes)
    }

    @Test
    fun shortSecondTzRaw_isUnset() {
        val s = ConfigToSeed.seed(listOf(entry(0x11, "SECOND_TIMEZONE_OFFSET", 0x4A))) // 1 byte
        assertNull(s.secondTimezoneOffsetMinutes)
    }

    // ---- out-of-range vibration clamped ------------------------------------------------------

    @Test
    fun vibrationAbove100_clampedTo100() {
        // raw byte 0xFF = 255 → clamp to 100.
        val s = ConfigToSeed.seed(listOf(entry(0x0A, "VIBE_STRENGTH", 0xFF)))
        assertEquals(100, s.vibrationStrength)
    }

    @Test
    fun vibrationZero_isAllowed() {
        val s = ConfigToSeed.seed(listOf(vibe(0)))
        assertEquals(0, s.vibrationStrength)
    }

    // ---- device info passthrough -------------------------------------------------------------

    @Test
    fun deviceInfo_passedThroughOntoSeed() {
        val di = ConfigToSeed.DeviceInfo(model = "HW2.0", firmwareVersion = "1.2.3", batteryLevel = 88)
        val s = ConfigToSeed.seed(listOf(vibe(60)), di)
        assertEquals("HW2.0", s.deviceInfo.model)
        assertEquals("1.2.3", s.deviceInfo.firmwareVersion)
        assertEquals(88, s.deviceInfo.batteryLevel)
    }

    @Test
    fun deviceInfo_absentDefaultsToEmpty() {
        val s = ConfigToSeed.seed(listOf(vibe(60)))
        assertNull(s.deviceInfo.model)
        assertNull(s.deviceInfo.firmwareVersion)
        assertNull(s.deviceInfo.batteryLevel)
    }
}
