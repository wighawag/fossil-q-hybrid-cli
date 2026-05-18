package nodomain.freeyourgadget.gadgetbridge.util;

/**
 * Minimal shim — only terminateNull() is used by non-HR vendored code.
 * The real class imports commons-lang3 (ArrayUtils.subarray) — not worth vendoring.
 */
public class StringUtils {
    public static String terminateNull(String input) {
        if (input == null || input.isEmpty()) return "\0";
        if (input.charAt(input.length() - 1) == 0) return input;
        return input + "\0";
    }
}
