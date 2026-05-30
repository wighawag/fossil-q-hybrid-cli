# Prompt for next session — WP-SYNCSTATUS (per-row "on watch?" markers + leave-prompt + alarm auto-save)

You are working on **fossil-q-hybrid-cli** at `/home/wighawag/dev/github/wighawag/fossil-q-hybrid-cli`
(Android companion app for a Fossil Q Hybrid smartwatch + `:protocol` + `:cli` modules).

## Goal

Make it **visible and honest which configured rows are actually on the watch vs. only in the local
DB**, prompt the user to save when leaving a screen with unsaved-to-watch changes, and make **alarm
edits auto-save to the watch** (removing the explicit "Save to watch" need on the Alarms screen).
Apply the visibility + leave-prompt across **all three editable per-watch sections**: Notifications,
Alarms, Buttons.

This was designed in a prior session. **Follow the design below exactly** — it was reviewed and
approved by the user. Do the work in the committed steps below (1, 2, 2b, 3, 4, 5 — full gates pass +
commit after EACH step; adjust seams if you find something better, but keep the model).

Three user refinements folded in: (a) the Notifications **Play button is DISABLED for rules not yet
on the watch** (Step 2); (b) **Settings** gets a **vibration-pattern dropdown + single Buzz button**
replacing the fixed buzz buttons (dropdown lists ONLY the reserved patterns {1,2,3,5,6,7,8} — silent
0/9 + the 4≡3 dup skipped — and Buzz uses the play-only path; the diagnostic filter+play button is
removed), so the user can feel each useful pattern (Step 2b); (c) the
alarm **auto-save shows the blocking spinner modal** like a manual save (Step 4).

## Background / why (already decided — do not re-litigate)

- Every watch section uploads as **one whole file** (alarms = 32-slot file; notification filter =
  concatenated entries + reserved buzz entries; buttons = whole config). There is NO per-row upload.
  So "is row X on the watch?" is really **"was this section's file re-pushed AFTER row X's last
  edit?"** → the correct model is a **per-row `updatedAt` timestamp** compared against a **per-watch
  per-section `…SyncedAt` timestamp**. (A per-row boolean is WRONG — it can't represent "the file
  was re-pushed so everything is current now", and mishandles deletes.)
- The rows are ALREADY persisted to Room immediately on every add/edit/delete (the VM intents write
  straight away). "Save to watch" is a SEPARATE action that pushes the section file. So "leaving
  without saving" never loses data — it just means **the watch is out of sync with the DB**. Hence
  the leave UX is a **prompt** ("Save to watch? / Leave"), NOT a forced auto-save, and NOT a
  background-save. Sync-on-connect already re-pushes a pending targeted save when the watch
  reconnects (`ConnectSyncDecider` honors `hadPendingSync`), so backgrounding is already safe.
- The BLE upload effect is **on-device-pending**. Our best honest "it's on the watch" signal is
  `SyncResult.performed` containing the section (the orchestrator ran the put). Set the per-watch
  `…SyncedAt` when the service reports that section performed. There is NO watch read-back for these
  unreadable sections (see `WP-DEFAULTS-STATUS.md`), so `performed` is as truthful as we can be.

## What already exists (REUSE — do NOT reimplement)

- **DB (WP4):** `AppDatabase` (Room, **version = 1**, `exportSchema = false`, no migrations yet,
  `fallbackToDestructiveMigration` NOT set). Entities: `WatchEntity` (PK `macAddress`; has
  `lastSyncTime: Long = 0`, `isActive`, `stepGoal`, `vibrationStrength`),
  `WatchAlarmEntity` (PK `[watchMac, slotId]`), `NotificationRuleEntity` (PK `[watchMac,
  packageName]`), `ButtonMappingEntity` (PK `[watchMac, buttonId]`). All children CASCADE-delete.
  `WatchRepository` is the suspend bridge (`upsertAlarm`, `deleteAlarmSlot`, `upsertRule`,
  `deleteRule`, `upsertButton`/equiv, `getActiveWatch`, `observeActiveWatch`, `observeRules(mac)`,
  `getRules(mac)`, etc.). DAOs: `WatchDao`, `WatchAlarmDao`, `NotificationRuleDao`,
  `ButtonMappingDao`.
- **Sync (WP14):** `SyncOrchestrator.sync(input, uploader, sections, mode)` → `SyncResult(mac,
  performed, skipped, errors)`. `SyncSection { ALARMS, NOTIFICATION_FILTER, BUTTONS, VIBRATION,
  NUDGE, SECOND_TIMEZONE }` + `ALARMS_ONLY`/`NOTIFICATIONS_ONLY`/`BUTTONS_ONLY`/`SETTINGS_ONLY`/`ALL`.
  `ServiceSaveToWatch.trigger(context, sections, forceProvision=false)` → `WatchConnectionService
  .syncNow(...)`. The service runs the pass in `submitSync(...)` (`WatchConnectionService.kt`
  ~line 644–657): after `val result = SyncStateReporter.reportAround(...) { SyncOrchestrator.sync(...) }`
  there is an `if (result != null) { Log.i(TAG, "sync done …") }` block — **add the per-section
  `…SyncedAt` write right there**, for each `section in result.performed`.
- **`WatchRepository` upsert paths (ALL must stamp `updatedAt`):** single-row `upsertAlarm` /
  `upsertRule` / `upsertButton` (lines ~98/110/115) AND the multi-row `upsertAll` paths used by
  defaults-seed / transfer / provisioning — `replaceDefaultsSections(...)` (line ~136) and the seed/
  transfer block (lines ~147–174). If you stamp `updatedAt` only on the single-row path, seeded/
  provisioned rows keep `updatedAt = 0` and show "pending" forever. So set it at the **repository
  layer in every write path** (single + `upsertAll` + replace/seed). See the ordering caveat in
  Step 1.
- **Per-screen save seams + screens (mirror these):**
  - Notifications: `NotificationsViewModel` (`saveToWatch()`, `playRule(pkg)`, intents `addRule`/
    `updateRule`/`deleteRule`/`setVibePattern`/`setHandPosition`; injects `NotificationSync`,
    `NotificationPlay`), `NotificationsScreen` (`NotificationsContent` stateless body + `RuleRow`),
    `NotificationSync` (`ServiceNotificationSync` → `NOTIFICATIONS_ONLY`).
  - Alarms: `AlarmsViewModel` (`saveToWatch()`, intents `addAlarm`/`updateAlarm`/`deleteAlarm`),
    `AlarmsScreen` (`AlarmsContent` + rows), `AlarmSync` (`ServiceAlarmSync` → `ALARMS_ONLY`).
  - Buttons: `ButtonsViewModel` (`saveToWatch()`), `ButtonsScreen`, `ButtonSync` (`BUTTONS_ONLY`).
  - Shared UI: `SyncSaveButton` (spinner + disable while SYNCING), `SyncSavingDialog` (blocking
    "Saving…" modal), `ConnectionBanner`, `SyncProgressUi` (maps the process-wide `SyncState`).
    `MainActivity` already uses `androidx.activity.compose.BackHandler` (line ~168) — reuse that for
    the leave-prompt.
- **WP11 just landed** (commits `3fe74bc`→`bff5fe6`): pure `NotificationDecider`,
  `PostedNotificationExtractor`, `NotificationDispatcher`, `NotificationPlay` seam
  (`ServiceNotificationPlay`/`NoopNotificationPlay`), `FossilNotificationListenerService` (+
  manifest), `NotificationAccess` (WP10 permission row in Setup), and a **per-app Play test button**
  on each Notifications rule row. See `WP11-STATUS.md`.

## Architecture rules (this codebase's conventions — follow EXACTLY)

- **Two-layer:** a pure, JVM/Robolectric-testable core (no Android Service/BLE) + a thin Android
  shell. The "is this row on the watch?" logic MUST be a pure helper (unit-tested), separate from
  the Room/service wiring.
- **Injectable seams** with `Service*` prod impl + `Noop*`/fake default (mirror `AlarmSync` /
  `NotificationSync` / `ButtonSync` / `NotificationPlay`). Any new "auto-save trigger" must be a
  narrow seam so the VM stays unit-testable without a service/BLE.
- **On-device effects are on-device-pending** — assert the pure helper + the seam calls + the
  Robolectric-testable VM/Room glue, NOT pixels or live BLE.
- **Invent NO new wire bytes. Do NOT touch the CLI or `:protocol` wire output.** Reuse the existing
  targeted-save paths (`ALARMS_ONLY` etc.).
- **No automatic git commits beyond the step commits described here. Do NOT push.**

## Steps (commit after EACH; full gates pass per commit)

### Step 1 — Synced-marker in the DB (shared pure core + Room migration)
- Add `val updatedAt: Long = 0` to `NotificationRuleEntity`, `WatchAlarmEntity`,
  `ButtonMappingEntity`. Set it (to `System.currentTimeMillis()`, injected/overridable for tests)
  on every upsert path in `WatchRepository` (or in the VM intents — pick the single chokepoint;
  prefer the repository so ALL writers get it). **Important:** the WP14 sync data-loader / compilers
  read these rows — adding a field must NOT change compiled wire bytes (the compilers ignore
  `updatedAt`; verify `:protocol` golden tests + the android sync tests stay green).
- Add to `WatchEntity`: `val alarmsSyncedAt: Long = 0`, `val notificationFilterSyncedAt: Long = 0`,
  `val buttonsSyncedAt: Long = 0`.
- **Room migration 1→2** (bump `AppDatabase` version to 2, add `addMigrations(MIGRATION_1_2)` with
  `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT 0` for each new column; 6 columns total across 4
  tables). Keep `exportSchema = false`. Provide the migration so existing installs don't wipe.
  (Confirm `DbTestBase`/Robolectric in-memory tests still build the schema — they create from the
  entities, so they get v2 automatically.)
- Write the synced timestamps in `WatchConnectionService.submitSync` at the `result.performed` hook
  (map `SyncSection.ALARMS→alarmsSyncedAt`, `NOTIFICATION_FILTER→notificationFilterSyncedAt`,
  `BUTTONS→buttonsSyncedAt`; write via the repo on the ble-worker; use `System.currentTimeMillis()`).
- **Pure helper** `qhybrid.android.sync.SectionSyncStatus` (or similar):
  `fun isOnWatch(rowUpdatedAt: Long, sectionSyncedAt: Long): Boolean = rowUpdatedAt <= sectionSyncedAt
  && sectionSyncedAt > 0` and `fun pendingCount(rows: List<Long>, sectionSyncedAt: Long): Int`.
  Decide the `sectionSyncedAt == 0` (never synced) case explicitly → everything pending. Unit-test
  thoroughly (never-synced, edited-after-sync, edited-before-sync, equal timestamps, empty rows).
- **ORDERING CAVEAT (decide + test):** a provision/seed writes the rows (`updatedAt = now`) and then
  the same connect's sync sets `…SyncedAt = now`. Because the comparison is `rowUpdatedAt <=
  sectionSyncedAt`, the sync timestamp is taken AFTER the row writes, so a freshly seeded+synced row
  is correctly "on watch" (equal or earlier `updatedAt`). Make sure the `…SyncedAt` write uses a
  timestamp captured when the sync pass COMPLETES (after the row writes), and that `isOnWatch` uses
  `<=` (not `<`) so an equal timestamp counts as synced. Add a test for the seed-then-sync case
  (seeded rows show on-watch after the provisioning sync performs the section).
- Gate → commit (`wp-syncstatus: …`).

### Step 2 — Per-row badge + pending banner (Notifications, Alarms, Buttons)
- Surface `…SyncedAt` from the active `WatchEntity` into each screen's UiState (the VMs already
  observe the active watch; add the timestamp + a derived `pendingCount` + per-row `isOnWatch` via
  the Step-1 pure helper). Keep the UiState a PURE function of the rows + watch (unit-test the
  derivation).
- Each row shows a small badge: ✓ "on watch" (synced) vs ⏳ "not synced" (pending). A header/banner
  shows "N change(s) not on the watch — Save to watch" when `pendingCount > 0` (hidden when 0).
- **Notifications Play button: DISABLE it for any rule that is NOT yet on the watch** (pending).
  (User decision: simpler than a warning note — a disabled ▶ makes it obvious the watch doesn't have
  this rule's vibe/hands yet. Enable it once the row is ✓ on-watch.) The per-row `isOnWatch` from the
  Step-1 helper drives the `enabled` flag. Keep `playRule` honest (it already no-ops for an unsaved
  package). Unit-test the VM-level `isOnWatch` per-row + the enable rule.
- Gate → commit.

### Step 2b — Settings: replace the buzz buttons with a vibration-pattern dropdown + one Buzz button
- In `SettingsScreen.kt` `TestVibrationCard`, the current UI is **two** fixed buttons ("Vibrate
  (single)"=5, "Vibrate (triple)"=1) PLUS a diagnostic "Put filter + send buzz" button.
- Replace ALL of them with **one vibration-pattern dropdown + a single "Buzz" button** that buzzes
  the selected pattern (play-only), so the user can feel each pattern.
- **The dropdown lists EXACTLY the reserved patterns**, NOT all 10. The distinct useful patterns are
  `qhybrid.protocol.requests.fossil.notification.BuzzPatterns.RESERVED_PATTERNS` = **`{1, 2, 3, 5, 6,
  7, 8}`** (7 entries; silent `0`/`9` and the `4≡3` duplicate are deliberately skipped). Label each
  with `VibePatterns.label(p)` (e.g. `1 — CALL (triple)`). Default the selection to `5`
  (`VibrationSync.PATTERN_SINGLE`).
- **The Buzz button uses the play-only path** — `vm.vibrateWatch(selected)` →
  `VibrationSync.buzz(pattern)` (`forceFilterPlay=false`) → the WP3 service's `buzzPlayOnly` (a SINGLE
  `NOTIFICATION_PLAY` put). This works because **all 7 reserved patterns are already on the watch**
  in the reserved filter (written at provisioning + folded into every notification sync via
  `BuzzPatterns.reservedEntries()`). Do NOT use `onVibrateWithFilter` / `forceFilterPlay=true` here.
- **REMOVE the diagnostic "Put filter + send buzz" button** (and you may drop the
  `onVibrateWithFilter` plumbing from this screen if nothing else uses it — but KEEP
  `vm.vibrateWatchWithFilter` / `VibrationSync.buzz(..., forceFilterPlay=true)` available; just stop
  surfacing the button. Check no other caller depends on it before removing the wiring).
- Disable the Buzz button with the same `progress.saveEnabled(hasActiveWatch)` rule. No new wire
  bytes. Update `SettingsViewModelTest`/`SettingsScreen` tests if they assert the old two/three-button
  behavior; add a test that the dropdown selection drives `vibrateWatch(selected)` for each reserved
  pattern (and that only reserved patterns are offered).
- Gate → commit.

### Step 3 — Leave-with-pending prompt (all 3 screens)
- When a screen has `pendingCount > 0`, intercept leaving it (the in-app nav away AND system back via
  `BackHandler`): show a "Save to watch?" dialog → **Save** (calls the screen's `saveToWatch()`, show
  the existing `SyncSavingDialog` modal, then leave) / **Leave** (navigate away, rows stay in DB) /
  **Cancel** (stay). NO silent auto-save. NO background-save (sync-on-connect covers backgrounding;
  do NOT add `onPause`/`onStop` push behavior). Keep the decision logic (should-prompt) pure +
  unit-tested; the dialog/nav is the thin shell.
- Note: `MainActivity` hosts the screens (bottom-nav/`NavigationBar`). The "leave" includes
  switching bottom-nav tabs — decide a clean interception (e.g. a shared "pending-guard" the nav
  consults) and document it. Keep it simple and consistent across the 3 screens.
- Gate → commit.

### Step 4 — Alarms auto-save-on-edit (drop the explicit Save on Alarms)
- Each `addAlarm`/`updateAlarm`/`deleteAlarm` in `AlarmsViewModel`, after the Room write, triggers
  the targeted `ALARMS_ONLY` save via the existing `AlarmSync` seam (the whole 32-slot file re-push —
  which is exactly what the manual Alarms save already does). **Debounce** rapid edits (e.g. coalesce
  within ~750 ms or save after the write settles) so a burst of toggles doesn't spam BLE — make the
  debounce injectable/pure-testable. The synced-marker (Steps 1–2) then flips each alarm row to ✓
  shortly after.
- **Show the blocking spinner modal on an auto-save** (user decision): the auto-save must publish
  `SyncState` SYNCING just like the manual save so the existing `SyncSavingDialog` ("Saving to
  watch…") appears while the alarm push is in flight, then SUCCESS/ERROR. `ServiceSaveToWatch
  .trigger` already publishes SYNCING synchronously — confirm the Alarms screen still renders
  `SyncSavingDialog(progress)` so the modal shows on the auto-triggered save (it does for the manual
  save today; keep it). If the debounce defers the trigger, publish SYNCING when the trigger
  actually fires (not on every keystroke) so the modal doesn't flicker on each edit — i.e. the modal
  appears once per coalesced save, not per edit.
- Remove the explicit "Save to watch" button from the Alarms screen (or keep it as a manual
  "re-sync now" that's now redundant — prefer REMOVING it for a clean UX, since every edit
  auto-saves). The leave-prompt (Step 3) becomes effectively a no-op for Alarms once edits
  auto-save (pendingCount returns to 0 quickly) — that's fine; keep the guard for the brief in-flight
  window / disconnected case.
- Consider whether Notifications/Buttons should ALSO auto-save: the user said "do it for alarms";
  for Notifications/Buttons KEEP the explicit Save + the leave-prompt (they're edited in richer
  multi-field dialogs where a save-per-keystroke would be wrong). Document this asymmetry in the
  status doc.
- Unit-test: an alarm edit triggers the `AlarmSync` (fake records the call); debounce coalesces a
  burst into one trigger.
- Gate → commit.

### Step 5 — Status doc
- Short `WP-SYNCSTATUS-STATUS.md`: the timestamp model (per-row `updatedAt` vs per-watch
  `…SyncedAt`, the `performed`-based write, the never-synced case), the badge/banner, the
  leave-prompt (prompt not auto-save; no background-save), the alarms auto-save (+ debounce) and why
  Notifications/Buttons stay manual, and what's on-device-pending. Add a one-line banner where
  appropriate. Commit.

## Mandatory gates (run before EVERY commit; trust XML counts not banners; do NOT push)

```
  ./gradlew :protocol:test               # 124, 0 failures (MUST stay — no wire change)
  ./gradlew :android:testDebugUnitTest   # currently 402, 0 failures/errors — report the new count
  ./gradlew :android:lintDebug :android:assembleDebug   # must succeed
  ./gradlew :cli:shadowJar && ./fossil-q --help | md5sum # must == 7533ceccb6b29f81f6172bd5a71c5b98
```

Android count helper:

```
  python3 -c "import glob,xml.etree.ElementTree as ET; t=f=e=0
  [ (t:=t+int(r.get('tests',0)), f:=f+int(r.get('failures',0)), e:=e+int(r.get('errors',0))) for x in glob.glob('android/build/test-results/testDebugUnitTest/*.xml') for r in [ET.parse(x).getroot()]];
  print(t,f,e)"
```

Prefer read/grep/find/ls over bash for exploration; `edit` for precise changes. **Git is clean at
commit `13a6a69`** (this prompt; the last CODE commit is `bff5fe6` — WP11 + the per-app Play button,
all landed). Do NOT push; do NOT touch the CLI or wire bytes.

## Before coding

Report the current gate counts (confirm 124 / 402 green). Then read: `WP11-STATUS.md`,
`WP-DEFAULTS-STATUS.md` (the "unreadable sections" rationale), the entities + `WatchRepository` +
`AppDatabase`, the `submitSync` `result.performed` hook in `WatchConnectionService.kt`, and one full
screen+VM+sync-seam trio (Notifications is the freshest). Then present a 4–6 bullet refined plan
(esp. the exact Room migration + where you set `updatedAt` + the nav-leave interception approach) and
**wait for approval before editing code.**
