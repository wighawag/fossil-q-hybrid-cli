package qhybrid.android.navcue

import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.navcue.TurnCueMapper.Maneuver
import qhybrid.android.navcue.TurnCueMapper.Stage
import qhybrid.protocol.requests.fossil.notification.NavCuePatterns

/**
 * WP-NAV — guards the reserved nav-cue filter set against the pure mapper: EVERY cue the mapper can
 * emit must have a matching reserved entry on the watch (else a play-only cue silently no-ops). This
 * is the test that would have caught the original "no vibration/hands" bug (the cue path needs a
 * reserved filter entry, exactly like buzz).
 */
class NavCuePatternsCoverageTest {

    @Test
    fun everyEmittableCueHasAReservedEntry() {
        val reservedKeys = NavCuePatterns.reservedEntries()
            .map { it.hourDeg.toInt() to it.vibe.toInt() }
            .toSet()

        for (m in Maneuver.values()) {
            for (stage in Stage.values()) {
                val cue = TurnCueMapper.decide(m, stage) ?: continue
                val key = cue.hourDeg to cue.buzzPattern
                assertTrue(
                    "no reserved nav-cue entry for $m/$stage → deg=${cue.hourDeg} vibe=${cue.buzzPattern}",
                    key in reservedKeys,
                )
                // Both hands equal, so the entry's minDeg matches too (entries set min == hour).
                assertTrue(cue.hourDeg == cue.minDeg)
            }
        }
    }

    @Test
    fun reservedPackageNamesAreStableAndDistinct() {
        val names = NavCuePatterns.reservedEntries().map { it.packageName }
        assertTrue("package names must be unique", names.size == names.toSet().size)
        // Sanity: a known cue (turn LEFT now = 270° + ONE_LONG(8)).
        assertTrue("qhybrid.linux.nav.270.8" in names)
    }
}
