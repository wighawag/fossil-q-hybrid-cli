# WP13 — Calendar → Watch alarm slots 16–31 (Status)

✅ **DONE & VERIFIED** (provable core JVM/Robolectric-tested; the live `CalendarContract` cursor
read + the `ContentObserver` firing + the BLE alarm-file upload stay **on-device-pending**). Makes
the watch's **calendar alarm slots (16–31)** track the user's **system calendar**. Follows the
proven two-layer pattern (pure injectable core + thin Android shell) and **invents NO new wire
bytes** — it reuses the golden-tested WP9 mapper + the WP14 32-slot alarm upload end-to-end. Shipped
in 4 commits (`wp13:`), full gates pass + commit after each.

## The reused pure half (WP9 — untouched)

`qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper.mapEventsToAlarmSlots(events, now,
zone)` (golden-tested in `:protocol`, 124/0/0) already does the whole alarm-logic half: filter to
`[now, now+7d)`, de-dup on wire identity `(daysMask, hour, minute)`, sort by start, take the nearest
≤16, emit non-repeating + enabled `AlarmSlot`s in slots 16, 17, …. WP13 **only** feeds it
`(title, DTSTART)` pairs and writes its output to Room. **Nothing in `:protocol` changed.**

## Files

- **`calendar/CalendarSource.kt`** — narrow read seam (`upcomingEvents(now, windowDays):
  List<CalendarEvent>`) + `FakeCalendarSource` (test/VM default). Keeps the map-and-persist glue
  unit-testable with no real provider.
- **`calendar/CalendarAlarmSync.kt`** — the **pure** core: `(mac, events, now, zone) →
  List<WatchAlarmEntity>` via WP9, mapping each `AlarmSlot → WatchAlarmEntity` (re-key to the
  upper-case mac; `updatedAt` left at `0` — the repo stamps it). No Room, no Android.
- **`calendar/SystemCalendarSource.kt`** — the production `CalendarSource` via
  `CalendarContract.Instances` (Step 2 below).
- **`calendar/CalendarRefresher.kt`** — the read→map→replace orchestrator + `refreshAndMaybePush`.
  Fake-seam + in-memory-Room tested. Reports **changed vs. no-op** so a no-op refresh never pokes
  BLE.
- **`calendar/CalendarPush.kt`** — the **silent** push seam (`ServiceCalendarPush` /
  `NoopCalendarPush`); see the push decision below.
- **`calendar/CalendarAccess.kt`** — pure `READ_CALENDAR` grant helper (`isGranted(Boolean)` +
  Context wrapper) + the `PERMISSION` constant.
- **`db/WatchAlarmDao.kt`** — `deleteCalendarForWatch` (16..31, mirror of `deleteStandardForWatch`).
- **`db/WatchRepository.kt`** — `replaceCalendarAlarms(mac, rows)` (one txn: delete 16..31 +
  `upsertAll`, re-keyed to upper-case mac, `updatedAt` stamped). Mirrors `replaceDefaultsSections`,
  scoped to the calendar range. **Never touches slots 0–15.**
- **`WatchConnectionService.kt`** — the `ContentObserver` + debounce + on-connect/on-grant refresh.
- **`MainActivity.kt`** — the WP10 Calendar-access Setup row.
- **`AndroidManifest.xml`** — `<uses-permission android:name="android.permission.READ_CALENDAR"/>`.

## `CalendarContract` query choice: `Instances` (decided)

`SystemCalendarSource` queries `CalendarContract.Instances.CONTENT_URI/<begin>/<end>` for the
`[now, now+7d)` window. **`Instances` (not raw `Events`)** because the provider expands recurring
events into the concrete dated occurrences inside the range — exactly what WP9 wants; raw `Events`
would need manual RRULE expansion. Projects `TITLE` + `BEGIN` + `ALL_DAY`, sorted by `BEGIN ASC`,
read off the main thread.

## Ring offset (lead time) — Settings, default 1 min before the event

A **calendar-alarm ring offset** (`AppSettings.calendarAlarmOffsetMinutes`, default **1 min**, range
0..120) lets the watch ring N minutes BEFORE each event. Applied in the **pure** `CalendarAlarmSync`
by shifting each event's start earlier (`start - offset`) BEFORE the WP9 mapper runs, so all of WP9's
windowing / weekday / dedup / sort logic operates on the actual ring time (a 10:15 event with a
30-min offset maps to a 09:45 alarm — including the correct weekday bit, even across midnight).
Persisted via `SettingsPrefs` (`SettingsVocabulary.normalizeCalendarOffset`); the Settings
"Calendar alarms" card steps it ±1 min. Changing it calls `SettingsViewModel.setCalendarAlarmOffset`
→ pokes `WatchConnectionService.refreshCalendarNow` so slots 16–31 re-map + silently re-push. No new
wire bytes (just a different wall-clock minute/hour in the same alarm file).

## All-day handling: skip (decided + documented)

All-day events (`Instances.ALL_DAY = 1`) are **skipped**. An all-day event's `BEGIN` is midnight UTC,
which maps to an arbitrary local wall-clock hour — and the watch calendar alarm is a wall-clock
hour/minute, so there is no meaningful time to set. Only timed events become alarms.

## Where `refresh()` + the `ContentObserver` live

Folded into the **WP3 `WatchConnectionService`** (the persistent always-connected component that
already does on-connect refreshes — rule cache, activity), NOT a second foreground service. It:
- registers a `ContentObserver` on `Instances.CONTENT_URI` (gated on `READ_CALENDAR`), debounced via
  the WP-SYNCSTATUS **`CoroutineDebouncer`** (1.5 s) — the provider fires several `onChange`s per
  edit, so a burst coalesces into ONE refresh;
- **refreshes on connect** (mirror of WP11's on-connect rule-cache refresh) so slots 16–31 track the
  calendar hands-free;
- **refreshes on (re-)grant** via `ACTION_REFRESH_CALENDAR` (the Setup permission row), which also
  (re-)registers the observer;
- runs the refresh off the main thread on a `CoroutineScope(Dispatchers.IO + SupervisorJob())`,
  cancelled + observer unregistered in `onDestroy` (mirror of `FossilNotificationListenerService`).

`CalendarRefresher.refresh()` itself is pure-ish (fake `CalendarSource` + in-memory Room) and
detects whether the calendar rows actually changed (compared by wire-relevant fields, ignoring
`updatedAt`), so a no-op refresh never rewrites Room nor pokes BLE.

## Push decision: SILENT `syncNow(ALARMS_ONLY)` on a changed refresh (decided)

A background calendar change is a **silent background effect** — exactly like a WP11 posted
notification, NOT a user-initiated foreground save. So a refresh that **actually changed** the rows
pokes `WatchConnectionService.syncNow(ALARMS_ONLY)` **directly** via `ServiceCalendarPush`,
publishing **no** `SyncState` (no blocking "Saving to watch…" modal). A no-op refresh fires nothing
(unit-tested: changed → push once; no-op → no push). This is the deliberate **asymmetry** vs. the
foreground Alarms-screen auto-save (WP-SYNCSTATUS Step 4), which DOES show the modal because it is a
user-initiated edit. The push re-uses the golden-tested WP14 path: `SyncDataLoader` already splits
Room alarms into `alarms` (0–15) + `calendarAlarms` (16–31), `SyncOrchestrator` compiles the whole
32-slot file — **no orchestrator change**; no new wire bytes.

## `updatedAt` / `alarmsSyncedAt` interaction (calendar rows on-watch for free)

`replaceCalendarAlarms` stamps each row's `updatedAt` (the single WP-SYNCSTATUS chokepoint), so a
fresh calendar row reads as "pending" until the next alarm sync. When the silent push (or any later
alarm sync) re-pushes the 32-slot file, the `result.performed` hook in `runOnConnectSync` writes the
per-watch `alarmsSyncedAt` — which then flips the calendar rows (16–31) to ✓ "on watch" via the
existing `SectionSyncStatus.isOnWatch` derivation, **with no WP13-specific marker code**. Calendar
rows never appear on the WP16b Alarms screen (it filters to 0–15), so the user-facing auto-save never
touches them and they never clobber the user's standard alarms.

## KNOWN ISSUE / FIELD NOTES — calendar read freshness vs. the lazy `Instances` table

**Symptom (observed on a real device):** a just-added / server-synced event (e.g. set up on a
laptop, visible in the phone's calendar app) does NOT become a watch alarm immediately — it can lag
**~5 minutes**, then appears on its own (with the silent push / "saving…" modal). A manual "Resync
calendar now" during that window finds nothing.

**Root cause (confirmed):** Android's calendar provider expands the **`Instances`** table
(the per-occurrence table we query) **lazily/asynchronously**. A new event lands in the **`Events`**
table immediately (so the calendar app shows it), but the expanded `Instances` rows for the
`[now, now+7d)` range can lag by minutes — especially right after an account sync. So our read
honestly returns 0 occurrences until the provider catches up. This is a provider behaviour, not a
mapping bug (the same event maps fine once expanded).

**What we TRIED and REVERTED (do not naively re-attempt):**
- Switching the primary read to `CalendarContract.Instances.query(cr, projection, begin, end)` to
  *force* eager expansion of the range. **Regressed real devices** (commit `92a95cf`, reverted in
  `ba31f6e`): on at least one device that call returned a non-null cursor whose columns did not
  resolve via `getColumnIndex(...)` (returned -1), so EVERY row was skipped → a calendar that
  genuinely had events read as **0 events**. Combined with the full-replace this WIPED slots 16–31,
  and since every subsequent read also returned 0, neither resync nor auto-sync restored them.
  **Lesson:** `Instances.query(...)` is NOT a safe drop-in for the range-URI cursor across OEM
  providers; if revisited it MUST be validated by column-index resolution + a non-destructive
  fallback, and gated behind the FAILED-read guard below.

**What we KEPT (the durable safety net, commit `5874ecb` + `ba31f6e`):**
- The PRIMARY read is the **original, field-proven range-bounded `Instances` `CONTENT_URI/<begin>/<end>`
  cursor** (the version that worked, only with the expansion delay).
- `CalendarSource.readUpcoming` returns a **`Read(ok, events)`** so a query that THROWS is reported
  as `Read.FAILED` and `CalendarRefresher` leaves slots 16–31 **untouched** (never wipes on a
  provider error). A genuinely-empty *successful* read still clears them.
- The `ContentObserver` watches the **top-level `CalendarContract.CONTENT_URI`** (notifyForDescendants)
  so it wakes on the `Events` change as well as `Instances`.
- A user-initiated resync / permission grant runs a **bounded settle-retry** (3×, 4 s apart) that
  STOPS EARLY once a read succeeds with ≥1 event — so it self-corrects within ~12 s if the provider
  is still expanding, without BLE churn.

**Residual limitation (accepted for now):** the ~minutes delay on a brand-new event can still occur
if the provider hasn't expanded `Instances` within the settle-retry window AND no `Events` observer
tick fires — the next observer/connect refresh then picks it up. Also, if the delay is on the
*server→phone account sync* side (event not yet in the phone's provider at all), nothing app-side can
help.

**If we revisit (ideas to explore):**
- Read the **`Events`** table directly for *non-recurring* events (they have a concrete `DTSTART`
  and don't need instance expansion), and use the `Instances` range cursor only for recurring ones —
  this would make single new events appear instantly without the fragile `Instances.query`.
- Or call `Instances.query(...)` purely as an **expansion trigger** (ignore its cursor result) and
  still READ via the proven range-URI cursor immediately after.
- Either path must be validated on-device and kept behind the `Read.FAILED` anti-wipe guard.

## What's on-device-pending

The live `CalendarContract.Instances` cursor read, the real `ContentObserver` firing on a calendar
edit, and the BLE alarm-file upload are hardware/device verified by the user. Everything provable
off-device is unit-tested: the WP9-through-mapping (`CalendarAlarmSyncTest`), the `replaceCalendarAlarms`
16–31-only full-replace + mac/`updatedAt` (`ReplaceCalendarAlarmsTest`), the read→map→replace glue +
change detection + the silent-push-on-change-only rule (`CalendarRefresherTest`), and the
`READ_CALENDAR` grant check (`CalendarAccessTest`).

## Gates at completion

- `:protocol:test` — **124 / 0 / 0** (untouched; no wire bytes).
- `:android:testDebugUnitTest` — **489 / 0 / 0** (was 441 at WP start; incl. the read-only calendar
  section, the ring-offset + resync-now setting, and the calendar-read-freshness/anti-wipe fixes).
- `:android:lintDebug :android:assembleDebug` — succeed.
- `:cli:shadowJar` + `./fossil-q --help` md5 — **unchanged** (`7533ceccb6b29f81f6172bd5a71c5b98`).
