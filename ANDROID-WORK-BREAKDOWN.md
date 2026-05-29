# Fossil Q Hybrid Android App — Work Breakdown (Isolated, Testable Parts)

This document breaks **[ANDROID-PLAN.md](ANDROID-PLAN.md)** into discrete work packages (WPs). Each WP is designed to be:

- **Self-contained**: handed to a fresh context with only this section + named reference files.
- **Independently testable**: has explicit verification that does NOT require later WPs.
- **Decoupled**: depends only on earlier WPs through narrow, named interfaces (mostly `BleTransport`, the Room DAOs, and the protocol "compile bytes" helpers).

> **Testing philosophy**: We separate **pure logic** (byte compilation, parsing, time/alarm math, calendar→alarm mapping) from **platform glue** (BLE, services, Compose). Pure logic is unit-tested on the JVM with NO device and NO emulator. Platform glue is tested with instrumented tests or manual on-device checklists. This lets ~70% of the work be verified without hardware.

---

## Dependency Graph

```
 WP0  Build split (:protocol / :cli / :android)
   │
   ├─► WP1  Protocol "headless façade" (FossilController + FakeBleTransport)   [JVM unit-testable]
   │     │
   │     ├─► WP2  AndroidBleTransport (real BluetoothGatt)        [on-device]
   │     │     │
   │     │     └─► WP3  Foreground service + CompanionDeviceManager reconnect  [on-device]
   │     │
   │     ├─► WP4  Room DB + multi-watch + clone/transfer          [JVM/Robolectric unit-testable]
   │     │
   │     ├─► WP5  Alarm domain logic (16/16 split, byte compile)  [JVM unit-testable]
   │     ├─► WP6  Notification rule compile + play file           [JVM unit-testable]
   │     ├─► WP7  Button mapping compile (multi-entry, dial modes) [JVM unit-testable]
   │     ├─► WP8  Activity/Sleep/Calorie parsing surface          [JVM unit-testable]
   │     └─► WP9  Calendar→alarm mapping (pure)                    [JVM unit-testable]
   │
   ├─► WP10 Permissions/setup carousel (Compose)                  [on-device / UI test]
   ├─► WP11 Notification listener glue → WP6                      [on-device]
   ├─► WP12 Button event glue (gesture → media/find-phone) → WP7  [on-device]
   ├─► WP13 Calendar provider read + ContentObserver → WP9        [Robolectric/on-device]
   ├─► WP14 WorkManager periodic + sync-on-connect orchestration  [instrumented]
   ├─► WP15 In-app log viewer (level filter, export)              [JVM + UI]
   └─► WP16 Compose UI screens (dashboard, alarms, notif, buttons, calibration, sleep, settings)  [on-device]
```

**Key idea:** WP5–WP9 are *pure byte/logic packages* that only depend on the owned protocol classes (`qhybrid.protocol.*`) and produce `byte[]` / domain objects. They can each be built and unit-tested in complete isolation against the documented wire formats in `FINDINGS.md`, with zero Android and zero hardware. They are the safest, highest-leverage work to parallelize.

> **⚠️ POST-RE-OWN STATUS (read this first).** The PROTOCOL-REOWN-WP is **DONE**
> (see `PROTOCOL-REOWN-WP.md`, `PROTOCOL-PROVENANCE.md`). Several assumptions below
> were written *before* the re-own and are now **OBSOLETE** — ignore them:
> - There is **no vendored GadgetBridge tree, no shims, no `sync.sh`, no
>   `androidStrippedJar`/`androidApi`** anymore. The protocol is **owned**, pure,
>   platform-neutral Java in package **`qhybrid.protocol.*`** (deps: slf4j only).
> - The request classes are **ours to edit** — the old "vendored code is never
>   modified" rule no longer applies.
> - The canonical entry point is the **`qhybrid.protocol.FossilController`** façade
>   with settings **passed in** via `qhybrid.protocol.model.{SyncSettings,
>   NotificationFilterEntry}`. Do NOT `new FossilQAdapter(...)` + disk-load.
> - Class locations now: `qhybrid.protocol.FossilQAdapter`,
>   `qhybrid.protocol.BleTransport`, `qhybrid.protocol.ButtonConfigBuilder`,
>   `qhybrid.protocol.ActivityParser`; `ButtonGestureDetector` is nested in the
>   adapter. Disk config (`DeviceConfig`/`GlobalConfig`/`NotificationConfig`) moved
>   to **`:cli`**. `:cli` and `:android` both use plain `project(':protocol')`.
> - Byte builders for WP5–WP9 are owned and partly static:
>   `FossilQAdapter.buildNotificationFilterFile(...)` (WP6),
>   `AlarmsSetRequest.createFileFromAlarms(...)` + `Alarm` (WP5),
>   `ButtonConfigBuilder`/`buttonconfig.ConfigFileBuilder` (WP7),
>   `ActivityParser` (WP8). Unit-test against the golden tests in
>   `protocol/src/test` (`FakeBleTransport` + golden bytes already exist).
> - Runtime gotcha: the Fossil Q is single-link; CLI `le-connection-abort-by-local`
>   just means the phone is still connected — disconnect it first (transport issue,
>   not protocol).

---

> **Companion document:** This breakdown is meant to be read **alongside [ANDROID-PLAN.md](ANDROID-PLAN.md)**. The plan describes *what* the app does and *why* (architecture, schema, feature behavior); this document describes *how to slice the work* into isolated, testable pieces. Each WP names the exact `ANDROID-PLAN.md` sections it implements. Hand a fresh context **both files** + the WP's named reference files.

> **~~Vendored code is never modified.~~ (OBSOLETE after the re-own.)** The protocol
> is now **owned** under `qhybrid.protocol.*` and is ours to edit. The former
> vendored `gadgetbridge/` tree, the `android/`/`androidx/`/`nodomain/` shims, and
> `sync.sh` have all been **deleted**. Provenance is recorded in
> `PROTOCOL-PROVENANCE.md` + `NOTICE`. The helper builders (notification filter,
> button config, etc.) live in `qhybrid.protocol.*` (some as static methods).

> **What we derive from Gadgetbridge:** the **protocol layer** (Fossil Q request
> classes, file transfer, encoders, auth) was derived from GB and re-owned as clean
> platform-neutral Java. We do **NOT** use GB's Android BLE engine (`BtLEQueue`,
> `DeviceCommunicationService`, `ControlCenterv2`). `AndroidBleTransport` is NEW
> code implementing the same `qhybrid.protocol.BleTransport` seam the CLI uses
> ("Option A" — thin transport). See ANDROID-PLAN.md §1.

### Architecture decision: thin transport (Option A) vs. vendor GB's real BLE stack (Option B)

**Option A (chosen and now fully realised):** Use our owned `qhybrid.protocol.*` classes; write a NEW `AndroidBleTransport` implementing the `BleTransport` seam; write our own lightweight foreground service + `CompanionDeviceManager` reconnect + Compose permission carousel. Keeps the app lightweight and Compose-native. (The protocol re-own removed the last reason this was ever in tension with the build.)

**Option B:** Vendor GB's REAL Android BLE engine (`BtLEQueue`, `DeviceCommunicationService`, `ControlCenterv2`, permission activities, coordinators). Maximizes reuse of GB's battle-tested service/permission code but pulls in dozens of deeply-coupled classes (`GBApplication`, greenDAO, prefs) that this repo deliberately stubbed out — large, messy, and fights the "lightweight" goal. Would also bloat the `BleTransport` seam the CLI depends on.

**This breakdown assumes Option A throughout.** If Option B is chosen, WP2 / WP3 / WP10 change substantially and a separate "re-vendor GB BLE stack" WP must be inserted before WP2. (Hybrid option: stay on Option A but vendor only GB's permission *wizard screens* in isolation, without its BLE engine.)

### Reference trees (read-only — NEVER compiled into our build)

Two full reference trees exist locally and should be consulted by the relevant WPs. **Neither is added to any `srcDirs` or dependency** — they are read-only references we copy *logic and byte formats* from, re-expressed in our own (Compose / `BleTransport`-seam) code:

- **`tmp/Gadgetbridge/`** — the FULL upstream Gadgetbridge source (not the partial slice in `gadgetbridge/`). Useful patterns:
  - `service/btle/BtLEQueue.java` (~1098 lines) — GATT callback handling, write-with/without-response selection, MTU negotiation, CCCD descriptor writes, reconnect/backoff. **Borrow the logic behind `BleTransport`; do NOT adopt the class** (it hard-depends on `GBApplication`, `GBExceptionHandler`, `R`, `DeviceSupport`, coordinators, greenDAO).
  - `service/GBCompanionDeviceService.java`, `util/BondingUtil.java` — `CompanionDeviceManager` association + bonding state machine.
  - `util/PermissionsUtils.java`, `activities/welcome/WelcomeFragmentPermissions.java`, `activities/PermissionsActivity.java` — permission list/check logic + rationale flow (GB uses XML Views/RecyclerView; we re-express in Compose).
  - `deviceevents/GBDeviceEventFindPhone.java` — CDM-based find-phone.
- **`tmp/FossilOfficialApp-deobf/`** — the deobfuscated official Fossil app. The ground-truth **byte-format reference**: cross-check our reverse-engineered encoders (notification filter, button config, alarm modes, play file) against the official app's actual encoders. Upgrades WP5/WP6/WP7 golden bytes from "hardware-observed" to "hardware-observed AND confirmed against the source encoder."

**Per-WP reference mapping:** WP2 → `BtLEQueue`; WP3 → `GBCompanionDeviceService` + `BondingUtil`; WP10 → `PermissionsUtils` + welcome flow; WP12 → `GBDeviceEventFindPhone`; WP5/WP6/WP7/WP9 → `FossilOfficialApp-deobf` encoders for byte-format confirmation.

---

## WP0 — Gradle Multi-Module Split

**Status:** ✅ DONE. Multi-module split (`:protocol` / `:cli` / `:android`) complete; CLI behaves identically (run via `./fossil-q`). *(Historical note: the original split used a vendored `protocol/gadgetbridge/` tree + `sync.sh` + shim-stripped jar. The PROTOCOL-REOWN-WP later **removed all of that** — protocol is now owned `qhybrid.protocol.*`. `DeviceConfig`/`GlobalConfig`/`NotificationConfig` now live in `:cli`.)*

**Goal:** `:protocol` (pure Java) + `:cli` (Java app) + `:android` (Android app) in one tree. CLI stays 100% working.

**Scope:**
- `settings.gradle`: include `:protocol`, `:cli`, `:android`.
- ~~Move vendored `gadgetbridge/` + shims into `:protocol`.~~ *(Superseded: the protocol was later re-owned as `qhybrid.protocol.*`; the vendored tree + shims were deleted.)*
- `:cli` depends on `:protocol`; keeps `Main.java`, `DbusTransport`, `BluezTransport`, JLine, JSON config.
- `:android` skeleton only (empty `Activity`, `srcDirs` reuse pattern from the plan), depends on `:protocol`.
- ~~Keep `sync.sh` working.~~ *(`sync.sh` has since been removed.)*

**Reference files:** `build.gradle`, `settings.gradle`, `ROADMAP.md` (Phase 2), `ANDROID-PLAN.md` §1.

**Isolated test (no hardware):**
- `./gradlew :protocol:compileJava` succeeds.
- `./gradlew :cli:shadowJar` produces the same `fossil-q` jar; `fossil-q --help` lists all existing commands.
- `./gradlew :android:assembleDebug` builds the empty skeleton APK.
- Smoke: run one CLI command that doesn't need hardware (e.g. an `--help`/dry-run path) to confirm classpath wiring.

**Done when:** all three modules compile; CLI jar behaves identically to before the split.

---

## WP0.5 — Walking Skeleton (Installable "Hello-Watch" APK)

**Status:** ✅ DONE — FULLY VERIFIED ON HARDWARE. On a real phone the APK: connects → downloads supported file versions (full file-transfer over 3dda0003/0004) → runs the complete Fossil auth handshake (status `03 07 00` → request → watch VIBRATES → user presses TOP button → `03 06 00 01` ACCEPTED) → Android creates the BLE bond → reads live device info (Model `HW.0.0`, Firmware `HW0.0.2.9r.v3`, Battery `22%`, Protocol `Fossil 2.x`). This exercises far more of the protocol than the WP0.5 minimum.

Implemented: real `AndroidBleTransport` (Kotlin) bridging async `BluetoothGatt` to the blocking `BleTransport` contract; a Compose screen (editable MAC field prefilled `D9:20:71:11:74:2A`, permission button, Connect button) driving `FossilQAdapter` off the main thread; slf4j→logcat logging (`slf4j-android`). *(Historical: WP0.5 used a SHIM-STRIPPED `:protocol` artifact (`androidApi`/`androidStrippedJar`) so the real Android SDK resolved. The PROTOCOL-REOWN-WP **removed** that machinery — `:android` now uses a plain `project(':protocol')` because the protocol has no Android stubs at all.)*

**Critical bugs found & fixed during on-device bring-up (the shim-strip exposed real Android-API mismatches):**
1. `new BluetoothGattCharacteristic(uuid)` (1-arg) existed on our JVM stub but NOT on real Android → `NoSuchMethodError`/crash. Fixed: use the real 3-arg constructor `(UUID, properties, permissions)` in `FossilQAdapter` + `QHybridSupport`, and add that constructor to the stub so both targets compile.
2. `AndroidBleTransport` wrote `ENABLE_NOTIFICATION_VALUE` to ALL characteristics, but Fossil uses **INDICATE** on `3dda0003`/`3dda0005` (write+indicate) and NOTIFY on the rest (FINDINGS #2). With the wrong CCCD value the watch never sent the file header (3dda0003) or the auth response (3dda0005) → NPE in `FileGetRawRequest` + a ~60s auth stall with no vibrate. Fixed: pick `ENABLE_INDICATION_VALUE` vs `ENABLE_NOTIFICATION_VALUE` from the characteristic's properties.
3. `AndroidBleTransport.connect()` must enable notifications on the six `3dda000x` characteristics before init (the protocol assumes the transport did this, like `BluezTransport`).

NOTE: this skeleton calls `FossilQAdapter` directly — the `FossilController` façade remains WP1's deliverable. The on-screen "if the watch vibrates" message is a hedge because the adapter doesn't yet surface live auth state to the UI; a proper auth-state callback belongs to WP1/the re-own WP.

**Goal:** Prove the *entire toolchain* end-to-end with the smallest possible runnable APK, BEFORE building any features. This de-risks the build/permission/BLE setup early so we know the foundation is sound.

**Scope (deliberately minimal):**
- `:android` produces an installable debug APK with a single Compose screen.
- One "Request permissions" button (Bluetooth/Scan only — minimal subset).
- One "Connect" button that, given the known MAC, uses `:protocol` (`FossilController` + `AndroidBleTransport` stub-or-minimal) to attempt a BLE connect and print device info (battery, firmware) to the screen + logcat.
- It is OK if `AndroidBleTransport` is only a first-pass here; WP2 hardens it. The point is to prove `:protocol` links and runs inside an APK on a real phone.

**Depends on:** WP0. (Pairs with an early/partial WP2 and a minimal slice of WP10.)

**Reference files:** `ANDROID-PLAN.md` §1 (build), §2 (permissions), §4.A; `BleTransport.java`.

**Isolated test (on-device):**
- `./gradlew :android:installDebug` installs on a phone.
- App launches, requests BT permission, and on "Connect" shows the watch's battery % + firmware string (or a clean error if out of range).

**Done when:** a real phone runs the APK and reads at least one piece of live data from the watch. This is the "the setup works" milestone you asked for.

---

## WP1 — Protocol Headless Façade + FakeBleTransport

**Status:** ✅ DONE (delivered by the PROTOCOL-REOWN-WP). `qhybrid.protocol.FossilController`
is the façade; `qhybrid.protocol.FakeBleTransport` (+ `FileTransferResponder` /
`FileGetResponder`) is the test fake. 22 JVM tests in `protocol/src/test` lock the
wire bytes (golden alarms/config/notif-filter/activity + full file-put + auth
handshake). Settings are passed in via `qhybrid.protocol.model.{SyncSettings,
NotificationFilterEntry}` (no disk loading in :protocol). Callbacks: `onActivityData`,
`onEventJson`, `onAuthRequired`, `onConfigSynced`.

**Goal:** A clean, platform-agnostic entry point into the protocol that both Android and tests drive, plus an in-memory fake transport for unit testing the request/response queue without any BLE.

**Scope:**
- Define/confirm a small façade (e.g. `FossilController`) over `FossilQAdapter` exposing: `connect/init`, `syncTime`, `setAlarms(byte[])`, `uploadNotificationFilter(byte[])`, `playNotification(...)`, `setButtons(byte[])`, `setInactivityNudge(...)`, `setVibrationStrength(...)`, `requestActivity()`, callbacks for `onInitialized`, `onActivityData`, `onEventJson`.
- Implement `FakeBleTransport implements BleTransport`: records writes, lets a test inject notification frames (e.g. canned auth `03 07 01`, file-put acceptance type 3, CRC confirm type 8, close type 4).
- No new protocol behavior — just expose + make testable what `FossilQAdapter` already does.

**Reference files:** `protocol/src/main/java/qhybrid/protocol/FossilQAdapter.java`, `qhybrid/protocol/FossilController.java`, `qhybrid/protocol/BleTransport.java`, `FINDINGS.md` (#8 file transfer flow, #14 auth, #5 MTU). **STATUS: DONE** — `FossilController` façade + `FakeBleTransport` + golden tests delivered by the PROTOCOL-REOWN-WP; settings are passed in via `SyncSettings`.

**Isolated test (no hardware):**
- Drive `FakeBleTransport` through a full init sequence and assert the exact write byte sequences (animation → MTU handled locally → device info → config → buttons → INITIALIZED).
- Auth handshake: inject `03 07 00` then `03 06 00 01`, assert `02 06 ...` confirm was written.
- File-put: assert chunking to MTU, CRC confirmation handling, and `onFilePut(true)`.

**Done when:** the protocol can be exercised end-to-end on the JVM with deterministic, asserted byte traffic.

---

## WP2 — AndroidBleTransport (real BluetoothGatt)

**Status:** ✅ DONE — HARDWARE-VERIFIED. `android/.../AndroidBleTransport.kt` is now a
robust `BleTransport` over `BluetoothGatt`, and `MainActivity` drives the protocol through
the `FossilController` façade (not the raw `FossilQAdapter`). Verified on a real phone:
fresh-bond `connect → watch vibrates → TOP button → 03 06 00 01 ACCEPTED → createBond →
INITIALIZED` in ~4.5s (all real bond-negotiation time, no dead wait); already-bonded
fast-path `03 07 01 → INITIALIZED` with no button; clean Disconnect; reads battery 22% /
firmware HW0.0.2.9r.v3 / model HW.0.0. `:protocol:test` (22) stays green; `./fossil-q --help`
unchanged.

**Hardening delivered over the WP0.5 first pass (all behind the `BleTransport` seam — NO
protocol/wire-byte changes):**
1. **Per-characteristic write-type** (FINDINGS #2): `WRITE_TYPE_NO_RESPONSE` (command) for the
   write+notify / write-without-response chars (3dda0002/0004/0006/0007); `WRITE_TYPE_DEFAULT`
   (request) only for the INDICATE chars (3dda0003/0005). Mirrors `BluezTransport.getWriteType()`.
   *(WP0.5 wrongly used request for everything.)*
2. **Connect → `STATE_CONNECTED` latch → discover with retry/backoff** (3 attempts, 250ms·n),
   verifying the six Fossil chars resolved; clean timeout → disconnect+false.
3. **`refreshGattCache()`** via reflective `BluetoothGatt.refresh()` — **option (b): fallback
   only**, between failed discovery attempts, to clear a stale service cache.
4. **Proactive `requestMtu(512)`** in `connect()` wired to `mtuCallback` (FINDINGS #5 — the
   adapter never issues an ATT-layer MTU request itself); negotiated to 185 on this watch.
5. **`pair()` polls `device.bondState`** (the authoritative system state) instead of the
   `ACTION_BOND_STATE_CHANGED` broadcast. *The broadcast-receiver approach missed the BONDED
   transition and cost a full 30s timeout AFTER the bond had already succeeded — the polling
   version returns ~within 200ms of the real bond completing.* Short-circuits if already bonded;
   exits early on return-to-NONE after BONDING (genuine failure).
6. **Stale-callback guards**: each read/write op records its expected characteristic UUID; late
   callbacks from a prior op are ignored so they can't complete the next op's latch.
   Submission-failure (write/read/descriptor/MTU returning false) is handled, not hung.
7. **Clean teardown**: best-effort disable notifications → `disconnect()` + `close()` → release
   all in-flight latches → single connection-state callback (unexpected drops fire it too, so
   out-of-range/return is visible; intentional disconnect doesn't double-fire).
8. **`MainActivity` via `FossilController`**: auth prompt shown only when the watch actively
   requests auth; live `Link: Connected/Disconnected` from the connection callback; Disconnect
   button; GATT teardown on `onDestroy`.

**Goal:** Implement `BleTransport` on top of Android's native `BluetoothGatt`.

**Scope:**
- GATT connect/discover/services, characteristic write (command vs request per `FINDINGS.md` #2), `enableNotifications` (CCCD), MTU request, notification callback fan-out.
- Bonding via `device.createBond()` after auth (plan §4.A).
- Map Android threading to the adapter's callback model.

**Depends on:** WP0, WP1 (interface + façade).

**Reference files:** `BleTransport.java`, `FINDINGS.md` #2 (write types), #7 (pairing/agent), #15 (connection timing), `ANDROID-PLAN.md` §4.A.

**Isolated test (on-device, minimal):**
- Standalone test harness Activity: connect to the known MAC, run init via `FossilController`, log to logcat: battery %, firmware, "INITIALIZED".
- Verify the auth button-press flow once (fresh bond) and once (already bonded → `03 07 01`).
- No UI, no DB — just transport + façade.

**Done when:** the test harness reliably connects, authenticates, and reports device info on real hardware. ✅ Met — see Status above.

---

## WP3 — Foreground Service + Companion Reconnect

**Status:** ✅ DONE — HARDWARE-VERIFIED. A foreground `WatchConnectionService`
(`START_STICKY`, `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`, persistent notification) now
OWNS the `FossilController` + `AndroidBleTransport` for the session (ownership moved out of
`MainActivity`), driving all blocking transport work on a single `ble-worker` thread.
Reconnect is event-driven via `CompanionDeviceManager` — NO continuous scanning. Verified
on a real phone (API 31+):
- **Associate** (CDM chooser) → persist MAC → arm `startObservingDevicePresence` →
  auto-connect → fresh bond (TOP button → `03 06 00 01` → `createBond`) → INITIALIZED.
- **Close app** → service keeps the link; persistent notification stays (Connected · NN%).
- **Out of range** (battery pulled) → GATT timeout (status 8) → DISCONNECTED, presence
  re-armed; **back in range** → system CDM `onDeviceAppeared` wakes the service →
  bonded fast-path `03 07 01` → INITIALIZED in ~1s, **without opening the app**.
- **Reboot** → `BootReceiver` (BOOT_COMPLETED allow-list) re-arms presence + starts the
  service → CDM wake → reconnected hands-free (confirmed via `dumpsys`: FGS foreground,
  LE-encrypted bonded link, `WatchPresenceService` bound by system uid 1000).
- No `startScan` anywhere in the logs; no ANRs; clean teardown on every drop.

`:android:assembleDebug` + `:android:lintDebug` clean; `:protocol:test` (22) stays green;
`./fossil-q --help` unchanged; `AndroidBleTransport.kt` / `FossilController` / `BleTransport`
/ wire bytes untouched.

**New files** (all `qhybrid.android`): `WatchState.kt` (process-wide `StateFlow<WatchStatus>`),
`WatchConnectionService.kt` (FGS, owns controller/transport, static `connectNow/syncNow/
disconnect/onDeviceAppeared/stop`), `WatchPresenceService.kt` (`CompanionDeviceService`,
API 31+ presence callbacks), `CompanionManager.kt` (associate / observe / MAC persistence in
SharedPreferences — isolated so WP4 can swap to Room / battery-opt prompt), `BootReceiver.kt`,
`ReconnectFallback.kt` (minimal one-shot MAC-filtered scan for API 26–30; no-op on 31+).
`MainActivity.kt` rewritten as a thin client (observe StateFlow + Associate/Connect/Sync/
Disconnect/Battery-exempt buttons; reuses WP2 auth-prompt-only-when-needed via AUTH_REQUIRED).
The `runOnConnectSync` hook is a deliberate no-op placeholder for WP5/6/9; WP14 calls `syncNow`.

**Manifest deltas:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` (31+), `POST_NOTIFICATIONS` (33+),
`ACCESS_COARSE_LOCATION` (≤30 lint fix); the service, the CDM presence service
(`BIND_COMPANION_DEVICE_SERVICE` + `android.companion.CompanionDeviceService` intent-filter),
and the boot receiver; `companion_device_setup` feature.

**Goal:** Keep the link alive in the background and auto-reconnect when the watch reappears.

**Scope:**
- Foreground service hosting the `FossilController`/transport; persistent notification.
- `CompanionDeviceManager.associate()` flow; handle `onDeviceAppeared` → connect.
- Battery-optimization exemption prompt; boot-completed restart.
- Exposes a connection-state `StateFlow` and a "sync now" entry point for other WPs.

**Depends on:** WP2.

**Reference files:** `ANDROID-PLAN.md` §2 (Companion), §4.A, §5.

**Isolated test (on-device checklist):**
- Associate watch; walk out of range → service shows "disconnected"; walk back → auto-reconnects without opening the app.
- Reboot phone → service restarts and reconnects.
- Confirm no continuous scanning (battery): verify reconnect is event-driven.

**Done when:** disconnect/reconnect and reboot survival work hands-free.

---

## WP4 — Room Database + Multi-Watch + Clone/Transfer

**Goal:** Persistence layer with the 4 entities and the settings-transfer operation.

**Scope:**
- `WatchEntity`, `WatchAlarmEntity`, `NotificationRuleEntity`, `ButtonMappingEntity` + DAOs (plan §3).
- Active-watch selection; per-watch queries.
- `transferSettings(fromMac, toMac)` bulk copy with `REPLACE`.

**Depends on:** WP0 (module exists). No BLE.

**Reference files:** `ANDROID-PLAN.md` §3.

**Isolated test (JVM/Robolectric, in-memory DB):**
- CRUD round-trips for each entity.
- Foreign-key cascade delete (deleting a watch removes its alarms/rules/buttons).
- `transferSettings`: seed Watch A with N alarms/rules/buttons → transfer → assert Watch B has identical rows under its MAC, Watch A untouched.

**Done when:** all DAO tests pass headlessly; transfer is verified by assertions.

---

## WP5 — Alarm Domain Logic (16/16 split + byte compile)

**Status:** ✅ DONE & VERIFIED (JVM, no hardware). Pure helper added to `:protocol`:
`qhybrid.protocol.requests.fossil.alarm.AlarmSlot` (platform-neutral domain object
mirroring WP4's `WatchAlarmEntity`) + `AlarmCompiler` (16/16 split + 3 wire modes +
32-slot guard). 14 new golden-byte tests (`Wp5AlarmCompilerTest`) green; `:protocol:test`
now 36 total (22 prior + 14), 0 failures. `./fossil-q --help` unchanged;
`:android:assembleDebug` succeeds. No protocol wire bytes / `Alarm.java` /
`AlarmsSetRequest.java` / `BleTransport` / `AndroidBleTransport.kt` / WP3 service touched.

- **Placement:** pure helper in `:protocol` (reuses the golden-locked `Alarm.getData()`
  for standard modes; the undocumented calendar non-repeat-weekday `[0x80|days][min][hour]`
  is built directly in `AlarmCompiler` since `Alarm` can't emit it).
- **daysMask ↔ wire bitmask:** **1:1 identity, NO translation.** `WatchAlarmEntity.daysMask`
  (bit0=Sun..bit6=Sat) is exactly the hardware-corrected wire `days` byte (bit3=Wed,
  bit4=Thu per FINDINGS #12). The mask is passed straight through. (The `WEEKDAY_*`
  constants on `Alarm.java` are mislabeled for Wed/Thu but are unused by the byte path.)
- **Doc note found:** the `--days 30 = Mon-Fri` CLI example (FINDINGS line 357) is
  mislabeled — 0x1E (30) is Mon-Thu; true Mon-Fri is 0x3E (62), matching the `0xBE`
  hardware capture at FINDINGS line 1038. Both values are locked by tests.

> **CLI FOLLOW-UP (not part of this WP — do later):** wire `AlarmSlot`/`AlarmCompiler`
> (and the eventual WP9 calendar→alarm mapping) into the `:cli` `alarm` command so the
> CLI can drive the 16/16 split + calendar slots too, reusing this same pure helper.

**Goal:** Convert a list of alarm domain objects into the watch's alarm file bytes, honoring the 16/16 slot split and all three wire modes.

**Scope:**
- Compile standard alarms (slots 0–15): repeating `[0x80|days][minute|0x80][hour]`, one-shot `[0xFF][minute][hour]`.
- Reserve slots 16–31 for calendar (accept an injected list; compile non-repeating weekday `[0x80|days][minute][hour]`).
- Corrected weekday bitmask (bit3=Wed, bit4=Thu — `FINDINGS.md` #12). Enforce 32-slot max.

**Depends on:** WP1 (uses the existing alarm request/`FilePutRequest` path).

**Reference files:** `FINDINGS.md` #12 (alarm formats, bitmask, max count), `AlarmsSetRequest.java`, `Alarm.java`, `ANDROID-PLAN.md` §4.B.

**Isolated test (no hardware):**
- Golden-byte tests: each mode produces exactly the documented bytes (reuse the hardware-verified table in `FINDINGS.md` #12, e.g. non-repeat Thu 11:14 → `90 0E 0B`).
- Weekday mask: Mon–Fri shortcut → correct mask using corrected bits.
- 33 alarms → rejected before upload.

**Done when:** byte output matches the FINDINGS table for every case.

---

## WP6 — Notification Rule Compile + Play File

**Goal:** Pure functions to (a) compile all per-app rules into the multi-entry filter file, and (b) build the play file for a given package.

**Scope:**
- `compileFilter(rules) → byte[]` (32 bytes/entry) using package CRC = `crc("qhybrid.linux.<name>")`.
- `buildPlayFile(packageName) → byte[]` (official 12-length-buffer format).
- Vibe pattern 0–9, hour/min degrees 0–359.

**Depends on:** WP1 (reuses `buildNotificationFilterData` / `buildOfficialNotificationFile` logic from `FossilQAdapter`, extracted to a pure helper).

**Reference files:** `FossilQAdapter.java` (`buildNotificationFilterData`, `buildOfficialNotificationFile`, `computeNullTerminatedCrc`), `NotificationConfig.java`, `FINDINGS.md` #14, #17, `ANDROID-PLAN.md` §4.C.

**Isolated test (no hardware):**
- Filter golden bytes: compare against the official-app 7-entry capture in `FINDINGS.md` #17 (CRCs + positions + vibe).
- CRC determinism per package name.
- Multi-entry concatenation length = N×32.

**Done when:** compiled filter reproduces the documented CRC/position/vibe layout.

**STATUS: DONE & VERIFIED.** Pure helper `qhybrid.protocol.requests.fossil.notification.NotificationCompiler`
(`compileFilter`, `compileEntry`, `compileEntryWithCrc`, `buildPlayFile`, `computeNullTerminatedCrc`).
The existing `FossilQAdapter` filter + play builders now **delegate** to it (single source of truth;
live wire bytes unchanged). The impure play path injects `System.currentTimeMillis()` (timestamp +
messageId) into the pure `buildPlayFile`. Façade `FossilController.buildPlayFile(...)` added next to
`buildNotificationFilterFile(...)`. Golden tests: `protocol/src/test/.../golden/Wp6NotificationCompilerTest.java`
(19 tests) — known-package CRCs (`com.whatsapp`=0x40C7ED7C, `com.google.android.calendar`=0xBA3DC156),
per-entry layout, the full FINDINGS #17 7-entry/224-byte capture via the CRC-injected builder, N×32
length, input-order, and the deterministic play file with time-dependent fields asserted at the right
offsets. `:protocol:test` 68 green. **Variable-length / SENDER_NAME (0x02) form (FINDINGS #21d) is NOT
implemented — possible future work.**

> **TODO (CLI wiring, later):** the `:cli` `notify` / `notify-config` commands should be routed
> through this same pure helper (via `FossilController.buildNotificationFilterFile` /
> `FossilController.buildPlayFile`) so there is exactly one notification-byte implementation.

---

## WP7 — Button Mapping Compile (multi-entry + dial modes)

**Goal:** Pure compilation of button configuration files, including multi-entry mode toggles and the supported dial modes.

**Scope:**
- Single- and multi-entry button files via `ButtonConfigBuilder` (top/middle/bottom).
- Dial modes: Alert, Timezone 2, Alarm, Date, 24-Hour (the only physical modes); music = phone-side action, NOT a dial mode.
- Model-aware availability hook (which modes a given watch face supports).

**Depends on:** WP1.

**Reference files:** `ButtonConfigBuilder.java`, `ConfigPayload.java`, `ConfigFileBuilder.java`, `FINDINGS.md` #19/#21b/#22, `ANDROID-PLAN.md` §4.E.

**Isolated test (no hardware):**
- Golden bytes vs captured payloads embedded in `ButtonConfigBuilder` (ALARM_SEQUENCED, DATE_TOGGLE, 24H, GOAL_TRACKING).
- Multi-entry CRC32 trailer correctness.
- Map a 3-mode toggle → assert structure (counts, headers, payload order).

**Done when:** generated files match captured byte patterns.

---

## WP8 — Activity / Sleep / Calorie Parsing Surface

**Goal:** Expose `ActivityParser` outputs (steps, calories, sleep periods) as clean domain objects for the UI/DB. The parser already exists — this WP is the API + tests around it.

**Scope:**
- Thin domain mapping: `ActivityData` → per-day step/calorie totals; `detectSleep(...)` → sleep periods + quality.
- No new parsing math; verify against fixtures.

**Depends on:** WP1 (parser lives in `:protocol`).

**Reference files:** `ActivityParser.java`, `activity.bin` / `activity-test.bin` (fixtures), `ANDROID-PLAN.md` §4.H.

**Isolated test (no hardware):**
- Parse `activity-test.bin`; assert total steps, calorie sum, and that sleep detection returns the expected period count/duration.
- Edge: empty file, single segment, multi-segment ordering.

**Done when:** fixtures parse to stable, asserted values.

---

## WP9 — Calendar → Alarm Mapping (pure)

**Status:** ✅ DONE & VERIFIED (JVM, no hardware). Pure helper added to `:protocol`:
`qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper` (alongside WP5's
`AlarmSlot`/`AlarmCompiler`). 13 new tests (`Wp9CalendarAlarmMapperTest`) green;
`:protocol:test` now 49 total (36 prior + 13), 0 failures. `./fossil-q --help`
unchanged; `:android:assembleDebug` succeeds. No protocol wire bytes / `AlarmSlot` /
`AlarmCompiler` output / `BleTransport` / `AndroidBleTransport.kt` / WP3 service touched.

- **Placement:** pure helper in `:protocol` (only `java.time` + slf4j-free). WP9
  PRODUCES `AlarmSlot` objects that WP5's `AlarmCompiler` compiles 1:1. No Android
  calendar APIs (that is WP13, which will feed `(title, DTSTART)` pairs in).
- **Entry point (time injected, no system clock):**
  `CalendarAlarmMapper.mapEventsToAlarmSlots(List<CalendarEvent>, long nowEpochMillis, ZoneId zone)`.
  `CalendarEvent` = `(String title, long startEpochMillis)`. Local weekday/hour/minute
  computed via `Instant.atZone(zone)`, so DST/offset is handled by `java.time` and
  tests pass a fixed `now` + `ZoneId` for determinism.
- **Pipeline:** filter to `[now, now + 7 days)` (half-open) → de-dup on wire identity
  `(daysMask, hour, minute)` (earliest-start wins, tie-break title then original index)
  → sort by start → take nearest ≤16 → assign slots 16, 17, … → emit non-repeating,
  enabled `AlarmSlot`s.
- **Weekday → daysMask:** 1:1 wire bits (bit0=Sun..bit6=Sat; bit3=Wed, bit4=Thu per
  FINDINGS #12), NO translation — same convention as WP5. Friday → bit5 (`0x20`), which
  `AlarmCompiler.encode` turns into `[0xA0][min][hour]` (acceptance: Fri 10:15 → `A0 0F 0A`).

> **CLI FOLLOW-UP (not part of this WP — do later):** wire `CalendarAlarmMapper`
> into the `:cli` `alarm` command so the CLI can drive calendar→alarm slots 16–31
> too, reusing this same pure helper (pair with the WP5 CLI follow-up).

**Goal:** Pure function turning a list of `(title, startEpochMillis)` events into ≤16 non-repeating weekday alarm domain objects for slots 16–31.

**Scope:**
- Filter to next 7 days, sort, take nearest 16.
- Map each event's local weekday/hour/minute → alarm domain object (non-repeating weekday).
- De-dup/clamp; deterministic ordering.

**Depends on:** WP5 (produces alarm domain objects WP5 compiles). Does **not** depend on Android calendar APIs.

**Reference files:** `FINDINGS.md` #12 (non-repeat weekday format), `ANDROID-PLAN.md` §4.D / §4.B.

**Isolated test (no hardware):**
- Given fixed "now" and a fixed event list, assert exactly which events become alarms and their slot order.
- Event >7 days out is excluded; 20 events → only 16 nearest kept.
- A Friday 10:15 event → alarm domain object with Friday bit + 10:15 (then verify via WP5 it compiles to the right bytes).

**Done when:** mapping is deterministic and verified against fixtures.

---

## WP10 — Permissions / Setup Carousel (Compose)

**Goal:** First-run wizard that requests each permission with rationale (plan §2).

**Scope:** Welcome → Bluetooth/Scan → Notification Access → Calendar → Battery Optimization. Detect already-granted; deep-link to system settings where needed.

**Depends on:** WP0. (Can be built before WP2–WP9; it only gates them at runtime.)

**Reference files:** `ANDROID-PLAN.md` §2.

**Isolated test (on-device / UI test):**
- Fresh install → each step requests the right permission; re-entry skips granted ones; "open settings" intents resolve.

**Done when:** all permissions reachable and state correctly reflected.

---

## WP11 — Notification Listener Glue → WP6

**Goal:** `NotificationListenerService` that maps an intercepted notification's package → rule → triggers play file via the service.

**Depends on:** WP3 (service/connection), WP6 (compile/play), WP4 (rule lookup).

**Reference files:** `ANDROID-PLAN.md` §4.C.

**Isolated test (on-device):**
- With a rule for a test app, post a notification → watch vibrates with the configured pattern + hands move to configured degrees.
- No rule → no watch action. Verify dedupe/rate-limit.

**Done when:** real notifications drive the configured watch behavior.

---

## WP12 — Button Event Glue (gesture → media / find-phone)

**Goal:** Consume button events from `3dda0006`, run `ButtonGestureDetector`, dispatch to MediaController / preferred-app launcher / find-phone ring.

**Depends on:** WP3, WP7 (to have configured a `FORWARD_TO_PHONE` button), WP1 (`ButtonGestureDetector`).

**Reference files:** `FossilQAdapter` `ButtonGestureDetector`, `ANDROID-PLAN.md` §4.E.2/§4.E.3/§4.J.

**Isolated test (on-device):**
- Single/double/long press → correct media action; with music dead + preferred app set (e.g. Bandcamp) → launches and plays.
- Double-press find-phone → loud ring bypassing DND; stop in app.

**Done when:** gestures map to actions reliably across single/double/long.

---

## WP13 — Calendar Provider Read + ContentObserver → WP9

**Goal:** Read system calendar events and observe changes; feed WP9.

**Depends on:** WP9 (mapping), WP4 (cache), WP10 (permission).

**Reference files:** `ANDROID-PLAN.md` §4.D.1, §4.D.3.

**Isolated test (Robolectric / on-device):**
- Insert a test event via provider → `ContentObserver` fires → cache updates with mapped alarms.
- Cloud-sync simulation: external write → observer fires.

**Done when:** provider reads + change detection produce the WP9 alarm set.

---

## WP14 — Sync Orchestration (WorkManager + Sync-on-Connect)

**Goal:** Tie triggers together: periodic safety job, ContentObserver push, and sync-on-connect; compile (WP5/WP6/WP7) and upload via service (WP3).

**Depends on:** WP3, WP4, WP5, WP6, WP7, WP9, WP13.

**Reference files:** `ANDROID-PLAN.md` §4.D.2/§4.D.5.

**Isolated test (instrumented):**
- Disconnected: change calendar → on reconnect, slots 16–31 upload.
- Periodic worker runs and reconciles; queued uploads flush on connect.

**Done when:** all three triggers converge to a single correct upload pipeline.

---

## WP15 — In-App Log Viewer + Debug Menu (level filter + export)

**Goal:** Capture INFO/DEBUG logs into a ring buffer; Compose console with level filter + export. Also host the **Debug Menu** — a developer-tools surface reached from a top-right overflow/gear in the app, gated so it never ships enabled in release (e.g. `BuildConfig.DEBUG`).

**Scope:**
- An SLF4J appender (or bridge) feeding an in-memory ring buffer with level + timestamp.
- The protocol already logs via SLF4J — route those through too.
- INFO = friendly operational lines (plan §4.I examples); DEBUG = raw hex/GATT/DB.
- **Debug Menu** (plan §4.I.4): top-right entry → a screen of dev/testing actions, with the log console living here too. First actions:
  - **DB tools (WP4):** dump Room DB to the console, seed sample data, **clone/transfer between two MACs** (`WatchRepository.transferSettings`), wipe a watch (exercises CASCADE), list/switch active watch.
  - **BLE/protocol tools (WP3):** connect/disconnect/sync-now, show link state + negotiated MTU.
  - **Misc:** build/version info, permission state, CDM associations.
  - *Note:* this replaces the throwaway WP4 in-`MainActivity` debug panel (dump-to-logcat tag `FossilQ-DB`) that verified multi-watch + clone on two real watches and was then removed.

**Depends on:** WP0; richer once WP1–WP4 emit logs / expose the repository.

**Reference files:** `ANDROID-PLAN.md` §4.I (incl. §4.I.4 Debug Menu).

**Isolated test (JVM + UI):**
- Unit: push N log records of mixed levels → filter returns expected subset; export format stable.
- UI: filter toggles update the list; copy/export produces a text blob; Debug Menu actions invoke the right repo/service calls against a fake.

**Done when:** log routing + filter + export verified (buffer unit-tested headlessly); Debug Menu reachable and release-gated.

---

## WP16 — Compose UI Screens

**Goal:** The user-facing screens, each backed by a ViewModel reading WP4 + WP8 and writing via the service.

**Sub-parts (each independently buildable with fake data/preview):**
- **16a Dashboard**: connection status, battery, steps/goal, active-watch selector, Find Watch.
- **16b Alarms**: list/add/edit/delete (slots 0–15), day picker, weekday/weekend shortcuts.
- **16c Notifications**: app list + search, per-app vibe + hand degrees editor.
- **16d Buttons**: per-button mapping + dial-mode toggles (model-aware).
- **16e Calibration**: interactive hand alignment (WP F flow).
- **16f Sleep/Activity**: charts from WP8.
- **16g Settings**: nudge, vibration strength, timezone, preferred music app, settings transfer (WP4), log viewer (WP15).

**Depends on:** WP4, WP8, plus service (WP3) for live actions. Each screen testable with Compose previews + fakes before wiring real data.

**Reference files:** `ANDROID-PLAN.md` §3–§5, ROADMAP "UI Screens".

**Isolated test (UI / preview):**
- Compose preview for each screen with fake state; basic UI tests for interactions (add alarm, toggle rule, drag calibration hand → emits correct intent to a fake controller).

**Done when:** each screen renders + emits correct domain actions against a fake controller/DAO.

---

## Suggested Execution Waves (for parallelization)

| Wave | Parts | Can run in parallel? | Hardware? |
|------|-------|----------------------|-----------|
| 0 | WP0 | no (foundation) | no |
| 1 | WP1, WP4 | yes | no |
| 2 | **WP5, WP6, WP7, WP8, WP9** (all pure logic) | **yes — 5 independent fresh contexts** | no |
| 3 | WP2, WP10, WP15 | yes | WP2 yes; others no |
| 4 | WP3, WP13 | yes | WP3 yes; WP13 partial |
| 5 | WP11, WP12, WP14 | yes | yes |
| 6 | WP16a–g | yes (per screen) | partial |

**Highest-leverage parallelization:** Wave 2 (WP5–WP9) — five pure-logic packages, each verifiable against `FINDINGS.md` golden bytes and `activity.bin` fixtures with zero hardware and zero Android emulator. These de-risk the entire protocol surface before any UI or BLE glue exists.

---

## Cross-Cutting Test Assets (build once, reuse everywhere)

- `FakeBleTransport` (WP1) — drives any protocol path headlessly.
- Golden-byte fixtures extracted from `FINDINGS.md` (#12 alarms, #17 notification filter) and `ButtonConfigBuilder` captures.
- `activity.bin` / `activity-test.bin` — activity/sleep parsing fixtures (already in repo).
- A `FakeFossilController` for UI WPs so screens can be tested without BLE/DB.
