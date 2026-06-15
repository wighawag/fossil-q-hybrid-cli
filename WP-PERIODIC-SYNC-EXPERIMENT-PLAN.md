# WP: Periodic Background-Sync Toggle (a DRIFT experiment, not a known fix)

**Status:** PLAN ONLY. Not implemented. Deferred in favour of investigating the auth/provisioning
drift angle first (see "Why deferred" below).

## What this would be

A **Settings toggle, default OFF**, that enables a periodic background sync on the long-lived
watch connection, so the user can test the drift hypothesis at their leisure with the existing
(idle, event-driven) behaviour unchanged by default.

## The hypothesis it tests (and why it is WEAK)

Storm-drift step #1 (FINDINGS, "Storm-drift step #1") established only this:
- Our app's connection sits IDLE (event-driven only, no periodic traffic).
- The official app, over a ~13-min idle connection, sends periodic background SYNC bursts (file
  GET/LIST) plus a post-sync hand animation.
- The "buttons go dead" / async-event re-send storm ("drift") appeared on OUR app, "worse after the
  watch has been used a while", and is cleared only by a re-provision. It did NOT appear in any
  official-app capture.

That is a **correlation with a confound**: the app that drifts is also the silent one; the app that
does not drift is also the busy one. There is NO demonstrated mechanism. Nothing in the firmware or
disassembly says "an idle connection degrades after N minutes."

**Evidence AGAINST this hypothesis (why it is probably not the cause):**
- The drift is cleared only by a full **re-provision**, NOT by a reconnect. If mere idleness caused
  it, a fresh reconnect (new connection, no stale idle state) would clear it. It does not. That
  points at **auth / secret-key / config state**, not "the connection went quiet."
- The official app differs from ours in many ways (auth handshake, config writes, subscription
  order, MTU, connection params); "periodic sync" is just the first difference we happened to look
  at. Picking it as THE cause is premature.

So this experiment is a **cheap, falsifiable probe of a weak theory**, not a fix we expect to work.
If the soak test (below) shows drift still happens with periodic sync ON, we have cleanly
eliminated this theory and should pursue the auth/provisioning angle.

## Why deferred

The strongest evidence (re-provision-clears-it, not reconnect) points at auth/provisioning state,
not idleness. So the higher-value next step is to diff the official app's auth/provisioning/config
maintenance against ours (analysis, no code). That investigation is happening first; this toggle is
kept here to revisit if that angle does not pan out, or as an independent cross-check.

## Implementation sketch (when/if revisited)

Codebase templates exist for every piece (the `navCueEnabled` opt-in boolean is the closest
analog).

1. **Pref** — `android/.../settings/SettingsPrefs.kt`:
   - Add `backgroundSyncEnabled: Boolean = false` to `AppSettings` (+ a `SettingsVocabulary`
     default `BACKGROUND_SYNC_DEFAULT_ENABLED = false`), a `KEY_BACKGROUND_SYNC` SharedPreferences
     key, read in `get()`, a `setBackgroundSync(enabled)` setter, and a line in `replaceAll(...)`.
   - Mirror `navCueEnabled` exactly.
2. **UI** — `android/.../settings/SettingsScreen.kt` + `SettingsViewModel.kt`:
   - A toggle row ("Periodic background sync (experimental)"), wired through the ViewModel like the
     nav-cue toggle. Copy should say it is an experiment and OFF by default.
3. **Periodic driver** — `android/.../WatchConnectionService.kt`:
   - When connected AND `backgroundSyncEnabled`, run a ~5-min timer that fires a LIGHTWEIGHT action
     (recommend a TIME re-push — cheap, already-implemented path — not a full reconcile).
   - Cancel the timer on disconnect and when the toggle goes off. The timer must NEVER initiate a
     connect (only fire while already connected), so it cannot undermine the deliberately
     event-driven, no-polling reconnect design (see that service's header comment: "NO continuous
     scanning ... we do NOT keep polling/connecting on a timer").
   - Default OFF => production behaviour byte-for-byte unchanged.
4. **Tests** — unit-test the pref round-trip + the ViewModel toggle (existing test style). The timer
   behaviour and the on-device soak test are NOT unit-testable here.

### Open implementation decisions (decide when revisited)
- Periodic action: TIME re-push (recommended, lightest) vs a fuller sync.
- Interval: fixed ~5 min (mirror the official app) vs also expose the interval as a setting.

## The soak test that would validate/refute it

On-device (user-run; not automatable here):
1. Toggle OFF (default). Leave the watch idle-connected to our app for >15-20 min, then press a
   button. Does the buttons-dead / re-send storm appear? (establish the baseline)
2. Toggle ON. Repeat the idle soak. Does the drift still appear?
- If drift disappears only with the toggle ON => periodic activity prevents it; promote from
  experiment to a real (still opt-in, or default-on) feature.
- If drift appears either way => this theory is refuted; pursue auth/provisioning drift.

Do NOT promote this to a "fix" or change the default until a soak test shows it actually helps; the
causal link (periodic activity -> no drift) is unproven.

## Provenance
- FINDINGS "Storm-drift step #1" (the periodic-write inventory + the honest correction that
  `02 f1 05` is PLAY_ANIMATION, not a keep-alive).
- FINDINGS "RECONCILIATION (2026-06-15): the storm is NOT caused by the missing ack".
- FINDINGS "Wedged play handle ... only a re-provision clears it" (the auth/config-state pointer).
