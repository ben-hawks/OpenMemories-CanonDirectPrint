# CanonDirectPrint for Sony α7

[![CI](https://github.com/ben-hawks/OpenMemories-CanonDirectPrint/actions/workflows/ci.yaml/badge.svg?branch=main)](https://github.com/ben-hawks/OpenMemories-CanonDirectPrint/actions/workflows/ci.yaml)

Print photos from a **Sony α7 (ILCE-7)** straight to a **Canon Ivy 2 Mini Photo
Printer** (Zink, 2×3") or a **Canon SELPHY** Wi-Fi photo printer (CP900 and
later, 4×6" postcard), using an [OpenMemories](https://github.com/ma1co/OpenMemories-Framework)
PlayMemories Camera App.

*CanonDirectPrint is an unofficial community project. It is not affiliated with or
endorsed by Canon or Sony, and is unrelated to Canon's own "Canon PRINT" apps.*

```
α7 ── Wi-Fi ──► bridge (Android phone / Raspberry Pi / ESP32) ── Bluetooth ──► Ivy 2
α7 ── Wi-Fi ─────────────────────────────────────────────────────────────────► SELPHY
```

* **Ivy 2**: the original α7 has Wi-Fi but **no Bluetooth**, and the Ivy 2 only
  speaks Bluetooth, so the camera talks Wi-Fi to a small **bridge** that relays
  the bytes to the printer. The camera app implements the whole printer
  protocol and image processing; the bridge is a dumb byte pipe, so any of the
  bridges works.
* **SELPHY**: Wi-Fi SELPHYs speak Canon's CPNP protocol over the network, and
  newer ones (such as the CP1300) also AirPrint/IPP, so the camera prints to them
  **directly**, either by joining the printer's own *Direct Connection* network or
  with both on the same Wi-Fi. No bridge needed.

| Directory | What |
|---|---|
| [`app/`](app) | **CanonDirectPrint**, the camera app (Android 2.3 / API 10) |
| [`bridge-android/`](bridge-android) | **CanonDirectPrint Bridge**, companion app for an Android phone (Android 6+) |
| [`bridge/`](bridge/README.md) | Bridge for Linux / Raspberry Pi (Python, stdlib only) + Ivy 2 and SELPHY simulators |
| [`esp32/`](esp32/README.md) | Experimental ESP32 bridge sketch |
| [`docs/DESIGN.md`](docs/DESIGN.md) | How it works, protocol notes, design decisions |

## Status

| Hardware | Status |
|---|---|
| Sony α7 (ILCE-7) | ✅ Tested: install, UI and keys, Wi-Fi, printing |
| Canon SELPHY CP1300 | ✅ Tested: prints over AirPrint/IPP at *Standard* and *High* quality |
| Other SELPHYs with AirPrint (CP1200, CP1500, ...) | Expected to work like the CP1300; not tested |
| SELPHY CP900/CP910 (Canon CPNP, no AirPrint) | Tested against the simulator only |
| Canon Ivy 2 via the Android bridge | ✅ Tested: prints (Pixel phone hotspot, current Android) |
| Canon Ivy 2 via the Raspberry Pi bridge | Tested against the simulator only |
| ESP32 bridge | Experimental; not compiled or tested |

Everything is built and tested on every push by GitHub Actions: unit, lint and
end-to-end tests against simulated printers. If you try a combination that has
not been tested, please open an issue with your results and the log file
`CDPRINT/LOG.TXT` from the memory card. The log is not visible over USB in
*MTP* mode, which only shows photos; use *Mass Storage* mode or a card reader.

## Quick start (with an Android phone as the bridge)

1. **Build** (or download the `apks` artifact from the latest [CI run](https://github.com/ben-hawks/OpenMemories-CanonDirectPrint/actions/workflows/ci.yaml?query=branch%3Amain)):
   ```sh
   ./gradlew assembleDebug
   # app/build/outputs/apk/debug/CanonDirectPrint-debug-*.apk             (camera)
   # bridge-android/build/outputs/apk/debug/CanonDirectPrintBridge-debug-*.apk (phone)
   ```
   Building needs JDK 17+ and the Android SDK with the `platforms;android-10`
   package (`sdkmanager "platforms;android-10"`).
2. **Install on the camera** with [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE)
   (camera connected by USB in *MTP* or *Mass Storage* mode):
   ```sh
   pmca-console install -f CanonDirectPrint-debug-0.1.0.apk
   ```
3. **Phone**: install *CanonDirectPrint Bridge*, pair the Ivy 2 in the phone's Bluetooth
   settings (it appears as *Canon (xx:xx) Mini Printer*), turn on the phone's
   **Wi-Fi hotspot**, pick the printer in the app and tap **Start bridge**.
4. **Camera**: open *CanonDirectPrint* from the application list →
   *Wi-Fi settings* → join the phone's hotspot (once).
5. Pick a photo, choose the layout, press **ENTER** (or the shutter). The camera
   finds the bridge automatically.

If the camera cannot reach the printer, tap **Test printer connection** in the
phone app (with the bridge stopped). It connects to the Ivy 2 over Bluetooth on its
own and shows the printer's battery level, or which connection methods failed.
Make sure the printer is switched on and awake, and not connected to another phone
or to Canon's own app, since it accepts only one connection at a time. The bridge
connects over Bluetooth only while the camera is printing, so the Ivy 2's status
LED blinking white ("not connected") while the bridge is idle is normal.

With a Raspberry Pi instead, see [`bridge/README.md`](bridge/README.md).

## Quick start (Canon SELPHY)

1. Install *CanonDirectPrint* on the camera as above.
2. Put the SELPHY on Wi-Fi, either way:
   * **Direct**: on the printer choose *Wi-Fi settings → Direct Connection*
     (called *Connection method → Direct* on some models). It shows a network name and
     password. On the camera, open *CanonDirectPrint → Wi-Fi settings* and join that network.
   * **Shared network**: connect the printer and the camera to the same Wi-Fi
     (for example your phone's hotspot).
3. Open *Printer status & settings* and press ▲/▼ until it shows **Canon SELPHY**.
   It should find the printer and show whether paper and ink are loaded.
   The status page also shows whether the printer offers AirPrint/IPP.
4. Pick a photo and press **ENTER**. The camera stays on until the SELPHY reports
   the print as finished (about a minute per postcard).

The app first tries AirPrint/IPP and falls back to Canon's CPNP protocol on
printers without AirPrint (such as the CP900); it then uses whichever worked
first from then on. **Tested on a SELPHY CP1300**, which prints over IPP; it
accepts CPNP jobs but refuses their print connection. A printer problem, such as
missing paper, is reported straight away and does not trigger the switch.
`selphy_protocol=cpnp` or `selphy_protocol=ipp` in `CONFIG.TXT` forces one protocol.

Photos are rendered for **postcard (4×6") paper**, turned sideways automatically
to fit the landscape sheet. *Fill* prints borderless. *Fit* shows the whole photo
and uses the printer's bordered layout. With L or card paper the printer crops to
its own format.

## Using the camera app

| Screen | Keys |
|---|---|
| Photo list | ▲▼ select, ENTER open, trash exit |
| Print | ▲▼ option, ◀▶ or dials change it, **ENTER / shutter print**, trash back or cancel |
| Printer status | ▲▼ switch Ivy 2 / SELPHY, ENTER refresh, ◀▶ Ivy 2 auto power off (3/5/10 min), trash back |

Options: **Printer** Ivy 2 or SELPHY; **Layout** *Fill* (borderless, crops to
the sheet) or *Fit* (white borders); **Rotate to fit** turns photos sideways so
they use the whole sheet; **Quality** (SELPHY only) *High* renders from the
full-resolution photo, *Standard* from the ~1616×1080 preview the camera stores
in every JPEG; **Copies** (Ivy 2: sent a minute apart, since the printer gives no
"done" signal; SELPHY: each copy is sent once the previous one has printed).

*High* is the default. The postcard's native raster is 1808×1232, so the preview
image has to be scaled up about 12% (about 270 dpi instead of 300). The full
photo is decoded in strips to fit in the camera's 24 MB app memory, which takes
about 10 seconds longer on the α7. If that fails (for example for a RAW-only photo), the camera
says why and asks whether to print from the preview instead (ENTER) or cancel
(TRASH). The Ivy 2 has no Quality option: its 640×1616 raster is already
covered by the preview image.

The Ivy 2 refuses to print when its battery level is below 30 (of 63), the
paper cover is open, it is out of paper or the wrong Smart Sheet is loaded. The
SELPHY refuses without a paper or ink cassette. The camera shows which.

### Optional configuration

Create `CDPRINT/CONFIG.TXT` on the memory card:

```
# Printer used until one is chosen on the camera: ivy2 or selphy
printer=selphy
# Skip auto-discovery and always use this SELPHY
selphy_host=192.168.1.50
# SELPHY protocol: auto (default), cpnp (Canon) or ipp (AirPrint)
selphy_protocol=auto
# Skip auto-discovery and always use this Ivy 2 bridge
bridge_host=192.168.4.1
bridge_port=9100
# JPEG quality sent to the printer (50-100)
jpeg_quality=95
# Pause between 990-byte data chunks, as in the reference client
chunk_delay_ms=20
```

## Testing without hardware

```sh
python3 bridge/canondirectprint_bridge.py --simulate     # fake Ivy 2 behind a bridge, saves images to received/
sudo python3 bridge/fake_selphy.py           # fake SELPHY (CPNP on UDP 8609, IPP on 631), saves images to received/
./gradlew :app:testDebugUnitTest             # includes Java → Python end-to-end tests for both printers
cd bridge && python3 -m unittest -v test_bridge
```

## Credits

* [ma1co](https://github.com/ma1co): Sony-PMCA-RE, OpenMemories-Framework,
  PMCADemo (parts of the camera UI code are adapted from PMCADemo, MIT).
* [dtgreene/ivy2](https://github.com/dtgreene/ivy2): reverse engineering of the Ivy 2 protocol.
* [selphy_go](https://github.com/tbleher/selphy_go) by Wilmer van der Gaast, with notes by Solomon
  Peachy and Thomas Bleher: reverse engineering of the SELPHY CPNP protocol. selphy_go is GPLv2;
  this project reimplements the protocol in Java from its documentation (`PROTOCOL`,
  `send-protocol.txt`) and does not include its code.
