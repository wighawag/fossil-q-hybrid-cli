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

## What's on-device-pending

The live `CalendarContract.Instances` cursor read, the real `ContentObserver` firing on a calendar
edit, and the BLE alarm-file upload are hardware/device verified by the user. Everything provable
off-device is unit-tested: the WP9-through-mapping (`CalendarAlarmSyncTest`), the `replaceCalendarAlarms`
16–31-only full-replace + mac/`updatedAt` (`ReplaceCalendarAlarmsTest`), the read→map→replace glue +
change detection + the silent-push-on-change-only rule (`CalendarRefresherTest`), and the
`READ_CALENDAR` grant check (`CalendarAccessTest`).

## Gates at completion

- `:protocol:test` — **124 / 0 / 0** (untouched; no wire bytes).
- `:android:testDebugUnitTest` — **461 / 0 / 0** (was 441 at WP start; +20).
- `:android:lintDebug :android:assembleDebug` — succeed.
- `:cli:shadowJar` + `./fossil-q --help` md5 — **unchanged** (`7533ceccb6b29f81f6172bd5a71c5b98`).
