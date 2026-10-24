// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.buttonconfig;

/** Test-only bridge to the package-private {@link HidKeyPayload#data(int, int)}. */
public final class HidKeyPayloadTestAccess {
    private HidKeyPayloadTestAccess() {}

    public static byte[] data(int declarationId, int usage) {
        return HidKeyPayload.data(declarationId, usage);
    }
}
