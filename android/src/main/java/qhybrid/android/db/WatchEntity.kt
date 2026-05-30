package qhybrid.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WP4 — registry of every watch the user owns (ANDROID-PLAN §3, entity #1).
 *
 * This is the SOURCE OF TRUTH for watch identity and state. WP3's
 * [qhybrid.android.CompanionManager] SharedPreferences "associated_mac" pref is kept
 * as the lightweight "which MAC do we auto-reconnect to" pointer (read cheaply at boot
 * by BootReceiver), DELIBERATELY left untouched so the hardware-verified WP3
 * connect/reconnect/boot flow cannot be disturbed by adding Room.
 *
 * Exactly one watch should have [isActive] == true at a time; see
 * [WatchDao.setActive] which enforces this transactionally.
 */
@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey val macAddress: String,
    val name: String,
    val model: String?,
    val firmwareVersion: String?,
    val batteryLevel: Int,
    val isActive: Boolean = false, // Active watch receives live notifications
    val stepGoal: Int = 10000,
    val vibrationStrength: Int = 50,
    val lastSyncTime: Long = 0,
    // WP-SYNCSTATUS — per-section "this section's file was last (re-)pushed to the watch at" stamps.
    // Set when a sync pass reports the section in SyncResult.performed (see
    // WatchConnectionService.runOnConnectSync). Compared against each row's updatedAt by
    // [qhybrid.android.sync.SectionSyncStatus.isOnWatch] to decide "is this row on the watch?".
    // 0 = that section has never been synced to this watch (→ every row is pending).
    val alarmsSyncedAt: Long = 0,
    val notificationFilterSyncedAt: Long = 0,
    val buttonsSyncedAt: Long = 0,
)
