# Ivy2 Print for Sony α7

Print photos from a **Sony α7 (ILCE-7)** straight to a **Canon Ivy 2 Mini Photo
Printer** (Zink, 2×3") or a **Canon SELPHY** Wi-Fi photo printer (CP900 and
later, 4×6" postcard), using an [OpenMemories](https://github.com/ma1co/OpenMemories-Framework)
PlayMemories Camera App.

```
α7 ── Wi-Fi ──► bridge (Android phone / Raspberry Pi / ESP32) ── Bluetooth ──► Ivy 2
α7 ── Wi-Fi ─────────────────────────────────────────────────────────────────► SELPHY
```

* **Ivy 2**: the original α7 has Wi-Fi but **no Bluetooth**, and the Ivy 2 only
  speaks Bluetooth, so the camera talks Wi-Fi to a small **bridge** that relays
  the bytes to the printer. The camera app implements the whole printer
  protocol and image processing; the bridge is a dumb byte pipe, so any of the
  bridges works.
* **SELPHY**: Wi-Fi SELPHYs speak Canon's CPNP protocol over the network, so the
  camera prints to them **directly**, either by joining the printer's own
  *Direct Connection* network or with both on the same Wi-Fi. No bridge needed.

| Directory | What |
|---|---|
| [`app/`](app) | **Ivy2 Print**, the camera app (Android 2.3 / API 10) |
| [`bridge-android/`](bridge-android) | **Ivy2 Bridge**, companion app for an Android phone (Android 6+) |
| [`bridge/`](bridge/README.md) | Bridge for Linux / Raspberry Pi (Python, stdlib only) + Ivy 2 and SELPHY simulators |
| [`esp32/`](esp32/README.md) | Experimental ESP32 bridge sketch |
| [`docs/DESIGN.md`](docs/DESIGN.md) | How it works, protocol notes, design decisions |

> **Status:** everything builds and is tested against a simulated printer
> (unit, lint and end-to-end tests; a GitHub Actions workflow is in
> `docs/ci-workflow.yml`, copy it to `.github/workflows/` to enable it). It has **not yet been tested on a
> real camera or printer**. Please open an issue with your results and the log
> file `IVY2PRNT/LOG.TXT` from the memory card.

## Quick start (with an Android phone as the bridge)

1. **Build** (or download the APKs from the CI artifacts once CI is enabled):
   ```sh
   ./gradlew assembleDebug
   # app/build/outputs/apk/debug/Ivy2Print-debug-*.apk             (camera)
   # bridge-android/build/outputs/apk/debug/Ivy2Bridge-debug-*.apk (phone)
   ```
   Building needs JDK 17+ and the Android SDK with the `platforms;android-10`
   package (`sdkmanager "platforms;android-10"`).
2. **Install on the camera** with [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE)
   (camera connected by USB in *MTP* or *Mass Storage* mode):
   ```sh
   pmca-console install -f Ivy2Print-debug-0.1.0.apk
   ```
3. **Phone**: install *Ivy2 Bridge*, pair the Ivy 2 in the phone's Bluetooth
   settings (it appears as *Canon (xx:xx) Mini Printer*), turn on the phone's
   **Wi-Fi hotspot**, pick the printer in the app and tap **Start bridge**.
4. **Camera**: open *Ivy2 Print* from the application list →
   *Wi-Fi settings* → join the phone's hotspot (once).
5. Pick a photo, choose the layout, press **ENTER** (or the shutter). The camera
   finds the bridge automatically.

With a Raspberry Pi instead, see [`bridge/README.md`](bridge/README.md).

## Quick start (Canon SELPHY)

1. Install *Ivy2 Print* on the camera as above.
2. Put the SELPHY on Wi-Fi, either way:
   * **Direct**: on the printer choose *Wi-Fi settings → Direct Connection*
     (called *Connection method → Direct* on some models). It shows a network name and
     password. On the camera, open *Ivy2 Print → Wi-Fi settings* and join that network.
   * **Shared network**: connect the printer and the camera to the same Wi-Fi
     (for example your phone's hotspot).
3. Open *Printer status & settings* and press ▲/▼ until it shows **Canon SELPHY**.
   It should find the printer and show whether paper and ink are loaded.
4. Pick a photo and press **ENTER**. The camera stays on until the SELPHY reports
   the print as finished (about a minute per postcard).

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
they use the whole sheet; **Copies** (Ivy 2: sent a minute apart, since the
printer gives no "done" signal; SELPHY: each copy is sent once the previous one
has printed).

The Ivy 2 refuses to print when its battery level is below 30 (of 63), the
paper cover is open, it is out of paper or the wrong Smart Sheet is loaded. The
SELPHY refuses without a paper or ink cassette. The camera shows which.

### Optional configuration

Create `IVY2PRNT/CONFIG.TXT` on the memory card:

```
# Printer used until one is chosen on the camera: ivy2 or selphy
printer=selphy
# Skip auto-discovery and always use this SELPHY
selphy_host=192.168.1.50
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
python3 bridge/ivy2_bridge.py --simulate     # fake Ivy 2 behind a bridge, saves images to received/
python3 bridge/fake_selphy.py                # fake SELPHY on UDP 8609, saves images to received/
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
