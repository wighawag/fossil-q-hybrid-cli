package qhybrid.android.sync

/**
 * WP-PROGRESS (sub-part 2) — the **pure, injectable** "publish SYNCING → SUCCESS/ERROR around a
 * sync pass" core, extracted from the WP3 [qhybrid.android.WatchConnectionService] so the
 * SyncState lifecycle contract is JVM-unit-testable WITHOUT the Android service or any BLE.
 *
 * The service is still the SINGLE writer of [SyncState] (cross-cutting rule 3) — it just delegates
 * the phase choreography here so the decision logic ("flip SYNCING first, then SUCCESS from the
 * result, ERROR if the pass throws before producing one") can be proven headlessly with a fake
 * sync block. The clock is injected ([now]) so this stays test-friendly; BLE stays behind the
 * service.
 *
 * Honesty note (see [SyncState]): SUCCESS means the pass ran to completion and produced a
 * [SyncResult]; per-section failures live in [SyncResult.errors] (surfaced via
 * [SyncState.SyncStatus.hadSectionErrors]). ERROR means the pass threw before producing one.
 */
object SyncStateReporter {

    /**
     * Run [pass] (a full [SyncOrchestrator.sync] invocation), publishing the SyncState lifecycle:
     * SYNCING before, SUCCESS with the produced [SyncResult] after, or ERROR (with the message) if
     * [pass] throws. Returns the [SyncResult] on success, or null if [pass] threw (the exception is
     * NOT re-thrown — the caller logs it; the holder already recorded the ERROR phase).
     *
     * @param now epoch-millis supplier (injected; `System::currentTimeMillis` in production).
     */
    inline fun reportAround(now: () -> Long, pass: () -> SyncResult): SyncResult? {
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = now())
        return try {
            val result = pass()
            SyncState.publish(SyncState.SyncPhase.SUCCESS, result = result, nowMillis = now())
            result
        } catch (e: Exception) {
            SyncState.publish(
                SyncState.SyncPhase.ERROR,
                errorMessage = e.message ?: e.javaClass.simpleName,
                nowMillis = now(),
            )
            null
        }
    }
}
