# TV Remote Helper

A tiny Android TV background app that keeps a paired Bluetooth LE remote (e.g. an Amazon Fire TV remote) reconnecting to a non-Amazon Android/Google TV.

Fire TV remotes sleep and only reconnect on a key press, and after a TV reboot they often need a full re-pair. This helper starts at boot, keeps a passive GATT auto-connect plus HID-host connect armed, and scans for the remote, so a single key press brings it back.

## Install (adb)
```
S="-s <tv-ip>:5555"; P=com.pradhul.remotehelper
adb $S install -r remote-helper.apk
adb $S shell pm grant $P android.permission.BLUETOOTH_CONNECT
adb $S shell pm grant $P android.permission.BLUETOOTH_SCAN
adb $S shell dumpsys deviceidle whitelist +$P
adb $S shell settings put global hidden_api_policy 1
adb $S shell am start-foreground-service -n $P/.RemoteService --es address AA:BB:CC:DD:EE:FF
adb $S logcat -s RemoteHelper
```
Build: `./gradlew assembleDebug` (JDK 17, SDK 34).
