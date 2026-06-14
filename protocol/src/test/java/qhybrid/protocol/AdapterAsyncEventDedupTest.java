// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the "multi-buzz per press" bug (FINDINGS, logcat 2026-06-14): the watch can emit
 * the SAME async-event frame on 3dda0006 ~10x within a few ms for ONE physical press (identical
 * opcode+eventType+sequence+data). The adapter must de-duplicate these so a single press yields a
 * single emitted event (otherwise each copy drives a full effect — e.g. arm a timer + an ALARMS
 * sync — turning one press into a buzz/sync storm).
 *
 * De-dup is scoped to the discrete-ACTION event types (music 0x05 / micro_app 0x08 /
 * app-notification 0x04) and keyed on the exact frame within a short time window; a DIFFERENT
 * sequence (a genuine next press) is always handled.
 */
public class AdapterAsyncEventDedupTest {

    private static List<String> captureEvents(FossilQAdapter adapter) {
        List<String> events = new ArrayList<>();
        adapter.setOnEventJson(events::add);
        return events;
    }

    @Test
    void repeatedIdenticalMusicFrame_emitsExactlyOnce() {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        List<String> events = captureEvents(adapter);

        // One press = the SAME 0x05 MUSIC frame (op=01, type=05, seq=0x14, data=02=TOGGLE) x10.
        for (int i = 0; i < 10; i++) {
            t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x14, 0x02);
        }

        long music = events.stream().filter(e -> e.contains("\"type\":\"music\"")).count();
        assertEquals(1, music, "10 identical music frames for one press must emit exactly ONE event");
    }

    @Test
    void differentSequence_isHandledNotDeduped() {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        List<String> events = captureEvents(adapter);

        // Three DISTINCT presses: the watch increments the sequence byte (0x14, 0x15, 0x16).
        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x14, 0x02);
        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x15, 0x02);
        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x16, 0x02);

        long music = events.stream().filter(e -> e.contains("\"type\":\"music\"")).count();
        assertEquals(3, music, "distinct sequences are distinct presses — all handled");
    }

    @Test
    void differentData_sameSequence_isHandled() {
        // Defensive: same seq but different payload is not the identical frame, so it is handled.
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        List<String> events = captureEvents(adapter);

        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x14, 0x02); // TOGGLE
        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x14, 0x03); // NEXT

        long music = events.stream().filter(e -> e.contains("\"type\":\"music\"")).count();
        assertEquals(2, music);
    }

    @Test
    void repeatAfterWindow_isHandledAgain() throws InterruptedException {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        List<String> events = captureEvents(adapter);

        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x14, 0x02);
        // Wait past the 750ms de-dup window; the SAME frame is then a genuine new event.
        Thread.sleep(800);
        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x05, 0x14, 0x02);

        long music = events.stream().filter(e -> e.contains("\"type\":\"music\"")).count();
        assertEquals(2, music, "an identical frame after the de-dup window is a new press");
    }

    @Test
    void nonActionEvents_areNotDeduped() {
        // Heartbeats (0x02) repeating with the same sequence must NOT be suppressed — only the
        // discrete-action types (0x04/0x05/0x08) are de-duped.
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        List<String> events = captureEvents(adapter);

        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x02, 0x07);
        t.injectNotification(FakeBleTransport.UUID_CHAR_BUTTON, 0x01, 0x02, 0x07);

        long beats = events.stream().filter(e -> e.contains("\"type\":\"heartbeat\"")).count();
        assertTrue(beats >= 2, "heartbeats are not de-duped (got " + beats + ")");
    }
}
