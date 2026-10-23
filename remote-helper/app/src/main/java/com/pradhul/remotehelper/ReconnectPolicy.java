package com.pradhul.remotehelper;

/**
 * Pure reconnect decisions for the Fire TV remote helper.
 * Keep a single autoConnect GATT client armed; only replace it when that client is dead.
 */
final class ReconnectPolicy {
    static final String TARGET_NAME = "AR";
    static final String HID_SERVICE_UUID = "00001812-0000-1000-8000-00805f9b34fb";

    /** Matches {@link android.bluetooth.le.ScanSettings#SCAN_MODE_LOW_POWER}. */
    static final int SCAN_LOW_POWER = 0;
    /** Matches {@link android.bluetooth.le.ScanSettings#SCAN_MODE_BALANCED}. */
    static final int SCAN_BALANCED = 1;
    /** Matches {@link android.bluetooth.le.ScanSettings#SCAN_MODE_LOW_LATENCY}. */
    static final int SCAN_LOW_LATENCY = 2;
    static final int SCAN_NONE = -1;

    static final long BOOT_WINDOW_MS = 10 * 60 * 1000L;
    static final long WAKE_SCAN_MS = 60 * 1000L;

    enum GattPhase { NONE, PENDING, CONNECTED }

    enum Event {
        SETUP, PERIODIC, GATT_DISCONNECTED, GATT_FAILED, ACL_DISCONNECTED,
        HID_DISCONNECTED, HID_CONNECTED, BT_OFF, BT_ON, SCAN_SEEN
    }

    enum GattAction { KEEP, ARM, REPLACE, RELEASE }

    private ReconnectPolicy() {}

    static GattAction gattAction(GattPhase phase, Event event) {
        switch (event) {
            case SETUP:
            case BT_ON:
            case PERIODIC:
                return phase == GattPhase.NONE ? GattAction.ARM : GattAction.KEEP;
            case GATT_DISCONNECTED:
            case GATT_FAILED:
                return phase == GattPhase.NONE ? GattAction.ARM : GattAction.REPLACE;
            case ACL_DISCONNECTED:
            case HID_DISCONNECTED:
                return phase == GattPhase.CONNECTED ? GattAction.REPLACE : GattAction.KEEP;
            case HID_CONNECTED:
                return GattAction.KEEP;
            case BT_OFF:
                return GattAction.RELEASE;
            case SCAN_SEEN:
                return phase == GattPhase.NONE ? GattAction.ARM : GattAction.KEEP;
            default:
                return GattAction.KEEP;
        }
    }

    /**
     * A GATT client is dead only after a disconnect. A non-success status on
     * STATE_CONNECTED / STATE_CONNECTING must not tear down a client that is
     * still moving; wait for STATE_DISCONNECTED.
     */
    static boolean isGattDead(int newState, int status) {
        final int stateDisconnected = 0;
        return newState == stateDisconnected;
    }

    static boolean isCurrentClient(int callbackGeneration, int currentGeneration) {
        return callbackGeneration == currentGeneration;
    }

    static boolean isTargetAdvertisement(String configuredAddress, String advAddress,
            String name, boolean bonded) {
        if (configuredAddress != null && advAddress != null
                && configuredAddress.equalsIgnoreCase(advAddress)) {
            return true;
        }
        return bonded && name != null && TARGET_NAME.equals(name);
    }

    static boolean shouldScan(boolean hidConnected, boolean adapterOn) {
        return adapterOn && !hidConnected;
    }

    static int scanMode(boolean hidConnected, long elapsedSinceStartMs, long elapsedSinceWakeHintMs) {
        if (hidConnected) return SCAN_NONE;
        if (elapsedSinceStartMs < WAKE_SCAN_MS || elapsedSinceWakeHintMs < WAKE_SCAN_MS) {
            return SCAN_LOW_LATENCY;
        }
        if (elapsedSinceStartMs < BOOT_WINDOW_MS) return SCAN_BALANCED;
        return SCAN_LOW_POWER;
    }
}
