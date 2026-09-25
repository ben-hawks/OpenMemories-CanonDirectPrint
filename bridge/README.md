# Linux / Raspberry Pi bridge

`ivy2_bridge.py` relays TCP connections from the camera to the printer's
Bluetooth RFCOMM channel. Python 3.7+ standard library only, Linux only (it uses
the kernel's `AF_BLUETOOTH` sockets, so PyBluez is not needed).

## Setup (Raspberry Pi OS)

1. **Pair the printer** (once). Turn the printer on, then:

   ```sh
   sudo hciconfig hci0 sspmode 0   # see note below
   bluetoothctl
   [bluetooth]# agent on
   [bluetooth]# default-agent
   [bluetooth]# scan on             # wait for "Canon (xx:xx) Mini Printer"
   [bluetooth]# pair 04:7F:0E:XX:XX:XX
   [bluetooth]# trust 04:7F:0E:XX:XX:XX
   [bluetooth]# exit
   ```

   The [ivy2](https://github.com/dtgreene/ivy2) project found that the Pi needs
   Secure Simple Pairing disabled (`sspmode 0`) or connecting fails with
   *"Invalid exchange"*; that setting does not survive a reboot.

2. **Give the camera a network.** Either put the Pi and the camera on the same
   Wi-Fi, or let the Pi host a hotspot (Raspberry Pi OS Bookworm):

   ```sh
   sudo nmcli device wifi hotspot ssid IVY2-BRIDGE password ivy2print
   ```

   With a hotspot the Pi is the network's gateway, which the camera also tries
   automatically.

3. **Run the bridge:**

   ```sh
   python3 ivy2_bridge.py --printer 04:7F:0E:XX:XX:XX
   ```

   To start it at boot, edit and install `ivy2-bridge.service` (instructions inside).

## Testing without a printer

`--simulate` replaces the printer with a software stand-in (`fake_printer.py`)
that speaks the same protocol and saves every image it receives to
`received/`. Note that printer images are upside down (the printer expects them
rotated by 180 degrees).

```sh
python3 ivy2_bridge.py --simulate
python3 -m unittest -v test_bridge
```

## Options

| Option | Default | |
|---|---|---|
| `--printer` | | Printer Bluetooth address |
| `--channel` | 1 | RFCOMM channel |
| `--port` | 9100 | TCP port for the camera |
| `--discovery-port` | 9101 | UDP discovery port (0 = off) |
| `--name` | hostname | Name announced to the camera |
| `--idle-timeout` | 180 | Seconds before an idle connection is dropped |
| `--simulate` | | Use the simulated printer |
