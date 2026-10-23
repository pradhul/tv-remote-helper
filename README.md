# TV Remote Helper

A tiny background app for Android TV / Google TV that keeps a paired **Amazon Fire TV remote** (or any Bluetooth LE remote) reconnecting reliably on a non-Amazon TV.

## Why this exists

I replaced the laggy stock remote on my Xiaomi Mi TV (Android 14, Google TV) with a Fire TV remote. It worked great, except for two things:

1. **It drops off when idle.** Fire TV remotes go to sleep to save battery and only wake up on a key press. That part is by design.
2. **It never came back after a TV restart.** After every reboot I had to hold the Home button for up to a minute to re-pair it, even though the TV still had it paired.

Digging into the Bluetooth logs showed the pairing was fine. The TV just wasn't trying to reconnect when the remote woke up. Forums ([HA-Firemote](https://github.com/PRProd/HA-Firemote/discussions/115), [Raspberry Pi](https://forums.raspberrypi.com/viewtopic.php?t=380138), [r/ShieldAndroidTV](https://www.reddit.com/r/ShieldAndroidTV/comments/1irdop2/)) confirm that a sleeping Fire remote can't be woken from the TV side, so no app can make it work *without* a key press. What an app *can* do is make sure the TV is listening and grabs the remote the instant it wakes.

**Result:** after a reboot, a single button press reconnects the remote within a second or two. No more holding Home.

## How it works

On boot, a small foreground service starts and, for the remote's Bluetooth address:

- **Passive auto-connect:** keeps one `connectGatt(autoConnect = true, TRANSPORT_LE, PHY_LE_1M)` client open so the controller can accept the remote as soon as it advertises. This client is not torn down on a timer. `BluetoothGatt.connect()` is never called on it (that call is a *direct* connect in AOSP and drops the accept-list entry after it times out).
- **HID host connect:** asks the system HID host profile to connect the remote as an input device. Hidden `BluetoothHidHost` APIs need `BLUETOOTH_PRIVILEGED` and are tried once, then disabled after `SecurityException`.
- **BLE scan:** while HID is down, scans with OR-filters on the bonded address, the name `AR`, and the HID service UUID (`00001812-0000-1000-8000-00805f9b34fb`). Low-latency for 60s after boot or a disconnect, then balanced for 10 minutes, then low-power. Directed advertisements still may never reach the app scanner — the armed GATT client is the path that catches those.
- **Re-arming:** a dead GATT client (disconnect / failed status) is closed and a new autoConnect client is armed. ACL/HID disconnect only replaces GATT if that client was actually connected. A 60s watchdog only arms a missing client; it does not cancel a pending background connection.
- **Lost bond:** if the pairing is ever lost, it starts re-pairing and opens Bluetooth settings.

Everything is logged under the `RemoteHelper` tag.

## Install (via adb)

```sh
S="-s <tv-ip>:5555"; P=com.pradhul.remotehelper
adb connect <tv-ip>:5555
adb $S install -r remote-helper.apk
adb $S shell pm grant $P android.permission.BLUETOOTH_CONNECT
adb $S shell pm grant $P android.permission.BLUETOOTH_SCAN
adb $S shell pm grant $P android.permission.POST_NOTIFICATIONS
adb $S shell dumpsys deviceidle whitelist +$P
adb $S shell cmd appops set $P RUN_ANY_IN_BACKGROUND allow
adb $S shell settings put global hidden_api_policy 1
# set your remote's address (find it in Settings > Remotes & accessories, or `dumpsys bluetooth_manager`)
adb $S shell am start-foreground-service -n $P/.RemoteService --es address AA:BB:CC:DD:EE:FF
adb $S logcat -s RemoteHelper
```

To undo the hidden-API setting: `adb $S shell settings delete global hidden_api_policy`.

## Build

JDK 17 and Android SDK 34:

```sh
./gradlew assembleDebug
```

## Tested on

The app was tested on a Xiaomi Mi TV (MiTV_MZTU0, Android 14) with an Amazon Fire TV remote (BLE, vendor 0x0171). It should work with other Android TV devices and BLE remotes, but you'll need to pass your remote's own address.

## License

[MIT](LICENSE). Use it, fork it, and improve it.
