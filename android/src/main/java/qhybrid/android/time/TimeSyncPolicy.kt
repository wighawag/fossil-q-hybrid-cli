package qhybrid.android.time

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * WP-TZSYNC: decides when the watch's time (TimeConfigItem 0x000C, which carries the UTC offset the
 * watch uses for display) must be re-pushed while the link stays up.
 *
 * Previously the time was pushed ONLY on link-up, and the link is long-lived (auto-connect
 * keep-alive), so a timezone change or a DST flip while connected left the watch showing the old
 * offset until the next reconnect. Android broadcasts TIMEZONE_CHANGED / TIME_SET for explicit
 * changes, but sends NOTHING for a DST transition. So the service also arms a Doze-proof alarm at
 * the zone's next offset transition ([nextOffsetChangeMillis]), plus a cheap backstop check on
 * screen-on / unlock and every [OFFSET_CHECK_INTERVAL_MS]. This pure policy keeps those checks from
 * touching BLE unless the offset actually moved.
 */
object TimeSyncPolicy {

    /** How often the service re-checks the phone's UTC offset (DST has no broadcast). */
    const val OFFSET_CHECK_INTERVAL_MS: Long = 15L * 60L * 1000L

    /**
     * TIME_SET can fire repeatedly (NITZ/NTP corrections, and alongside TIMEZONE_CHANGED); a forced
     * push from it is skipped if one already went out this recently.
     */
    const val TIME_SET_DEBOUNCE_MS: Long = 5_000L

    /** Fire the DST alarm this long AFTER the transition, so the new offset is surely in effect. */
    const val TRANSITION_GRACE_MS: Long = 5_000L

    /**
     * Whether to push the time now.
     *
     * @param force an explicit user request or a TIME_SET / TIMEZONE_CHANGED broadcast: always push.
     * @param lastPushedOffsetMinutes the offset last pushed to this link (null = never pushed).
     * @param currentOffsetMinutes the phone's current UTC offset.
     */
    fun shouldPush(force: Boolean, lastPushedOffsetMinutes: Int?, currentOffsetMinutes: Int): Boolean =
        force || lastPushedOffsetMinutes == null || lastPushedOffsetMinutes != currentOffsetMinutes

    /**
     * Whether a TIME_SET broadcast should force a push: only if the last push is older than
     * [TIME_SET_DEBOUNCE_MS] ([msSinceLastPush] null = never pushed on this link).
     */
    fun timeSetForces(msSinceLastPush: Long?): Boolean =
        msSinceLastPush == null || msSinceLastPush >= TIME_SET_DEBOUNCE_MS

    /**
     * Wall-clock millis at which to wake for the zone's next UTC-offset transition (DST start/end)
     * after [nowMillis], plus [TRANSITION_GRACE_MS]; null if the zone has no further transitions.
     */
    fun nextOffsetChangeMillis(
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? {
        val t = zone.rules.nextTransition(Instant.ofEpochMilli(nowMillis)) ?: return null
        return t.instant.toEpochMilli() + TRANSITION_GRACE_MS
    }

    /** The phone's current UTC offset in minutes (same computation the protocol layer sends). */
    fun currentOffsetMinutes(nowMillis: Long = System.currentTimeMillis()): Int =
        TimeZone.getDefault().getOffset(nowMillis) / 60_000
}
