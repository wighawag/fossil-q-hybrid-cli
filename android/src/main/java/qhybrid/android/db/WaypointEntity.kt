package qhybrid.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WP-TRACKER \u2014 a single logged GPS waypoint (ANDROID: GPS waypoint tracker feature).
 *
 * Captured either by a TRACKER-role multi-function gesture on the 0x05 stream (short=MINOR,
 * double=MAJOR) or by a Path-2 single-press LOG_WAYPOINT button (0x08 `type:"button"` event). Pure
 * persistence \u2014 no BLE, no GPS here; the GPS fix is captured behind a seam in the tracker shell and
 * the resulting lat/lon written through [WatchRepository].
 *
 * NOT FK-bound to a watch: a waypoint outlives the watch it was logged with (and the user may log
 * one with no active watch row). [watchMac] is therefore an optional informational column, not a
 * foreign key \u2014 deleting a watch does NOT cascade-delete its waypoints.
 *
 * [kind] is one of [qhybrid.android.tracker.TrackerController.WaypointKind] (MINOR / MAJOR), stored
 * as its enum name string.
 */
@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The watch that logged this point, if any (informational; NOT a foreign key). */
    @ColumnInfo(index = true) val watchMac: String? = null,
    /** "MINOR" | "MAJOR" \u2014 [qhybrid.android.tracker.TrackerController.WaypointKind] name. */
    val kind: String,
    val lat: Double,
    val lon: Double,
    /** GPS horizontal accuracy in metres, or null if unknown. */
    val accuracyM: Float? = null,
    /** Epoch millis the fix was captured. */
    val capturedAt: Long,
    /** Optional free-form note. */
    val note: String? = null,
)
