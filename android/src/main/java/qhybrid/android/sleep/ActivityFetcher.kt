package qhybrid.android.sleep

import qhybrid.protocol.ActivityParser
import java.time.ZoneId

/**
 * WP-ACTIVITY (sub-part 1) — the **pure, injectable fetch→parse core** for the activity/sleep
 * pipeline. Mirrors the proven WP14 [qhybrid.android.sync.SyncOrchestrator] /
 * WP16 provable-core pattern: all of the orchestration/decision logic lives here as a pure,
 * JVM/Robolectric-unit-testable function (with a fake byte source), and the actual BLE read of the
 * watch's activity file stays behind the WP3 [qhybrid.android.WatchConnectionService].
 *
 * **What it does:** given a raw activity file `byte[]` (the same bytes the WP3 service / CLI
 * `activity` command receive from `FossilController.requestActivity` → `onActivityData`), it parses
 * it via the golden-tested WP8 surface (`ActivityParser.parse` → [SleepActivityAdapter.fromParsed],
 * which runs `ActivitySummarizer.summarizeByDay` + `detectSleepSessions`) and produces the
 * chart-ready [ActivityChartData] (whose [ActivityChartData.totalSteps] is the Dashboard step
 * total). It invents **NO parsing math and NO wire bytes** — it only wires the existing pieces.
 *
 * **Tolerance (no-watch / empty / partial / malformed):** the watch returns `byte[0]` when there
 * is no activity data on it (see `FossilQAdapter.fetchActivity`'s `FILE_EMPTY` path), and
 * `ActivityParser.parse` throws on too-short / unsupported files. This core treats null, empty,
 * too-short, and any parse failure as **"no data"** → [ActivityChartData.EMPTY] (never throws), so
 * the UI simply shows nothing rather than crashing. A successful parse with zero records yields an
 * empty-but-valid chart too.
 *
 * **Day-bucketing zone is injected** (no system clock), exactly like WP8 / [SleepActivityAdapter].
 *
 * This piece is deliberately free of Android / coroutines / the service so it is trivially
 * unit-testable against the `activity.bin` / `activity-test.bin` fixtures + a fake byte source.
 */
object ActivityFetcher {

    /**
     * Parse a raw activity file into [ActivityChartData]. Returns [ActivityChartData.EMPTY] for
     * null / empty / too-short / unparseable input (never throws). [zone] buckets minute-records
     * into local days (injected — no system clock).
     */
    fun parse(raw: ByteArray?, zone: ZoneId): ActivityChartData {
        if (raw == null || raw.isEmpty()) return ActivityChartData.EMPTY
        return try {
            val data: ActivityParser.ActivityData = ActivityParser.parse(raw)
            SleepActivityAdapter.fromParsed(data, zone)
        } catch (e: IllegalArgumentException) {
            // Too short / unsupported version / malformed → treat as "no data" (UI shows nothing).
            ActivityChartData.EMPTY
        } catch (e: RuntimeException) {
            // Any other decode failure is non-fatal for the UI — fall back to empty.
            ActivityChartData.EMPTY
        }
    }

    /**
     * The Dashboard step total for a raw activity file: the same [ActivityChartData.totalSteps] the
     * Sleep screen shows (Σ per-day steps == `ActivityData.totalSteps()`, locked by the WP8 golden
     * tests). Returns 0 for no-data input. Convenience over [parse] for callers that only need the
     * step count.
     */
    fun totalSteps(raw: ByteArray?, zone: ZoneId): Int = parse(raw, zone).totalSteps
}
