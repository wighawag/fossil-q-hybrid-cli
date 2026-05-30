package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchEntity
import qhybrid.protocol.model.NotificationFilterEntry

/**
 * WP-BUZZ-PLAYONLY-SIMPLIFY — regression guard for the connect-time upload POLICY.
 *
 * The standalone per-connect reserved-buzz-filter upload was removed: the reserved buzz entries
 * now reach the watch ONLY via (a) new-watch provisioning and (b) the notification-sync fold-in.
 * The whole-file NOTIFICATION_FILTER (0x0C00) put must therefore be issued on connect ONLY when
 * provisioning a brand-new watch — NEVER on a known watch's reconnect.
 *
 * This composes the two PURE pieces the service wires together on a successful connect —
 * [ConnectSyncDecider.decide] then (when it returns a Sync) [SyncOrchestrator.sync] — against a
 * recording [Uploader], and asserts the NOTIFICATION_FILTER upload count per scenario. No Android
 * service, no BLE, no new infra: it exercises the exact decision seam the connect path uses.
 */
class ConnectUploadPolicyTest {

    /** Minimal recording uploader: we only care whether/how often the filter (0x0C00) is put. */
    private class RecordingUploader : Uploader {
        var filterUploads = 0
        override fun uploadAlarms(alarmFile: ByteArray) = true
        override fun uploadNotificationFilter(entries: List<NotificationFilterEntry>): Boolean {
            filterUploads++; return true
        }
        override fun uploadButtons(buttonConfigFile: ByteArray) = true
        override fun applyVibrationStrength(strength: Int) = true
        override fun applyInactivityNudge(
            fromHour: Int, fromMinute: Int, toHour: Int, toMinute: Int,
            inactiveMinutes: Int, enabled: Boolean,
        ) = true
        override fun applySecondTimezone(offsetMinutes: Int) = true
    }

    private fun watch(mac: String = "AA:00:00:00:00:01") =
        WatchEntity(
            macAddress = mac, name = "W", model = null, firmwareVersion = null,
            batteryLevel = 0, isActive = true, vibrationStrength = 50,
        )

    private fun rule(pkg: String) =
        NotificationRuleEntity("AA:00:00:00:00:01", pkg, 2, 90, 180)

    /** Mirror the service's connect hook: decide, then sync only if the decision says so. */
    private fun runConnect(
        up: Uploader,
        input: SyncInput,
        hadPendingSync: Boolean,
        isNewWatch: Boolean,
    ): ConnectSyncDecider.Decision {
        val d = ConnectSyncDecider.decide(
            hadPendingSync = hadPendingSync,
            requestedSections = null,
            isNewWatch = isNewWatch,
        )
        if (d is ConnectSyncDecider.Decision.Sync) {
            SyncOrchestrator.sync(input, up, d.sections)
        }
        return d
    }

    // ---- known watch: NO filter put on connect (the regression we removed) ----

    @Test
    fun knownWatchReconnect_issuesNoNotificationFilterPut() {
        val up = RecordingUploader()
        // A known watch WITH synced app rules — the old reserved-only upload would have wiped these.
        val input = SyncInput(watch = watch(), rules = listOf(rule("com.example.app")))
        val d = runConnect(up, input, hadPendingSync = false, isNewWatch = false)

        assertEquals(ConnectSyncDecider.Decision.None, d)
        assertEquals("known-watch reconnect must not put the filter (0x0C00)", 0, up.filterUploads)
    }

    @Test
    fun knownWatchReconnect_noRules_stillNoFilterPut() {
        val up = RecordingUploader()
        val d = runConnect(up, SyncInput(watch = watch()), hadPendingSync = false, isNewWatch = false)

        assertEquals(ConnectSyncDecider.Decision.None, d)
        assertEquals(0, up.filterUploads)
    }

    // ---- new watch: provisioning DOES put the filter (with rules present) -----

    @Test
    fun newWatchProvisioning_putsNotificationFilterOnce() {
        val up = RecordingUploader()
        val input = SyncInput(watch = watch(), rules = listOf(rule("com.example.app")))
        val d = runConnect(up, input, hadPendingSync = false, isNewWatch = true)

        assertTrue(d is ConnectSyncDecider.Decision.Sync)
        assertEquals("provisioning a brand-new watch puts the filter exactly once", 1, up.filterUploads)
    }

    @Test
    fun newWatchProvisioning_noRules_skipsFilterPut() {
        // With no notification rules the orchestrator skips the (empty) filter file. The reserved
        // entries still ride along on the FIRST real notification sync via the fold-in (covered by
        // the ServiceUploader fold-in tests), not by an empty provisioning filter.
        val up = RecordingUploader()
        val d = runConnect(up, SyncInput(watch = watch()), hadPendingSync = false, isNewWatch = true)

        assertTrue(d is ConnectSyncDecider.Decision.Sync)
        assertEquals(0, up.filterUploads)
    }

    // ---- pending user sync on a known watch: honoured (not a per-connect put) -

    @Test
    fun pendingUserSyncOnKnownWatch_putsFilterBecauseUserAskedNotPerConnect() {
        // A user-requested sync (e.g. Save-to-watch while the link was down) is NOT the per-connect
        // reserved-only upload — it's the user's own sync, which correctly folds reserved entries in.
        val up = RecordingUploader()
        val input = SyncInput(watch = watch(), rules = listOf(rule("com.example.app")))
        val d = runConnect(up, input, hadPendingSync = true, isNewWatch = false)

        assertTrue(d is ConnectSyncDecider.Decision.Sync)
        assertEquals(1, up.filterUploads)
    }

    @Test
    fun knownWatchReconnect_isNotASync() {
        // Belt-and-braces: the only path that uploads on connect is a Sync decision; a known watch
        // never produces one, so it can never put any file on connect.
        val d = ConnectSyncDecider.decide(hadPendingSync = false, requestedSections = null, isNewWatch = false)
        assertFalse(d is ConnectSyncDecider.Decision.Sync)
    }
}
