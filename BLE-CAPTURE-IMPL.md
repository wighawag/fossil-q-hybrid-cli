# BLE Capture Implementation Plan

Based on BLE capture analysis of the official Fossil app (bugreport5-8, 2026-05-21).
See FINDINGS.md #21a-21f and #22 for full analysis.

## Implementation Status

| Change | Status | Notes |
|--------|--------|-------|
| 1. Remove 0x0011 from syncConfiguration | ✅ Done | Also fixed setTimezoneOffset(), generateTimeConfigItem() comment |
| 2. `second-timezone` CLI command | ✅ Done | setSecondTimezone() + SecondTimezoneCmd |
| 3. Mode toggle button support | ✅ Done | ButtonConfigBuilder, multi-entry `+` syntax, `mode_toggle` shorthand |
| 4. Goal tracking button | ✅ Done | Payload captured from bugreport6, `goal_tracking` keyword |
| 5. Goal config 0x0017/0x0018 | ✅ Done | `goal-config` CLI command sends config 0x17 (target) + 0x18 (current) |

## Changes Required (ordered by importance)

---

### 1. Fix: Remove Config 0x0011 from syncConfiguration() — CRITICAL BUG

**File:** `src/main/java/qhybrid/linux/FossilQAdapter.java`

**Problem:** `syncConfiguration()` sends `TimezoneOffsetConfigItem` (config 0x0011) with the
local timezone offset on every connect. But 0x0011 is the SECOND TIMEZONE, not primary.
The watch gets primary TZ from `TimeConfigItem` (0x000C) offset field. Our code overwrites
any second timezone the user has set.

**What to change in `syncConfiguration()` (around line 840):**
- Remove `TimezoneOffsetConfigItem` from the config items array
- Remove the `timezoneOffset` variable computation (it's still used in `generateTimeConfigItem`)
- Remove the `addDeviceInfo` call for `ITEM_TIMEZONE_OFFSET`

**Before:**
```java
queueWrite(new ConfigurationPutRequest(new ConfigurationPutRequest.ConfigItem[]{
        new ConfigurationPutRequest.DailyStepGoalConfigItem(stepGoal),
        new ConfigurationPutRequest.VibrationStrengthConfigItem(vibrationStrength),
        new ConfigurationPutRequest.TimezoneOffsetConfigItem(timezoneOffset),  // REMOVE
        generateTimeConfigItem()
}, shimAdapter), false);
```

**After:**
```java
queueWrite(new ConfigurationPutRequest(new ConfigurationPutRequest.ConfigItem[]{
        new ConfigurationPutRequest.DailyStepGoalConfigItem(stepGoal),
        new ConfigurationPutRequest.VibrationStrengthConfigItem(vibrationStrength),
        generateTimeConfigItem()
}, shimAdapter), false);
```

**Also fix the comment** in `generateTimeConfigItem()` (around line 979):
- OLD: "The watch uses TimezoneOffsetConfigItem (0x0011) to shift the displayed time"
- NEW: "The watch uses the offset field in TimeConfigItem (0x000C) to shift displayed time.
  Config 0x0011 is for the SECOND timezone only. See FINDINGS.md #21a."

**Also fix `setTimezoneOffset()` (around line 531):**
This method is used by the `timezone` CLI command. It currently sends config 0x0011 +
TimeConfigItem together. It should ONLY send TimeConfigItem with the new offset.
Rename or keep for compatibility, but change the implementation:

```java
public void setTimezoneOffset(short minutes) {
    if (!useFossilProtocol) { ... }
    // Config 0x000C (TIME) carries the TZ offset the watch uses for display.
    // Config 0x0011 is SECOND timezone — don't touch it here.
    long millis = System.currentTimeMillis();
    queueWrite(new ConfigurationPutRequest(
            new ConfigurationPutRequest.TimeConfigItem(
                    (int) (millis / 1000),
                    (short) (millis % 1000),
                    minutes),
            shimAdapter), false);
}
```

---

### 2. Add `second-timezone` CLI command

**Files:** `Main.java` (new subcommand), `FossilQAdapter.java` (new method)

**New adapter method:**
```java
public void setSecondTimezone(short offsetMinutes) {
    // Config 0x0011 = SECOND_TIMEZONE_OFFSET
    // Range: -720 to 840 minutes, or 1024 to disable
    // See FINDINGS.md #21a
    queueWrite(new ConfigurationPutRequest(
            new ConfigurationPutRequest.TimezoneOffsetConfigItem(offsetMinutes),
            shimAdapter), false);
}
```

**New CLI command in Main.java:**
```java
@Command(name = "second-timezone", description = "Set second timezone offset (for SECOND_TIMEZONE button function)")
static class SecondTimezoneCmd implements Callable<Integer> {
    @ParentCommand Main parent;

    @Parameters(index = "0", description = "Timezone offset in minutes from UTC (e.g. -300 for EST, 330 for IST, 540 for JST). Use 'off' or '1024' to disable.")
    String offset;

    @Override
    public Integer call() {
        short minutes;
        if (offset.equalsIgnoreCase("off") || offset.equalsIgnoreCase("disable") || offset.equals("1024")) {
            minutes = 1024;
        } else {
            try {
                minutes = Short.parseShort(offset);
            } catch (NumberFormatException e) {
                System.err.println("Invalid offset: " + offset);
                return 1;
            }
            if (minutes < -720 || minutes > 840) {
                System.err.println("Offset must be between -720 and 840 minutes (or 1024 to disable)");
                return 1;
            }
        }

        FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
        adapter.setSecondTimezone(minutes);
        if (minutes == 1024) {
            System.out.println("Second timezone disabled");
        } else {
            System.out.printf("Second timezone set to UTC%+.1f (%d minutes)%n", minutes / 60.0, minutes);
        }
        sleep(1000);
        adapter.shutdown();
        return 0;
    }
}
```

Register in `@Command` subcommands list on the Main class.

---

### 3. Add Mode Toggle button support (multi-entry buttons)

**File:** `gadgetbridge/.../ConfigPayload.java` — add new enum entry (zero-patch concern!)

⚠️ **CONSTRAINT:** Zero patches to vendored GadgetBridge code. We cannot modify ConfigPayload.java.

**Alternative approach:** Handle mode toggle entirely in `FossilQAdapter.overwriteButtons()`
and `Main.ButtonsCmd.parsePayload()`.

**In Main.java ButtonsCmd:**
- Accept `+`-separated multi-function names: `"second_timezone+date+step_goal_progress"`
- Parse into an array of ConfigPayload entries
- Pass the array to a new adapter method that builds multi-entry button config

**The problem:** `ConfigFileBuilder` takes `ConfigPayload[]` with one per button. It doesn't
support multiple entries per button. And we can't modify ConfigFileBuilder.

**Solution:** Build the button config file manually in `FossilQAdapter` when any button has
multiple entries. The format is well-understood from FINDINGS.md #19 and #21b.

**New ConfigPayload-like approach:**
Since we can't modify the vendored ConfigPayload enum, create a new class in our code:

```java
// In qhybrid/linux/ (our code, not vendored)
public class ButtonConfig {
    public static final byte[] STEP_GOAL_PROGRESS_HEADER = new byte[]{0x01, 0x02, 0x1a, 0x00};
    public static final byte[] STEP_GOAL_PROGRESS_DATA = new byte[]{
        // Captured from official Fossil app (bugreport5, t=169.6s)
        (byte)0x01,(byte)0x00,(byte)0x01,(byte)0x02,(byte)0x1a,(byte)0x36,
        (byte)0x00,(byte)0x00,(byte)0x00,(byte)0x01,(byte)0x00,(byte)0x08,
        (byte)0x00,(byte)0x04,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0x02,
        (byte)0x00,(byte)0x00,(byte)0x01,(byte)0x01,(byte)0x1d,(byte)0x00,
        (byte)0x89,(byte)0x02,(byte)0x01,(byte)0x04,(byte)0xb0,(byte)0x03,
        (byte)0x00,(byte)0x89,(byte)0x05,(byte)0x01,(byte)0x07,(byte)0xb0,
        (byte)0x03,(byte)0x00,(byte)0xb0,(byte)0x03,(byte)0x00,(byte)0xb0,
        (byte)0x03,(byte)0x00,(byte)0x08,(byte)0x01,(byte)0x50,(byte)0x00,
        (byte)0x01,(byte)0x00,(byte)0xa6,(byte)0x79,(byte)0x57,(byte)0xcc
    };
    
    // Build a complete button config file with multi-entry support
    public static byte[] buildButtonConfig(ConfigPayload[][] buttonsEntries) {
        // buttonsEntries[0] = TOP entries, [1] = MIDDLE, [2] = BOTTOM
        // Each can have 1+ ConfigPayload entries
        // Follow the format from ConfigFileBuilder but with variable entry counts
        // ... (see below)
    }
}
```

**Actually simpler approach:** For mode toggle specifically, the official Fossil app uses
exactly 3 functions: SECOND_TIMEZONE + DATE + appId_0x001a. We can build this as a special
case without modifying ConfigFileBuilder:

In `ButtonsCmd.parsePayload()`, treat "mode_toggle" as returning null, then handle it
separately in the `call()` method by calling a dedicated adapter method
`overwriteButtonsWithModeToggle(top, middle, bottom)` that manually constructs the binary.

But this gets messy. The **cleanest approach** is:

1. In `Main.java`, parse button specs into a new type `ButtonSpec[]` (one per button)
   where each `ButtonSpec` has a list of `ConfigPayload` entries
2. For "mode_toggle", expand it to `[SECOND_TIMEZONE, DATE, STEP_GOAL_PROGRESS]`
3. Pass to adapter which builds the binary manually (bypassing ConfigFileBuilder
   when any button has >1 entry)

**STEP_GOAL_PROGRESS payload** — this is NOT the same as STEP_GOAL_COMPLETION.
STEP_GOAL_COMPLETION header: `01 02 1c 00` (appId 0x001c)
STEP_GOAL_PROGRESS header: `01 02 1a 00` (appId 0x001a)
Different payload data. We captured the full 54-byte payload from the BLE trace.

---

### 4. Notification filter improvements (lower priority, no code changes yet)

From the capture we learned:
- Per-contact filtering via SENDER_NAME field (0x02) works
- Only CALL(1), TEXT(2), DEFAULT(4) vibe patterns are used by official app
- Variable-length filter entries (32 bytes without sender, more with)
- Same CRC can appear multiple times with different senders

No immediate code changes needed — our current filter works. Future enhancement.

---

## Summary of files to change

| File | Changes |
|------|---------|
| `FossilQAdapter.java` | Fix syncConfiguration (remove 0x0011), fix setTimezoneOffset, fix generateTimeConfigItem comment, add setSecondTimezone() |
| `Main.java` | Add SecondTimezoneCmd, add to subcommands list, enhance ButtonsCmd for mode_toggle |
| `CONTEXT.md` | Already updated with gotcha note |
| `FINDINGS.md` | Already updated with #21a-21f |
| `TODO.md` | Already updated with new items |

## What NOT to change

- No vendored GadgetBridge files (zero-patch constraint)
- No changes to ConfigPayload.java, ConfigFileBuilder.java, etc.
- Button multi-entry support deferred to separate session (complex, needs manual binary builder)
