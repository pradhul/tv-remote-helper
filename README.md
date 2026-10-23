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

- **Passive auto-connect:** keeps a `connectGatt(autoConnect = true)` request open, so Android connects as soon as the remote advertises.
- **HID host connect:** asks the system HID host profile to connect the remote as an input device. This uses a hidden API via reflection, which is why `hidden_api_policy` is relaxed.
- **Targeted BLE scan:** watches for the remote's advertisement and immediately triggers a connect.
- **Re-arming:** it re-arms after disconnects, after Bluetooth toggles, and from a check every 60 seconds.
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
