package qhybrid.android.sync

import qhybrid.protocol.model.NotificationFilterEntry

/**
 * WP14 — the narrow "uploader" seam the pure [SyncOrchestrator] drives. This is the
 * provable-core boundary (mirrors the WP16 `*Sync`/`*Source` pattern): the orchestrator
 * decides *what* to compile and *in what order*, then calls these methods; the actual BLE
 * write lives behind the production impl ([qhybrid.android.WatchConnectionService]'s
 * `ServiceUploader`), so the decision logic is JVM/Robolectric-unit-testable against a fake.
 *
 * All payloads are produced by the golden-tested protocol compilers/façade (WP5/6/7 +
 * `FossilController` settings methods) — the orchestrator invents NO new wire bytes; it only
 * hands already-compiled bytes / typed config to the uploader.
 *
 * Every method returns whether the upload was actually *performed* (true) vs. *intentionally
 * skipped because there was nothing to push* (false), so [SyncOrchestrator] can build an honest
 * [SyncResult].
 *
 * **Contract (do NOT conflate the two failure-ish outcomes):** `false` means ONLY "I had nothing
 * to upload" — it MUST NOT be used for a write that was attempted and failed/timed out. A genuine
 * upload FAILURE must THROW, so [SyncOrchestrator.runSection] records it as a [SyncError] (→
 * SyncState ERROR / partial-failure UI) instead of misclassifying it as a clean skip. A silent
 * `false` on a failed BLE put would be reported as SUCCESS, never stamp the section's
 * `…SyncedAt`, and leave the row "not synced" with NO error shown — the exact bug this contract
 * prevents.
 */
interface Uploader {

    /**
     * Upload a compiled alarm file (legacy 3-bytes-per-alarm format from WP5
     * [qhybrid.protocol.requests.fossil.alarm.AlarmCompiler]). [byteCount] is passed for
     * logging; the bytes are the source of truth.
     */
    fun uploadAlarms(alarmFile: ByteArray): Boolean

    /**
     * Upload the notification filter (one 32-byte entry per [NotificationFilterEntry], WP6
     * [qhybrid.protocol.requests.fossil.notification.NotificationCompiler]).
     */
    fun uploadNotificationFilter(entries: List<NotificationFilterEntry>): Boolean

    /**
     * Upload a compiled button-config file (SETTINGS_BUTTONS 0x0600, WP7
     * [qhybrid.protocol.requests.fossil.button.ButtonCompiler]).
     */
    fun uploadButtons(buttonConfigFile: ByteArray): Boolean

    /** Apply the live vibration-strength config (0–100; ConfigurationPutRequest item 0x0A). */
    fun applyVibrationStrength(strength: Int): Boolean

    /** Apply the live inactivity-nudge config (ConfigurationPutRequest item 0x09). */
    fun applyInactivityNudge(
        fromHour: Int,
        fromMinute: Int,
        toHour: Int,
        toMinute: Int,
        inactiveMinutes: Int,
        enabled: Boolean,
    ): Boolean

    /** Apply the live second-timezone offset (minutes from UTC; ConfigurationPutRequest item 0x11). */
    fun applySecondTimezone(offsetMinutes: Int): Boolean
}
