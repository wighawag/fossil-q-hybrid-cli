// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.alarm;

/**
 * WP5 — platform-neutral alarm domain object (the input to {@link AlarmCompiler}).
 *
 * <p>Mirrors the fields of the Android {@code WatchAlarmEntity} (WP4) so that
 * {@code :android} can map its Room row to this struct without {@code :protocol}
 * ever depending on Android. This class lives in {@code :protocol} so the byte
 * compilation stays pure and JVM-testable.
 *
 * <p><b>Slot ranges (the 15/1/16 split):</b>
 * <ul>
 *   <li>0..14 — standard user alarms (repeating or one-shot)</li>
 *   <li>15 — reserved TIMER slot (multi-function "ring in N min"; one-shot)</li>
 *   <li>16..31 — calendar-sync slots (non-repeating weekday, owned by WP9/WP13)</li>
 * </ul>
 *
 * <p><b>daysMask convention — identical to the hardware wire byte (FINDINGS #12):</b>
 * bit0=Sun, bit1=Mon, bit2=Tue, <b>bit3=Wed, bit4=Thu</b>, bit5=Fri, bit6=Sat.
 * This is the SAME layout as {@code WatchAlarmEntity.daysMask}, so no bit-order
 * translation is needed — the mask is passed straight through as the wire {@code days}
 * byte. (Note: the {@code WEEKDAY_*} constants on {@link Alarm} are mislabeled for
 * Wed/Thu but are not used by the byte path.)
 */
public final class AlarmSlot {
    private final int slotId;       // 0..31
    private final int hour;         // 0..23, local time
    private final int minute;       // 0..59, local time
    private final int daysMask;     // bit0=Sun..bit6=Sat (== wire days byte)
    private final boolean repeating; // true=repeats weekly, false=one-shot
    private final boolean enabled;
    private final String label;

    public AlarmSlot(int slotId, int hour, int minute, int daysMask,
                     boolean repeating, boolean enabled, String label) {
        this.slotId = slotId;
        this.hour = hour;
        this.minute = minute;
        this.daysMask = daysMask;
        this.repeating = repeating;
        this.enabled = enabled;
        this.label = label;
    }

    public int getSlotId() { return slotId; }
    public int getHour() { return hour; }
    public int getMinute() { return minute; }
    public int getDaysMask() { return daysMask; }
    public boolean isRepeating() { return repeating; }
    public boolean isEnabled() { return enabled; }
    public String getLabel() { return label; }

    @Override
    public String toString() {
        return "AlarmSlot{slot=" + slotId + ", " + hour + ":" + String.format("%02d", minute)
                + ", days=0x" + Integer.toHexString(daysMask & 0xFF)
                + ", repeating=" + repeating + ", enabled=" + enabled + "}";
    }
}
