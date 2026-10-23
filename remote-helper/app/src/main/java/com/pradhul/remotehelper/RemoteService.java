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
    static final long BOOT_WINDOW_MS = 10 * 60 * 1000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothDevice device;
    private String address;
    private BluetoothGatt gatt;
    private BluetoothProfile hidProxy;
    private BluetoothLeScanner scanner;
    private boolean scanning; private int scanMode = -1;
    private long startedAt;
    private long lastKick;
    private boolean receiverRegistered;
    private boolean hidReflectDisabled; // set after SecurityException (BLUETOOTH_PRIVILEGED)
    private long lastHardRearm;
    static final long HARD_REARM_MS = 5 * 60 * 1000L;
    private final Runnable rearmTask = () -> hardRearm("scheduled");

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
        goForeground();
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(ACTION_HID_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED); else registerReceiver(rx, f);
        receiverRegistered = true;
        Log.i(TAG, "service created, SDK " + Build.VERSION.SDK_INT);
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rh", "Remote Helper", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "rh").setContentTitle("Remote Helper")
                .setContentText("Keeping BLE remote connectable").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, n);
        } catch (Exception e) { Log.e(TAG, "startForeground failed (permissions granted?)", e); }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i != null ? i.getStringExtra(EXTRA_ADDRESS) : null;
        if (a != null && BluetoothAdapter.checkBluetoothAddress(a.toUpperCase(Locale.US))) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("address", a.toUpperCase(Locale.US)).apply();
            Log.i(TAG, "address set to " + a);
            if (gatt != null) { gatt.close(); gatt = null; }
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
        try { for (BluetoothDevice d : adapter.getBondedDevices()) if (d.getAddress().equalsIgnoreCase(address)) { device = d; bonded = true; } }
        catch (SecurityException e) { Log.e(TAG, "BLUETOOTH_CONNECT not granted", e); return; }
        Log.i(TAG, "target " + address + " name=" + safeName() + " bonded=" + bonded + " bondState=" + device.getBondState());
        if (hidProxy == null) {
            boolean ok = adapter.getProfileProxy(this, proxyListener, HID_HOST);
            Log.i(TAG, "getProfileProxy(HID_HOST) requested=" + ok);
        }
        armGatt();
        startScan();
        h.removeCallbacks(periodic);
        h.postDelayed(periodic, 60_000);
    }

    private String safeName() { try { return device.getName(); } catch (Exception e) { return "?"; } }

    // Strategy A: passive autoConnect GATT
    private void armGatt() {
        if (gatt != null || device == null) return;
        try {
            gatt = device.connectGatt(this, true, gattCb, BluetoothDevice.TRANSPORT_LE);
            Log.i(TAG, "A: connectGatt(autoConnect=true) armed: " + (gatt != null));
        } catch (Exception e) { Log.e(TAG, "A: connectGatt failed", e); }
    }

    private void hardRearm(String why) {
        Log.i(TAG, "watchdog re-arm (" + why + ")");
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        lastHardRearm = SystemClock.elapsedRealtime();
        armGatt();
        startScan();
    }

    private void scheduleRearm(String why) {
        Log.i(TAG, "re-arm scheduled in 1s (" + why + ")");
        h.removeCallbacks(rearmTask);
        h.postDelayed(rearmTask, 1000);
    }

    private void kickGatt() {
        // direct (fast) connect attempt in addition to the background one
        try {
            if (gatt != null) { boolean r = gatt.connect(); Log.i(TAG, "A: gatt.connect() -> " + r); }
            else armGatt();
        } catch (Exception e) { Log.e(TAG, "A: kick failed", e); }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "A: GATT state status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                h.post(() -> {
                    try { g.close(); } catch (Exception ignored) {}
                    if (g == gatt) gatt = null;
                    scheduleRearm("gatt status=" + status + " newState=" + newState);
                });
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                h.post(() -> hidConnect("gatt-connected"));
            }
        }
    };

    // Strategy B: hidden BluetoothHidHost.connect via reflection
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
    private void logHidState() { Log.i(TAG, "B: HID state=" + hidState()); }

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

    // Strategy C: filtered BLE scan
    private void startScan() {
        if (adapter == null || !adapter.isEnabled() || address == null) return;
        if (hidState() == BluetoothProfile.STATE_CONNECTED) { stopScan(); return; }
        int mode = (SystemClock.elapsedRealtime() - startedAt < BOOT_WINDOW_MS) ? ScanSettings.SCAN_MODE_BALANCED : ScanSettings.SCAN_MODE_LOW_POWER;
        if (scanning && mode == scanMode) return;
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { Log.w(TAG, "C: no LE scanner"); return; }
        try {
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build());
            ScanSettings s = new ScanSettings.Builder().setScanMode(mode)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build();
            scanner.startScan(filters, s, scanCb);
            scanning = true; scanMode = mode;
            Log.i(TAG, "C: scan started mode=" + mode);
        } catch (Exception e) { Log.e(TAG, "C: startScan failed (BLUETOOTH_SCAN granted?)", e); }
    }

    private void stopScan() {
        if (!scanning) return;
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanning = false; scanMode = -1;
        Log.i(TAG, "C: scan stopped");
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { h.post(() -> onSeen(r)); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) h.post(() -> onSeen(r)); }
        @Override public void onScanFailed(int code) { Log.e(TAG, "C: scan failed code=" + code); scanning = false; h.postDelayed(RemoteService.this::startScan, 5000); }
    };

    private void onSeen(ScanResult r) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastKick < 1500) return;
        lastKick = now;
        BluetoothDevice d = r.getDevice();
        Log.i(TAG, "C: remote advertising rssi=" + r.getRssi() + " bond=" + d.getBondState());
        if (d.getBondState() == BluetoothDevice.BOND_NONE) {
            Log.w(TAG, "bond missing -> createBond() = " + d.createBond());
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { Log.w(TAG, "could not open BT settings: " + e); }
            return;
        }
        device = d;
        hidConnect("adv-seen");
        kickGatt();
    }

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            logHidState();
            if (hidState() != BluetoothProfile.STATE_CONNECTED) {
                boolean overdue = SystemClock.elapsedRealtime() - lastHardRearm >= HARD_REARM_MS;
                hardRearm(overdue ? "periodic 5min hard" : "periodic 60s");
            }
            h.postDelayed(this, 60_000);
        }
    };

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                Log.i(TAG, "adapter state " + s);
                if (s == BluetoothAdapter.STATE_ON) setup();
                if (s == BluetoothAdapter.STATE_OFF) { scanning = false; if (gatt != null) { gatt.close(); gatt = null; } }
                return;
            }
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || address == null || !address.equalsIgnoreCase(d.getAddress())) return;
            if (ACTION_HID_STATE.equals(a)) {
                int s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                Log.i(TAG, "HID state changed -> " + s);
                if (s == BluetoothProfile.STATE_CONNECTED) stopScan();
                else if (s == BluetoothProfile.STATE_DISCONNECTED) scheduleRearm("hid-state-0");
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                Log.i(TAG, "ACL connected"); h.postDelayed(() -> hidConnect("acl-connected"), 500);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                Log.i(TAG, "ACL disconnected -> re-arm"); scheduleRearm("acl-disconnected");
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                Log.i(TAG, "bond state -> " + i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1));
            }
        }
    };

    @Override public void onDestroy() {
        Log.w(TAG, "service destroyed");
        h.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) gatt.close();
        if (hidProxy != null) adapter.closeProfileProxy(HID_HOST, hidProxy);
        if (receiverRegistered) unregisterReceiver(rx);
        super.onDestroy();
    }
}
