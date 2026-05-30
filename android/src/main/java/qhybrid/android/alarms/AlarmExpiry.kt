package qhybrid.android.alarms

import qhybrid.android.db.WatchAlarmEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure helper to decide whether a **one-off** alarm has already fired (its single occurrence is in
 * the past) so the UI can show it as auto-deactivated once its time has passed.
 *
 * This is a **display-only** concern: the watch itself drops a one-off alarm after it fires, but
 * the row stays in our DB. Rather than mutate Room (which would create a spurious pending sync),
 * we DERIVE an "effectively off" state in the UI from the alarm's set-time ([WatchAlarmEntity.updatedAt],
 * the wall-clock millis when the row was last written) and the wire days convention ([AlarmDays]).
 *
 * Applies to the two one-off shapes the editor can produce ([AlarmsScreen]'s editor):
 *   - **Plain one-off** (`!isRepeating` and `daysMask == 0`): fires once at the next occurrence of
 *     hour:minute on/after the moment it was set.
 *   - **Single-weekday one-off** (`!isRepeating` and exactly one day bit set): fires once at the
 *     next occurrence of that weekday at hour:minute on/after the moment it was set.
 *
 * Repeating alarms (weekly, multi-day, or the explicit single-day "Repeats weekly") never expire.
 */
object AlarmExpiry {

    /**
     * A conservative safety margin (ms) for the UPLOAD-suppression caller so we never disable a
     * one-off the WATCH hasn't actually fired yet when the phone clock runs ahead of the watch's.
     * Suppressing too early would silently kill a valid alarm (the worst outcome); re-arming a
     * one-off slightly late at worst lets it buzz once more, which is recoverable. 10 minutes
     * comfortably absorbs realistic phone↔watch clock skew. The UI display passes 0 (exact) because
     * showing "passed" a few minutes early is purely cosmetic.
     */
    const val UPLOAD_SUPPRESS_GRACE_MS: Long = 10 * 60 * 1000L

    /**
     * True iff [alarm] is a one-off whose single scheduled occurrence is at/before
     * `nowMillis - graceMillis`. Repeating alarms, and one-offs whose occurrence is still in the
     * future (or within the grace window), return false.
     *
     * [graceMillis] lets the upload path stay CONSERVATIVE under phone↔watch clock skew: only treat
     * a one-off as fired once we're confidently past its time by [graceMillis]. The UI passes 0
     * (exact boundary) since an early "passed" label is only cosmetic. Must be >= 0.
     *
     * [nowMillis] / [zone] are injected so this is deterministic and unit-testable.
     */
    fun hasPassed(
        alarm: WatchAlarmEntity,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        graceMillis: Long = 0L,
    ): Boolean {
        if (alarm.isRepeating) return false
        val occurrence = nextOccurrenceMillis(alarm, zone) ?: return false
        // Strictly past the occurrence plus the grace margin (so we don't disable an alarm the watch
        // may not have fired yet). At exactly occurrence+grace we consider it fired.
        return nowMillis - graceMillis.coerceAtLeast(0L) >= occurrence
    }

    /**
     * The wall-clock millis of the one-off's single firing, computed as the FIRST occurrence of the
     * alarm's hour:minute (optionally constrained to a single weekday) on/after the moment the row
     * was set ([WatchAlarmEntity.updatedAt]). Returns null for repeating alarms or masks that are
     * not a recognised one-off shape (0 or >1 day bits with multiple days = weekly).
     */
    fun nextOccurrenceMillis(alarm: WatchAlarmEntity, zone: ZoneId): Long? {
        if (alarm.isRepeating) return null
        val mask = alarm.daysMask and AlarmDays.EVERYDAY
        val dayCount = AlarmDays.dayCount(mask)
        if (dayCount > 1) return null // 2+ days is inherently weekly, not a one-off

        val setAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(alarm.updatedAt), zone)
        // Earliest candidate: today (relative to set time) at the alarm's hour:minute.
        var candidate = setAt
            .withHour(alarm.hour)
            .withMinute(alarm.minute)
            .withSecond(0)
            .withNano(0)
        // If that instant is at/after the set time it's valid for "today"; else roll to tomorrow.
        if (!candidate.isAfter(setAt)) candidate = candidate.plusDays(1)

        if (dayCount == 1) {
            // Constrain to the single selected weekday: roll forward up to 7 days until the
            // weekday matches the chosen day bit.
            val targetBit = mask
            var guard = 0
            while (dayBitFor(candidate) != targetBit && guard < 8) {
                candidate = candidate.plusDays(1)
                guard++
            }
        }
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }

    /** The [AlarmDays] wire bit (bit0=Sun … bit6=Sat) for [dt]'s day-of-week. */
    private fun dayBitFor(dt: LocalDateTime): Int {
        // java.time DayOfWeek: MONDAY=1 … SUNDAY=7. AlarmDays bit order is Sun-first.
        // SUN=bit0, MON=bit1 … SAT=bit6.
        val isoMondayBased = dt.dayOfWeek.value // 1..7 (Mon..Sun)
        val sunFirstIndex = if (isoMondayBased == 7) 0 else isoMondayBased // Sun→0, Mon→1 … Sat→6
        return 1 shl sunFirstIndex
    }
}
