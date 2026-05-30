# WP-SYNCSTATUS — Status

✅ **DONE & VERIFIED** (provable core JVM/Robolectric-tested; the BLE upload effect stays
**on-device-pending**). Makes it **visible and honest which configured rows are actually on the
watch vs. only in the local DB**, prompts to save when leaving a screen with unsaved-to-watch
changes, and makes **alarm edits auto-save to the watch**. Applied across the three editable
per-watch sections: **Notifications, Alarms, Buttons**. Android-only — **no wire change, no CLI
change** (protocol golden tests stay 124/0/0; `./fossil-q --help` md5 unchanged).

Shipped in 5 commits (Steps 1, 2, 2b, 3, 4 — full gates pass + commit after each).

## The timestamp model (per-row `updatedAt` vs per-watch `…SyncedAt`)

Every watch section uploads as **one whole file** (alarms = 32-slot file; notification filter =
concatenated entries + reserved buzz entries; buttons = whole config). There is NO per-row upload.
So "is row X on the watch?" is really **"was this section's file re-pushed AFTER row X's last
edit?"** → a comparison of:

- a **per-row `updatedAt`** (epoch millis) on `WatchAlarmEntity` / `NotificationRuleEntity` /
  `ButtonMappingEntity` — "when this row was last written to the DB"; stamped by `WatchRepository`
  on **every** write path (single-row `upsertAlarm`/`upsertRule`/`upsertButton` **and** the bulk
  `replaceDefaultsSections` seed + `transferSettings` clone), via an injectable clock; against
- a **per-watch per-section `…SyncedAt`** on `WatchEntity` (`alarmsSyncedAt`,
  `notificationFilterSyncedAt`, `buttonsSyncedAt`) — "when this section's file was last (re-)pushed
  to this watch".

A **per-row boolean would be WRONG**: it can't represent "the file was re-pushed so everything is
current now", and it mishandles deletes. The timestamp model handles both — a re-push bumps
`…SyncedAt` past every surviving row, and deletes don't matter (only surviving rows are queried).

### The pure helper (`qhybrid.android.sync.SectionSyncStatus`)

```
isOnWatch(rowUpdatedAt, sectionSyncedAt) = sectionSyncedAt > 0 && rowUpdatedAt <= sectionSyncedAt
pendingCount(rowUpdatedAts, sectionSyncedAt) = count of rows NOT on watch
```

- **Never-synced** (`sectionSyncedAt == 0`) → everything pending (decided explicitly).
- **`<=` (not `<`)** so an equal timestamp counts as on-watch — see the ordering caveat.
- Unit-tested exhaustively: never-synced, edited-before-sync, edited-after-sync, equal timestamps,
  empty rows, seed-then-sync.

### The `performed`-based write (our truest "it's on the watch" signal)

The BLE upload is **on-device-pending**, and these sections are **unreadable** (no watch read-back —
see `WP-DEFAULTS-STATUS.md`). So the truest signal is `SyncResult.performed` containing the section
(the orchestrator ran the put). In `WatchConnectionService.runOnConnectSync` at the
`result.performed` hook, for each performed section we write the matching `…SyncedAt` via the repo
on the ble-worker, using a timestamp **captured AFTER the sync pass completes**.

### ORDERING CAVEAT (decided + tested)

A provision/seed writes the rows (`updatedAt = now`) and the **same** connect's sync sets
`…SyncedAt`. Because the `…SyncedAt` timestamp is captured **after** the row writes (and `isOnWatch`
uses `<=`), a freshly seeded+synced row is correctly "on watch". Covered by a seed-then-sync test and
an edit-after-sync test (which flips the row back to pending).

### Room migration 1→2

`AppDatabase` bumped to **version 2** with `MIGRATION_1_2` (6 additive `ALTER TABLE … ADD COLUMN …
INTEGER NOT NULL DEFAULT 0` across 4 tables). `exportSchema = false` kept. Existing installs are NOT
wiped (additive; existing rows default to `0` = pending until the next sync stamps the section). The
Robolectric in-memory tests build from the entities → v2 automatically; a dedicated migration test
builds a v1 schema by hand, runs the migration, and asserts the columns are added with old data
intact. The WP14 compilers ignore `updatedAt`, so **no wire bytes change**.

## The badge + pending banner (all 3 screens)

Each screen's UiState derives (purely, unit-tested) from the active `WatchEntity` + the rows:

- `…SyncedAt` (from the active watch), a per-row `isOnWatch(id)`, and a `pendingCount`.
- **Per-row badge** (`SyncRowBadge`): ✓ "On watch" vs ⏳ "Not synced".
- **Header banner** (`PendingSyncBanner`): "N change(s) not on the watch — Save to watch" when
  `pendingCount > 0` (hidden at 0; pluralised by the pure `pendingMessage`).
- **Notifications Play button is DISABLED** for any rule **not yet on the watch** (the watch lacks
  that rule's vibe/hands until the filter is re-pushed). Drives `enabled` off the per-row
  `isOnWatch`; `playRule` already no-ops for an unsaved package.
- Buttons badge shows only for **configured** slots (an unset slot has no sync state).

## Settings: vibration-pattern dropdown + single Buzz (Step 2b)

`TestVibrationCard` now has **one vibration-pattern dropdown + a single "Buzz" button** (replacing
the old two fixed buzz buttons + the diagnostic "Put filter + send buzz" button). The dropdown lists
**exactly** `BuzzPatterns.RESERVED_PATTERNS = {1,2,3,5,6,7,8}` (silent 0/9 + the 4≡3 dup skipped),
labelled via `VibePatterns.label`, default 5. Buzz uses the **play-only** path
(`vm.vibrateWatch(selected)` → `VibrationSync.buzz(forceFilterPlay=false)`) — works because all 7
reserved patterns are already on the watch in the reserved filter. **No new wire bytes.**
`vibrateWatchWithFilter` / `VibrationSync.buzz(forceFilterPlay=true)` is KEPT in the VM/seam (just no
longer surfaced).

## The leave-with-pending prompt (Step 3 — prompt, NOT auto-save; NO background-save)

Rows are ALWAYS persisted to Room immediately, so leaving never loses data — it just means the
**watch is out of sync with the DB**. Hence the leave UX is a **prompt**, NOT a forced auto-save and
NOT a background-save (sync-on-connect re-pushes a pending targeted save on reconnect, so
backgrounding is already safe — no `onPause`/`onStop` push was added).

**Approach (a):** a SINGLE `LeaveGuardState` lifted into `MainActivity` gates **both** bottom-nav tab
switches and the system back press. Each editable screen publishes its `pendingCount` + a "Save to
watch" action into the guard via the shared `PublishLeaveGuard` composable (cleared on dispose). The
host's `requestLeave()` defers a navigation when `LeaveGuardLogic.shouldPrompt(pendingCount)` is true
and shows a "Save to watch?" dialog: **Save** (push + show the blocking `SyncSavingDialog`, then
leave) / **Leave** (navigate away, rows stay in the DB) / **Cancel** (stay). The pure
`LeaveGuardLogic.shouldPrompt` + the holder are unit-tested; the dialog/nav is the thin shell.

## Alarms auto-save (Step 4) + why Notifications/Buttons stay manual

Each `addAlarm`/`updateAlarm`/`deleteAlarm` (and the toggle/days/shortcut intents that route through
`updateAlarm`) triggers a **debounced** `ALARMS_ONLY` push via the existing `AlarmSync` seam after
the Room write — the whole 32-slot file re-push, exactly what the old manual save did. A new
injectable `Debouncer` seam (`CoroutineDebouncer` prod ~750 ms window; `ImmediateDebouncer` /
`RecordingDebouncer` test fakes) **coalesces a burst into ONE push**, so a burst of toggles doesn't
spam BLE and the blocking "Saving to watch…" modal appears **once per coalesced save**, not per
keystroke (the seam publishes SYNCING when it actually fires). The synced-marker then flips each
alarm row to ✓ shortly after. The explicit Alarms "Save to watch" button was **removed** (replaced
by a `SyncStatusRow` for live feedback); the leave-prompt guard stays for the brief
in-flight/disconnected window.

**Asymmetry (decided):** Notifications & Buttons **keep** the explicit Save + the leave-prompt —
they're edited in richer multi-field dialogs where a save-per-keystroke would be wrong. Only Alarms
auto-saves.

## What's on-device-pending

The actual BLE file uploads (alarm/filter/button files) are hardware-verified by the user — and
these sections are unreadable, so `SyncResult.performed` is as truthful as we can be about "it's on
the watch". Everything provable off-device is unit-tested: the `SectionSyncStatus` truth-table, the
repository `updatedAt` stamping on every write path, the `…SyncedAt` write + seed-then-sync ordering,
the migration's additive columns, the per-screen UiState derivations + the Play-enable rule, the
reserved-pattern dropdown routing, the `LeaveGuardLogic`/holder, the alarm auto-save trigger, and the
debounce coalescing.

## Gates at completion

- `:protocol:test` — **124 / 0 / 0** (untouched; no wire bytes).
- `:android:testDebugUnitTest` — **441 / 0 / 0** (was 402 at WP start; +39).
- `:android:lintDebug :android:assembleDebug` — succeed.
- `:cli:shadowJar` + `./fossil-q --help` md5 — **unchanged** (`7533ceccb6b29f81f6172bd5a71c5b98`).
