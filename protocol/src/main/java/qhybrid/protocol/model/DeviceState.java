// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.model;

/**
 * Connection/initialisation lifecycle state of the watch. Owned replacement for
 * GadgetBridge's {@code GBDevice.State} (only the enum was ever used here).
 */
public enum DeviceState {
    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
    INITIALIZING,
    INITIALIZED,
    AUTHENTICATION_REQUIRED,
    WAITING_FOR_RECONNECT
}
