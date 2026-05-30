// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import qhybrid.protocol.ButtonConfigBuilder;
import qhybrid.protocol.buttonconfig.ConfigPayload;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP-SYNCFIX — end-to-end button-config file-PUT through {@link FakeBleTransport}, proving the new
 * {@link FossilQAdapter#setButtonsRaw(byte[], CompletableFuture)} / {@link
 * FossilController#setButtons(byte[], CompletableFuture)} overload **completes its future** when the
 * watch acknowledges the write.
 *
 * <p>This is the contract the Android sync service relies on to WAIT for the button write (holding
 * the BLE link open until the put commits) instead of fire-and-forget — the bug where the button
 * file never committed on-device even though the bytes were correct. Same wire path/bytes as the
 * golden button compiler (no bytes invented).
 */
public class AdapterButtonPutTest {

    private static final short SETTINGS_BUTTONS_HANDLE = 0x0600;

    private static byte[] reassemble(List<FakeBleTransport.Write> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (FakeBleTransport.Write w : chunks) {
            out.write(w.data, 1, w.data.length - 1);
        }
        return out.toByteArray();
    }

    private static byte[] sampleButtonFile() {
        // A simple single-action mapping on each button (TOP/MIDDLE/BOTTOM), via the golden builder.
        ButtonConfigBuilder.ButtonEntry[] top = { ButtonConfigBuilder.entryFrom(ConfigPayload.STOPWATCH) };
        ButtonConfigBuilder.ButtonEntry[] mid = { ButtonConfigBuilder.entryFrom(ConfigPayload.DATE) };
        ButtonConfigBuilder.ButtonEntry[] bot = { ButtonConfigBuilder.entryFrom(ConfigPayload.FORWARD_TO_PHONE_MULTI) };
        return ButtonConfigBuilder.build(top, mid, bot);
    }

    @Test
    void buttonUpload_completesFutureOnAck() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        forceFossilProtocol(adapter);

        byte[] file = sampleButtonFile();
        assertTrue(file.length > 0, "golden button file must be non-empty");

        CompletableFuture<Boolean> done = new CompletableFuture<>();
        adapter.setButtonsRaw(file, done);

        // type-3 accept on the SETTINGS_BUTTONS handle.
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(SETTINGS_BUTTONS_HANDLE));

        // Adapter has written data chunks; reassemble + CRC-confirm + close.
        byte[] payload = reassemble(t.writesTo(FakeBleTransport.UUID_CHAR_DATA));
        assertTrue(payload.length > 0, "expected data chunks on the data characteristic");

        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.crcConfirmFrame(SETTINGS_BUTTONS_HANDLE, payload));
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.closeFrame(SETTINGS_BUTTONS_HANDLE));

        // The whole point of the fix: the future resolves true (the caller can WAIT on it).
        assertTrue(done.get(2, TimeUnit.SECONDS), "button file-put future should complete true on ack");

        // The uploaded file payload carries the golden button bytes (handle/len framing + file).
        int fileLen = payload.length - 12 - 4; // [handle+ver+0+len = 12][file][crc32 = 4]
        assertEquals(file.length, fileLen, "uploaded file length matches the golden button file");
        byte[] uploaded = new byte[file.length];
        System.arraycopy(payload, 12, uploaded, 0, file.length);
        assertArrayEquals(file, uploaded, "uploaded bytes match the golden button file");
    }

    @Test
    void buttonUpload_noFuture_stillWorks() {
        // The fire-and-forget overload must still be callable (back-compat); just no completion.
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        forceFossilProtocol(adapter);
        adapter.setButtonsRaw(sampleButtonFile()); // must not throw
    }

    private static void forceFossilProtocol(FossilQAdapter adapter) {
        adapter.detectProtocol("HW0.0.2.9r.v3");
        assertTrue(adapter.isFossilProtocol());
    }
}
