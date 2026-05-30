package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WP-PULLSYNC — unit tests for the pure on-connect sync decision: sync is user-initiated, the
 * only automatic case is provisioning a brand-new watch.
 */
class ConnectSyncDeciderTest {

    @Test
    fun knownWatchReconnect_doesNotSync() {
        val d = ConnectSyncDecider.decide(
            hadPendingSync = false,
            requestedSections = null,
            isNewWatch = false,
        )
        assertEquals(ConnectSyncDecider.Decision.None, d)
    }

    @Test
    fun newWatch_runsFullProvisioningSync() {
        val d = ConnectSyncDecider.decide(
            hadPendingSync = false,
            requestedSections = null,
            isNewWatch = true,
        )
        assertEquals(ConnectSyncDecider.Decision.Sync(SyncSection.ALL, "new watch — one-time provisioning full sync"), d)
    }

    @Test
    fun pendingUserSync_honouredWithRequestedSections() {
        val d = ConnectSyncDecider.decide(
            hadPendingSync = true,
            requestedSections = SyncSection.SETTINGS_ONLY,
            isNewWatch = false,
        )
        assertEquals(
            ConnectSyncDecider.Decision.Sync(SyncSection.SETTINGS_ONLY, "user-requested sync on connect"),
            d,
        )
    }

    @Test
    fun pendingUserSync_nullSections_defaultsToFullReconcile() {
        val d = ConnectSyncDecider.decide(
            hadPendingSync = true,
            requestedSections = null,
            isNewWatch = false,
        )
        assertEquals(
            ConnectSyncDecider.Decision.Sync(SyncSection.ALL, "user-requested sync on connect"),
            d,
        )
    }

    @Test
    fun pendingUserSync_takesPrecedenceOverNewWatch() {
        // A user Save-to-watch while disconnected wins over the new-watch provisioning default
        // (it targets exactly what the user asked for).
        val d = ConnectSyncDecider.decide(
            hadPendingSync = true,
            requestedSections = SyncSection.BUTTONS_ONLY,
            isNewWatch = true,
        )
        assertEquals(
            ConnectSyncDecider.Decision.Sync(SyncSection.BUTTONS_ONLY, "user-requested sync on connect"),
            d,
        )
    }
}
