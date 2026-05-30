# WP-BUZZ-PLAYONLY-SIMPLIFY — Stop re-uploading the reserved buzz filter on every connect

You are working on **fossil-q-hybrid-cli** at
`/home/wighawag/dev/github/wighawag/fossil-q-hybrid-cli`.

## Context (what already exists — do NOT redo)

The manual "Vibrate" buzz is now a **single play-file put** (`WP-BUZZ-PLAYONLY`, committed):

- `:protocol` has `requests/fossil/notification/BuzzPatterns.java` — the single source of truth for
  the **reserved buzz filter entries**: one notification-filter entry per useful vibration pattern,
  under a stable package name `qhybrid.linux.buzzN` (N == the vibration pattern byte), matched by
  package-name CRC. Useful patterns = `{1,2,3,5,6,7,8}` (skips silent `0/9` and the `4≡3` duplicate).
  `BuzzPatterns.reservedEntries()` / `reservedFilterFile()` / `crcForPattern()` / `isReservedPattern()`
  / `packageNameForPattern()`. Golden-tested in `protocol/.../BuzzPatternsTest.java` and
  `BuzzPlayOnlyTest.java`.
- `FossilController.buzzPlayOnly(int pattern)` → a SINGLE `NOTIFICATION_PLAY` (0x0900) put for the
  reserved package; `FossilController.uploadReservedBuzzFilter()` → puts the reserved-only filter.
- The notification filter is a **WHOLE FILE** put (`FileHandle.NOTIFICATION_FILTER 0x0C00`): every
  notification-rule change rebuilds and re-puts the entire file. `WatchConnectionService`'s
  `ServiceUploader.uploadNotificationFilter(entries)` already **folds the reserved buzz entries into
  the synced filter** (de-duped by package, user rules win), so after any notification sync the
  on-watch filter = `[user rules] + [reserved buzz entries]` and the buzz still matches.
- A BRAND-NEW watch gets a one-time full provisioning sync on first connect
  (`ConnectSyncDecider` → `runOnConnectSync(controller, SyncSection.ALL)` →
  `SyncOrchestrator.sync(...)` → `ServiceUploader.uploadNotificationFilter`), so the reserved entries
  ARE written during the "add watch" provisioning.

The file-PUT handshake itself is correct (VERIFY-based, `WP-FILEPUT-RELIABLE`) — do NOT touch it.

## The problem to fix

`WatchConnectionService` ALSO uploads a **reserved-only** filter once per connection via
`ensureReservedBuzzFilter(controller)` (guarded by the `reservedBuzzFilterUploaded: AtomicBoolean`,
which is reset on every disconnect). This is wrong for two reasons:

1. **Redundant work every reconnect.** The watch keeps its files across a clean disconnect, so a
   known/provisioned watch does NOT need the filter re-uploaded — yet we re-put it (~1 extra file-PUT,
   ~0.8s on the BLE control channel) on every connect.
2. **It can CLOBBER the user's app notification rules.** If the user has app notification rules synced
   (on-watch filter = `[user rules] + [reserved]`), `ensureReservedBuzzFilter` uploads the
   **reserved-ONLY** whole file, wiping the user's app rules until the next notification sync. That's a
   latent data-loss bug.

The user's decision (option **B**): **rely solely on the existing paths that already write the
reserved entries** — (a) new-watch provisioning sync, and (b) the fold-in during any notification
sync — and **drop the standalone reserved-only upload entirely**. Watches go through the "add watch"
step, which provisions the filter (with reserved entries folded in), so the reserved entries are
present without a per-connect or pre-buzz reserved-only put. If a buzz is ever attempted on a watch
whose filter has no matching reserved entry, it simply **falls back to the two-put `buzz()`**
(filter+play) — already implemented for non-reserved patterns; extend that fallback intent to the
"reserved entry maybe missing" case via the play-only call's natural behaviour (see Design).

## What to implement (Android only — `:android`)

In `android/.../WatchConnectionService.kt`:

1. **Delete the standalone reserved-only upload.** Remove `ensureReservedBuzzFilter(controller)` and
   its call sites:
   - the call in `connectAndInit` (after `registerWatchRow(mac)`),
   - the call at the top of `runBuzz` (the "make sure the reserved filter is present" line).
   Remove the `ensureReservedBuzzFilter(...)` private method itself, and the
   `reservedBuzzFilterUploaded: AtomicBoolean` field plus its reset in the disconnect
   `setConnectionCallback { up -> ... }` branch. Remove `FossilController.uploadReservedBuzzFilter()`
   if it has NO remaining callers (grep first; keep `BuzzPatterns.reservedEntries()`/`reservedFilterFile()`
   and the fold-in usage). Leaving an unused `uploadReservedBuzzFilter()` is acceptable if you prefer,
   but prefer removing dead code; document the choice in the commit message.

2. **Keep the buzz play-only.** `runBuzz` still calls `controller.buzzPlayOnly(pattern)` for a reserved
   pattern (single play put) and falls back to `controller.buzz(pattern)` (filter+play) for a
   non-reserved pattern — UNCHANGED, minus the removed `ensureReservedBuzzFilter` line.

3. **Keep the fold-in.** `ServiceUploader.uploadNotificationFilter(entries)` MUST keep merging
   `BuzzPatterns.reservedEntries()` into the synced filter (the mechanism that actually keeps the
   reserved entries on the watch). Do NOT remove this.

4. **No change to provisioning.** New-watch provisioning already uploads the (folded) filter — leave it.

> Net effect: the reserved buzz entries are written ONLY when the filter is (re)written for a real
> reason — new-watch provisioning or a notification-rule sync — never as a per-connect or per-buzz
> standalone put. No clobber, no redundant reconnect put.

## Tests

- `:android` unit tests must stay green (currently **242**, 0 failures). The buzz path is seam/VM-tested
  headlessly; removing the per-connect upload should not change those. If any test asserted the
  per-connect reserved upload, update it to the new behaviour (it should not — grep to confirm).
- `:protocol` golden tests for `BuzzPatterns` / play-only stay green (currently **121**). If you remove
  `FossilController.uploadReservedBuzzFilter()`, ensure no test referenced it (grep; the contract test
  uses `buzzPlayOnly`, not the reserved-filter upload).
- OPTIONAL (nice-to-have, only if cheap): a headless `:android` test asserting that on a normal
  reconnect of a KNOWN watch, NO `NOTIFICATION_FILTER` (0x0C00) put is issued (i.e. the per-connect
  reserved upload is gone). Only add if it fits the existing seam without inventing new infra.

## Gates (run before EVERY commit; all must pass)

- `./gradlew :protocol:test` green (report count; currently 121).
- `./gradlew :android:testDebugUnitTest` green, 0 failures (trust the XML count, not banners):
  ```
  python3 -c "import glob,xml.etree.ElementTree as ET; t=f=e=0
  [ (t:=t+int(r.get('tests',0)), f:=f+int(r.get('failures',0)), e:=e+int(r.get('errors',0))) for x in glob.glob('android/build/test-results/testDebugUnitTest/*.xml') for r in [ET.parse(x).getroot()]];
  print(t,f,e)"
  ```
  (currently 242; report the new count.)
- `./gradlew :android:assembleDebug` + `:android:lintDebug` succeed.
- `./gradlew :cli:shadowJar` succeeds AND `./fossil-q --help` md5 == `7533ceccb6b29f81f6172bd5a71c5b98`.

## Workflow

- Per `/home/wighawag/.pi/agent/AGENTS.md`: this WP authorizes the gradle/test/commit commands and a
  single commit (small change), overriding the general "no auto-commit" rule. Do NOT push.
- One commit is fine given the size. Message: `wp-buzz-playonly: drop per-connect reserved-filter upload (rely on provisioning + sync fold-in)`.
- After committing: `git log --oneline -1` + `git show --stat HEAD`. An external auto-commit may claim
  the staged changes with a different message — if the diff is right, that's fine; don't rewrite
  history, just report it.
- Prefer `read`/`grep`/`find`/`ls` over `bash` for exploration; `edit` for precise changes.

## On-device verification (the user runs it — give the recipe)

Watch is **single-bond** (phone OR Linux, not both).

1. **CLI regression (phone BT off):** `./fossil-q --verbose notify --vibe 5` must STILL buzz.
2. **Android:** `adb install -r android/build/outputs/apk/debug/android-debug.apk`,
   `adb logcat > save.log`.
   - **Known (already-added) watch:** connect and confirm there is **NO `FilePut[0x0C00]` put on
     connect** anymore. Then Settings → Test vibration → Vibrate (single)/(triple): each tap is a
     SINGLE `FilePut[0x0900] ... VERIFY 0x84 ... COMPLETE` and the watch buzzes (single vs triple).
   - **Fresh watch (re-add / provisioning):** removing + re-adding the watch runs provisioning, which
     uploads the filter (with reserved entries folded in) once. After that, buzz works play-only.
   - Confirm the user's app notification rules are NOT wiped by connecting/buzzing.
   Capture `save.log` and report the `FilePut[...]` sequence on connect and per buzz.

## Acceptance

- The reserved buzz entries are written ONLY via new-watch provisioning and the notification-sync
  fold-in — never as a per-connect or per-buzz standalone reserved-only put. No redundant reconnect
  file-PUT; no clobbering of user app-notification rules. Buzz stays a single play-file put for
  reserved patterns (fallback to filter+play otherwise). Gates green; CLI still buzzes; no push.

## Do NOT

- Do NOT push to git.
- Do NOT remove the reserved-entry FOLD-IN in `ServiceUploader.uploadNotificationFilter` (that is the
  mechanism that keeps the reserved entries on the watch).
- Do NOT change the file-PUT handshake (`WP-FILEPUT-RELIABLE`), the no-response DATA pacing/retry, the
  `CONTROL_WRITE_ACK_MS` tuning, the single-link-writer model, or the CLI.
- Do NOT change any FILE byte formats (filter/play/button/alarm/config) — only the connect-time upload
  policy.
- Do NOT implement auto-buzz-on-strength-change (possible future WP-VIBEACK).
```
