/*
 * Derived from GadgetBridge (Codeberg: Gadgetbridge/Gadgetbridge) @ commit
 * f5b5416ca84de63a65e527e1e5e0a5202f3e3f4f (GPLv3). Adapted and re-owned for
 * fossil-q-hybrid: platform-neutral (no Android types), package qhybrid.protocol.
 * This file is part of fossil-q-hybrid, licensed AGPLv3. See PROTOCOL-PROVENANCE.md.
 */
/*  Copyright (C) 2020-2024 Daniel Dakhno

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package qhybrid.protocol.requests.fossil.file;

import static qhybrid.protocol.WriteBatch.calcMaxWriteChunk;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.UUID;
import java.util.zip.CRC32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qhybrid.protocol.WriteBatch;
import qhybrid.protocol.FossilWatchAdapter;
import qhybrid.protocol.file.FileHandle;
import qhybrid.protocol.requests.fossil.FossilRequest;
import qhybrid.protocol.requests.fossil.file.ResultCode;

/**
 * WP-FILEPUT-RELIABLE: re-implements the file-PUT state machine to MATCH the official Fossil app
 * (decoded under {@code tmp/FossilOfficialApp-deobf}). The earlier {@code wp-buzztest} heuristics
 * (complete-on-type-8, fire-and-forget close) were a guess at the protocol; this is the real
 * firmware contract.
 *
 * <p>Control channel = FTC {@code 3dda0003} (write-with-response / INDICATE). Data channel =
 * {@code 3dda0004} (write-WITHOUT-response). Operation codes:
 * {@code PUT_FILE=3, VERIFY_FILE=4, EOF_REACH=8, ABORT_FILE=9, WAITING_REQUEST=10}. A response
 * frame is {@code op | 0x80} (PUT→0x83, VERIFY→0x84, EOF_REACH→0x88, ABORT→0x89, WAITING→0x8A).
 * Control frames are little-endian: {@code [opByte][handle:2][status:1][...]}.
 *
 * <p>The sequence (mirrors {@code TransmitDataPhase} / {@code PutFileRequest} /
 * {@code TransferDataRequest} / {@code VerifyFileRequest}):
 * <ol>
 *   <li><b>PUT_FILE(3)</b> open on 3dda0003 → watch replies {@code 0x83} accept.</li>
 *   <li>Transmit data chunks on 3dda0004 (write-without-response, paced).</li>
 *   <li>Watch reports progress/finish via <b>EOF_REACH(0x88)</b> / <b>WAITING_REQUEST(0x8A)</b>
 *       carrying {@code sizeWritten} (offset 4) + {@code CRC32} of bytes received (offset 8).
 *       Compare that CRC to our own {@link CRC32} of the bytes we sent.
 *       <ul>
 *         <li>bytes complete + CRC match → send <b>VERIFY_FILE(4)</b> and await its {@code 0x84}.</li>
 *         <li>otherwise → resume PUT_FILE(3) from the written offset (bounded retry, ≈3).</li>
 *       </ul></li>
 *   <li><b>VERIFY_FILE(4)</b> on 3dda0003 → <b>WAIT for {@code 0x84} SUCCESS</b>. THIS is the real
 *       "file committed" confirmation (the put completes here, NOT on type-8). ABORT(0x89) /
 *       non-success → {@code onFilePut(false)}.</li>
 * </ol>
 */
public class FilePutRawRequest extends FossilRequest {

    private static final Logger PUTLOG = LoggerFactory.getLogger(FilePutRawRequest.class);

    private static final UUID CONTROL_CHARACTERISTIC =
            UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d");
    private static final UUID DATA_CHARACTERISTIC =
            UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d");

    /** Mirrors the official app's no-progress retry bound (TransmitDataPhase.m11144q → 3). */
    private static final int DATA_TRANSFER_RETRY_THRESHOLD = 3;
    /** Mirrors VerifyFileRequest's retryThreshold (= 3). */
    private static final int VERIFY_RETRY_THRESHOLD = 3;

    public enum UploadState {INITIALIZED, UPLOADING, VERIFYING, UPLOADED, FAILED}

    public UploadState state;

    public ArrayList<byte[]> packets = new ArrayList<>();

    private final short handle;

    private final FossilWatchAdapter adapter;

    byte[] file;

    int fullCRC;

    /** Total file length (bytes transmitted as data chunks = our payload length). */
    private long totalLength;

    /** sizeWritten from the most recent EOF_REACH/WAITING_REQUEST, for no-progress detection. */
    private long lastSizeWritten = -1;
    private int noProgressRetries = 0;
    private int verifyRetries = 0;
    // Recovery from a wedged handle: if PUT_FILE is rejected with OPERATION_IN_PROGRESS (the watch
    // still holds the handle half-open from an earlier aborted/timed-out put — observed to survive
    // even a reconnect), send ABORT_FILE(9) to clear it then retry the open ONCE. busyRetries bounds
    // this; recoveringFromBusy suppresses the self-inflicted ABORT ack (0x89) so it isn't treated as
    // a watch-initiated failure.
    private static final int BUSY_RETRY_THRESHOLD = 1;
    private int busyRetries = 0;
    private boolean recoveringFromBusy = false;

    // Bug 3 NOTE — a NOTIFICATION_PLAY open rejected with NOT_ENOUGH_MEMORY (0x86) means the watch's
    // notification-file area is FULL of orphaned play files (puts that never reached VERIFY/SUCCESS).
    // We do NOT try to auto-recover by deleting: on-device (2026-06-17, firmware HW0.0.2.9r.v3)
    // DELETE_FILE(0x0B) is honoured ONLY for a file that exists, and THREE delete targets all
    // returned NOT_FOUND (the fresh rotated index, the 0x09FF area handle, and a sweep of prior
    // minors). A half-open play file is firmware-internal scratch the app cannot address, so it is
    // reclaimed only by the watch's own GC (a reset/re-provision). The real fix is PREVENTION (never
    // leave a play put half-open). So on 0x86 we fail fast (below). See FINDINGS "Bug 3".

    public FilePutRawRequest(short handle, byte[] file, FossilWatchAdapter adapter) {
        this.handle = handle;
        this.adapter = adapter;

        int fileLength = file.length;
        ByteBuffer buffer = this.createBuffer();
        buffer.putShort(1, handle);
        buffer.putInt(3, 0);
        buffer.putInt(7, fileLength);
        buffer.putInt(11, fileLength);

        this.data = buffer.array();

        this.file = file;
        this.totalLength = fileLength;

        state = UploadState.INITIALIZED;
    }

    public FilePutRawRequest(FileHandle handle, byte[] file, FossilWatchAdapter adapter) {
        this(handle.getHandle(), file, adapter);
    }

    public short getHandle() {
        return handle;
    }

    @Override
    public void handleResponse(java.util.UUID uuid, byte[] value) {
        if (!uuid.toString().equals("3dda0003-957f-7d4a-34a6-74696673696d")) {
            return;
        }
        if (value.length == 0) {
            return;
        }
        // Response frames are op|0x80; the low nibble recovers the op code (3/4/8/9/10).
        int responseType = value[0] & 0x0F;
        log("response: " + responseType);
        PUTLOG.info("FilePut[0x{}] state={} <- control type={} ({} bytes)",
                String.format("%04X", handle), state, responseType, value.length);

        // Ignore any framed control response addressed to a DIFFERENT file handle. A previous put's
        // DELAYED/stale control frame (which DOES arrive late on BlueZ) can land while the NEXT put
        // is current; without this guard the next put would mis-read it as its own and corrupt its
        // state. Frames 3/4/8/9/10 carry the handle at offset 1.
        if (value.length >= 3
                && (responseType == 3 || responseType == 4 || responseType == 8
                    || responseType == 9 || responseType == 10)) {
            short frameHandle = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort(1);
            if (frameHandle != this.handle) {
                PUTLOG.info("FilePut[0x{}] ignoring stale control type={} for other handle 0x{}",
                        String.format("%04X", handle), responseType, String.format("%04X", frameHandle));
                return;
            }
        }

        switch (responseType) {
            case 3:
                handlePutAccept(value);
                break;
            case 8:   // EOF_REACH — progress/finish report (sizeWritten + watch CRC32)
            case 10:  // WAITING_REQUEST — same payload prefix + a firmware-proposed timeout
                handleEofReach(value);
                break;
            case 4:
                handleVerifyResponse(value);
                break;
            case 9:
                handleAbort(value);
                break;
            default:
                break;
        }
    }

    /** PUT_FILE(3) accepted → transmit the data chunks on 3dda0004. */
    private void handlePutAccept(byte[] value) {
        if (value.length < 5 || (value[0] & 0x0F) != 3) {
            throw new RuntimeException("wrong answer header");
        }
        // The PUT-accept frame carries a STATUS byte at offset 3. ONLY status 0 (SUCCESS) means the
        // watch actually opened the file and is ready for data. A non-zero status — e.g.
        // OPERATION_IN_PROGRESS(2) when a PRIOR put on this handle timed out and the watch still has
        // it half-open — means the open was REJECTED. Transmitting data anyway writes into the void:
        // the watch never sends EOF_REACH, so the put just hangs until the caller's ~12s timeout
        // (observed on-device 2026-06-14 for the timer ALARMS upload: accept = 83 .. 02). Fail FAST
        // instead so the caller gets an honest, immediate failure (and the next attempt — once the
        // watch's stale handle clears — can succeed) rather than a 12s stall.
        // Mask to UNSIGNED: the status byte for firmware-internal errors is 0x80..0x8D (128..141),
        // which sign-extends to a NEGATIVE int as a raw byte and would never match ResultCode's
        // positive codes (so 0x86 would wrongly resolve to UNKNOWN instead of NOT_ENOUGH_MEMORY).
        int acceptStatus = value[3] & 0xFF;
        ResultCode acceptCode = ResultCode.fromCode(acceptStatus);
        if (!acceptCode.inidicatesSuccess()) {
            // OPERATION_IN_PROGRESS: the watch still holds this handle half-open (a prior put never
            // cleanly closed). Send ABORT_FILE(9) to release it then retry the open ONCE, rather
            // than failing permanently — otherwise the handle stays wedged across reconnects and this
            // file can NEVER upload (observed on-device 2026-06-14 for ALARMS handle 0x0A).
            if (acceptCode == ResultCode.OPERATION_IN_PROGRESS && busyRetries < BUSY_RETRY_THRESHOLD) {
                busyRetries++;
                recoveringFromBusy = true;
                PUTLOG.warn("FilePut[0x{}] open busy (OPERATION_IN_PROGRESS) — ABORT_FILE(9) + retry open ({}/{})",
                        String.format("%04X", handle), busyRetries, BUSY_RETRY_THRESHOLD);
                abortThenReopen();
                return;
            }
            // NOTE: 0x86 = 134 = FIRMWARE_INTERNAL_ERROR_NOT_ENOUGH_MEMORY (NOT "NOT_SUPPORT",
            // which is 0x88 = 136). On the NOTIFICATION_PLAY handle (0x0900) this means the watch's
            // file area is FULL of orphaned play files (e.g. left by an earlier storm of half-open
            // puts that never reached VERIFY/SUCCESS). A BLE re-pair does NOT reclaim it; only the
            // watch's own file-area GC (a watch reset) does. Proper fix: delete/overwrite the play
            // file the way the official app does instead of PUT-ing a new file per buzz.
            //
            // Bug 3: a play-handle 0x86 = the notification-file area is wedged full of orphans. We do
            // NOT auto-recover (delete is a dead end on this firmware — see the note above + FINDINGS);
            // fail fast and let PREVENTION keep the area from wedging. A wedged watch needs a
            // re-provision/reset to reclaim the area.
            PUTLOG.warn("FilePut[0x{}] PUT_FILE rejected: status {} ({}) — not transmitting; failing fast",
                    String.format("%04X", handle), acceptCode, acceptStatus);
            state = UploadState.FAILED;
            onFilePut(false);
            return;
        }
        recoveringFromBusy = false;
        state = UploadState.UPLOADING;

        WriteBatch transactionBuilder = adapter.getDeviceSupport().createWriteBatch("file upload");

        this.prepareFilePackets(this.file);
        PUTLOG.info("FilePut[0x{}] accepted — writing {} data chunk(s) ({} bytes)",
                String.format("%04X", handle), packets.size(), file.length);

        for (int i = 0, packetCount = packets.size(); i < packetCount; i++) {
            byte[] packet = packets.get(i);
            transactionBuilder.write(DATA_CHARACTERISTIC, packet);
            onPacketWritten(transactionBuilder, i, packetCount);
        }

        transactionBuilder.queue();
    }

    /**
     * EOF_REACH(0x88) / WAITING_REQUEST(0x8A): the watch reports sizeWritten (offset 4) and a CRC32
     * of the bytes it received (offset 8). Compare to our own CRC32. If the file is fully received
     * and the CRC matches → VERIFY_FILE(4). Otherwise resume PUT_FILE(3) (bounded retry).
     */
    private void handleEofReach(byte[] value) {
        if (value.length < 12) {
            // A short EOF/keepalive frame with no size/crc payload — nothing to act on.
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        short frameHandle = buffer.getShort(1);
        byte status = value[3];
        long sizeWritten = buffer.getInt(4) & 0xFFFFFFFFL;
        int crc = buffer.getInt(8);

        ResultCode code = ResultCode.fromCode(status);
        if (!code.inidicatesSuccess()) {
            onFilePut(false);
            throw new RuntimeException("upload status: " + code + "   (" + status + ")");
        }
        if (frameHandle != this.handle) {
            throw new RuntimeException("wrong response handle");
        }

        boolean crcMatch = crc == this.fullCRC;
        boolean complete = sizeWritten == this.totalLength;

        PUTLOG.info("FilePut[0x{}] EOF_REACH sizeWritten={}/{} crc={} (match={})",
                String.format("%04X", handle), sizeWritten, totalLength,
                String.format("%08X", crc), crcMatch);

        if (complete && crcMatch) {
            sendVerifyFile();
            return;
        }

        // Partial / CRC mismatch → resume PUT_FILE from the written offset (bounded retry, mirroring
        // the official 3-no-progress-retry threshold). The watch generally sends all-or-timeout for
        // our small files, but we honour the firmware's resume contract rather than falsely succeed.
        if (!crcMatch && complete) {
            PUTLOG.warn("FilePut[0x{}] CRC mismatch on full transfer (watch={} ours={}) — resuming",
                    String.format("%04X", handle),
                    String.format("%08X", crc), String.format("%08X", this.fullCRC));
        }

        if (sizeWritten == lastSizeWritten || sizeWritten == 0) {
            noProgressRetries++;
        } else {
            noProgressRetries = 0;
        }
        lastSizeWritten = sizeWritten;

        if (noProgressRetries >= DATA_TRANSFER_RETRY_THRESHOLD) {
            PUTLOG.warn("FilePut[0x{}] no-progress retry threshold reached ({}); FAIL",
                    String.format("%04X", handle), noProgressRetries);
            state = UploadState.FAILED;
            onFilePut(false);
            return;
        }

        resumePutFile(sizeWritten);
    }

    /** Re-open PUT_FILE(3) from the given written offset to continue the transfer. */
    private void resumePutFile(long writtenOffset) {
        long remaining = this.totalLength - writtenOffset;
        ByteBuffer buffer = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x03);
        buffer.putShort(1, this.handle);
        buffer.putInt(3, (int) writtenOffset);
        buffer.putInt(7, (int) remaining);
        buffer.putInt(11, (int) this.totalLength);
        // Re-arm the resume offset so prepareFilePackets re-chunks from there.
        this.resumeOffset = (int) writtenOffset;
        PUTLOG.info("FilePut[0x{}] resuming PUT_FILE from offset {} ({} remaining)",
                String.format("%04X", handle), writtenOffset, remaining);
        adapter.getDeviceSupport().createWriteBatch("file resume")
                .write(CONTROL_CHARACTERISTIC, buffer.array())
                .queue();
    }

    private int resumeOffset = 0;

    /** Send VERIFY_FILE(4) and await its 0x84 SUCCESS (the real "file committed" confirmation). */
    private void sendVerifyFile() {
        state = UploadState.VERIFYING;
        ByteBuffer buffer = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x04);
        buffer.putShort(this.handle);
        PUTLOG.info("FilePut[0x{}] data complete + CRC match — sending VERIFY_FILE(4)",
                String.format("%04X", handle));
        adapter.getDeviceSupport().createWriteBatch("file verify")
                .write(CONTROL_CHARACTERISTIC, buffer.array())
                .queue();
    }

    /** VERIFY_FILE response (0x84): SUCCESS completes the put; otherwise honest failure. */
    private void handleVerifyResponse(byte[] value) {
        // WAITING_REQUEST while verifying is handled by the 0x8A path; here we expect 0x84.
        if (value.length < 4) {
            throw new RuntimeException("wrong file verify header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        short frameHandle = buffer.getShort(1);
        if (frameHandle != this.handle) {
            onFilePut(false);
            throw new RuntimeException("wrong file verify handle");
        }
        byte status = buffer.get(3);
        ResultCode code = ResultCode.fromCode(status);
        if (!code.inidicatesSuccess()) {
            // Bounded retry of the VERIFY itself (mirrors VerifyFileRequest retryThreshold = 3).
            if (verifyRetries < VERIFY_RETRY_THRESHOLD) {
                verifyRetries++;
                PUTLOG.warn("FilePut[0x{}] VERIFY status {} ({}) — retry {}/{}",
                        String.format("%04X", handle), code, status, verifyRetries, VERIFY_RETRY_THRESHOLD);
                sendVerifyFile();
                return;
            }
            state = UploadState.FAILED;
            onFilePut(false);
            throw new RuntimeException("file verify failed: " + code + "   (" + status + ")");
        }

        this.state = UploadState.UPLOADED;
        log("uploaded file");
        PUTLOG.info("FilePut[0x{}] COMPLETE (VERIFY_FILE 0x84 SUCCESS)", String.format("%04X", handle));
        onFilePut(true);
    }

    /**
     * Send ABORT_FILE(9) to clear a half-open handle, then immediately re-send the PUT_FILE(3) open
     * to retry the transfer. Both control writes are queued in order on 3dda0003.
     */
    private void abortThenReopen() {
        ByteBuffer abort = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        abort.put((byte) 0x09);
        abort.putShort(this.handle);
        // Re-send the original PUT_FILE(3) open (full file, offset 0) — same framing as the first try.
        this.resumeOffset = 0;
        ByteBuffer open = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
        open.put((byte) 0x03);
        open.putShort(1, this.handle);
        open.putInt(3, 0);
        open.putInt(7, (int) this.totalLength);
        open.putInt(11, (int) this.totalLength);
        adapter.getDeviceSupport().createWriteBatch("file abort+reopen")
                .write(CONTROL_CHARACTERISTIC, abort.array())
                .write(CONTROL_CHARACTERISTIC, open.array())
                .queue();
    }

    /** ABORT_FILE(0x89): the watch aborted the transfer — honest failure (unless it's the ack to
     *  our OWN abort during busy-recovery, which we ignore and wait for the re-opened PUT accept). */
    private void handleAbort(byte[] value) {
        if (recoveringFromBusy) {
            PUTLOG.info("FilePut[0x{}] ABORT ack during busy-recovery — ignoring (awaiting re-open accept)",
                    String.format("%04X", handle));
            return;
        }
        PUTLOG.warn("FilePut[0x{}] WATCH ABORT (control type 9) in state {}",
                String.format("%04X", handle), state);
        state = UploadState.FAILED;
        this.onFilePut(false);
        throw new RuntimeException("file put aborted by watch");
    }

    @Override
    public boolean isFinished() {
        return this.state == UploadState.UPLOADED || this.state == UploadState.FAILED;
    }

    private void prepareFilePackets(byte[] file) {
        int maxPacketSize = calcMaxWriteChunk(adapter.getMTU()) - 1;

        CRC32 fullCRC = new CRC32();
        fullCRC.update(file);
        this.fullCRC = (int) fullCRC.getValue();

        packets.clear();

        int start = this.resumeOffset;
        int remaining = file.length - start;
        int packetCount = (int) Math.ceil(remaining / (float) maxPacketSize);

        for (int i = 0; i < packetCount; i++) {
            int chunkOffset = start + i * maxPacketSize;
            int currentPacketLength = Math.min(maxPacketSize, file.length - chunkOffset);
            byte[] packet = new byte[currentPacketLength + 1];
            packet[0] = (byte) i;
            System.arraycopy(file, chunkOffset, packet, 1, currentPacketLength);
            packets.add(packet);
        }
    }

    public void onFilePut(boolean success) {

    }

    public void onPacketWritten(WriteBatch transactionBuilder, int packetNr, int packetCount) {

    }

    @Override
    public byte[] getStartSequence() {
        return new byte[]{0x03};
    }

    @Override
    public int getPayloadLength() {
        return 15;
    }

    @Override
    public UUID getRequestUUID() {
        return UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d");
    }
}
