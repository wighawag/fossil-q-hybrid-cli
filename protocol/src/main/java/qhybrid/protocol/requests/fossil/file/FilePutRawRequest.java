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

public class FilePutRawRequest extends FossilRequest {

    // WP-BUZZTEST diagnostics (C): INFO-level trace of the file-PUT handshake so on-device logcat
    // shows WHY a put stalls (e.g. a missing type-4 close-ack). Logging only — no behaviour change.
    private static final Logger PUTLOG = LoggerFactory.getLogger(FilePutRawRequest.class);
    public enum UploadState {INITIALIZED, UPLOADING, CLOSING, UPLOADED}

    public UploadState state;

    public ArrayList<byte[]> packets = new ArrayList<>();

    private short handle;

    private FossilWatchAdapter adapter;

    byte[] file;

    int fullCRC;

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
        if (uuid.toString().equals("3dda0003-957f-7d4a-34a6-74696673696d")) {
            int responseType = value[0] & 0x0F;
            log("response: " + responseType);
            // WP-BUZZTEST (C): trace every control-channel frame the put sees, with handle + state.
            PUTLOG.info("FilePut[0x{}] state={} <- control type={} ({} bytes)",
                    String.format("%04X", handle), state, responseType, value.length);
            // WP-BUZZTEST: ignore any framed control response addressed to a DIFFERENT file handle.
            // Since we now COMPLETE a put on its type-8 CRC-confirm and advance the serial queue,
            // a previous put's DELAYED type-4 close-ack (which DOES arrive on BlueZ) can land while
            // the NEXT put is current. That stale ack carries the previous handle; without this
            // guard the next put mis-reads it as its own close with the wrong handle and aborts
            // ("wrong file closing handle") — killing e.g. the buzz's play file. Frames 3/4/8 carry
            // the handle at offset 1; ignore them when they aren't for THIS put.
            if ((responseType == 3 || responseType == 4 || responseType == 8) && value.length >= 3) {
                short frameHandle = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort(1);
                if (frameHandle != this.handle) {
                    PUTLOG.info("FilePut[0x{}] ignoring stale control type={} for other handle 0x{}",
                            String.format("%04X", handle), responseType, String.format("%04X", frameHandle));
                    return;
                }
            }
            switch (responseType) {
                case 3: {
                    if (value.length != 5 || (value[0] & 0x0F) != 3) {
                        throw new RuntimeException("wrong answer header");
                    }
                    state = UploadState.UPLOADING;

                    WriteBatch transactionBuilder = adapter.getDeviceSupport().createWriteBatch("file upload");
                    java.util.UUID uploadCharacteristic = (UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d"));

                    this.prepareFilePackets(this.file);
                    PUTLOG.info("FilePut[0x{}] accepted — writing {} data chunk(s) ({} bytes)",
                            String.format("%04X", handle), packets.size(), file.length);

                    for (int i = 0, packetCount = packets.size(); i < packetCount; i++) {
                        byte[] packet = packets.get(i);
                        transactionBuilder.write(uploadCharacteristic, packet);
                        onPacketWritten(transactionBuilder, i, packetCount);
                    }

                    transactionBuilder.queue();
                    break;
                }
                case 8: {
                    if (value.length == 4) return;
                    ByteBuffer buffer = ByteBuffer.wrap(value);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    short handle = buffer.getShort(1);
                    int crc = buffer.getInt(8);
                    byte status = value[3];

                    ResultCode code = ResultCode.fromCode(status);
                    if(!code.inidicatesSuccess()){
                        throw new RuntimeException("upload status: " + code + "   (" + status + ")");
                    }

                    if (handle != this.handle) {
                        throw new RuntimeException("wrong response handle");
                    }

                    if (crc != this.fullCRC) {
                        throw new RuntimeException("file upload exception: wrong crc");
                    }


                    ByteBuffer buffer2 = ByteBuffer.allocate(3);
                    buffer2.order(ByteOrder.LITTLE_ENDIAN);
                    buffer2.put((byte) 4);
                    buffer2.putShort(this.handle);

                    adapter.getDeviceSupport().createWriteBatch("file close")
                            .write(
                                    UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d"),
                                    buffer2.array()
                            )
                            .queue();

                    // WP-BUZZTEST: the type-8 CRC-confirm IS the watch's "file received + verified"
                    // signal — the bytes are committed on the watch at this point. We still send the
                    // close frame above (a courtesy / protocol nicety), but we COMPLETE the put here
                    // rather than waiting for a type-4 close-ack.
                    //
                    // WHY: on this firmware over Android's GATT stack the type-4 close-ack is NEVER
                    // delivered (confirmed on-device: every file-PUT gets accept → data → type-8,
                    // then silence). Waiting for it stalled the strictly-serial request queue, so a
                    // follow-up put (e.g. the buzz's NOTIFICATION_PLAY file after its filter) never
                    // ran — no vibration. The CLI/BlueZ path does receive the final ack, but the file
                    // is equally committed at type-8 there (verified: CLI buzz works), so completing
                    // on type-8 yields the SAME effective outcome on both transports. A later type-4
                    // (other firmware) is handled as a harmless no-op (the put is already UPLOADED).
                    this.state = UploadState.UPLOADED;
                    PUTLOG.info("FilePut[0x{}] COMPLETE (CRC confirmed at type-8; close sent)",
                            String.format("%04X", handle));
                    onFilePut(true);
                    break;
                }
                case 4: {
                    // Already completed at type-8 (this firmware doesn't send type-4 anyway). If a
                    // type-4 close-ack DOES arrive (other firmware/transport), treat it as a no-op
                    // — the put is already UPLOADED and the queue has advanced.
                    if (state == UploadState.UPLOADED) return;
                    if (value.length == 9) return;
                    if (value.length != 4 || (value[0] & 0x0F) != 4) {
                        throw new RuntimeException("wrong file closing header");
                    }
                    ByteBuffer buffer = ByteBuffer.wrap(value);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);

                    short handle = buffer.getShort(1);

                    if (handle != this.handle) {
                        onFilePut(false);
                        throw new RuntimeException("wrong file closing handle");
                    }

                    byte status = buffer.get(3);

                    ResultCode code = ResultCode.fromCode(status);
                    if(!code.inidicatesSuccess()){
                        onFilePut(false);
                        throw new RuntimeException("wrong closing status: " + code + "   (" + status + ")");
                    }

                    this.state = UploadState.UPLOADED;

                    onFilePut(true);

                    log("uploaded file");
                    PUTLOG.info("FilePut[0x{}] COMPLETE (close-ack received)",
                            String.format("%04X", handle));

                    break;
                }
                case 9: {
                    PUTLOG.warn("FilePut[0x{}] WATCH-SIDE TIMEOUT (control type 9) in state {}",
                            String.format("%04X", handle), state);
                    this.onFilePut(false);
                    throw new RuntimeException("file put timeout");
                    /*timeout = true;
                    ByteBuffer buffer2 = ByteBuffer.allocate(3);
                    buffer2.order(ByteOrder.LITTLE_ENDIAN);
                    buffer2.put((byte) 4);
                    buffer2.putShort(this.handle);

                    new WriteBatch("file close")
                            .write(
                                    adapter.getDeviceSupport().getCharacteristic(UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d")),
                                    buffer2.array()
                            )
                            .queue(adapter.getDeviceSupport().getQueue());

                    this.state = UploadState.CLOSING;
                    break;*/
                }
            }
        }
    }

    @Override
    public boolean isFinished() {
        return this.state == UploadState.UPLOADED;
    }

    private void prepareFilePackets(byte[] file) {
        int maxPacketSize = calcMaxWriteChunk(adapter.getMTU()) - 1;

        byte[] data = file;

        CRC32 fullCRC = new CRC32();

        fullCRC.update(data);
        this.fullCRC = (int) fullCRC.getValue();

        int packetCount = (int) Math.ceil(data.length / (float) maxPacketSize);

        for (int i = 0; i < packetCount; i++) {
            int currentPacketLength = Math.min(maxPacketSize, data.length - i * maxPacketSize);
            byte[] packet = new byte[currentPacketLength + 1];
            packet[0] = (byte) i;
            System.arraycopy(data, i * maxPacketSize, packet, 1, currentPacketLength);

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
