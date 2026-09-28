# Design notes

This document records what was learned from the reference projects and the
resulting plan for CanonDirectPrint.

## 1. How OpenMemories apps work

* Sony cameras with *PlayMemories Camera Apps* (the ILCE-7 included) run a
  stripped-down **Android 2.3.7 (API 10)** userland next to the camera firmware.
  Apps are ordinary APKs, wrapped in an encrypted `.spk` container by Sony's
  store. [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) reproduces the
  store's USB install protocol, so any APK (debug-signed is fine) can be installed
  with `pmca-console install -f app.apk`. It converts APK ↔ SPK itself.
* Apps must target Android 2.3: Java only, no support libraries, API 10 widgets,
  JAR (v1) signing. There is no touch screen on the A7; input comes from the
  hardware keys, which arrive as `KeyEvent`s whose **scan codes** are defined in
  `com.sony.scalar.sysutil.ScalarInput` (`ISV_KEY_UP`, `ISV_KEY_ENTER`,
  `ISV_KEY_DELETE`, dials, …).
* [OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework)
  provides two jars:
  * **stubs**: Jasmin-assembled signatures of Sony's private `com.sony.scalar.*`
    APIs. `compileOnly`; the real classes come from the camera firmware.
  * **framework**: small wrappers with a fallback implementation for normal
    Android devices: `DeviceInfo`, `DisplayManager` (LCD/EVF, colour depth,
    non-square framebuffer pixels), `MediaManager`/`ImageInfo` (memory card
    photos via `AvindexStore`, EXIF data, thumbnail, the ~1616px embedded
    *screennail* preview and the full JPEG), `DateTime`.
* [PMCADemo](https://github.com/ma1co/PMCADemo) shows the conventions every app
  follows, which CanonDirectPrint reuses:
  * a `BaseActivity` that maps scan codes to callbacks, uses the trash key as
    "back", and broadcasts `DAConnectionManagerService.AppInfoReceive` on resume;
  * `DAConnectionManagerService.apo` broadcasts to disable auto power off during
    long operations;
  * an `ExitCompleted` receiver that kills the process when the camera closes the app;
  * Wi-Fi via the standard `WifiManager`; the camera's own Wi-Fi settings screen
    can be opened with the `com.sony.scalar.app.wifisettings.WifiSettings` intent;
  * a log file on the memory card (there is no logcat without adb).
* [OpenMemories-CI](https://github.com/ma1co/OpenMemories-CI) boots several
  camera firmwares in a patched QEMU to test the boot loader, updater and USB
  shell. It needs decrypted firmware dumps (kept encrypted with a private key)
  and does not start the Android app environment, so it cannot run apps. Our CI
  therefore builds the APKs and runs unit, lint and end-to-end tests against a
  simulated printer instead.

## 2. The Ivy 2 protocol

From [dtgreene/ivy2](https://github.com/dtgreene/ivy2):

* Bluetooth Classic, **RFCOMM channel 1** (SPP). The printer must be paired.
* Commands are fixed **34-byte** packets: start code `0x430F`, `int16 1`,
  `int8 32` (both `-1` for START_SESSION), `uint16` command, a write flag, then
  the payload. Responses echo the command id in bytes 5–6, an error code in
  byte 7, payload from byte 8.
* Commands: `START_SESSION (0)` → battery, MTU; `GET_STATUS (257)` → error code,
  battery, USB, cover open / no paper / wrong Smart Sheet flags;
  `SETTING_ACCESSORY (259)` → auto power off, firmware, photo count (write: set
  auto power off 3/5/10 min); `PRINT_READY (769)` → announces the image length;
  `REBOOT (65535)`.
* Printing: START_SESSION, GET_STATUS (refuse if battery < 30, cover open, no
  paper, wrong sheet), SETTING_ACCESSORY, PRINT_READY(len), then the raw JPEG in
  990-byte chunks (~20 ms apart), then wait for one message (up to 60 s).
* Image: laid out on a 1280×1920 (2:3) canvas (crop-to-fill or fit), resized to
  **640×1616** (non-square printer pixels) and **rotated 180°**, JPEG.
* The battery level is a 6-bit field (0–63); it is not clear whether that is a
  percentage, so the app shows the raw value.

## 3. The hardware problem: the ILCE-7 has no Bluetooth

The original α7 (ILCE-7, 2013) has **Wi-Fi and NFC but no Bluetooth radio**.
(Framework stubs do contain a `didep.Bluetooth` class, but it only reports the
state of the BLE location link on later bodies, API level 15+, and offers no
data channel.) The USB port is device-only, so a USB Bluetooth dongle cannot be
used either.

The camera therefore needs a **Wi-Fi → Bluetooth bridge**. Options considered:

| | Pros | Cons |
|---|---|---|
| Bridge speaks a high-level "print this JPEG" HTTP API | simple camera code | protocol logic duplicated in every bridge; errors harder to surface |
| **Bridge is a transparent TCP ↔ RFCOMM byte pipe** (chosen) | tiny bridges (Pi, Android, ESP32); the protocol, image processing and all error reporting live in one place (the camera app); same code would work over real Bluetooth on another device | must frame the RFCOMM stream on the camera side |

## 4. Architecture (Ivy 2)

```
┌────────── Sony α7 (Android 2.3) ──────────┐        ┌──── bridge ────┐          ┌─────────┐
│ MainActivity  photo list (MediaManager)   │  Wi-Fi │ TCP :9100      │ BT SPP   │ Canon   │
│ PrintActivity preview, options, progress  │ ─────► │   ⇅ byte pipe  │ ───────► │ Ivy 2   │
│ PrintRenderer screennail → 640×1616 JPEG  │  TCP   │ RFCOMM ch. 1   │ RFCOMM   │         │
│ Ivy2Printer   protocol session            │        │ UDP :9101      │          └─────────┘
│ BridgeDiscovery / PrinterConnector        │ ◄───── │  discovery     │
└───────────────────────────────────────────┘  UDP   └────────────────┘
```

Bridges: `bridge/canondirectprint_bridge.py` (Linux/Raspberry Pi), `bridge-android/`
(companion phone app; the phone's hotspot is the camera's network), and
`esp32/` (experimental).

### Finding the bridge (no typing on the camera)

1. `bridge_host` in `CDPRINT/CONFIG.TXT`, if set;
2. otherwise a UDP broadcast `CANONDIRECTPRINT_BRIDGE_DISCOVER` → `CANONDIRECTPRINT_BRIDGE port=9100 name=…`;
3. the last bridge that worked;
4. the Wi-Fi gateway (the bridge itself when it hosts the hotspot).

### Framing

RFCOMM and TCP are streams and the bridge may merge or split packets.
`MessageReader` cuts 34-byte messages and accepts a shorter message once the
line has been quiet for 250 ms. It deliberately does not look for the `0x430F`
start code in replies. A real Ivy 2 (via the Android bridge) answered
START_SESSION with 34 bytes that start differently, and the reference client
never checks the start code of replies either; it only reads the command echo
(bytes 5-6) and the error code (byte 7). The first version resynchronised on
`0x430F`, so it discarded the printer's valid reply and timed out. When the
bridge cannot reach the printer it simply closes the TCP connection; the camera
reports that as "bridge found, but it cannot reach the printer".

### Image pipeline (memory-safe on the camera)

The camera embeds a ~1616×1080 *screennail* in every JPEG, which is already at
printer resolution. It is decoded instead of the 24 MP original (with a
power-of-two sample size computed by `PrintLayout.sampleSize` when only a full
image is available). `PrintLayout` folds EXIF rotation, optional rotate-to-fit
for landscape photos, fill/fit scaling, the 2:3 → 640×1616 squash and the 180°
turn into **one affine transform**, so rendering is a single `Canvas.drawBitmap`
into a 4 MB bitmap followed by JPEG compression.

### Camera UI

* **Photo list** (newest first, thumbnails) plus *Printer status & settings*
  and *Wi-Fi settings*.
* **Print screen**: preview of the sheet; ▲▼ select option, ◀▶/dials change it
  (Layout: Fill/Fit, Rotate to fit, Copies); ENTER or shutter prints; trash
  cancels/goes back. Progress bar during the transfer. Auto power off is
  disabled while printing. Multiple copies are sent one at a time, 60 s apart,
  since the printer has no documented "busy" flag.
* **Printer screen**: battery, paper/cover state, firmware, photo count, and
  auto power off (◀▶).

## 5. Canon SELPHY support (CPNP)

From [selphy_go](https://github.com/tbleher/selphy_go) (`PROTOCOL`,
`send-protocol.txt`, captured from a CP900):

* SELPHY Wi-Fi models speak **CPNP**. Every packet has a 16-byte header:
  `"CPNP"`, a u16 command (bit 15 set in replies), u16 0, a u16 sequence number
  (replies echo it), a u16 job id and a u32 payload length.
* **UDP port 8609**: `DISCOVER 0x101` (broadcast; the reply carries MAC and IP),
  `GET_ID 0x130` (IEEE 1284 id, e.g. `MDL:CP1300`), `GET_STATUS 0x120` (paper
  and ink cassette state: 1 = missing, 4 = ready, plus the model name),
  `FLUSH 0x151`, `START_TCP 0x110` (client/user/job names in UTF-16; the reply
  gives the job id and a TCP port).
* **TCP**: the client polls `GET_STATUS`. Byte 0x12 of the reply is the job
  state: 0 wait, 1 send job flags (`DATA` with 0x40 bytes; 2 = borderless,
  3 = bordered), 2 send data (the printer asks for `length` bytes at `offset`,
  which the client sends as a 0x68-byte chunk header plus the file bytes, in
  `DATA` packets of at most 4 KiB, each acknowledged), 3 done (client sends an
  end-of-job `DATA`), 4 error. Repeated identical replies mean "busy". The
  printer may stop asking before the end of the file.
* The printer decodes a **baseline JPEG** itself and scales it to the paper;
  no raster conversion is needed.

Implementation choices:

* **No bridge.** The camera joins the SELPHY's *Direct Connection* access
  point or a shared network. Printer lookup mirrors the Ivy 2 bridge lookup:
  `selphy_host` config, CPNP broadcast discovery, last printer, then the Wi-Fi
  gateway (the printer itself in Direct mode).
* **Rendering**: `PaperFormat.SELPHY_POSTCARD` is 1808×1232 (the printer's
  native raster with bleed, from the plane header in the notes), landscape,
  upright. The same affine pipeline as the Ivy 2 renders into it. Portrait photos
  are turned sideways when *Rotate to fit* is on. *Fit* sends the printer's
  bordered flag so none of the photo is lost in the borderless bleed. If
  allocating the 9 MB output bitmap fails, the renderer falls back to 16-bit
  colour.
* **Source resolution**: the camera's ~1616×1080 screennail would be scaled up
  about 12% for the 1808×1232 raster. *Quality: High* (the default for the
  SELPHY) renders from the full photo instead. Decoding a 24 MP JPEG whole needs
  96 MB (24 MB even at 1/2), far beyond the camera's heap, so
  `PrintRenderer.renderForPrinterFromFullImage` uses `BitmapRegionDecoder`
  (API 10) to decode 256-row bands at the largest power-of-two subsample that
  still gives printer resolution (1/2 for 6000×4000). It draws each band into the
  output through the same affine transform. `PrintLayout.planStrips` gives
  each band the axis-aligned output rectangle its rows map to. Bands are
  decoded with a few rows of overlap, for bilinear filtering, and clipped to
  their own rectangle, so neighbours meet exactly with no seams, and bands
  cropped away in *Fill* mode are skipped. Peak memory is the output bitmap
  plus one band (about 1.5 MB). The camera gives the app a 24 MB budget
  (Java heap and bitmaps together), which the first field test hit: the 7 MB
  screennail kept for the preview left no room for the 9 MB output bitmap. The
  screennail is therefore released before a full-resolution render and decoded
  again afterwards (or for the fallback). The small on-screen preview is a
  separate bitmap and stays visible. On the α7 with the CP1300, 3.2 MB of the
  24 MB was in use before the render, and rendering a 6000×4000 photo took
  about 9.4 s. The bands are kept at 256 rows on purpose: larger bands would
  be faster but use more of the tight memory budget. Any failure (out of
  memory, no JPEG for RAW-only shots) asks the user before falling back to
  the screennail. The Ivy 2 does
  not offer the option: its 640×1616 raster maps 1:1 onto the screennail's
  long side.
* **Copies**: `SelphyPrinter.print` returns only when the printer reports the
  job done, so copies are simply sent one after another.
* Error details inside the job (for example ribbon exhausted mid-print) are not
  decoded by the reverse engineering yet. The app reports "SELPHY reported an
  error" and relies on the printer's own screen for details.
* `bridge/fake_selphy.py` simulates a SELPHY. `SelphyEndToEndTest` drives the
  real `SelphyPrinter` against it, and `CpnpTest` checks packet encoding
  against the captured traffic.

Not verified: whether the CP910, CP1200 and CP1500 accept CPNP print jobs
(the CP1300 does not, see below), and how SELPHYs handle paper sizes other
than postcard.

### AirPrint/IPP fallback (CP1300 field report)

On a real CP1300 the UDP part of CPNP worked (discovery, GET_ID, status), but
the TCP connection to the port announced by START_TCP was refused. The
CP1300 (and later models) also support AirPrint/IPP, a documented standard,
so the app now has two SELPHY transports, chosen by `SelphyJob`:

* **CPNP hardening**: the raw START_TCP reply is logged; the job connection is
  retried a few times and also tried with the port bytes swapped, since only a
  CP900 capture exists. If no connection can be made, `JobConnectException`
  says so. Nothing has been sent at that point, so trying IPP is safe.
* **IPP** (`ipp` package): a minimal IPP/1.1 encoder/decoder and a
  socket-level HTTP/1.1 client (Content-Length or chunked replies, no
  HttpURLConnection quirks on Android 2.3). Get-Printer-Attributes checks
  that `image/jpeg` is accepted and reads `printer-state-reasons`
  (`media-empty`, `marker-supply-empty`, ...). Print-Job sends the same
  1808×1232 JPEG with `print-scaling` `fill` or `fit` when supported, then
  Get-Job-Attributes is polled until the job completes.
* **AUTO** (default): try the protocol that worked last time, IPP first
  initially. Fall back to the other one only when the first could not start
  a job at all. Printer problems (paper, ink) are reported, not retried.
  `selphy_protocol=cpnp|ipp` forces one.

Confirmed on a CP1300 (device id `MDL:SELPHY CP1300;CMD:Raster3`). The
START_TCP reply was `30303030 c001 0000`: a big-endian ephemeral port
(49152, then 49153 for the next job), exactly as on the CP900. The printer
still refused every connection to it (the first attempt took about 10 s to be
refused), so byte order and timing are not the cause. IPP printed first time:
formats `image/urf, application/octet-stream, image/jpeg, image/pwg-raster`,
job complete after about 67 s. Because of this, AUTO now tries IPP first.
Printers without AirPrint refuse port 631 immediately, so the CP900 loses
almost nothing, and the CP1300 no longer wastes about 12 s and leaves an
abandoned CPNP job on the printer.

`fake_selphy.py` serves IPP too. Its `--refuse-tcp` flag reproduces the CP1300
behaviour, and `SelphyEndToEndTest` covers the fallback, the forced
protocols, and "no paper does not fall back".

## 6. Testing

* JVM unit tests for everything protocol- and geometry-related (Ivy 2 and CPNP) (packets match
  the reference implementation byte for byte, stream framing, print flow and
  error handling against an in-memory fake printer, layout maths, discovery).
* End-to-end tests run the Java clients against `bridge/canondirectprint_bridge.py
  --simulate` and `bridge/fake_selphy.py` and check that the printer receives
  the exact JPEG.
* Python tests for the bridge relay and discovery.
* Android lint with `NewApi` against API 10 (the app is compiled against the
  API 10 platform itself).

Verified on hardware (ILCE-7 with a SELPHY CP1300): installing with
Sony-PMCA-RE, the camera UI and keys, joining the printer's Direct Connection
network, CPNP discovery and status, printing over IPP (with the automatic
fallback from CPNP), full-resolution rendering within the 24 MB budget, and
log timestamps from the camera clock.

Still verified only against simulators: the Ivy 2 path (the printer's
behaviour after the image transfer, multi-copy timing, and the Android,
Raspberry Pi and ESP32 bridges), and CPNP printing on a CP900.
