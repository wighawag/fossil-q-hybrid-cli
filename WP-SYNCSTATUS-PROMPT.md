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
approved by the user. Do the work in the **4 committed steps** below (full gates pass + commit after
EACH step; adjust seams if you find something better, but keep the model).

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
  ~line 644): after `val result = SyncStateReporter.reportAround(...) { SyncOrchestrator.sync(...) }`
  there is an `if (result != null) { Log… }` block — **this is the hook point** to write the
  per-section `…SyncedAt` for each `section in result.performed`.
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
- Gate → commit (`wp-syncstatus: …`).

### Step 2 — Per-row badge + pending banner (Notifications, Alarms, Buttons)
- Surface `…SyncedAt` from the active `WatchEntity` into each screen's UiState (the VMs already
  observe the active watch; add the timestamp + a derived `pendingCount` + per-row `isOnWatch` via
  the Step-1 pure helper). Keep the UiState a PURE function of the rows + watch (unit-test the
  derivation).
- Each row shows a small badge: ✓ "on watch" (synced) vs ⏳ "not synced" (pending). A header/banner
  shows "N change(s) not on the watch — Save to watch" when `pendingCount > 0` (hidden when 0).
- Notifications Play button: if the tapped rule is **pending**, surface a brief note/snackbar
  ("Save to watch first — the watch doesn't have this rule yet") instead of (or in addition to)
  poking the play; keep `playRule` honest (it already no-ops for an unsaved package — extend so the
  UI can tell the user WHY). Unit-test the VM-level pending logic.
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
commit `bff5fe6`** (WP11 + the per-app Play button, all landed). Do NOT push; do NOT touch the CLI or
wire bytes.

## Before coding

Report the current gate counts (confirm 124 / 402 green). Then read: `WP11-STATUS.md`,
`WP-DEFAULTS-STATUS.md` (the "unreadable sections" rationale), the entities + `WatchRepository` +
`AppDatabase`, the `submitSync` `result.performed` hook in `WatchConnectionService.kt`, and one full
screen+VM+sync-seam trio (Notifications is the freshest). Then present a 4–6 bullet refined plan
(esp. the exact Room migration + where you set `updatedAt` + the nav-leave interception approach) and
**wait for approval before editing code.**
```
