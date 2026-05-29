// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.model;

/**
 * A simple name/details pair describing one piece of device info (e.g.
 * "DEVICE_SECURITY_VERSION" -> "1.0"). Owned replacement for GadgetBridge's
 * {@code GenericItem}.
 */
public final class DeviceInfoItem {
    private final String name;
    private final String details;

    public DeviceInfoItem(String name, String details) {
        this.name = name;
        this.details = details;
    }

    public String getName() { return name; }
    public String getDetails() { return details; }
}
