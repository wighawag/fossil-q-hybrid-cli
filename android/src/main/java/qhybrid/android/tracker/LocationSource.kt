package qhybrid.android.tracker

/**
 * WP-TRACKER \u2014 narrow, injectable seam over the device GPS so the tracker logic stays testable with
 * a fake fix. The production impl ([ServiceTrackerDispatch.SystemLocationSource]) reads the
 * platform fused location (last-known + a single high-accuracy update with a timeout); tests inject
 * a fake that returns a canned [Fix] (or null) so [ServiceTrackerDispatch] / the dispatcher path is
 * verified without GPS.
 *
 * The live fused provider is **on-device-pending** (it needs ACCESS_FINE_LOCATION granted + a real
 * device); the seam keeps everything above it unit-tested.
 */
interface LocationSource {
    /** A captured GPS fix. */
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?, val timestamp: Long)

    /**
     * Capture the current best GPS fix, or `null` if unavailable (no permission / no fix / timeout).
     * Must be safe to call off the main thread; blocking is acceptable (the shell calls it on a
     * worker). Never throws.
     */
    fun currentFix(): Fix?

    companion object {
        /** WP-TRACKER: whether the live fused-location source is wired (on-device-pending). */
        const val LOCATION_WIRED = false
    }
}

/** A no-op [LocationSource] that always returns null \u2014 the safe default until GPS is wired. */
object NoopLocationSource : LocationSource {
    override fun currentFix(): LocationSource.Fix? = null
}
