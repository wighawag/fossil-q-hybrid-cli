/*
 * Derived from GadgetBridge (Codeberg: Gadgetbridge/Gadgetbridge) @ commit
 * f5b5416ca84de63a65e527e1e5e0a5202f3e3f4f (GPLv3). Adapted and re-owned for
 * fossil-q-hybrid: platform-neutral (no Android types), package qhybrid.protocol.
 * This file is part of fossil-q-hybrid, licensed AGPLv3. See PROTOCOL-PROVENANCE.md.
 */
/*  Copyright (C) 2019-2024 Arjan Schrijver, Daniel Dakhno, Taavi Eomäe

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
package qhybrid.protocol.requests.fossil.alarm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import qhybrid.protocol.model.DeviceState;
import qhybrid.protocol.FossilWatchAdapter;
import qhybrid.protocol.file.FileHandle;
import qhybrid.protocol.requests.fossil.file.FilePutRequest;
import qhybrid.protocol.util.Version;

public class AlarmsSetRequest extends FilePutRequest {
    public AlarmsSetRequest(Alarm[] alarms, FossilWatchAdapter adapter) {
        super(FileHandle.ALARMS, createFileFromAlarms(alarms, adapter.getSupportedFileVersion(FileHandle.ALARMS)), adapter);
    }

    static public byte[] createFileFromAlarms(Alarm[] alarms, short fileFormat) {
        ByteBuffer buffer;
        boolean newFormat = fileFormat == 0x03;
        if (!newFormat) {
            buffer = ByteBuffer.allocate(alarms.length * 3);
            for (Alarm alarm : alarms) buffer.put(alarm.getData());
        } else {
            int sizeWhole = 17 * alarms.length;
            for(Alarm alarm : alarms){
                String label = alarm.getTitle();
                if (label == null || label.isEmpty()) {
                    label = "---";
                }
                label = label.substring(0, Math.min(label.length(), 15));
                alarm.setTitle(label);

                String message = alarm.getMessage();
                if (message == null || message.isEmpty()) {
                    message = "---";
                }
                message = message.substring(0, Math.min(message.length(), 50));
                alarm.setMessage(message);

                sizeWhole += label.getBytes().length + message.getBytes().length;
            }
            buffer = ByteBuffer.allocate(sizeWhole); // 4 for overall length
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            for (Alarm alarm : alarms) {
                String label = alarm.getTitle();
                String message = alarm.getMessage();
                int alarmSize = 17 + label.getBytes().length + message.getBytes().length;

                buffer.put((byte) 0x00); // No information why
                buffer.putShort((short) (alarmSize - 3)); // Alarm size, 0 above and this does not count
                buffer.put((byte) 0x00); // Probably entry id time data
                buffer.putShort((short) 3); // Probably entry length
                buffer.put(alarm.getData());

                buffer.put((byte) 0x01); // Another entry id label
                buffer.putShort((short) (label.getBytes().length + 1));  // Entry length
                buffer.put(label.getBytes());
                buffer.put((byte) 0x00); // Null terminator

                buffer.put((byte) 0x02); // Entry ID subtext
                buffer.putShort((short) (message.getBytes().length + 1)); // Entry length
                buffer.put(message.getBytes());
                buffer.put((byte) 0x00); // Null terminator
            }
        }

        return buffer.array();
    }
}
