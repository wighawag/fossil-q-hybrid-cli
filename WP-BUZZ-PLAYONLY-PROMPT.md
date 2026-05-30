# WP-BUZZ-PLAYONLY — Make the manual "Vibrate" buttons buzz via a single play-file put

You are working on the **fossil-q-hybrid-cli** project at
`/home/wighawag/dev/github/wighawag/fossil-q-hybrid-cli`.

## Goal

Make the two manual "Vibrate" buttons on the Android **Settings** screen actually vibrate the watch
on-device, by switching the buzz from a **two-put** sequence (upload filter, then play) to a
**single play-file put**. The notification filter entries that define the vibration patterns are
uploaded **once at connect**, so a buzz only needs to send the play file (which the watch matches by
package-name CRC). This structurally eliminates the BLE sequencing races that the two-put path keeps
hitting on Android (see "Why" below).

- "Vibrate (single)" → strong single buzz (pattern `5` = `ONE_SHORT_VIBE`)
- "Vibrate (triple)" → triple buzz (pattern `1` = `CALL`)

These are user-facing, on the **Settings** screen (NOT the Debug menu). They are an on-device test
tool: pressing one makes the watch buzz immediately (connecting first if the link is down, with an
honest error if unreachable).

## Why we are doing this (read carefully — this is the crux)

The buzz needs the watch to know **which vibration pattern** to use. The pattern lives in a
**notification filter entry** (`FileHandle.NOTIFICATION_FILTER` `0x0C00`), matched by **package-name
CRC**. The **play file** (`FileHandle.NOTIFICATION_PLAY` `0x0900`) just says "fire the notification
for package X"; the watch looks up the matching filter entry to decide vibration + hand movement.

The current `FossilQAdapter.playNotificationWithPattern(...)` does **two sequential file-puts per
buzz**: (1) upload a filter carrying the pattern, then (2) upload the play file. On this watch (Fossil
Q Hybrid HW0.0.2.9r.v3) over **Android's GATT stack**, sequencing two file-puts on the single BLE
control channel (`3dda0003`) is extremely fragile. We peeled back SIX layers of failure on-device,
each fix exposing the next — all rooted in "the second put must follow the first":

1. Auto-sync-on-connect contended with the buzz puts. (Fixed in WP-PULLSYNC: removed auto-sync.)
2. This firmware **never sends the type-4 file-close ACK** over Android — the first put hung the
   strictly-serial request queue and the second put never ran. (Fixed: complete a put on its type-8
   CRC-confirm; see `FilePutRawRequest`.)
3. BlueZ's **delayed** close-ack landed on the *next* put and aborted it ("wrong file closing
   handle"). (Fixed: ignore control frames whose handle != the current put's handle.)
4. The close write (write-with-response on the INDICATE char, never acked) **blocked the BLE thread
   ~10s**, delaying the next put. (Fixed: send the close fire-and-forget via
   `BleTransport.writeCharacteristicNoWait` / `WriteBatch.writeNoWait`.)
5. Fire-and-forget close then **collided with the next put's open** — Android allows only ONE
   outstanding GATT op, so the open was rejected ("submission failed"). (Fixed: the no-wait path
   still briefly waits `NO_RESPONSE_PACING_MS` for the stack "ready" signal.)
6. **Current blocker:** the close write now gets *swallowed* in the race with the immediately
   following play-open (no `WRITE -> 04 00 0c` line appears), so the watch never sees a clean close,
   waits out its own ~10s internal finalize timeout, then accepts the play put too late and returns
   a **type-9 watch-side PUT timeout** → no buzz.

Every failure is a symptom of **sequencing two file-puts**. The filter does NOT need re-uploading on
every buzz — it only needs to *exist* on the watch. So: upload the pattern filters **once at
connect**, and make the buzz a **single play-file put**. One put per buzz = no second put to
sequence = the entire class of races above disappears. This is also how the firmware/official app are
designed to work (reserved package CRCs + play-by-CRC; the official app ships a 7-entry filter at
setup — see FINDINGS.md "Notification Filter (7 entries)").

**The CLI already works** (it buzzes over BlueZ) because BlueZ delivers the close-ack and paces
writes implicitly; do NOT regress the CLI. The fixes from the steps above are committed and correct —
this WP builds on them, it does not revert them.

## Verified facts (re-confirm while reading)

- Buzz pattern bytes (hardware-tested, FINDINGS.md §"vibration field (0xC3)"):
  `1=CALL(triple)`, `2=TEXT(double)`, `3=EMAIL(single)`, `4=DEFAULT(==3, duplicate)`,
  `5=ONE_SHORT_VIBE(strong single)`, `6=TWO_SHORT_VIBES(strong double)`,
  `7=THREE_SHORT_VIBES(strong triple)`, `8=ONE_LONG_VIBE(long)`, `0=AUTO/9=NO_VIBE(silent)`.
  Distinct *useful* patterns = **1,2,3,5,6,7,8** (skip silent 0/9 and the 4≡3 duplicate).
- The notification filter is a **single whole-file** (`0x0C00`), uploaded **atomically — the whole
  file replaces what's on the watch** (FINDINGS.md §21c). There is **no per-entry write**. So the
  reserved buzz entries must live in the SAME file as any real user notification rules — they cannot
  be written independently.
- The filter is **multi-entry**: `NotificationCompiler.compileFilter(List<NotificationFilterEntry>)`
  concatenates N fixed-32-byte entries; the watch matches by package-name CRC. **No firmware
  entry-count limit** (FINDINGS.md §"No firmware entry count limit observed").
- Each entry carries BOTH the vibration pattern (`0xC3`) AND hand-movement (`0xC2`: hourDeg, minDeg,
  duration). It is the SAME filter that real app notifications use.
- The play file carries a `packageCrc` (`NotificationCompiler.buildPlayFile(...)`); the watch matches
  it to a filter entry. `computeNullTerminatedCrc(name) = CRC32(name + '\0')`. Golden values already
  in tests: `qhybrid.linux`=0x0F1E3BE9, `qhybrid.linux.call`=0xBDC750F4, `qhybrid.linux.text`=0x79511FC4.
- **Android connect does `controller.init(false)` (MINIMAL init) — it does NOT upload any
  notification filter.** (`WatchConnectionService.connectAndInit` ~line 311.) So the reserved buzz
  filter is NOT currently on the watch on Android; this WP must upload it at connect.
- Single link writer: ONLY `WatchConnectionService` talks to `AndroidBleTransport`, always on the
  `ble-worker` executor. Do NOT regress this.
- A file-put now COMPLETES on its type-8 CRC-confirm (does NOT await the type-4 close-ack); the close
  is sent fire-and-forget; foreign-handle control frames are ignored. (All in `FilePutRawRequest`.)
  The play file is the SAME file-put path, so it benefits from these fixes — but as a SINGLE put it
  avoids the two-put races entirely.

## Required reading FIRST

- `/home/wighawag/.pi/agent/AGENTS.md` (safety rules; this WP authorizes the gradle/test/commit
  commands + per-sub-part commits below, overriding the general "no auto-commit" rule; do NOT push).
- `ANDROID-FOLLOWUPS-PLAN.md` — cross-cutting design rules (provable-core-first, no new wire bytes,
  thin golden-tested passthroughs, single link writer) and the WP-VIBEACK grounding facts.
- `FINDINGS.md` — search "Notification Filter", "vibration field (0xC3)", "HAND_MOVEMENT (0xC2)",
  "entry count limit", "Notification Filter (7 entries)".
- `protocol/.../FossilQAdapter.java` — `playNotificationWithPattern`, `playNotificationByPackageName`,
  `uploadNotificationFilterWithPattern`, `uploadNotificationFilterEntries`,
  `buildOfficialNotificationFile`, `buildNotificationFilterData`, `computeNullTerminatedCrc`.
- `protocol/.../requests/fossil/notification/NotificationCompiler.java` — `compileFilter`,
  `compileEntry`, `buildPlayFile`, `computeNullTerminatedCrc`.
- `protocol/.../FossilController.java` — `buzz(int)`, `buzz(int,int,int)`, `playNotification(pkg)`,
  `uploadNotificationFilter(entries)`, `buildPlayFile(...)`, `buildNotificationFilterFile(entries)`.
- `protocol/.../requests/fossil/file/FilePutRawRequest.java` — the type-8-completion + foreign-handle
  guard + `sendCloseFrame()` (fire-and-forget). Understand why a SINGLE put has no race.
- `protocol/.../model/NotificationFilterEntry.java`.
- `protocol/src/test/java/qhybrid/protocol/AdapterFilePutTest.java`, `AdapterButtonPutTest.java`,
  `ControllerBuzzTest.java`, `FossilControllerInitTest.java` (FakeBleTransport + FileTransferResponder
  harness; mirror these for the play-only contract test).
- `android/.../WatchConnectionService.kt` — `connectAndInit` (init(false), the on-connect hook),
  `runBuzz` / `submitBuzz` / `ACTION_BUZZ` / `buzzNow`, `ServiceUploader`, the ble-worker model.
- `android/.../settings/VibrationSync.kt` (`PATTERN_SINGLE=5`, `PATTERN_TRIPLE=1`,
  `ServiceVibrationSync`), `SettingsViewModel.vibrateWatch`, `SettingsScreen.kt` TestVibrationCard.
- `android/src/test/java/qhybrid/android/settings/SettingsViewModelTest.kt` (FakeVibration).

## Design (implement this)

1. **Reserved buzz filter entries (one per distinct useful pattern), matched by package CRC.**
   Define stable reserved package names, e.g. `qhybrid.linux.buzz1` (triple), `qhybrid.linux.buzz2`
   (double), `qhybrid.linux.buzz3` (single), `qhybrid.linux.buzz5` (strong single),
   `qhybrid.linux.buzz6` (strong double), `qhybrid.linux.buzz7` (strong triple),
   `qhybrid.linux.buzz8` (long) — pattern == the suffix number. Put the naming + the
   `pattern -> packageName` mapping in ONE place (a small pure helper, e.g. `BuzzPatterns` in
   `:protocol`, golden-tested: name->crc and pattern->name are stable). Keep `qhybrid.linux` itself
   working for real notifications.
2. **Build the reserved filter as a multi-entry file** via `NotificationCompiler.compileFilter` and
   expose it on `FossilController` (thin passthrough; reuse existing compiler — NO new wire bytes).
   The reserved entries use neutral hands (90/90) unless you have reason otherwise.
3. **Upload the reserved filter ONCE at connect** on Android: in
   `WatchConnectionService.connectAndInit`, after a successful Fossil init, upload the reserved buzz
   filter (a single file-put — the ONLY put at connect, so no two-put race). Decide how it coexists
   with real user notification rules (the filter is whole-file): either (a) the reserved entries are
   always appended to whatever notification-rule file the sync builds, or (b) for this WP, upload the
   reserved-only filter at connect and leave the existing notification-sync path as-is — pick the
   simplest correct option and document it. Prefer folding the reserved entries into the same filter
   the notification sync produces so a later notification sync doesn't wipe them.
4. **Play-only buzz primitive.** Add `FossilController.buzzPlayOnly(int pattern)` →
   adapter sends ONLY the `NOTIFICATION_PLAY` file for the reserved package whose CRC matches that
   pattern (NO filter upload). This is a single file-put. Reuse
   `buildOfficialNotificationFile(..., packageName)` / `NotificationCompiler.buildPlayFile`.
   Keep the existing `buzz(int)` (filter+play) for the CLI / other callers — do NOT break it.
5. **Wire Android buzz to play-only.** `WatchConnectionService.runBuzz` calls the play-only path.
   The reserved filter is already on the watch from connect, so the play file matches and the watch
   vibrates with the right pattern. Keep the connect-then-buzz + SyncState SYNCING→SUCCESS/ERROR.
6. **UI unchanged** (the two Settings buttons already call `vibrateWatch(5)` / `vibrateWatch(1)`).

## Provable core / tests (no new wire bytes)

- `:protocol` golden/contract tests:
  - `BuzzPatterns` (or equivalent): pattern↔packageName mapping + null-terminated CRC values are
    stable (golden).
  - Play-only contract test through the `FakeBleTransport` / `FileTransferResponder` harness: calling
    `buzzPlayOnly(5)` performs EXACTLY ONE file-put — a `NOTIFICATION_PLAY` (0x0900) whose play-file
    `packageCrc` equals the reserved pattern-5 package CRC — and NO `NOTIFICATION_FILTER` (0x0C00)
    put. Drive it to completion on type-8 (mirror `ControllerBuzzTest`).
  - Reserved-filter builder test: the multi-entry filter contains one 32-byte entry per reserved
    pattern with the correct vibe byte (`0xC3`) and CRC per entry (mirror `AdapterFilePutTest`'s
    `assertFilterEntry`).
- `:android` tests: VM/seam mapping stays headless (FakeVibration). `vibrateWatch(5)`/`(1)` hit the
  seam with the right pattern (existing tests keep passing). The live BLE buzz is on-device-pending.

## Gates (run before EVERY commit; all must pass)

- `./gradlew :protocol:test` green. Report the count (currently 113).
- `./gradlew :android:testDebugUnitTest` green, 0 failures. Trust the XML count, NOT banners:
  ```
  python3 -c "import glob,xml.etree.ElementTree as ET; t=f=e=0
  [ (t:=t+int(r.get('tests',0)), f:=f+int(r.get('failures',0)), e:=e+int(r.get('errors',0))) for x in glob.glob('android/build/test-results/testDebugUnitTest/*.xml') for r in [ET.parse(x).getroot()]];
  print(t,f,e)"
  ```
  (currently 242; report the new count.)
- `./gradlew :android:assembleDebug` + `:android:lintDebug` succeed.
- `./gradlew :cli:shadowJar` succeeds AND `./fossil-q --help` md5 unchanged
  (baseline `7533ceccb6b29f81f6172bd5a71c5b98`). Rebuild the jar so the CLI re-test uses new code.

## Workflow

- Implement in discrete sub-parts (suggested: (1) protocol `BuzzPatterns` + reserved-filter builder
  + `buzzPlayOnly` passthrough + contract tests; (2) Android upload-reserved-filter-at-connect;
  (3) wire `runBuzz` to play-only). After EACH sub-part: run ALL gates, then
  `git add -A && git commit -m "wp-buzz-playonly: <sub-part>"`, then continue. Do NOT batch. Do NOT
  push. An external auto-commit may claim staged changes with a different message — if the diff is
  correct, that's acceptable; do NOT rewrite history, just report it (`git log --oneline -1`,
  `git show --stat HEAD`).
- Prefer `read`/`grep`/`find`/`ls` over `bash` for exploration. Use `edit` for precise changes.

## On-device verification (the user runs this — give them the recipe at the end)

The watch is **single-bond**: the phone and the Linux CLI cannot both be connected at once.

1. **CLI regression (phone Bluetooth OFF):** `./fossil-q --verbose notify --vibe 5` must STILL buzz
   (do not regress the working CLI path — it still uses `buzz`/`playNotificationWithPattern`).
2. **Android:** `adb install -r android/build/outputs/apk/debug/android-debug.apk`,
   `adb logcat > save.log`. Settings → Test vibration → "Vibrate (single)" then "Vibrate (triple)".
   Expect in logcat: the reserved filter uploaded ONCE at connect (`FilePut[0x0C00] COMPLETE`), then
   for EACH tap a SINGLE `FilePut[0x0900] ... COMPLETE` (NO `0x0C00` per tap, NO `type=9` timeout) →
   the watch buzzes (single vs triple). Capture `save.log` and report whether it buzzed + the
   `FilePut[...]` sequence.

## Acceptance

- Each buzz is a SINGLE play-file put; the reserved pattern filter is uploaded once at connect; the
  Android buttons vibrate the watch (single=5, triple=1), connecting first if needed, honest error if
  unreachable. No new wire bytes (reuses the golden filter/play compilers). Provable core (pattern
  mapping + reserved-filter bytes + single-put play-only contract) is unit-tested; the live buzz is
  on-device-verified via the recipe. CLI still buzzes. Gates green at each commit; no push.

## Do NOT

- Do NOT revert the committed fixes (type-8 completion, foreign-handle guard, no-wait close): they
  are correct and the play file relies on type-8 completion. This WP removes the *second* put, not
  those fixes.
- Do NOT implement auto-buzz-on-vibration-strength-change (possible future WP-VIBEACK).
- Do NOT add new wire bytes; reuse `NotificationCompiler` / `FossilController` / the golden compilers.
- Do NOT push to git.
```
