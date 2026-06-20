package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-SYNCSTATUS (Step 3) — the leave-with-pending prompt's pure decision + the tiny shared holder.
 * The dialog/nav is the thin shell (MainActivity); the should-prompt decision is unit-tested here.
 */
@RunWith(RobolectricTestRunner::class)
class LeaveGuardTest {

    // ---- pure decision -------------------------------------------------------

    @Test
    fun shouldPrompt_onlyWhenPending() {
        assertFalse(LeaveGuardLogic.shouldPrompt(0))
        assertTrue(LeaveGuardLogic.shouldPrompt(1))
        assertTrue(LeaveGuardLogic.shouldPrompt(5))
    }

    // ---- the shared holder ---------------------------------------------------

    @Test
    fun publishThenClear_updatesShouldPromptAndSave() {
        val guard = LeaveGuardState()
        // Pristine: nothing pending, no save action, no prompt.
        assertEquals(0, guard.pendingCount)
        assertNull(guard.save)
        assertFalse(guard.shouldPrompt)

        var saved = 0
        guard.publish(pendingCount = 2) { saved++ }
        assertEquals(2, guard.pendingCount)
        assertTrue(guard.shouldPrompt)
        guard.save!!.invoke()
        assertEquals(1, saved)

        // Leaving the screen clears the guard so a non-editable screen never prompts.
        guard.clear()
        assertEquals(0, guard.pendingCount)
        assertNull(guard.save)
        assertFalse(guard.shouldPrompt)
    }

    @Test
    fun publishWithZeroPending_doesNotPrompt() {
        val guard = LeaveGuardState()
        guard.publish(pendingCount = 0) { /* no-op */ }
        assertFalse(guard.shouldPrompt)
    }

    // ---- save-then-leave outcome (FINDINGS "surface watch-side save failure") ----------------

    private val ARMED = 1_000L

    @Test
    fun saveThenLeave_cleanSuccess_leaves() {
        assertEquals(
            LeaveGuardLogic.SaveThenLeave.LEAVE,
            LeaveGuardLogic.saveThenLeave(
                SyncState.SyncPhase.SUCCESS, lastUpdatedMillis = ARMED + 5,
                armedAtMillis = ARMED, hadSectionErrors = false,
            ),
        )
    }

    @Test
    fun saveThenLeave_successWithSectionErrors_staysAndSurfaces() {
        // The watch REJECTED the write (e.g. alarm VERIFY 0x05/0x86): the pass "succeeded" but a
        // section failed — must NOT leave. This is the exact bug: a rejected save let the user leave.
        assertEquals(
            LeaveGuardLogic.SaveThenLeave.STAY_ERROR,
            LeaveGuardLogic.saveThenLeave(
                SyncState.SyncPhase.SUCCESS, lastUpdatedMillis = ARMED + 5,
                armedAtMillis = ARMED, hadSectionErrors = true,
            ),
        )
    }

    @Test
    fun saveThenLeave_errorPhase_stays() {
        assertEquals(
            LeaveGuardLogic.SaveThenLeave.STAY_ERROR,
            LeaveGuardLogic.saveThenLeave(
                SyncState.SyncPhase.ERROR, lastUpdatedMillis = ARMED + 5,
                armedAtMillis = ARMED, hadSectionErrors = false,
            ),
        )
    }

    @Test
    fun saveThenLeave_syncing_waits() {
        assertEquals(
            LeaveGuardLogic.SaveThenLeave.WAIT,
            LeaveGuardLogic.saveThenLeave(
                SyncState.SyncPhase.SYNCING, lastUpdatedMillis = ARMED + 5,
                armedAtMillis = ARMED, hadSectionErrors = false,
            ),
        )
    }

    @Test
    fun saveThenLeave_stalePriorTerminalPhase_waits() {
        // A SUCCESS/ERROR published BEFORE the user armed save-then-leave must be ignored, otherwise
        // an earlier auto-save's outcome could prematurely leave (or block) on a stale signal.
        assertEquals(
            LeaveGuardLogic.SaveThenLeave.WAIT,
            LeaveGuardLogic.saveThenLeave(
                SyncState.SyncPhase.SUCCESS, lastUpdatedMillis = ARMED - 1,
                armedAtMillis = ARMED, hadSectionErrors = false,
            ),
        )
    }
}
