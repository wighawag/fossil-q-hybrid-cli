// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.alarm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * WP5 — Alarm domain logic: the 16/16 slot split + byte compilation.
 *
 * <p>Converts a list of {@link AlarmSlot} domain objects into the watch's alarm
 * file bytes (legacy 3-bytes-per-alarm format, {@code fileFormat != 0x03}),
 * honoring the 16/16 slot split and all three hardware-verified wire modes
 * (FINDINGS #12). Pure JVM logic — no Android, no BLE, no hardware.
 *
 * <p><b>Wire modes (3 bytes each):</b>
 * <ul>
 *   <li><b>Standard repeating</b> (slots 0..15, repeating=true):
 *       {@code [0x80|days] [minute|0x80] [hour]}</li>
 *   <li><b>Standard one-shot</b> (slots 0..15, repeating=false):
 *       {@code [0xFF] [minute] [hour]}</li>
 *   <li><b>Calendar non-repeating weekday</b> (slots 16..31):
 *       {@code [0x80|days] [minute] [hour]} — byte1 bit7 NOT set</li>
 * </ul>
 *
 * <p>The standard modes reuse the byte layout already produced (and golden-locked)
 * by {@link Alarm#getData()}; the calendar mode is built directly here because
 * {@link Alarm} cannot emit the undocumented non-repeating-weekday format. No
 * existing protocol wire bytes are changed.
 *
 * <p>The corrected weekday bitmask (FINDINGS #12: bit3=Wed, bit4=Thu) is honored
 * implicitly: {@code daysMask} is the wire {@code days} byte 1:1 (see {@link AlarmSlot}).
 */
public final class AlarmCompiler {

    /** Firmware-fixed 32-entry alarm table (FINDINGS #12: 33+ alarms time out). */
    public static final int MAX_ALARMS = 32;
    public static final int STANDARD_SLOT_MIN = 0;
    public static final int STANDARD_SLOT_MAX = 15;
    public static final int CALENDAR_SLOT_MIN = 16;
    public static final int CALENDAR_SLOT_MAX = 31;

    private AlarmCompiler() {}

    /**
     * Compile standard (slots 0..15) and calendar (slots 16..31) alarm lists into
     * the watch alarm file bytes, honoring the 16/16 split.
     *
     * <p>Disabled alarms are skipped. Output is ordered by ascending {@code slotId}.
     * Slot-range violations and exceeding {@link #MAX_ALARMS} are rejected with
     * {@link IllegalArgumentException} <b>before</b> any bytes are produced.
     *
     * @param standardAlarms alarms destined for slots 0..15 (may be empty/null)
     * @param calendarAlarms alarms destined for slots 16..31 (may be empty/null)
     * @return concatenated 3-bytes-per-enabled-alarm file (legacy format)
     */
    public static byte[] compile(List<AlarmSlot> standardAlarms, List<AlarmSlot> calendarAlarms) {
        List<AlarmSlot> standard = standardAlarms == null ? List.of() : standardAlarms;
        List<AlarmSlot> calendar = calendarAlarms == null ? List.of() : calendarAlarms;

        // --- Validate ranges (reject before producing bytes) ---
        for (AlarmSlot a : standard) {
            requireRange(a, STANDARD_SLOT_MIN, STANDARD_SLOT_MAX, "standard");
        }
        for (AlarmSlot a : calendar) {
            requireRange(a, CALENDAR_SLOT_MIN, CALENDAR_SLOT_MAX, "calendar");
        }

        // --- 32-slot max (FINDINGS #12: counts total entries, not just enabled) ---
        int total = standard.size() + calendar.size();
        if (total > MAX_ALARMS) {
            throw new IllegalArgumentException(
                    "Too many alarms: " + total + " > " + MAX_ALARMS
                            + " (watch alarm table is fixed at 32 entries; 33+ time out)");
        }

        // --- Merge, drop disabled, order by slot ---
        List<AlarmSlot> all = new ArrayList<>(total);
        all.addAll(standard);
        all.addAll(calendar);
        all.sort(Comparator.comparingInt(AlarmSlot::getSlotId));

        ByteBuffer buffer = ByteBuffer.allocate(all.size() * 3);
        for (AlarmSlot a : all) {
            if (!a.isEnabled()) continue;
            buffer.put(encode(a));
        }

        byte[] full = buffer.array();
        if (buffer.position() == full.length) {
            return full;
        }
        // Trim trailing bytes from skipped (disabled) slots.
        byte[] trimmed = new byte[buffer.position()];
        System.arraycopy(full, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /**
     * Encode a single alarm to its 3 wire bytes, selecting the mode from slot range
     * and {@code repeating}.
     */
    public static byte[] encode(AlarmSlot a) {
        boolean calendarSlot = a.getSlotId() >= CALENDAR_SLOT_MIN;
        byte minute = (byte) a.getMinute();
        byte hour = (byte) a.getHour();
        byte days = (byte) (a.getDaysMask() & 0x7F);

        if (calendarSlot) {
            // Calendar non-repeating weekday: [0x80|days] [minute] [hour]
            // byte1 has NO 0x80 marker (fires once on the weekday, then stops).
            return new byte[]{(byte) (0x80 | days), minute, hour};
        }

        // Standard slots reuse the golden-locked Alarm byte layout.
        Alarm alarm = a.isRepeating()
                ? new Alarm(minute, hour, days, a.getLabel(), "")
                : new Alarm(minute, hour, a.getLabel(), "");
        return alarm.getData();
    }

    private static void requireRange(AlarmSlot a, int min, int max, String kind) {
        if (a.getSlotId() < min || a.getSlotId() > max) {
            throw new IllegalArgumentException(
                    kind + " alarm slot " + a.getSlotId() + " out of range [" + min + ".." + max + "]");
        }
    }
}
