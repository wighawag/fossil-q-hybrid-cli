# WP-SWITCH-BUZZ-NOHANDS-WIRING — wire the mode-switch buzz to the no-hand short-duration path

## Status

DRAFT — not implemented. Depends on the committed protocol primitives in
`WP-BUZZ-DURATION-NOHANDS-PLAN.md` (commit `3a1e677`).

## Motivation

The `SWITCH_MULTI_FUNCTION_MODE` buzz currently plays a reserved buzz entry that carries a real
hand excursion with `duration = 10000ms`. Per FINDINGS #23/#24 the watch ignores a play file while
the hands have not returned (~7-12s). So even though `ServiceTrackerDispatch` correctly DEBOUNCES a
burst of switch presses into a single buzz for the final mode, that single buzz still triggers a
~10s hand lockout. Any later buzz (another switch outside the debounce window, a nav cue, a matched
notification) collides with that lockout and is dropped or fired late. The user feels buzzes
smeared over time and cannot tell which mode they landed on.

The protocol layer can now emit a reserved buzz with `moveHands=false` (hands `-1/-1`) and a short
duration, which fires the vibration pattern WITHOUT the hand excursion, so back-to-back switch
buzzes land cleanly. This WP wires that path into the switch-buzz feedback ONLY.

## Scope

- IN: the mode-switch confirmation buzz (`ServiceTrackerDispatch` SWITCH path → `buzzNow` →
  `runBuzz` → reserved play).
- IN: the reserved-buzz NOTIFICATION_FILTER fold-in so the on-watch reserved entries are the
  no-hand / short-duration variant (or a parallel set) the switch buzz matches against.
- OUT (unchanged): nav-cue entries keep their hand direction + duration (direction is the point).
- OUT: TRACKER gesture buzzes (short/double/long log + ring confirmations) — DECIDE during design
  whether these should also go no-hand. Default: leave them moving hands for now, change only the
  switch buzz, because that is the one the user reported. Revisit after on-device test.
- OUT: CLI exposure (still deferred, per the parent WP).

## The whole-file filter constraint (MUST NOT regress)

`NOTIFICATION_FILTER` (0x0C00) is a WHOLE FILE. The reserved-buzz entries are folded in by
`WatchConnectionService` `ServiceUploader.uploadNotificationFilter` (around line 1675), which
merges user rules + reserved buzz (`BuzzPatterns.reservedEntries()`) + reserved nav-cue
(`NavCuePatterns.reservedEntries()`). The change MUST go through that same fold-in. NEVER upload
just the reserved buzz file — that wipes the user's notification rules and the nav-cue entries.

## Design decision to make first (blocking)

The reserved buzz patterns (5/6/7/8 used for switch feedback) are SHARED: the same reserved entry
is matched by `buzzPlayOnly(pattern)` for EVERY caller of that pattern, not just the switch buzz.
If we flip the on-watch reserved entry for pattern 7 to `moveHands=false`, then ANY buzz of pattern
7 (e.g. a TRACKER double-press MAJOR-waypoint confirm = pattern 6, a long-press = pattern 8) also
stops moving hands. So there are two options:

- **Option A (simplest): make ALL reserved buzz entries no-hand + short duration.**
  Change the fold-in to call `BuzzPatterns.reservedEntries(SHORT_MS, moveHands=false)`. Every buzz
  becomes a clean no-lockout pulse. Pro: one-line-ish change, removes the lockout everywhere, fixes
  the smearing for switch AND any other reserved buzz. Con: buzzes lose the N-o'clock visual mark
  (you can no longer glance at the watch face to read which pattern fired). Given these are
  pocket-felt buzzes, the visual mark is likely low value; this is probably the right call.

- **Option B: add a SEPARATE no-hand reserved pattern set just for switch feedback.**
  Keep 5/6/7/8 as-is (hands move) and add new reserved patterns (distinct package CRCs) that are
  no-hand, used ONLY by the switch buzz. Pro: other buzzes keep their hand mark. Con: more reserved
  entries (filter-size budget — check the 0x0C00 whole-file size limit), more CRCs, the switch-buzz
  vocabulary (`SettingsVocabulary.SWITCH_BUZZ_*`) must map to the new set. More surface, more risk.

RECOMMENDATION: start with **Option A**, behind a settings flag if we want reversibility, and
measure on-device. Only fall back to Option B if losing the hand mark on non-switch buzzes is a
real regression.

## Implementation sketch (Option A)

1. **Pick the short duration constant.** Add e.g. `BuzzPatterns.SHORT_BUZZ_DURATION_MS` (candidate:
   0 or a small value like 500-1500ms). The HW behaviour of `duration` when `moveHands=false` is
   unconfirmed (the parent WP flags this as a post-HW-test decision), so make it a single named
   constant we can tune.

2. **Change the fold-in** in `WatchConnectionService` (`uploadNotificationFilter`, ~line 1675) to
   build the reserved buzz half via
   `BuzzPatterns.reservedEntries(SHORT_BUZZ_DURATION_MS, moveHands = false)` instead of the no-arg
   `reservedEntries()`. Keep the nav-cue half unchanged. Verify the merge order + dedup still hold.

3. **Trigger a re-sync** so the new reserved entries reach the watch. Options:
   - rely on the next notification-filter sync (on-connect / on rule edit), OR
   - explicitly kick `ServiceSaveToWatch.trigger(..., SyncSection matching the filter)` once after
     upgrade. Confirm which `SyncSection` drives `uploadNotificationFilter` and that a one-shot push
     does not clobber user rules (it folds them in, so it should be safe).

4. **No change needed in `ServiceTrackerDispatch`** — it still calls `buzz(switchBuzz)` →
   `buzzNow` → `runBuzz` → `buzzPlayOnly(pattern)`. The pattern is still reserved, so `runBuzz`
   takes the play-only branch; only the on-watch entry it matches now has no hand move. (Confirm
   `BuzzPatterns.isReservedPattern` still returns true for 5/6/7/8 — it must, the CRCs/vibe bytes
   are unchanged by the no-hand variant.)

5. **Optional: settings flag.** A `multiFunctionSwitchBuzzMoveHands` (or a global
   `reservedBuzzMoveHands`) pref so the user can restore the hand mark. Default false (no hands).
   Wire it into the fold-in. Only add if we want runtime reversibility; otherwise hardcode.

## Tests (off-device, pure)

- A `WatchConnectionService` / uploader-level test (mirroring the existing
  `SyncOrchestratorTest.trackerPath2ActionsCompileByteIdenticalToRingPhone` style) asserting the
  folded NOTIFICATION_FILTER now contains the reserved buzz entries with hands `-1/-1` and the
  chosen short duration, while user rules + nav-cue entries are preserved unchanged.
- A test asserting `isReservedPattern(5/6/7/8)` still holds and the play-only branch is still taken
  for a switch buzz (so we did not accidentally force the two-put `filter+play` path).
- Reuse the committed `BuzzPatternsTest` no-move assertions (already green) as the protocol-layer
  guarantee.

## On-device acceptance (the reason this WP exists)

After the connection is restored:

1. Add TRACKER (and ideally MUSIC_LYRION + TIMER) to the rotation in Settings so the switch button
   has multiple modes to cycle.
2. Switch modes SLOWLY (>12s between presses). Each switch should produce exactly ONE clean buzz of
   the per-mode pattern (phone=single, Lyrion=double, tracker=triple, timer=long by default), with
   NO hand excursion and NO ~10s lockout.
3. Switch modes RAPIDLY (within the debounce window). You should feel exactly ONE buzz, for the
   FINAL landed mode, and crucially NO trailing/late buzzes afterwards (the smearing is gone).
4. Cross-check: while in TRACKER, trigger a notification or a gesture buzz right after a switch —
   it should fire promptly, not queue behind a lockout.

If 2-4 hold, the "multiple buzz patterns over time" symptom is resolved.

## Risks / open questions

- HW behaviour of `duration` when `moveHands=false` (is there still a refractory period?). Drives
  the `SHORT_BUZZ_DURATION_MS` value. Resolve by on-device measurement.
- Filter-size budget if we ever go Option B (extra reserved entries).
- Whether to also convert the TRACKER gesture confirmation buzzes to no-hand (deferred; only the
  switch buzz is in scope here).
