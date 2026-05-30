// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP-ONBOARD (sub-part 1) — contract test for {@link FossilController#readConfig()} /
 * {@link FossilController#readConfig(CompletableFuture)}.
 *
 * <p>The fa\u00e7ade adds NO wire behaviour: it must delegate straight to
 * {@link FossilQAdapter#readConfig(CompletableFuture)} (the decode already exists in the adapter,
 * exercised by the CLI {@code read-config} command). This test injects a seam adapter that records
 * the delegation and completes the future, and asserts:
 *   <ul>
 *     <li>the future form forwards the caller's future to the adapter;</li>
 *     <li>the blocking form returns exactly what the adapter completed the future with;</li>
 *     <li>the blocking form returns an empty list (best-effort) on timeout.</li>
 *   </ul>
 */
public class FossilControllerReadConfigTest {

    /** Seam adapter: records the future passed to readConfig and completes it on demand. */
    private static class RecordingAdapter extends FossilQAdapter {
        final AtomicReference<CompletableFuture<List<ConfigEntry>>> captured = new AtomicReference<>();
        List<ConfigEntry> toComplete;          // completed synchronously if non-null
        boolean neverComplete;                 // leave the future pending (timeout path)

        RecordingAdapter(BleTransport t) { super(t); }

        @Override
        public void readConfig(CompletableFuture<List<ConfigEntry>> result) {
            captured.set(result);
            if (neverComplete) return;
            if (toComplete != null) result.complete(toComplete);
        }
    }

    private static FossilController controllerWith(RecordingAdapter adapter) {
        FakeBleTransport t = new FakeBleTransport();
        return new FossilController(t, adapter);
    }

    @Test
    void futureForm_forwardsCallersFutureToAdapter() {
        RecordingAdapter adapter = new RecordingAdapter(new FakeBleTransport());
        FossilController controller = controllerWith(adapter);

        CompletableFuture<List<FossilQAdapter.ConfigEntry>> future = new CompletableFuture<>();
        controller.readConfig(future);

        assertSame(future, adapter.captured.get(), "fa\u00e7ade must forward the caller's future to the adapter");
    }

    @Test
    void blockingForm_returnsAdapterCompletedEntries() {
        RecordingAdapter adapter = new RecordingAdapter(new FakeBleTransport());
        adapter.toComplete = List.of(
                new FossilQAdapter.ConfigEntry(0x0A, "VIBE_STRENGTH", new byte[]{(byte) 80}, "80%"),
                new FossilQAdapter.ConfigEntry(0x03, "DAILY_STEP_GOAL",
                        new byte[]{0x10, 0x27, 0x00, 0x00}, "10000 steps"));
        FossilController controller = controllerWith(adapter);

        List<FossilQAdapter.ConfigEntry> entries = controller.readConfig();

        assertNotNull(adapter.captured.get(), "adapter.readConfig must be invoked");
        assertEquals(2, entries.size());
        assertEquals(0x0A, entries.get(0).id);
        assertEquals(0x03, entries.get(1).id);
    }

    @Test
    void blockingForm_returnsEmptyOnTimeout() {
        RecordingAdapter adapter = new RecordingAdapter(new FakeBleTransport());
        adapter.neverComplete = true;
        FossilController controller = controllerWith(adapter);

        List<FossilQAdapter.ConfigEntry> entries = controller.readConfig(50L);

        assertNotNull(adapter.captured.get(), "adapter.readConfig must be invoked even on the timeout path");
        assertTrue(entries.isEmpty(), "best-effort: empty list on timeout, never throws");
    }
}
