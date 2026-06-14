# WP-BUZZ-DURATION-NOHANDS — configurable buzz duration + optional no-hand-movement

## Motivation

The manual "buzz" path drives the watch via `NOTIFICATION_PLAY` matched against a reserved
`NOTIFICATION_FILTER` entry (`BuzzPatterns` → `buzzPlayOnly`). Every reserved entry carries a real
`HAND_MOVEMENT` (the N-o'clock mark) with a hard-coded `duration = 10000ms`. The hand excursion +
return is what imposes the ~7–12 s lockout (FINDINGS #23/#24: "a play file is ignored if the hands
haven't returned"). The buzz itself is not the bottleneck — the hand choreography is.

Goal: let the user (a) shorten the hold duration and (b) optionally suppress hand movement
entirely, so the watch just buzzes the pattern with little/no lockout, enabling back-to-back buzzes.

## Scope (this WP)

- **ONLY the 7 reserved buzz patterns** (`BuzzPatterns.RESERVED_PATTERNS = {1,2,3,5,6,7,8}`) become
  configurable (duration + moveHands).
- **Nav-cue patterns (`NavCuePatterns`) are LEFT UNCHANGED** — a turn cue's hand direction is the
  whole point, so it keeps moving hands at the current duration. The fold-in already adds the two
  reserved sets separately (`BuzzPatterns.reservedEntries() + NavCuePatterns.reservedEntries()`), so
  only the buzz half changes.
- When `moveHands == false`: hands written as `-1/-1` ("no move", same sentinel the sub-eye uses).
  Duration: written as configured (irrelevant when hands don't move; revisit after HW test).
- When `moveHands == true`: keep the N-o'clock number display (visually identifiable buzz), but at
  the configured duration.

## Whole-file filter constraint (MUST NOT regress)

`NOTIFICATION_FILTER` (0x0C00) is a WHOLE FILE. Re-uploading after a buzz-settings change MUST go
through the existing fold-in (`ServiceUploader.uploadNotificationFilter` in
`WatchConnectionService.kt`) which merges **user rules + reserved buzz + reserved nav-cue**. Never
upload just the reserved buzz file — that would wipe the user's notification rules and the nav-cue
entries. The buzz-settings change triggers the SAME notification-filter sync with the current user
rules.

## Find-watch buzz (separate, complementary primitive)

There is a buzz path that needs NO filter and NO play and moves NO hands: the "call vibration" /
`findDevice` write on characteristic `3dda0005` (`FossilQAdapter.findDevice()` /
`stopFindDevice()`). It is a continuous buzz until stopped, with no selectable pattern. Expose it:

- `FossilController.findWatchBuzz()` / `stopFindWatchBuzz()` (delegate to the adapter).
- Android Settings: a **Find watch** start button + a **Stop** button (mirroring `VibrationSync` →
  `ServiceBuzz`). Raw start/stop for now (no auto-stop timer unless requested later).

## Future notes (NOT this WP)

- **Nav cues will likely want configurable duration too**, possibly with PER-TARGET config (per
  direction / per stage). Door left open by keeping `NavCuePatterns` parameterizable later; deferred
  until the buzz HW test informs the right defaults.
- **CLI exposure deferred.** The CLI is intentionally NOT updated in this WP. TODO: expose
  `--buzz-duration` / `--buzz-move-hands` (re-uploading the folded filter) and a `find-watch`
  start/stop subcommand once the on-device behaviour is confirmed.
- After HW measurement, decide whether `moveHands=false` should force `duration=0` and whether the
  lockout tracks duration or is a fixed firmware refractory period.
