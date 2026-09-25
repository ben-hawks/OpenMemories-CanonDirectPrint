# ESP32 bridge (experimental)

`canondirectprint_bridge/canondirectprint_bridge.ino` turns an original ESP32 into a pocket-sized
Wi-Fi → Bluetooth bridge: it opens its own access point (`CANONDIRECTPRINT-BRIDGE` /
`canondirectprint`) for the camera and relays TCP port 9100 to the printer over
Bluetooth Classic SPP.

**Status: untested.** It is written against the Arduino-ESP32 `BluetoothSerial`
API but has not yet been compiled in CI or tried with a real Ivy 2. The Raspberry Pi
script (`bridge/`) and the Android app (`bridge-android/`) are the reference
bridges; please report results.

* Only the original ESP32 has Bluetooth Classic. ESP32-S2/S3/C3/C6/H2 will not work.
* Set `PRINTER_ADDRESS` to your printer's Bluetooth address.
* Select partition scheme **Huge APP** in the Arduino IDE.
* On the camera, join `CANONDIRECTPRINT-BRIDGE` in *CanonDirectPrint → Wi-Fi settings*. The camera
  finds the bridge automatically (it is also the network's gateway, `192.168.4.1`).
