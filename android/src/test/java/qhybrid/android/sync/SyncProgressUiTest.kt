package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-PROGRESS (sub-part 3) — headless tests for the pure [SyncProgressUi.from] mapping the Save
 * button + status row render. Proves the spinner/disable/note state mapping WITHOUT Compose or a
 * device (the actual `CircularProgressIndicator` rendering is on-device-pending).
 */
class SyncProgressUiTest {

    private fun status(
        phase: SyncState.SyncPhase,
        result: SyncResult? = null,
        errorMessage: String? = null,
    ) = SyncState.SyncStatus(phase = phase, lastResult = result, errorMessage = errorMessage)

    private fun result(errors: List<SyncError> = emptyList()) =
        SyncResult("AA:00:00:00:00:01", listOf(SyncSection.BUTTONS), emptyList(), errors)

    @Test
    fun idle_isNoSpinnerNoNote() {
        val ui = SyncProgressUi.from(status(SyncState.SyncPhase.IDLE))
        assertFalse(ui.syncing)
        assertNull(ui.note)
        assertEquals(SyncProgressUi.Tone.NONE, ui.tone)
    }

    @Test
    fun syncing_spinnerAndDisablesSave() {
        val ui = SyncProgressUi.from(status(SyncState.SyncPhase.SYNCING))
        assertTrue(ui.syncing)
        assertEquals("Saving to watch…", ui.note)
        // Disabled while syncing, even with an active watch.
        assertFalse(ui.saveEnabled(hasActiveWatch = true))
    }

    @Test
    fun successClean_isSuccessNote() {
        val ui = SyncProgressUi.from(status(SyncState.SyncPhase.SUCCESS, result = result()))
        assertFalse(ui.syncing)
        assertEquals("Saved to watch.", ui.note)
        assertEquals(SyncProgressUi.Tone.SUCCESS, ui.tone)
        assertTrue(ui.saveEnabled(hasActiveWatch = true))
    }

    @Test
    fun successWithSectionErrors_isWarningNotBlanketSuccess() {
        val ui = SyncProgressUi.from(
            status(
                SyncState.SyncPhase.SUCCESS,
                result = result(errors = listOf(SyncError(SyncSection.ALARMS, "too many"))),
            ),
        )
        assertFalse(ui.syncing)
        assertEquals(SyncProgressUi.Tone.WARNING, ui.tone)
        assertTrue(ui.note!!.contains("alarms")) // honest: names the failed section
    }

    @Test
    fun error_isErrorNoteWithMessage() {
        val ui = SyncProgressUi.from(
            status(SyncState.SyncPhase.ERROR, errorMessage = "link lost"),
        )
        assertFalse(ui.syncing)
        assertEquals(SyncProgressUi.Tone.ERROR, ui.tone)
        assertTrue(ui.note!!.contains("link lost"))
    }

    @Test
    fun saveDisabledWhenNoActiveWatch_evenWhenIdle() {
        val ui = SyncProgressUi.from(status(SyncState.SyncPhase.IDLE))
        assertFalse(ui.saveEnabled(hasActiveWatch = false))
        assertTrue(ui.saveEnabled(hasActiveWatch = true))
    }
}
