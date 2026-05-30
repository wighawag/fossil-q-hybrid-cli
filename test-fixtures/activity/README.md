# Activity test fixtures

Real Fossil Q Hybrid **activity files** (file handle `0x0100`) captured from hardware,
used as **golden inputs** by the activity/sleep parsing tests:

- `protocol` — `ActivityParseTest`, `Wp8ActivitySummarizerTest`
- `android`  — `ActivityFetcherTest`, `SleepActivityAdapterTest`

These are **committed test fixtures**, not scratch output. They live here (not at the repo
root) and are explicitly un-ignored in `.gitignore` (the repo otherwise ignores `*.bin`) so
they can never be mistaken for build artifacts and deleted.

The tests resolve them via the `fossilq.repoRoot` system property
(`<repoRoot>/test-fixtures/activity/<name>`); the golden assertions are derived from these
exact bytes, so replacing a file means updating the matching assertions.

| File | Purpose |
|------|---------|
| `activity.bin` | A normal capture with a sleep session (drives the sleep-detection goldens). |
| `activity-test.bin` | A short capture (18 records) with steps but too little data for a sleep session. |
