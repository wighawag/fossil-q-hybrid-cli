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

                    this.state = UploadState.CLOSING;
                    PUTLOG.info("FilePut[0x{}] CRC confirmed — sent close (type 4), awaiting close-ack",
                            String.format("%04X", handle));
                    break;
                }
                case 4: {
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
