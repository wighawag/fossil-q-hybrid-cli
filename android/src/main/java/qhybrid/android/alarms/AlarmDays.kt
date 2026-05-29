package qhybrid.android.alarms

/**
 * WP16b — centralized day-of-week bitmask constants, shared by the ViewModel, the tests,
 * and the Compose UI so the day-picker chips and the shortcuts cannot drift apart.
 *
 * The convention is **identical to WP5** ([qhybrid.protocol.requests.fossil.alarm.AlarmSlot]
 * and [qhybrid.android.db.WatchAlarmEntity.daysMask]): it IS the hardware wire `days` byte
 * 1:1 — NO bit-order translation (FINDINGS #12):
 *
 * ```
 * bit0=Sun, bit1=Mon, bit2=Tue, bit3=Wed, bit4=Thu, bit5=Fri, bit6=Sat
 * ```
 *
 * Do not invent a different ordering anywhere in the UI — read/write [WatchAlarmEntity.daysMask]
 * straight through, and let WP5's [AlarmCompiler] emit the wire byte unchanged.
 */
object AlarmDays {
    const val SUN = 1 shl 0 // 0x01
    const val MON = 1 shl 1 // 0x02
    const val TUE = 1 shl 2 // 0x04
    const val WED = 1 shl 3 // 0x08
    const val THU = 1 shl 4 // 0x10
    const val FRI = 1 shl 5 // 0x20
    const val SAT = 1 shl 6 // 0x40

    /** Mon–Fri = bits 1..5 = 0x3E. */
    const val WEEKDAY = MON or TUE or WED or THU or FRI // 0x3E

    /** Sat + Sun = bit6 | bit0 = 0x41. */
    const val WEEKEND = SAT or SUN // 0x41

    /** All seven days = 0x7F. */
    const val EVERYDAY = SUN or MON or TUE or WED or THU or FRI or SAT // 0x7F

    /** The seven single-day bits in display order (Sun-first to match bit order). */
    val ALL_BITS = intArrayOf(SUN, MON, TUE, WED, THU, FRI, SAT)

    /** Short labels aligned 1:1 with [ALL_BITS]. */
    val SHORT_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** Toggle a single-day bit in [mask] and return the new mask (masked to 7 bits). */
    fun toggle(mask: Int, dayBit: Int): Int = (mask xor dayBit) and EVERYDAY

    /** Human summary for a days mask, e.g. "Weekdays", "Every day", "Mon, Wed, Fri", "Once". */
    fun summary(mask: Int, repeating: Boolean): String {
        val m = mask and EVERYDAY
        if (!repeating) return if (m == 0) "Once" else "Once · ${dayList(m)}"
        return when (m) {
            EVERYDAY -> "Every day"
            WEEKDAY -> "Weekdays"
            WEEKEND -> "Weekends"
            0 -> "No days"
            else -> dayList(m)
        }
    }

    private fun dayList(mask: Int): String =
        ALL_BITS.indices
            .filter { (mask and ALL_BITS[it]) != 0 }
            .joinToString(", ") { SHORT_LABELS[it] }
}
