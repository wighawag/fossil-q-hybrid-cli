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
package qhybrid.protocol.requests.fossil.notification;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.zip.CRC32;

import qhybrid.protocol.requests.fossil.notification.NotificationConfiguration;
import qhybrid.protocol.FossilWatchAdapter;
import qhybrid.protocol.file.FileHandle;
import qhybrid.protocol.requests.fossil.file.FilePutRequest;

public class NotificationFilterPutRequest extends FilePutRequest {
    public NotificationFilterPutRequest(NotificationConfiguration[] configs, FossilWatchAdapter adapter) {
        super(FileHandle.NOTIFICATION_FILTER, createFile(configs), adapter);
    }


    public NotificationFilterPutRequest(ArrayList<NotificationConfiguration> configs, FossilWatchAdapter adapter) {
        super(FileHandle.NOTIFICATION_FILTER, createFile(configs.toArray(new NotificationConfiguration[0])), adapter);
    }

    private static byte[] createFile(NotificationConfiguration[] configs){
        ByteBuffer buffer = ByteBuffer.allocate(configs.length * 27);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for(NotificationConfiguration config : configs){
            buffer.putShort((short) 25); //packet length

            CRC32 crc = new CRC32();
            crc.update(config.getPackageName().getBytes());

            buffer.put(PacketID.PACKAGE_NAME_CRC.id);
            buffer.put((byte) 4);
            buffer.putInt((int) crc.getValue());

            buffer.put(PacketID.GROUP_ID.id);
            buffer.put((byte) 1);
            buffer.put((byte) 2);

            buffer.put(PacketID.PRIORITY.id);
            buffer.put((byte) 1);
            buffer.put((byte) 0xFF);

            buffer.put(PacketID.MOVEMENT.id);
            buffer.put((byte) 8);
            buffer.putShort(config.getHour())
                    .putShort(config.getMin())
                    .putShort(config.getSubEye())
                    .putShort((short) 5000);

            buffer.put(PacketID.VIBRATION.id);
            buffer.put((byte) 1);
            buffer.put(config.getVibration().getValue());
        }

        return buffer.array();
    }

    enum PacketID{
        PACKAGE_NAME((byte) 0x01),
        SENDER_NAME((byte) 0x02),
        PACKAGE_NAME_CRC((byte) 0x04),
        GROUP_ID((byte) 0x80),
        APP_DISPLAY_NAME((byte) 0x81),
        ICON((byte) 0x82),
        PRIORITY((byte) 0xC1),
        MOVEMENT((byte) 0xC2),
        VIBRATION((byte) 0xC3);

        byte id;

        PacketID(byte id){
            this.id = id;
        }
    }

    enum VibrationType{
        SINGLE_SHORT((byte) 5),
        DOUBLE_SHORT((byte) 6),
        TRIPLE_SHORT((byte) 7),
        SINGLE_LONG((byte) 8),
        SILENT((byte) 9);

        byte id;
        VibrationType(byte id){
            this.id = id;
        }
    }
}
