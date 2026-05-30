# WP-DEFAULTS — Status

App-level **defaults profile** for the watch sections we CANNOT read back. Shipped in 6 commits
(one per sub-part). Android-only — **no wire change, no CLI change**.

## Scope: UNREADABLE sections only

The profile carries exactly the three sections the watch can't report back to the phone:

- **Alarms** (standard slots 0–15),
- **Notification rules**,
- **Button mappings** (TOP / MIDDLE / BOTTOM).

It does **NOT** carry any **readable** setting (vibration / step goal / nudge / 2nd-timezone) — those
are READ from the watch at provision time (WP-ONBOARD: `ConfigToSeed` + `PrefSeedDecision`); an
absent readable value becomes blank/off or its hardcoded constant, **never** a user default.
Calendar alarms (slots 16–31) are **not** in the profile (they come from WP13's calendar source).

## Factory button defaults (current vocabulary)

The plan's old `MUSIC_MULTIMODE` / `MUSIC_CONTROL` spec is **stale**; it is translated to the
post-WP-BTN vocabulary. The factory profile (`DefaultsProfile.FACTORY`) ships:

| Button        | Mode            | Action / cycle                                  |
|---------------|-----------------|-------------------------------------------------|
| TOP (0x10)    | `SINGLE_ACTION` | `STOPWATCH`                                      |
| MIDDLE (0x20) | `CUSTOM_TOGGLE` | canonical cycle `[TIMEZONE_2, ALARM, DATE]`      |
| BOTTOM (0x30) | `SINGLE_ACTION` | `MULTI_FUNCTION` (the current music-control payload; **`MUSIC_MULTIMODE → MULTI_FUNCTION`** translation, wire `01 06 12 00`) |

Factory **alarms** and **notification rules** are **empty** ("no surprises"). The user can edit or
clear any section; a cleared button section means new watches get **blank** buttons.

## Storage

ONE app-level (not per-watch) profile in a tiny SharedPreferences blob
(`SharedPreferencesDefaultsProfileStore`, mirroring `SharedPreferencesSettingsPrefs`), persisted as
the `DefaultsProfileJson` string (Android's bundled `org.json`, no new deps). The JSON codec is the
**single source of truth** for the shape and is **tolerant**: blank / malformed / foreign input →
factory profile; individual malformed rows are skipped; never throws. An **explicitly empty** button
array is honoured (a deliberately-cleared section survives a reload); only a missing/garbage blob
restores the factory buttons.

`DefaultsProfileStore` is the injectable seam (`get` / `set` / `resetToFactory`); an
`InMemoryDefaultsProfileStore` fake is the test/VM default.

## Apply-at-provision contract (full-overwrite of the unreadable sections)

`DefaultsToSeed.seed(profile, mac)` re-keys the profile onto the new watch's mac (normalized to
upper-case to match the `WatchEntity` PK). Empty profile section → empty seed list.

At **provision time** (`WatchConnectionService.provisionNewWatch`):

1. The seed rows are passed into the `SyncInput` (instead of the old empty lists) and pushed via the
   one-time `SyncOrchestrator.sync(..., mode = PROVISION)`.
2. The seeded child rows are **persisted** to Room alongside the seeded `WatchEntity`
   (`WatchRepository.replaceDefaultsSections`, a full-REPLACE), so the app DB reflects what was
   pushed. The parent row is written **first** (the children are FK-bound).
3. The **success gate is UNCHANGED**: the watch is "added" (row + children written) **only if** the
   notification-filter upload succeeded. The readable read-back and the defaults seeding are
   best-effort and **never block** adding.

**PROVISION force-writes buttons (the fix):** the `SyncOrchestrator` buttons section now mirrors the
alarms section — in `PROVISION` mode it force-writes the button file **even when empty** (an empty
compiled file actively BLANKS any pre-existing buttons on the watch), and writes the
factory/seed buttons when non-empty. `RECONCILE` mode still skip-empties (empty buttons → skipped).
Alarm / notification-filter behaviour is unchanged; no wire bytes were invented (reuses
`ButtonConfigBuilder` / `AlarmCompiler` / the uploader).

So: a newly-added watch gets the profile's unreadable sections **pushed/overwritten** — buttons
force-written even when empty (→ blank), alarms/rules blanked when empty — while the readable
settings still come from the watch read-back.

## Manual "Apply defaults to this watch"

A confirm-gated action (Settings card **and** the Defaults editor) re-pushes the defaults profile's
**buttons + notification filter (rules)** onto the already-added ACTIVE watch on demand, **without**
removing/re-adding it:

- `ApplyDefaultsSync` seam (`ServiceApplyDefaults` prod impl, `NoopApplyDefaults` default), exposed
  as `SettingsViewModel.applyDefaultsToActiveWatch()` / `DefaultsViewModel.applyToActiveWatch()`.
- It takes `DefaultsProfileStore.get()` → `DefaultsToSeed` re-keyed to the active mac →
  **full-REPLACE** the per-watch buttons + rules in Room (NOT a merge; alarms left untouched —
  `replaceAlarms = false`), then triggers a **targeted sync push** of buttons + the notification
  filter via the existing `ServiceSaveToWatch.trigger(...)` path.
- Publishes `SyncState` SYNCING immediately (blocking modal); the button is disabled while a
  sync/buzz is in flight and when there is no active watch
  (`SyncProgressUi.saveEnabled(state.hasActiveWatch)`); no-op (false) without an active watch.
- Gated behind a small confirm dialog (mirrors `RemoveWatchCard`) because it overwrites the user's
  per-watch button/notification setup.

The watch ends up with EXACTLY the profile's buttons + filter (full overwrite, same as provisioning
does for those sections). **No new wire path** — it only feeds DB rows the existing
targeted-sync path pushes via the existing compilers/uploader.

## Editor UI

A "Defaults for new watches" sub-screen (reached from a Settings card; an overlay surface with a
system-back handler) reuses the same button vocabulary as the per-watch Buttons screen, bound to the
`DefaultsProfileStore` via `DefaultsViewModel`. Includes per-button mode/action editing (cardinality
enforced via `ButtonMappingRules` so an invalid mapping can't persist), read-only alarm/rule
summaries, **reset-to-factory**, the **apply-to-this-watch** action, and **export/import**.

## Export / import (self-contained — NOT deferred)

WP-MULTIWATCH was intentionally skipped, so there is no shared per-watch codec. The export/import is
**self-contained** to the defaults profile and reuses the sub-part-1 `DefaultsProfileJson` as the
single source of truth:

- **Pure stream codec** `DefaultsProfileTransfer` (`toBytes`/`fromBytes`/`writeTo`/`readFrom`) —
  export→import is an in-memory **identity** round-trip (unit-tested); malformed/foreign bytes →
  factory (never crash).
- **Android wiring shipped (not deferred):** export writes the JSON to `cache/defaults` and opens
  the share-sheet via the app's existing FileProvider authority (`${packageName}.fileprovider`,
  reused from the log exporter; a `cache-path name="defaults"` entry was added to
  `res/xml/file_paths.xml`); import uses `ACTION_OPEN_DOCUMENT` and feeds the picked document's
  bytes to the tolerant VM importer. The on-device share-sheet / picker rendering is
  on-device-pending; the codec is the provable seam.

## Tests / gates

- `:protocol:test` = **124** (unchanged — android-only WP).
- `:android:testDebugUnitTest` = **349** (was 305 at WP start), 0 failures / 0 errors.
- `:android:assembleDebug` + `:android:lintDebug` succeed.
- `:cli:shadowJar` succeeds and `./fossil-q --help` md5 == `7533ceccb6b29f81f6172bd5a71c5b98`
  (CLI untouched).

New/changed code lives in `android/.../defaults/*` (model, JSON codec, store, mapper, transfer,
ViewModel, screen), `android/.../settings/ApplyDefaultsSync.kt`, the `SyncOrchestrator` buttons
section, `WatchRepository.replaceDefaultsSections`, `WatchConnectionService.provisionNewWatch`, the
Settings screen entry + apply card, and `MainActivity` navigation. Tests cover the codec round-trip,
factory defaults, store, the mapper, the PROVISION button force-write, the DB persist (provision +
apply), the VM forwarding, the editor VM, and export/import identity.
