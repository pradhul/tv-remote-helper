package com.pradhul.remotehelper;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.*;

@SuppressWarnings("MissingPermission")
public class RemoteService extends Service {
    static final String TAG = "RemoteHelper";
    static final String EXTRA_ADDRESS = "address";
    static final String DEFAULT_ADDR = "F3:4F:12:BF:58:8A";
    static final int HID_HOST = 4;
    static final String ACTION_HID_STATE = "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothDevice device;
    private String address;
    private BluetoothGatt gatt;
    private ReconnectPolicy.GattPhase gattPhase = ReconnectPolicy.GattPhase.NONE;
    private int gattGeneration;
    private BluetoothProfile hidProxy;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private int scanMode = -1;
    private long startedAt;
    private long lastWakeHint;
    private long lastKick;
    private boolean receiverRegistered;
    private boolean hidReflectDisabled;
    private boolean foregroundOk;

    static String getAddress(Context c) {
        return c.getSharedPreferences("cfg", MODE_PRIVATE).getString("address", DEFAULT_ADDR);
    }

    static void start(Context c, String reason, String addr) {
        Intent i = new Intent(c, RemoteService.class).putExtra("reason", reason);
        if (addr != null) i.putExtra(EXTRA_ADDRESS, addr);
        try { c.startForegroundService(i); } catch (Exception e) { Log.e(TAG, "startForegroundService failed", e); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startedAt = SystemClock.elapsedRealtime();
        lastWakeHint = startedAt;
        foregroundOk = goForeground();
        if (!foregroundOk) {
            stopSelf();
            return;
        }
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(ACTION_HID_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rx, f);
        }
        receiverRegistered = true;
        Log.i(TAG, "service created, SDK " + Build.VERSION.SDK_INT);
    }

    private boolean goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rh", "Remote Helper", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "rh").setContentTitle("Remote Helper")
                .setContentText("Keeping BLE remote connectable").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, n);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed (permissions granted?)", e);
            return false;
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (!foregroundOk) return START_NOT_STICKY;
        String a = i != null ? i.getStringExtra(EXTRA_ADDRESS) : null;
        if (a != null && BluetoothAdapter.checkBluetoothAddress(a.toUpperCase(Locale.US))) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("address", a.toUpperCase(Locale.US)).apply();
            Log.i(TAG, "address set to " + a);
            releaseGatt("address-changed");
            stopScan();
        }
        Log.i(TAG, "onStartCommand reason=" + (i != null ? i.getStringExtra("reason") : "restart"));
        setup();
        return START_STICKY;
    }

    private void setup() {
        if (adapter == null) { Log.e(TAG, "no bluetooth adapter"); return; }
        if (!adapter.isEnabled()) { Log.w(TAG, "bluetooth off; waiting for STATE_ON"); return; }
        address = getAddress(this);
        try { device = adapter.getRemoteDevice(address); } catch (Exception e) { Log.e(TAG, "bad address " + address, e); return; }
        boolean bonded = false;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equalsIgnoreCase(address)) { device = d; bonded = true; }
            }
        } catch (SecurityException e) { Log.e(TAG, "BLUETOOTH_CONNECT not granted", e); return; }
        Log.i(TAG, "target " + address + " name=" + safeName() + " bonded=" + bonded
                + " bondState=" + device.getBondState() + " gattPhase=" + gattPhase);
        if (hidProxy == null) {
            boolean ok = adapter.getProfileProxy(this, proxyListener, HID_HOST);
            Log.i(TAG, "getProfileProxy(HID_HOST) requested=" + ok);
        }
        applyGattAction(ReconnectPolicy.gattAction(gattPhase, ReconnectPolicy.Event.SETUP), "setup");
        startScan();
        h.removeCallbacks(periodic);
        h.postDelayed(periodic, 60_000);
    }

    private String safeName() { try { return device.getName(); } catch (Exception e) { return "?"; } }

    private boolean hidConnected() {
        return hidState() == BluetoothProfile.STATE_CONNECTED;
    }

    private void applyGattAction(ReconnectPolicy.GattAction action, String why) {
        switch (action) {
            case ARM:
                armGatt(why);
                break;
            case REPLACE:
                replaceGatt(why);
                break;
            case RELEASE:
                releaseGatt(why);
                break;
            case KEEP:
            default:
                break;
        }
    }

    private void armGatt(String why) {
        if (gatt != null || device == null) return;
        try {
            int gen = ++gattGeneration;
            gatt = device.connectGatt(this, true, gattCb, BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M, h);
            gattPhase = ReconnectPolicy.GattPhase.PENDING;
            Log.i(TAG, "A: connectGatt(autoConnect=true, PHY_1M) armed gen=" + gen + " why=" + why + " ok=" + (gatt != null));
            if (gatt == null) gattPhase = ReconnectPolicy.GattPhase.NONE;
        } catch (Exception e) {
            gattPhase = ReconnectPolicy.GattPhase.NONE;
            Log.e(TAG, "A: connectGatt failed (" + why + ")", e);
        }
    }

    private void replaceGatt(String why) {
        Log.i(TAG, "A: replace GATT (" + why + ")");
        releaseGatt(why);
        h.removeCallbacks(armLater);
        h.postDelayed(armLater, 300);
    }

    private final Runnable armLater = () -> {
        armGatt("delayed");
        startScan();
    };

    private void releaseGatt(String why) {
        h.removeCallbacks(armLater);
        BluetoothGatt g = gatt;
        gatt = null;
        gattPhase = ReconnectPolicy.GattPhase.NONE;
        gattGeneration++;
        if (g != null) {
            try { g.close(); } catch (Exception ignored) {}
            Log.i(TAG, "A: GATT closed (" + why + ")");
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "A: GATT state status=" + status + " newState=" + newState + " phase=" + gattPhase);
            if (g != gatt) {
                try { g.close(); } catch (Exception ignored) {}
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gattPhase = ReconnectPolicy.GattPhase.CONNECTED;
                hidConnect("gatt-connected");
                return;
            }
            if (ReconnectPolicy.isGattDead(newState, status)) {
                ReconnectPolicy.Event ev = (status != BluetoothGatt.GATT_SUCCESS)
                        ? ReconnectPolicy.Event.GATT_FAILED : ReconnectPolicy.Event.GATT_DISCONNECTED;
                applyGattAction(ReconnectPolicy.gattAction(gattPhase, ev),
                        "gatt status=" + status + " newState=" + newState);
                startScan();
            }
        }
    };

    private final BluetoothProfile.ServiceListener proxyListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            hidProxy = proxy;
            Log.i(TAG, "B: HID_HOST proxy connected: " + proxy.getClass().getName());
            logHidState();
            hidConnect("proxy-ready");
        }
        @Override public void onServiceDisconnected(int profile) { Log.w(TAG, "B: HID_HOST proxy disconnected"); hidProxy = null; }
    };

    private int hidState() {
        try { return hidProxy != null ? hidProxy.getConnectionState(device) : -1; } catch (Exception e) { return -2; }
    }
    private void logHidState() { Log.i(TAG, "B: HID state=" + hidState() + " gattPhase=" + gattPhase); }

    private void hidConnect(String why) {
        if (hidProxy == null || device == null) { Log.w(TAG, "B: hidConnect(" + why + ") skipped, proxy=" + hidProxy); return; }
        if (hidState() == BluetoothProfile.STATE_CONNECTED) { Log.i(TAG, "B: already HID connected (" + why + ")"); return; }
        if (hidReflectDisabled) { Log.d(TAG, "B: hidConnect(" + why + ") skipped (no BLUETOOTH_PRIVILEGED)"); return; }
        try {
            try {
                Method p = hidProxy.getClass().getMethod("setConnectionPolicy", BluetoothDevice.class, int.class);
                Object r = p.invoke(hidProxy, device, 100 /*ALLOWED*/);
                Log.i(TAG, "B: setConnectionPolicy(ALLOWED) -> " + r);
            } catch (Throwable t) { Log.d(TAG, "B: setConnectionPolicy n/a: " + t); if (isSecurity(t)) hidReflectDisabled = true; }
            Method m = hidProxy.getClass().getMethod("connect", BluetoothDevice.class);
            Object r = m.invoke(hidProxy, device);
            Log.i(TAG, "B: HidHost.connect (" + why + ") -> " + r);
        } catch (Throwable t) {
            if (isSecurity(t)) { hidReflectDisabled = true; Log.w(TAG, "B: SecurityException -> disabling HidHost reflection: " + t.getCause()); return; }
            Log.e(TAG, "B: HidHost.connect reflection failed (hidden_api_policy? privileged?): " + t
                    + (t.getCause() != null ? " cause=" + t.getCause() : ""));
        }
    }

    private static boolean isSecurity(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c instanceof SecurityException) return true;
        return false;
    }

    private void startScan() {
        if (!ReconnectPolicy.shouldScan(hidConnected(), adapter != null && adapter.isEnabled())) {
            stopScan();
            return;
        }
        if (address == null) return;
        long now = SystemClock.elapsedRealtime();
        int mode = ReconnectPolicy.scanMode(false, now - startedAt, now - lastWakeHint);
        if (scanning && mode == scanMode) return;
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { Log.w(TAG, "C: no LE scanner"); return; }
        try {
            List<ScanFilter> filters = Arrays.asList(
                    new ScanFilter.Builder().setDeviceAddress(address).build(),
                    new ScanFilter.Builder().setDeviceName(ReconnectPolicy.TARGET_NAME).build(),
                    new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(ReconnectPolicy.HID_SERVICE_UUID)).build());
            ScanSettings s = new ScanSettings.Builder().setScanMode(mode)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0).build();
            scanner.startScan(filters, s, scanCb);
            scanning = true;
            scanMode = mode;
            Log.i(TAG, "C: scan started mode=" + mode + " filters=address|name=AR|HID");
        } catch (Exception e) { Log.e(TAG, "C: startScan failed (BLUETOOTH_SCAN granted?)", e); }
    }

    private void stopScan() {
        if (!scanning) return;
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanning = false;
        scanMode = -1;
        Log.i(TAG, "C: scan stopped");
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { h.post(() -> onSeen(r)); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) h.post(() -> onSeen(r)); }
        @Override public void onScanFailed(int code) {
            Log.e(TAG, "C: scan failed code=" + code);
            h.post(() -> {
                scanning = false;
                scanMode = -1;
                h.postDelayed(RemoteService.this::startScan, 5000);
            });
        }
    };

    private void onSeen(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        String name;
        try { name = d.getName(); } catch (Exception e) { name = null; }
        if (!ReconnectPolicy.isTargetAdvertisement(address, d.getAddress(), name, d.getBondState() == BluetoothDevice.BOND_BONDED)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastKick < 1500) return;
        lastKick = now;
        Log.i(TAG, "C: remote advertising addr=" + d.getAddress() + " name=" + name
                + " rssi=" + r.getRssi() + " bond=" + d.getBondState());
        if (d.getBondState() == BluetoothDevice.BOND_NONE) {
            Log.w(TAG, "bond missing -> createBond() = " + d.createBond());
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { Log.w(TAG, "could not open BT settings: " + e); }
            return;
        }
        device = d;
        hidConnect("adv-seen");
        applyGattAction(ReconnectPolicy.gattAction(gattPhase, ReconnectPolicy.Event.SCAN_SEEN), "adv-seen");
    }

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            logHidState();
            applyGattAction(ReconnectPolicy.gattAction(gattPhase, ReconnectPolicy.Event.PERIODIC), "periodic");
            startScan();
            h.postDelayed(this, 60_000);
        }
    };

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                Log.i(TAG, "adapter state " + s);
                if (s == BluetoothAdapter.STATE_ON) {
                    lastWakeHint = SystemClock.elapsedRealtime();
                    setup();
                } else if (s == BluetoothAdapter.STATE_OFF) {
                    applyGattAction(ReconnectPolicy.gattAction(gattPhase, ReconnectPolicy.Event.BT_OFF), "bt-off");
                    stopScan();
                }
                return;
            }
            BluetoothDevice d = extraDevice(i);
            if (d == null || address == null || !address.equalsIgnoreCase(d.getAddress())) return;
            if (ACTION_HID_STATE.equals(a)) {
                int s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                Log.i(TAG, "HID state changed -> " + s + " phase=" + gattPhase);
                if (s == BluetoothProfile.STATE_CONNECTED) {
                    applyGattAction(ReconnectPolicy.gattAction(gattPhase, ReconnectPolicy.Event.HID_CONNECTED), "hid-connected");
                    stopScan();
                } else if (s == BluetoothProfile.STATE_DISCONNECTED) {
                    lastWakeHint = SystemClock.elapsedRealtime();
                    applyGattAction(ReconnectPolicy.gattAction(gattPhase, ReconnectPolicy.Event.HID_DISCONNECTED), "hid-disconnected");
                    startScan();
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                Log.i(TAG, "ACL connected");
                h.postDelayed(() -> hidConnect("acl-connected"), 500);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                Log.i(TAG, "ACL disconnected phase=" + gattPhase);
                lastWakeHint = SystemClock.elapsedRealtime();
                applyGattAction(ReconnectPolicy.gattAction(gattPhase, ReconnectPolicy.Event.ACL_DISCONNECTED), "acl-disconnected");
                startScan();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                Log.i(TAG, "bond state -> " + i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1));
            }
        }
    };

    private static BluetoothDevice extraDevice(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) {
            return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    @Override public void onDestroy() {
        Log.w(TAG, "service destroyed");
        h.removeCallbacksAndMessages(null);
        stopScan();
        releaseGatt("destroy");
        if (hidProxy != null && adapter != null) adapter.closeProfileProxy(HID_HOST, hidProxy);
        hidProxy = null;
        if (receiverRegistered) unregisterReceiver(rx);
        super.onDestroy();
    }
}
