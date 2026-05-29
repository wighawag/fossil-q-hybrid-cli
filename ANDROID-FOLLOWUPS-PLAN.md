# Android Follow-ups Plan — Buttons, Sync progress, Vibration ACK, Multi-watch UX, Defaults, Watch-first onboarding

> **Status:** PLAN ONLY (no code changes yet). This document scopes the follow-ups raised after
> WP-ACTIVITY landed, in the proven "provable-core-first + flag-deferred seam +
> commit-after-each-sub-part" style used by WP14 / WP16 / WP-ACTIVITY. Each work package below is
> independently shippable; they are ordered by value/risk. Nothing here changes protocol **wire
> bytes** — every BLE behaviour reuses an existing golden-tested path.
>
> **Work packages:** WP-BTN (button cardinality), WP-PROGRESS (sync spinner), WP-VIBEACK
> (confirmation buzz), WP-MULTIWATCH (multi-watch presentation + add/delete + export/import; removes
> clone/transfer), WP-DEFAULTS (defaults profile for unreadable sections + predefined button
> defaults + defaults export/import), WP-ONBOARD (watch-first onboarding).
>
> **Grounding (verified in code, 2026-05):**
> - Vibration write = `FossilQAdapter.setVibrationStrength` → `ConfigurationPutRequest`
>   `VibrationStrengthConfigItem` (config `0x0A`). It only **stores** the value; it does **not**
>   buzz. (`protocol/.../FossilQAdapter.java:713`.)
> - A real buzz primitive already exists: `FossilQAdapter.playNotificationWithPattern(...)` /
>   `playNotification(...)` (misfit `PlayNotificationRequest`, triggers vibration + hand move).
> - Buttons: the UI (`ButtonsScreen.SlotEditorDialog`) uses **multi-select checkboxes** for actions
>   in `SINGLE_ACTION`/`MUSIC_MULTIMODE`, and `SyncOrchestrator.entriesFor` emits **one wire entry
>   per stored id** — so a multi-checked "single action" button silently compiles into a
>   multi-entry cycle. The mode and the action cardinality disagree. (Bug.)
> - The dial-mode "cycle through modes in turn" toggle = `ButtonModes.CUSTOM_TOGGLE` and the
>   protocol `ButtonCompiler.compileMultiEntry` + `DialMode {ALERT, TIMEZONE_2, ALARM, DATE,
>   TWENTY_FOUR_HOUR}` — it exists and compiles, just needs clearer UI + correct cardinality rules.
> - Sync is **phone → watch only**. `SyncOrchestrator` reads Room and writes the watch; it never
>   reads settings back. Room is the source of truth. There is **no in-flight progress signal** the
>   UI can observe (fire-and-poke `syncNow`).
> - A watch → phone **config read DOES exist**: `FossilQAdapter.readConfig(CompletableFuture<List<
>   ConfigEntry>>)` returns the watch's live config incl. `0x0A VIBE_STRENGTH`,
>   `0x03 DAILY_STEP_GOAL`, `0x09 INACTIVE_NUDGE`, `0x11 SECOND_TIMEZONE_OFFSET`, battery, etc.
>   It is used by the CLI `read-config` command but is **NOT exposed on `FossilController`** (the
>   façade Android uses). (`protocol/.../FossilQAdapter.java:998`.)
> - "Add watch" today = `MainActivity.onAssociated()` fires `setAssociatedMac` + `startObserving` +
>   `connectNow` **and** `WatchRepository.registerWatch(mac)` immediately — registering an **empty
>   default-settings row before the watch is even connected/read**. So "added" == "associated".

---

## Cross-cutting design rules (apply to every WP below)

1. **Provable core first.** Put orchestration/decision/mapping logic in a pure, injectable,
   JVM/Robolectric-unit-testable piece with a fake seam. Keep the actual BLE behind the WP3
   service. Flip any `*_WIRED` flag true only once the real path is wired AND unit-tested.
2. **No new wire bytes.** Reuse `FossilController` / `FossilQAdapter` / the golden-tested compilers.
   If a needed method is missing on the façade, add a **thin golden-tested passthrough** in
   `:protocol` (e.g. `FossilController.readConfig(...)`).
3. **Single link writer.** Only `WatchConnectionService` talks to `AndroidBleTransport`, always on
   the **ble-worker** executor. Do NOT regress the ble-gatt HandlerThread.
4. **In-memory process-wide state holders** (mirroring `WatchState` / `ActivityState`) for live UI
   signals; persisted settings stay in Room (WP4). No speculative Room schema.
5. **Gates before every commit:** `./gradlew :protocol:test` (≥108), `:android:testDebugUnitTest`
   (≥183 + new, 0 failures), `:android:assembleDebug` + `:android:lintDebug`, `./fossil-q --help`
   unchanged (md5 `7533ceccb6b29f81f6172bd5a71c5b98`). Commit per sub-part; do **not** push.

---

## WP-BTN — Button mapping correctness (single-action vs dial-mode toggle)

**Problem.** The UI lets you tick multiple actions on a `SINGLE_ACTION` button (checkboxes), and
the compiler turns each ticked id into its own wire entry — silently producing a multi-entry cycle
that the user never intended. The genuine "cycle through several modes in turn" feature
(`CUSTOM_TOGGLE`) is present but under-surfaced and shares the same multi-select widget, so the two
concepts are visually indistinguishable.

**Decision (the cardinality contract).**
- `SINGLE_ACTION` → **exactly one** `ConfigPayload` action id.
- `MUSIC_MULTIMODE` → **exactly one** id (the multi-function is inside that payload, e.g.
  `FORWARD_TO_PHONE_MULTI` / `MUSIC_CONTROL`); offer only the music-capable actions.
- `CUSTOM_TOGGLE` → **one-or-more** dial-mode ids (the cycle). This is the "toggle that shows
  multiple modes in turn". Relabel in UI as "Cycle dial modes".

**Sub-parts (commit after each):**
1. **`wp-btn: cardinality core`** — a small pure helper (e.g. `ButtonMappingRules`) +
   normalization in `ButtonsViewModel.setSlot`: collapse non-toggle modes to a single id; keep the
   list only for `CUSTOM_TOGGLE`. Make `SyncOrchestrator.entriesFor` defensively honour the same
   rule (a `SINGLE_ACTION` mapping compiles to at most one entry even if a legacy row holds many).
   Unit tests: cardinality normalization + the compiled-bytes still match the WP7 golden compiler
   for representative single/toggle cases. Gates → commit.
2. **`wp-btn: UI single-select`** — `SlotEditorDialog`: render a **radio/dropdown single-select**
   action picker for `SINGLE_ACTION`/`MUSIC_MULTIMODE`; keep the multi-select chips ONLY for
   `CUSTOM_TOGGLE` (relabel "Cycle dial modes", clarify the help text). Preview/UI note that the
   editor cannot store an invalid combination. Gates → commit.
3. **(Optional, later) `wp-btn: per-model dial availability`** — use
   `ButtonCompiler.availableModes(DialModel)` to grey out dial modes the connected watch's face
   can't show (3-position vs 5-position). Needs a `DialModel` source (device info / user pick).
   Out of scope for the first pass (model-agnostic stays the default). Gates → commit.

**Acceptance:** the editor cannot produce a `SINGLE_ACTION` with >1 action; `CUSTOM_TOGGLE` clearly
reads as a cycle; compiled bytes for each mode reproduce the WP7 golden compiler; WP16d tests pass.

**Touches:** `android/.../buttons/ButtonsViewModel.kt`, `ButtonsScreen.kt`, `ButtonModes.kt`
(labels), `android/.../sync/SyncOrchestrator.kt` (defensive collapse), tests. **No wire change.**

---

## WP-PROGRESS — Sync progress indicator (spinner) for watch writes

**Problem.** Every "Save to watch" is fire-and-poke (`syncNow` = a `startService` intent) and
returns instantly; the UI shows only a static note. A real BLE write takes seconds (file transfer +
CRC + the bounded 30 s alarm-future wait). The user gets no feedback that something is happening.

**Decision.** Add a process-wide **`SyncState`** holder (mirrors `WatchState` / `ActivityState`):
`enum SyncPhase { IDLE, SYNCING, SUCCESS, ERROR }` + last `SyncResult` summary + timestamp. The WP3
service is the single writer: set `SYNCING` before `SyncOrchestrator.sync(...)`, then
`SUCCESS`/`ERROR` from the result. Each screen's ViewModel exposes it; the **Save button shows a
`CircularProgressIndicator` and disables while `SYNCING`**, then a transient success/error note.

**Scope note on "a spinner for each button":**
- The clean, deliverable interpretation = **a spinner on each screen's _Save to watch_ button**
  (Buttons, Alarms, Notifications, Settings) driven by `SyncState`.
- A spinner on each **physical** TOP/MIDDLE/BOTTOM card is NOT independently trackable: the button
  file is uploaded as **one combined `compileMultiEntry(top, mid, bot)` write**, so per-card
  spinners would all light up together. If desired, render the shared "buttons section syncing"
  state on all three cards — but it is the same underlying signal.

**Sub-parts (commit after each):**
1. **`wp-progress: SyncState holder`** — pure in-memory holder + `publish(phase, result?)`
   (clock injected). Unit tests (like `ActivityStateTest`). Gates → commit.
2. **`wp-progress: service wiring`** — `runOnConnectSync` / `submitSync` set
   SYNCING→SUCCESS/ERROR around `SyncOrchestrator.sync`. (BLE effect on-device-pending.)
   Gates → commit.
3. **`wp-progress: UI spinners`** — Save buttons across the screens observe `SyncState`
   (spinner + disabled while SYNCING; success/error note). VM exposes the flow; ViewModel tests
   assert the state mapping with a fake. Gates → commit.

**Acceptance:** triggering a sync flips `SyncState` SYNCING→SUCCESS/ERROR; the Save buttons reflect
it (headlessly tested via the holder + VM; the visual spinner is on-device-pending).

**Touches:** new `android/.../sync/SyncState.kt`, `WatchConnectionService.kt`, each `*Screen.kt` +
`*ViewModel.kt` Save path, tests. **No wire change.**

---

## WP-VIBEACK — Vibration-strength confirmation buzz

**Problem.** Setting vibration strength only stores config `0x0A`; the watch does not buzz, so the
user can't feel the new level (the Fossil app buzzes on change as a UX gesture). We have the buzz
primitive already.

**Decision.** After `applyVibrationStrength(n)` succeeds, send **one** play/vibrate so the user
feels the just-set strength. Sequence it AFTER the config write (on the ble-worker) so it uses the
new value. Reuse `FossilController` → `FossilQAdapter.playNotificationWithPattern(...)`; **invent no
bytes**. Add a small `FossilController` passthrough if a suitable one-shot "buzz now" method is not
already exposed on the façade (golden/contract-tested in `:protocol`).

**Sub-parts (commit after each):**
1. **`wp-vibeack: protocol passthrough`** — ensure `FossilController` exposes a one-shot buzz
   (e.g. `buzz()` / `playNotificationWithPattern`); contract test in `:protocol`. Gates → commit.
2. **`wp-vibeack: ordered apply+buzz`** — in the settings apply path (Uploader /
   `ServiceUploader.applyVibrationStrength`), do write-then-buzz on the ble-worker; unit-test the
   ORDER against a fake (write recorded before buzz). Decide: buzz only on an explicit user change
   (not on every reconcile sync) — gate it so the periodic safety sync does NOT buzz. Gates →
   commit.
3. **`wp-vibeack: Debug "Test vibration" button`** — add a Debug-menu (WP15, release-gated) "Buzz
   now" action so on-device verification needs no real notification. Gates → commit.

**Acceptance:** a user vibration-strength change writes `0x0A` then buzzes once (ordering
unit-tested); the periodic reconcile sync does NOT buzz; on-device the buzz intensity tracks the new
value (on-device-pending). **Testing recipe (on-device):** set 100 → trigger a real notification →
strong; set 25 → trigger again → weak; this isolates "stored & effective" from "acknowledge buzz".

**Touches:** `protocol/.../FossilController.java` (passthrough if needed),
`android/.../WatchConnectionService.kt` (ServiceUploader), `android/.../sync/Uploader.kt` /
`SyncOrchestrator.kt` (a "userInitiated" flag so reconcile doesn't buzz),
`android/.../debug/DebugTools.kt`, tests. **No wire change.**

---

## WP-MULTIWATCH — Multi-watch presentation + add/delete + export/import (removes clone/transfer)

**Problem.** Multi-watch is confusing today: the Dashboard has an "Active watch" dropdown
(`ExposedDropdownMenuBox`, "Receiving live notifications") but **adding** a watch is hidden behind
the gear→setup CDM flow and **deleting** only exists in the Debug menu ("Wipe", which only removes
the DB row, not the association). And the **clone/transfer** ("copy config from one watch to
another") is confusing now that defaults + onboarding exist.

**Decisions (your Q1–Q7 answers).**
- **Active-watch dropdown stays, kept clean** (selection only — no per-row actions in the dropdown).
  Clearly label the active watch as the one all actions apply to.
- **Add watch is first-class + easily available** (Q3): a prominent "Add watch" affordance on the
  Dashboard launching the existing WP3 CDM associate flow (the gear keeps permission/battery-opt
  setup). (Adding then runs WP-ONBOARD provisioning.)
- **Manage watches screen/sheet** for add + **delete** (Q1: keep dropdown clean → lifecycle lives
  here). Delete is destructive, with a confirmation ("Forget this watch? Removes its settings and
  stops auto-reconnect"). Delete = **full removal** (DB row CASCADE **+** un-associate + stop
  presence + disconnect) — the same full-delete WP-ONBOARD sub-part 4 implements.
- **Deleting the active watch auto-activates the first remaining** (Q2); none left → no-active state.
- **REMOVE clone/transfer entirely** (Q6 replacement): delete the Settings "Settings transfer" card,
  the Debug "Clone from→to", `SettingsViewModel.transferSettings`, and
  `WatchRepository.transferSettings` (+ their tests). Replaced by export/import below.
- **Export / import a watch's full config** (Q4/Q5/Q6): export the active watch's **entire** config
  — readable settings (vibration / step goal / nudge / 2nd-tz) **plus** alarms + notification rules
  + button mappings — as a **single JSON file**, shared via the existing `FileProvider` share-sheet
  (the WP15 log-export mechanism). Import via `ACTION_OPEN_DOCUMENT` file picker → **overwrite ALL**
  of the active watch's DB settings from the file, then sync to the watch on next save/connect.
  Import is the explicit path that lets a user deliberately override even the readable settings.

**Provable core.** A pure, JVM-tested **config codec**: `WatchConfig ⇄ JSON`
(`WatchConfig` = readable scalars + alarm/rule/button lists, `watchMac`-free). `encode` is
deterministic/stable; `decode` is tolerant (missing/extra/malformed fields → safe defaults, never
throws). A pure `applyTo(mac, config) → DB rows` mapper (re-keys to the target mac, full replace).
Shared with WP-DEFAULTS (defaults profile = the same shape minus scalars-vs-not split as noted).

**Sub-parts (commit after each):**
1. **`wp-multiwatch: config codec core`** — `WatchConfig` model + JSON encode/decode + `applyTo`
   mapper. Pure unit tests: round-trip identity, tolerance (malformed → defaults), full-replace
   semantics. Gates → commit.
2. **`wp-multiwatch: remove clone/transfer`** — delete the transfer card / Debug clone /
   `transferSettings` (VM + repo) + their tests. Gates → commit.
3. **`wp-multiwatch: export/import wiring`** — export active-watch config → JSON via FileProvider
   share; import via document picker → `applyTo` active watch (overwrite all) → mark dirty/sync.
   ViewModel/flow tests with a fake file source. Gates → commit.
4. **`wp-multiwatch: manage-watches UI`** — Dashboard "Add watch" button (CDM flow) + a Manage
   watches sheet/screen (list with per-row delete + confirmation); active-row labeled; delete →
   full removal + auto-activate first remaining. Keep the dropdown clean (selection only).
   Gates → commit.
5. **`wp-multiwatch: status banner`** — document the new IA, the removed clone/transfer, the
   export/import format + overwrite semantics, and the full-delete behaviour. Commit.

**Acceptance:** active watch is clearly indicated and selectable via the clean dropdown; Add watch
is reachable from the Dashboard; watches can be deleted (full removal + auto-activate first
remaining) with confirmation; clone/transfer is gone; a watch's full config exports to JSON and
imports back (overwriting the active watch); codec round-trip is identity (headless-tested). No wire
change (export/import only moves DB rows; the resulting sync reuses the golden compilers).

**Touches:** new `android/.../io/*` (config codec) + `android/.../watches/*` (manage screen),
`DashboardScreen.kt` / `DashboardViewModel.kt` (Add button + active labeling),
`SettingsScreen.kt` / `SettingsViewModel.kt` (remove transfer card + method), `DebugMenuScreen.kt`
/ `DebugTools.kt` (remove clone), `WatchRepository.kt` (remove `transferSettings`;
delete=full-removal lives in WP-ONBOARD), tests. **No wire change.**

> **Dependency note:** the full-delete teardown (un-associate + disconnect) is implemented in
> WP-ONBOARD sub-part 4; WP-MULTIWATCH's delete UI calls it. If WP-MULTIWATCH lands first, ship a
> minimal full-delete here and have WP-ONBOARD reuse it.

---

## WP-DEFAULTS — App-level "defaults profile" for the UNREADABLE sections only

**Your directive (Q4/Q5/Q6/Q7).** A single, app-level, user-editable **defaults profile** that is
applied to a watch **only at add/provision time**, and **only for the sections we cannot read back
from the watch**: default **alarms** (slots 0–15), default **notification rules**, default **button
mappings**. The **readable** settings (vibration / step goal / nudge / 2nd-tz) are **NOT** part of
the defaults profile — they are read from the watch (see WP-ONBOARD). A readable value that is
simply absent on the watch becomes its **blank/off** state (nudge off, 2nd-tz unset) or its
hardcoded constant where there is no meaningful "off" (vibration 50, step goal 10000) — never a
user default.

**Storage decision.** One app-level profile (not per-watch), stored like the WP16g
`SettingsPrefs` blob (or a tiny dedicated store). It holds: a list of default alarms, a list of
default notification rules, a list of default button mappings. May be **empty** for a section.
Calendar alarms (slots 16–31) are **NOT** in the profile — they come from WP13's calendar source,
not the user (see WP-ONBOARD note).

**FACTORY DEFAULTS (your spec — user-overridable).**
- **Alarms:** **empty** by default (a new watch starts with no standard alarms — "no surprises").
- **Notification rules:** **empty** by default.
- **Buttons:** **non-empty** — ship these three (the user can edit/clear any):
  - **Top** → `SINGLE_ACTION` = `STOPWATCH`.
  - **Middle** → `CUSTOM_TOGGLE` cycling `TIMEZONE_2, ALARM, DATE` (stored order = cycle order;
    the exact set/order is provisional and likely to change — keep it trivially editable).
  - **Bottom** → `MUSIC_MULTIMODE` = `MUSIC_CONTROL` (play/pause/prev/next).
- **Consequence (Q10, confirmed):** because buttons are an UNREADABLE section, every newly-added
  watch gets these three button mappings **pushed/overwritten** onto it at provision time (the
  "blank unreadable" wipe writes the profile content, which for buttons is non-empty). Alarms /
  notifications, being empty by default, get **blanked** on the watch. If the user clears the button
  defaults, new watches get blank buttons instead.

**Editing UI.** A "Defaults for new watches" sub-screen (reachable from Settings) reusing the same
editors the per-watch Alarms/Notifications/Buttons screens use, but bound to the profile store
instead of a watch row. (Could ship the store + a read-only summary first, full editors later.)

**Export / import of the defaults profile (Q7, confirmed).** The defaults profile can be
**exported to / imported from a JSON file** (same `FileProvider` share-sheet + `ACTION_OPEN_DOCUMENT`
picker WP-MULTIWATCH uses for per-watch config), so a user can carry their "new-watch defaults"
across installs/devices. Same serialization shape as a per-watch export, minus the `watchMac`.

**Sub-parts (commit after each):**
1. **`wp-defaults: profile store + model + factory defaults`** — an injectable `DefaultsProfile`
   seam + a SharedPreferences/JSON-backed impl holding default alarms/rules/buttons (reusing the WP4
   entity shapes minus the `watchMac`), pre-populated with the factory button defaults above. Pure
   (de)serialization + tolerance tests (empty / malformed → defaults/empty) + a test asserting the
   factory buttons (Top=Stopwatch / Middle=toggle / Bottom=Music). Gates → commit.
2. **`wp-defaults: apply-to-watch mapper`** — a pure `DefaultsProfile → (alarm rows, rule rows,
   button rows) for a given mac` mapper (re-keys to the new mac). Unit-tested (incl. the three
   factory button rows materialize correctly). (Consumed by WP-ONBOARD at provision time.)
   Gates → commit.
3. **`wp-defaults: editor UI`** — the "Defaults for new watches" screen (incl. reset-to-factory).
   Gates → commit.
4. **`wp-defaults: export/import`** — defaults-profile JSON export/import (shares the codec from
   WP-MULTIWATCH). Gates → commit.
5. **`wp-defaults: status banner`** — document the profile scope (unreadable-only), the factory
   button defaults, storage, export/import, and the apply-at-provision contract. Commit.

**Acceptance:** the profile round-trips; the factory buttons are Top=Stopwatch / Middle=toggle
(TIMEZONE_2,ALARM,DATE) / Bottom=Music; an empty alarm/rule section yields empty seed rows; a
profile with N default alarms yields exactly N alarm rows re-keyed to the new mac; export→import is
identity. No wire change (it only feeds DB rows that WP-ONBOARD then pushes).

**Touches:** new `android/.../defaults/*`, a Settings entry point, the shared config JSON codec
(WP-MULTIWATCH), tests. **No wire change.**

---

## WP-ONBOARD — Watch-first onboarding (read-readable + seed-unreadable-from-defaults + wipe; "added" only after)

**The locked model (your Q1–Q7 answers).** Adding a watch with an **unknown** MAC (no DB row —
either never added, or previously **deleted**) runs a one-time **provisioning** pass; the watch is
**not considered "added" until it completes**. read/seed/wipe happens **only** here — never on any
later sync.

**Provisioning sequence (on connect of an unknown MAC, on the ble-worker):**
1. **Read** the watch's config via `readConfig()` → seed the **readable** settings into the new
   `WatchEntity` / `SettingsPrefs`:
   - `0x0A VIBE_STRENGTH` → `WatchEntity.vibrationStrength` (clamped; absent → constant 50).
   - `0x03 DAILY_STEP_GOAL` → `WatchEntity.stepGoal` (absent → constant 10000).
   - `0x09 INACTIVE_NUDGE` → nudge prefs (absent/unset → **off**, not a default).
   - `0x11 SECOND_TIMEZONE_OFFSET` → 2nd-tz pref (absent/unset → **blank/unset**, not a default).
   - + live device info (battery / firmware / model) from the link.
   - **There is no user default for readable settings** — read wins; absent → blank/off/constant.
2. **Seed the UNREADABLE sections from the WP-DEFAULTS profile** (alarms 0–15, notification rules,
   button mappings), re-keyed to the new mac. Empty profile → empty seed.
3. **Persist** the seeded `WatchEntity` + child rows; mark active. **THIS is the moment the watch
   becomes "added".**
4. **Wipe-by-overwrite the watch to match the seed (full replacement, not merge):** run the sync
   for the unreadable sections so the watch ends up with **exactly** the seeded content and nothing
   else:
   - **Alarms:** `AlarmCompiler.compile(standard 0–15, calendar 16–31)` already builds **one whole
     32-slot file**; uploading it **replaces all 32 slots** — so any pre-existing watch alarms are
     wiped regardless of how many defaults there are (e.g. 2 default alarms ⇒ file has those 2,
     every other slot cleared). ✓ "wipe any potential others".
   - **Notification filter / buttons:** each is uploaded as one whole file too → full overwrite.
   - **Calendar alarms (slots 16–31) are re-synced as part of the SAME alarm file.** They are NOT
     in the defaults profile; they come from WP13's calendar source. At provision time we include
     whatever calendar rows exist in the DB (today: none until WP13 lands → those slots blanked,
     which is correct/no-surprises). Once WP13 lands, provisioning naturally writes them because
     they're part of the same 32-slot file. So "calendar should be re-synced too" is satisfied by
     writing the full alarm file at provision (and by WP13's own ongoing push thereafter).
   - **CRITICAL change vs. today:** the orchestrator currently **skips empty sections** (to avoid
     pushing empty files). For a **provisioning** pass we must do the **opposite** — push the
     (possibly empty) files to actively blank the watch. So the orchestrator needs a
     **`mode = PROVISION` (force-write-empties)** vs. the normal **`RECONCILE` (skip-empties)**
     path. Normal ongoing syncs keep skip-empties (we explicitly do NOT support "alarms set by
     other apps", per your Q3 — leaving them empty is fine and minimizes writes).
5. If `readConfig` **fails / returns empty** (e.g. unsupported Misfit firmware): still add the
   watch, seed readable settings from constants/off, seed unreadable from the defaults profile,
   wipe-by-overwrite as above, and surface a "couldn't read current settings" note. Never block
   onboarding. (Confirmed acceptable.)

**Delete = full removal (your Q1).** "Delete a watch" must remove the **DB row (CASCADE children)**
**and** the connection: clear the `CompanionManager` associated-MAC pref + the CDM association +
stop presence observing + disconnect. So a deleted watch will **not** auto-reconnect; re-adding it
is a fresh "unknown" provision. (Today's Debug "Wipe" only deletes the DB row — this WP makes
delete also tear down the association.)

**Associated-MAC pref vs. "added" (the split, confirmed acceptable).** Associate-time still sets
the `CompanionManager` SharedPreferences pref + arms presence + connects (so the hardware-verified
WP3 reconnect path is untouched), but it **no longer** writes a DB row. The DB "added" happens only
after provisioning. The pref is the "which MAC to reconnect to" pointer; the DB row is the
"officially added + provisioned" record. Delete clears both.

**Provable core.** Pure, JVM-unit-tested pieces, BLE behind the service:
- `ConfigToSeed` mapper: `List<ConfigEntry> + deviceInfo → (WatchEntity readable fields +
  nudge/2nd-tz pref values)` — present / absent→blank-off / clamped / malformed tolerant.
- `SyncOrchestrator` gains a `mode: SyncMode { RECONCILE, PROVISION }` param: PROVISION forces the
  unreadable-section writes even when empty (blank the watch); RECONCILE keeps skip-empties.
- A `WatchProvisioner` orchestration (pure, fake-seam): given config + defaults profile + mac →
  the seed rows + the PROVISION sync plan. Unit-tested end to end with fakes.

**Sub-parts (commit after each):**
1. **`wp-onboard: readConfig façade + ConfigToSeed core`** — add `FossilController.readConfig(...)`
   passthrough (contract-tested in `:protocol`) + the pure config→seed mapper (readable-only;
   absent→blank/off/constant). Tests. Gates → commit.
2. **`wp-onboard: orchestrator PROVISION mode`** — add `SyncMode { RECONCILE, PROVISION }` to
   `SyncOrchestrator` (PROVISION force-writes empties to blank unreadable sections; RECONCILE
   unchanged). Unit tests for both modes (incl. the full 32-slot alarm overwrite + calendar slots).
   Gates → commit.
3. **`wp-onboard: provisioning state + service flow`** — defer `registerWatch` out of
   `MainActivity.onAssociated`; add a PROVISIONING state holder ("Reading watch settings…"); on
   connect-of-unknown-MAC run readConfig → ConfigToSeed → seed rows from WP-DEFAULTS → persist +
   mark added → run a PROVISION sync. (BLE effect on-device-pending.) Gates → commit.
4. **`wp-onboard: full delete (DB + association)`** — make delete also clear the CDM association +
   pref + presence + disconnect (Debug "Wipe" and any user-facing delete). Tests for the repo/seam
   calls. Gates → commit.
5. **`wp-onboard: UI`** — setup/Dashboard shows "Reading watch settings… → Added"; delete confirms
   "this also forgets the watch / stops reconnecting". Gates → commit.
6. **`wp-onboard: status banner`** — document the provisioning state machine, readable-vs-unreadable
   split, PROVISION-vs-RECONCILE modes, the full-delete semantics, calendar-slots note. Commit.

**Acceptance:** adding an unknown/deleted watch reads its readable settings (vibration/stepGoal/
nudge/2nd-tz; absent→blank/off/constant), seeds the unreadable sections from the WP-DEFAULTS profile,
**wipes-by-overwrite** the watch (full 32-slot alarm file incl. calendar slots + whole notif/button
files) so watch == seed, and only THEN marks the watch added; delete removes the DB row AND tears
down the association/reconnect; known watches are never re-read/re-wiped; ongoing syncs stay
skip-empties. Headless: mapper + PROVISION-mode + provisioning transitions are unit-tested; the live
read/write is on-device-pending.

**Touches:** `protocol/.../FossilController.java` (readConfig passthrough), new
`android/.../onboard/*` (ConfigToSeed, WatchProvisioner), `android/.../sync/SyncOrchestrator.kt`
(+SyncMode), `MainActivity.kt` (defer registerWatch), `WatchConnectionService.kt` (provisioning read
+ PROVISION sync on connect; full disconnect on delete), `CompanionManager.kt` (un-associate on
delete), `WatchRepository`/`DebugTools` (delete = full removal), a PROVISIONING state holder, tests.
**No wire change** (reuses `readConfig` + the golden compilers).

---

## Suggested execution order

1. **WP-BTN** (fixes a real correctness bug; small, self-contained).
2. **WP-PROGRESS** (UX; introduces the reusable `SyncState` holder others can lean on).
3. **WP-VIBEACK** (UX; small; benefits from WP-PROGRESS's "userInitiated vs reconcile" split).
4. **WP-MULTIWATCH** (multi-watch IA + add/delete + export/import; introduces the shared config
   JSON codec WP-DEFAULTS reuses; removes clone/transfer).
5. **WP-DEFAULTS** (the unreadable-section defaults profile + factory button defaults; reuses the
   WP-MULTIWATCH codec; WP-ONBOARD consumes it).
6. **WP-ONBOARD** (largest; reorders onboarding, adds the watch→phone read seam + PROVISION mode +
   full delete). Do last so `SyncState`, the userInitiated-sync split, the config codec, and the
   defaults profile all already exist.

Each WP keeps `:protocol:test` ≥108 and `:android:testDebugUnitTest` green, `./fossil-q --help`
unchanged, the WP15 Debug Menu release-gated, and WP14 + WP16a–g + WP-ACTIVITY still working.
