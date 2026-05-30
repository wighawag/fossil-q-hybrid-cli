# WP11 — Notification Listener → Watch Play (Status)

✅ **DONE & VERIFIED** (provable core JVM/Robolectric-tested; the service wiring builds +
lint-passes; the live OS interception + BLE buzz flagged **on-device-pending**). Follows the proven
two-layer pattern: a pure, injectable decider/extractor/dispatch core + a thin Android shell (the
`NotificationListenerService` + the WP3 service). **Invents NO new wire bytes.** Shipped in 5
committed sub-parts (`wp11:` / `wp10:`).

## Scope

A `NotificationListenerService` intercepts posted Android notifications, matches each against the
active watch's **per-app rule** (WP4 `NotificationRuleEntity`), and (if matched) plays it on the
watch — buzz + move hands to the configured degrees — by reusing the WP6 compile path via the WP3
service. Folds in the one missing **WP10** piece this needs: a **Notification-Access** permission
step in Setup.

## The reused play path — play-only-by-package (NO new wire bytes, NO per-notification filter)

The per-app **vibration pattern + precise hand degrees are already on the watch** in its
`NOTIFICATION_FILTER` table (written at init/provisioning and re-pushed when the user edits an app's
rule via the WP14 sync). So a runtime notification is a **single play-only put by package**:
`FossilController.playNotification(packageName)` → the watch matches the play file's package CRC
against that on-watch filter and applies the configured vibe + hands itself. WP11 therefore only
decides **"play package X"** — the watch owns the pattern/degrees. No filter is uploaded per
notification.

## Decider policy (ported from the official Fossil app)

Recovered from `tmp/FossilOfficialApp-deobf` (`WatchNotificationManager.didReceivedNotification` +
`NotificationStatus` / `NotificationFactory`). The official listener forwards every post to a Flutter
layer; the **native pre-filter** signals + the **dedupe** are reproduced here:

- **Rule gate (first).** Only apps with a configured rule buzz. No rule → no action (we never send a
  useless play the watch would ignore for an unmatched CRC). Drops our own foreground-service
  notification and every unconfigured system/app notification cheaply.
- **Skip ongoing** (`FLAG_ONGOING_EVENT`) — media/nav/foreground-service notifications.
- **Skip group-summary** (`FLAG_GROUP_SUMMARY`) — only the child item buzzes, never the summary
  (avoids a double-buzz).
- **Skip download/progress** (`extras.progressMax != 0`) — download/upload/progress bars.
- **Consecutive-duplicate suppression** on `(id, packageName, title, text, whenTime)` — the official
  app's *exact* dedupe (collapses the in-place content re-posts apps spam). **Not** a time window;
  **not** a per-package rate-limit.
- **Priority is NOT filtered** — a per-app rule is an explicit opt-in, so a ruled app buzzes
  regardless of the notification's priority.

## Connection model — connect-then-play with a stale-drop

The WP3 service is designed to stay **persistently connected** (`START_STICKY`, auto-reconnect, CDM
presence, battery-exempt), matching the official app's "the link is always up" design. So a matched
notification is a **connect-then-play**:

- link up → play immediately on the ble-worker;
- link down → hold the package + kick a connect; play on the on-connect hook;
- a held play is **dropped if stale** (older than `PLAY_STALE_AFTER_MS` = **30 s**) so a notification
  queued during a long disconnect never buzzes minutes late;
- no associated watch → dropped silently (a passive notification is best-effort);
- publishes **no `SyncState`** (a silent background effect, not a user-initiated foreground action
  with a modal).

## Permission step (WP10)

The special **Notification Access** (the "notification listener" access — `Settings`-toggled, **not**
a runtime permission) is surfaced in the existing Setup `HomeScreen` in the same style as the other
permission rows: grant-state detection
(`NotificationManagerCompat.getEnabledListenerPackages` → `Settings.Secure
enabled_notification_listeners`) + a deep-link button
(`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`); the state re-reads on return from Settings. The
manifest registers the first `<service>` with `BIND_NOTIFICATION_LISTENER_SERVICE` + the
`NotificationListenerService` intent-filter.

## Files

- `NotificationDecider.kt` — pure decide (rule gate + skip filters + dedupe); `PostedNotification` +
  `NotificationDecision { None(reason) | Play(packageName) }`.
- `PostedNotificationExtractor.kt` — pure raw-`StatusBarNotification`-primitives → `PostedNotification`
  (flag decode + control-char/whitespace trim; mirrors the official `NotificationStatus`).
- `NotificationDispatcher.kt` — reads the cached rule set, runs the decider, forwards Play to the
  seam, tracks previous-notification for dedupe.
- `NotificationPlay.kt` — seam: `NotificationPlay { play(packageName): Boolean }`,
  `ServiceNotificationPlay` (pokes the WP3 service), `NoopNotificationPlay`.
- `FossilNotificationListenerService.kt` — thin Android shell: extract → cached-rule pre-gate →
  dispatch; caches the active watch's rule package-set off the main thread, kept live via
  `observeRules`.
- `NotificationAccess.kt` — WP10 grant detection + deep-link helper.
- `WatchConnectionService.kt` — `ACTION_PLAY_NOTIFICATION` + connect-then-play hook + 30 s stale-drop
  (reuses `FossilController.playNotification`).
- `MainActivity.kt` — the Setup notification-access permission row.
- `AndroidManifest.xml` — the `<service>` registration.

## What's on-device-pending

The live OS notification interception and the actual on-real-notification buzz/hand-move are
hardware-verified by the user. Everything provable off-device is unit-tested: the decide policy, the
field extraction, the rule-cache gating, the dispatch glue (with a fake seam), the seam contract, the
connect-then-play stale-drop logic, and the permission grant check.

## Gates at completion

- `:protocol:test` — **124 / 0 / 0** (untouched; no wire bytes).
- `:android:testDebugUnitTest` — **399 / 0 / 0** (was 359; +40 WP11/WP10 tests).
- `:android:lintDebug :android:assembleDebug` — succeed.
- `:cli:shadowJar` + `./fossil-q --help` md5 — **unchanged** (`7533ceccb6b29f81f6172bd5a71c5b98`).
