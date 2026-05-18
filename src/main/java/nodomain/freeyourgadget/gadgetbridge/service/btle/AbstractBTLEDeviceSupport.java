package nodomain.freeyourgadget.gadgetbridge.service.btle;

/**
 * Shim: only provides the static calcMaxWriteChunk() method.
 * Used by FilePutRawRequest via: import static ...AbstractBTLEDeviceSupport.calcMaxWriteChunk;
 */
public class AbstractBTLEDeviceSupport {
    public static int calcMaxWriteChunk(int mtu) {
        int safeMtu = Math.max(23, mtu);
        return Math.min(512, safeMtu - 3);
    }
}
