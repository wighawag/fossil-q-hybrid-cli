package nodomain.freeyourgadget.gadgetbridge.impl;

import android.content.Context;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;

import java.util.HashMap;
import java.util.Map;

public class GBDevice {
    public enum State {
        NOT_CONNECTED,
        CONNECTING,
        CONNECTED,
        INITIALIZING,
        INITIALIZED,
        AUTHENTICATION_REQUIRED,
        WAITING_FOR_RECONNECT
    }

    private State state = State.NOT_CONNECTED;
    private final Map<String, String> deviceInfos = new HashMap<>();
    private int batteryLevel = -1;
    private String firmwareVersion;

    public void setState(State state) {
        this.state = state;
    }

    public State getState() {
        return state;
    }

    public void addDeviceInfo(GenericItem item) {
        deviceInfos.put(item.getName(), item.getDetails());
    }

    public String getDeviceInfo(String key) {
        return deviceInfos.get(key);
    }

    public void sendDeviceUpdateIntent(Context context) {
        // no-op on Linux
    }

    public void setUpdateState(State state, Context context) {
        setState(state);
        sendDeviceUpdateIntent(context);
    }

    public void setBatteryLevel(int level) {
        this.batteryLevel = level;
    }

    public int getBatteryLevel() {
        return batteryLevel;
    }

    public void setFirmwareVersion(String version) {
        this.firmwareVersion = version;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }
}
