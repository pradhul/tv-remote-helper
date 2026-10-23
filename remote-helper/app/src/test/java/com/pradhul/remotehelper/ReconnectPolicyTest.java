package com.pradhul.remotehelper;

import static com.pradhul.remotehelper.ReconnectPolicy.Event;
import static com.pradhul.remotehelper.ReconnectPolicy.GattAction;
import static com.pradhul.remotehelper.ReconnectPolicy.GattPhase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReconnectPolicyTest {

    @Test
    public void periodicMustNotReplacePendingAutoConnect() {
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.PERIODIC));
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.CONNECTED, Event.PERIODIC));
        assertEquals(GattAction.ARM, ReconnectPolicy.gattAction(GattPhase.NONE, Event.PERIODIC));
    }

    @Test
    public void setupAndBtOnOnlyArmWhenMissing() {
        assertEquals(GattAction.ARM, ReconnectPolicy.gattAction(GattPhase.NONE, Event.SETUP));
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.SETUP));
        assertEquals(GattAction.ARM, ReconnectPolicy.gattAction(GattPhase.NONE, Event.BT_ON));
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.BT_ON));
    }

    @Test
    public void deadGattClientIsReplaced() {
        assertEquals(GattAction.REPLACE, ReconnectPolicy.gattAction(GattPhase.CONNECTED, Event.GATT_DISCONNECTED));
        assertEquals(GattAction.REPLACE, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.GATT_FAILED));
        assertEquals(GattAction.ARM, ReconnectPolicy.gattAction(GattPhase.NONE, Event.GATT_DISCONNECTED));
    }

    @Test
    public void aclOrHidDisconnectReplacesOnlyLiveGatt() {
        assertEquals(GattAction.REPLACE, ReconnectPolicy.gattAction(GattPhase.CONNECTED, Event.ACL_DISCONNECTED));
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.ACL_DISCONNECTED));
        assertEquals(GattAction.REPLACE, ReconnectPolicy.gattAction(GattPhase.CONNECTED, Event.HID_DISCONNECTED));
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.HID_DISCONNECTED));
    }

    @Test
    public void scanSeenDoesNotConvertPendingClientToDirectConnect() {
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.SCAN_SEEN));
        assertEquals(GattAction.ARM, ReconnectPolicy.gattAction(GattPhase.NONE, Event.SCAN_SEEN));
    }

    @Test
    public void bluetoothOffReleasesClient() {
        assertEquals(GattAction.RELEASE, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.BT_OFF));
        assertEquals(GattAction.RELEASE, ReconnectPolicy.gattAction(GattPhase.CONNECTED, Event.BT_OFF));
    }

    @Test
    public void hidConnectedKeepsArmedGatt() {
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.CONNECTED, Event.HID_CONNECTED));
        assertEquals(GattAction.KEEP, ReconnectPolicy.gattAction(GattPhase.PENDING, Event.HID_CONNECTED));
    }

    @Test
    public void isGattDeadOnlyWhenDisconnected() {
        assertTrue(ReconnectPolicy.isGattDead(/*disconnected*/ 0, /*success*/ 0));
        assertTrue(ReconnectPolicy.isGattDead(/*disconnected*/ 0, /*timeout*/ 8));
        assertFalse(ReconnectPolicy.isGattDead(/*connected*/ 2, /*success*/ 0));
        assertFalse(ReconnectPolicy.isGattDead(/*connected*/ 2, /*odd status*/ 133));
        assertFalse(ReconnectPolicy.isGattDead(/*connecting*/ 1, /*success*/ 0));
        assertFalse(ReconnectPolicy.isGattDead(/*connecting*/ 1, /*error*/ 133));
    }

    @Test
    public void staleCallbackIsIgnored() {
        assertTrue(ReconnectPolicy.isCurrentClient(3, 3));
        assertFalse(ReconnectPolicy.isCurrentClient(2, 3));
    }

    @Test
    public void targetAdvertisementMatchesAddressOrBondedName() {
        assertTrue(ReconnectPolicy.isTargetAdvertisement("F3:4F:12:BF:58:8A", "f3:4f:12:bf:58:8a", "?", false));
        assertTrue(ReconnectPolicy.isTargetAdvertisement("F3:4F:12:BF:58:8A", "AA:BB:CC:DD:EE:FF", "AR", true));
        assertFalse(ReconnectPolicy.isTargetAdvertisement("F3:4F:12:BF:58:8A", "AA:BB:CC:DD:EE:FF", "AR", false));
        assertFalse(ReconnectPolicy.isTargetAdvertisement("F3:4F:12:BF:58:8A", "AA:BB:CC:DD:EE:FF", "Other", true));
    }

    @Test
    public void scanModeFollowsHidAndWakeWindow() {
        assertEquals(ReconnectPolicy.SCAN_NONE, ReconnectPolicy.scanMode(true, 0, 0));
        assertEquals(ReconnectPolicy.SCAN_LOW_LATENCY, ReconnectPolicy.scanMode(false, 5_000, 5_000));
        assertEquals(ReconnectPolicy.SCAN_LOW_LATENCY, ReconnectPolicy.scanMode(false, 20 * 60 * 1000L, 10_000));
        assertEquals(ReconnectPolicy.SCAN_BALANCED, ReconnectPolicy.scanMode(false, 2 * 60 * 1000L, 2 * 60 * 1000L));
        assertEquals(ReconnectPolicy.SCAN_LOW_POWER, ReconnectPolicy.scanMode(false, 20 * 60 * 1000L, 20 * 60 * 1000L));
    }

    @Test
    public void shouldScanOnlyWhenAdapterOnAndHidDown() {
        assertTrue(ReconnectPolicy.shouldScan(false, true));
        assertFalse(ReconnectPolicy.shouldScan(true, true));
        assertFalse(ReconnectPolicy.shouldScan(false, false));
    }
}
