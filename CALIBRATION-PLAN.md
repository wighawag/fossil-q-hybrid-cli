# Interactive Calibration Plan

## Goal

Replace the current `calibrate` command (which just saves current position) with an interactive flow that guides the user through calibrating all three hands to 12:00:00.

## Why

The watch's hand positions drift from the internal RTC over time, or get offset when switching between time sync approaches (UTC vs local epoch). Calibration tells the watch "these hand positions = 12:00:00", so the RTC can drive them correctly afterward.

## Current State

- `fossil-q hands <hour> <min> <sub>` — moves hands to absolute degree positions
- `fossil-q calibrate` — saves current hand position as reference
- User must manually figure out how far off each hand is and do the math

## Proposed UX

```
$ fossil-q -d D9:20:71:11:74:2A calibrate

Calibration mode — we'll adjust each hand to point at 12 o'clock.
The watch will take control of the hands.

=== HOUR HAND ===
Use +/- to nudge, Enter to confirm, q to abort.
Current position: adjusting...
  [+] nudge clockwise
  [-] nudge counter-clockwise
  [Enter] hand is at 12 → confirm and move to next

> +
> +
> +
> -
> [Enter]
Hour hand calibrated.

=== MINUTE HAND ===
  [+] nudge clockwise
  [-] nudge counter-clockwise
  [Enter] confirm at 12

> -
> -
> [Enter]
Minute hand calibrated.

=== SUB-EYE (activity hand) ===
  [+] nudge clockwise
  [-] nudge counter-clockwise
  [Enter] confirm at 12

> [Enter]
Sub-eye calibrated.

Saving calibration...
Calibration saved. Syncing time...
Time synced. Hands should now show correct time.
```

## Implementation Steps

### 1. Request hand control
Call `RequestHandControlRequest` to take exclusive control of the hands.

### 2. Move all hands to approximate 12 o'clock
Send `MoveHandsRequest` with all three hands at 0° (absolute). This gets them close — the user then fine-tunes.

### 3. Interactive nudge loop (per hand)

For each hand (hour, minute, sub-eye):
- Track current degree offset (starts at 0)
- Read single keystrokes from stdin:
  - `+` or arrow-up: increment by nudge step (e.g. 6° = 1 minute mark)
  - `-` or arrow-down: decrement by nudge step
  - `Shift++` / `Shift+-`: fine nudge (1°)
  - `Enter`: confirm this hand, move to next
  - `q`: abort calibration, release hands
- After each nudge, send `MoveHandsRequest` with the updated absolute position for just that hand
- Small delay between moves to let the watch respond (~200ms)

### 4. Save calibration
Call `SaveCalibrationRequest` — tells the watch "current hand positions = 12:00:00 reference".

### 5. Release hand control
Call `ReleaseHandsControlRequest`.

### 6. Sync time
Call `syncTime()` so the watch immediately drives the hands to the correct current time from the newly calibrated reference.

## Technical Details

### Reading single keystrokes in Java

Java's `System.in` is line-buffered by default. Options:
- **JLine 3** library — provides raw terminal mode, single-char reads, arrow key support. Small dependency (~200KB). Already used by many CLI tools.
- **Raw terminal via stty**: `Runtime.exec("stty -icanon min 1")` before reads, `stty sane` after. Works but fragile.
- **Picocli `interactive` option**: picocli doesn't have built-in single-key input.

**Recommendation:** Add JLine 3 dependency. It handles terminal raw mode, arrow keys, and cleanup properly.

```groovy
implementation 'org.jline:jline:3.25.1'
```

### Nudge step sizes

| Key | Step | Rationale |
|-----|------|-----------|
| `+` / `-` | 6° | One minute mark on the dial (360°/60) |
| `Shift++` / `Shift+-` or `<` / `>` | 1° | Fine adjustment |
| Arrow up/down | 6° | Same as +/- |

360° / 6° = 60 steps for a full rotation. Worst case: 30 nudges to reach any position.

### MoveHandsRequest details

From the vendored code, `MovementConfiguration`:
- `setHourDegrees(int)` / `setMinuteDegrees(int)` / `setSubDegrees(int)`
- Movement type: absolute (direction=3)
- Each hand can be set independently (`isHourSet()`, `isMinuteSet()`, `isSubSet()`)

To move only one hand, create a `MovementConfiguration` and set only that hand's degrees.

### Fossil protocol compatibility

Both `RequestHandControlRequest` and `MoveHandsRequest` are misfit-style commands sent to `3dda0002`. They work on Fossil protocol watches too (confirmed by GadgetBridge code — `FossilWatchAdapter` delegates to these same request classes).

`SaveCalibrationRequest` is also a misfit-style `3dda0002` write.

### Edge cases

- **Watch disconnects during calibration**: detect via connection callback, print error, don't save
- **User presses q**: release hand control, don't save calibration
- **Wrap-around**: degrees should wrap at 360 (e.g., -1° → 359°)
- **Terminal cleanup**: JLine/stty must be restored even on exception (try-finally)

## Not In Scope (for now)

- Persisting calibration offsets to a config file
- Auto-detecting current hand positions (watch doesn't report this)
- Calibrating while time is running (must hold hands still)
