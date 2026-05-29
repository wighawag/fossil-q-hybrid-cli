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
    val lastSyncTime: Long = 0
)
