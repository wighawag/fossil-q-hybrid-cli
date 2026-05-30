package qhybrid.android.onboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.settings.AppSettings

/**
 * WP-ONBOARD — unit tests for [PrefSeedDecision]: seed the global nudge / 2nd-timezone prefs from a
 * new watch's read-back, but ONLY when the watch actually HAS the value set. A watch reporting nudge
 * OFF / 2nd-tz UNSET must NOT clobber an existing global pref.
 */
class PrefSeedDecisionTest {

    private fun seeded(
        nudgeEnabled: Boolean = false,
        nudgeMinutes: Int? = null,
        secondTz: Int? = null,
    ) = ConfigToSeed.SeededSettings(
        nudgeEnabled = nudgeEnabled,
        nudgeMinutes = nudgeMinutes,
        secondTimezoneOffsetMinutes = secondTz,
    )

    // ---- nudge ------------------------------------------------------------------------------

    @Test
    fun nudgeEnabledOnWatch_isSeeded() {
        val d = PrefSeedDecision.decide(seeded(nudgeEnabled = true, nudgeMinutes = 45))
        assertEquals(PrefSeedDecision.NudgeWrite(enabled = true, minutes = 45), d.nudge)
        assertTrue(d.writesAnything)
    }

    @Test
    fun nudgeOffOnWatch_leavesPrefUntouched() {
        val d = PrefSeedDecision.decide(seeded(nudgeEnabled = false, nudgeMinutes = null))
        assertNull("watch's nudge OFF must not clobber the global pref", d.nudge)
    }

    @Test
    fun nudgeEnabledButNoMinutes_leavesPrefUntouched() {
        // Defensive: enabled flag without a concrete minutes value is not enough to seed.
        val d = PrefSeedDecision.decide(seeded(nudgeEnabled = true, nudgeMinutes = null))
        assertNull(d.nudge)
    }

    // ---- second timezone --------------------------------------------------------------------

    @Test
    fun secondTzSetOnWatch_isSeeded() {
        val d = PrefSeedDecision.decide(seeded(secondTz = 330))
        assertEquals(330, d.secondTimezoneOffsetMinutes)
        assertTrue(d.writesAnything)
    }

    @Test
    fun secondTzNegativeOffset_isSeeded() {
        val d = PrefSeedDecision.decide(seeded(secondTz = -300))
        assertEquals(-300, d.secondTimezoneOffsetMinutes)
    }

    @Test
    fun secondTzZeroOffset_isSeeded() {
        // 0 (UTC) is a concrete, valid offset — distinct from "unset" (null).
        val d = PrefSeedDecision.decide(seeded(secondTz = 0))
        assertEquals(0, d.secondTimezoneOffsetMinutes)
        assertTrue(d.writesAnything)
    }

    @Test
    fun secondTzUnsetOnWatch_leavesPrefUntouched() {
        val d = PrefSeedDecision.decide(seeded(secondTz = null))
        assertNull("watch's UNSET 2nd-tz must not clobber the global pref", d.secondTimezoneOffsetMinutes)
    }

    // ---- combinations -----------------------------------------------------------------------

    @Test
    fun bothSet_bothSeeded() {
        val d = PrefSeedDecision.decide(seeded(nudgeEnabled = true, nudgeMinutes = 30, secondTz = 60))
        assertEquals(PrefSeedDecision.NudgeWrite(true, 30), d.nudge)
        assertEquals(60, d.secondTimezoneOffsetMinutes)
        assertTrue(d.writesAnything)
    }

    @Test
    fun neitherSet_writesNothing() {
        val d = PrefSeedDecision.decide(seeded())
        assertFalse(d.writesAnything)
        assertNull(d.nudge)
        assertNull(d.secondTimezoneOffsetMinutes)
    }

    @Test
    fun oneSetOneUnset_writesOnlyTheSetOne() {
        val d = PrefSeedDecision.decide(seeded(nudgeEnabled = true, nudgeMinutes = 15, secondTz = null))
        assertEquals(PrefSeedDecision.NudgeWrite(true, 15), d.nudge)
        assertNull(d.secondTimezoneOffsetMinutes)
        assertTrue(d.writesAnything)
    }

    @Test
    fun decision_ignoresCurrentPrefsForConservativePolicy() {
        // The current policy seeds purely from what the watch HAS; current prefs don't change it.
        val custom = AppSettings(nudgeEnabled = true, nudgeMinutes = 99, secondTimezoneOffsetMinutes = 120)
        val d = PrefSeedDecision.decide(seeded(secondTz = 60), custom)
        assertNull(d.nudge)
        assertEquals(60, d.secondTimezoneOffsetMinutes)
    }
}
