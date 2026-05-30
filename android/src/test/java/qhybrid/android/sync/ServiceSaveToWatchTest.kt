package qhybrid.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-SYNCFIX — headless test for the shared [ServiceSaveToWatch] trigger: it must publish
 * [SyncState] = SYNCING *synchronously* (immediate spinner) the moment Save is tapped, with the
 * injected clock. The service poke itself (connect-then-sync) is on-device-pending; the immediate
 * feedback is the provable part here.
 *
 * A Robolectric [Context] is used only to satisfy the `applicationContext` call; the service start
 * intent is a no-op in the test environment (no service runs), which is exactly the seam we want:
 * the SYNCING publish happens regardless of whether a watch is connected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceSaveToWatchTest {

    @Before fun resetBefore() = SyncState.reset()
    @After fun resetAfter() = SyncState.reset()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun triggerPublishesSyncingImmediatelyWithInjectedClock() {
        assertEquals(SyncState.SyncPhase.IDLE, SyncState.status.value.phase)
        val wired = ServiceSaveToWatch.trigger(context, SyncSection.BUTTONS_ONLY, now = { 777L })
        assertTrue(wired)
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.SYNCING, s.phase)
        assertTrue(s.isSyncing)
        assertEquals(777L, s.lastUpdatedMillis)
    }
}
