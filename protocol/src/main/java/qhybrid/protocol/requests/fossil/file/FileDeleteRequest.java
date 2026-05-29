/*
 * Derived from GadgetBridge (Codeberg: Gadgetbridge/Gadgetbridge) @ commit
 * f5b5416ca84de63a65e527e1e5e0a5202f3e3f4f (GPLv3). Adapted and re-owned for
 * fossil-q-hybrid: platform-neutral (no Android types), package qhybrid.protocol.
 * This file is part of fossil-q-hybrid, licensed AGPLv3. See PROTOCOL-PROVENANCE.md.
 */
/*  Copyright (C) 2019-2024 Andreas Shimokawa, Daniel Dakhno

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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import qhybrid.protocol.file.FileHandle;
import qhybrid.protocol.requests.fossil.FossilRequest;
import qhybrid.protocol.requests.fossil.file.ResultCode;

public class FileDeleteRequest extends FossilRequest {
    private boolean finished = false;
    private short handle;

    public FileDeleteRequest(short handle) {
        this.handle = handle;

        ByteBuffer buffer = createBuffer();

        buffer.putShort(handle);

        this.data = buffer.array();
    }

    public FileDeleteRequest(FileHandle handle){
        this(handle.getHandle());
    }

    @Override
    public void handleResponse(java.util.UUID uuid, byte[] value) {
        super.handleResponse(uuid, value);
        if(!uuid.toString().equals("3dda0003-957f-7d4a-34a6-74696673696d"))
            throw new RuntimeException("wrong response UUID");

        if(value.length != 4) throw new RuntimeException("wrong response length");

        if(value[0] != (byte) 0x8B) throw new RuntimeException("wrong response start");

        ByteBuffer buffer = ByteBuffer.wrap(value);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        if(buffer.getShort(1) != this.handle) throw new RuntimeException("wrong response handle");

        byte status = buffer.get(3);
        ResultCode code = ResultCode.fromCode(status);
        if(!code.inidicatesSuccess()) throw new RuntimeException("wrong response status: " + code + "(" + status + ")");

        this.finished = true;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public byte[] getStartSequence() {
        return new byte[]{(byte) 0x0B};
    }

    @Override
    public int getPayloadLength() {
        return 3;
    }
}
