#!/usr/bin/env python3
"""Wi-Fi (TCP) to Bluetooth (RFCOMM) bridge for the Canon Ivy 2 printer.

The Sony camera app connects to TCP port 9100; every connection is relayed
byte for byte to the printer's RFCOMM channel. The bridge does not interpret
the printer protocol, so it stays tiny and the same camera app works with any
bridge implementation (this script, the Android companion app, an ESP32).

It also answers UDP discovery broadcasts on port 9101 so the camera can find
it without typing an IP address:

    request:  CANONPRINT_BRIDGE_DISCOVER
    response: CANONPRINT_BRIDGE port=9100 name=<name>

Linux only (uses the kernel's RFCOMM sockets; no PyBluez needed).
Pair the printer first, see README.md.
"""

import argparse
import logging
import selectors
import socket
import threading

from fake_ivy2 import FakePrinter, connect_simulated

log = logging.getLogger("canonprint-bridge")

DISCOVERY_REQUEST = b"CANONPRINT_BRIDGE_DISCOVER"
# The printer's reference client writes at most 990 bytes at a time.
RFCOMM_CHUNK = 990


def open_printer(args):
    if args.simulate:
        log.info("using simulated printer")
        return connect_simulated(args.fake_ivy2)
    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    sock.settimeout(args.bt_timeout)
    try:
        sock.connect((args.printer, args.channel))
    except OSError:
        sock.close()
        raise
    sock.settimeout(None)
    return sock


def relay(client, printer, idle_timeout):
    """Copies bytes in both directions until either side closes or goes idle."""
    sel = selectors.DefaultSelector()
    sel.register(client, selectors.EVENT_READ, printer)
    sel.register(printer, selectors.EVENT_READ, client)
    to_printer = to_client = 0
    try:
        while True:
            events = sel.select(idle_timeout)
            if not events:
                log.info("idle for %ss, closing", idle_timeout)
                return
            for key, _ in events:
                src, dst = key.fileobj, key.data
                data = src.recv(65536)
                if not data:
                    return
                if dst is printer:
                    for i in range(0, len(data), RFCOMM_CHUNK):
                        dst.sendall(data[i:i + RFCOMM_CHUNK])
                    to_printer += len(data)
                else:
                    dst.sendall(data)
                    to_client += len(data)
    except OSError as e:
        log.warning("connection error: %s", e)
    finally:
        sel.close()
        log.info("relayed %d bytes to printer, %d bytes to camera", to_printer, to_client)


def handle_client(client, addr, args):
    log.info("camera connected from %s", addr[0])
    with client:
        client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            printer = open_printer(args)
        except OSError as e:
            # Closing the TCP connection without data tells the camera the printer is unreachable.
            log.error("cannot connect to printer %s: %s", args.printer, e)
            return
        with printer:
            log.info("connected to printer")
            relay(client, printer, args.idle_timeout)
    log.info("camera disconnected")


def discovery_responder(args, stop):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.host, args.discovery_port))
    sock.settimeout(0.5)
    reply = ("CANONPRINT_BRIDGE port=%d name=%s" % (args.port, args.name)).encode("ascii", "replace")
    log.info("answering discovery on UDP %d", args.discovery_port)
    with sock:
        while not stop.is_set():
            try:
                data, addr = sock.recvfrom(512)
            except socket.timeout:
                continue
            except OSError:
                return
            if data.strip() == DISCOVERY_REQUEST:
                log.info("discovery request from %s", addr[0])
                sock.sendto(reply, addr)


def serve(args, ready=None, stop=None):
    stop = stop or threading.Event()
    if args.discovery_port:
        threading.Thread(target=discovery_responder, args=(args, stop), daemon=True).start()
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(4)
    server.settimeout(0.5)
    args.port = server.getsockname()[1]
    log.info("listening on TCP %s:%d, printer %s", args.host, args.port,
             "SIMULATED" if args.simulate else "%s channel %d" % (args.printer, args.channel))
    if ready:
        ready.set()
    with server:
        while not stop.is_set():
            try:
                client, addr = server.accept()
            except socket.timeout:
                continue
            client.settimeout(None)
            # The printer accepts one connection at a time, so serve clients sequentially.
            handle_client(client, addr, args)


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--printer", help="printer Bluetooth address, e.g. 04:7F:0E:B7:46:0B")
    p.add_argument("--channel", type=int, default=1, help="RFCOMM channel (default 1)")
    p.add_argument("--host", default="0.0.0.0", help="address to listen on (default all)")
    p.add_argument("--port", type=int, default=9100, help="TCP port (default 9100)")
    p.add_argument("--discovery-port", type=int, default=9101, help="UDP discovery port, 0 to disable (default 9101)")
    p.add_argument("--name", default=socket.gethostname(), help="name announced to the camera")
    p.add_argument("--bt-timeout", type=float, default=15, help="Bluetooth connect timeout in seconds")
    p.add_argument("--idle-timeout", type=float, default=180, help="close idle connections after this many seconds")
    p.add_argument("--simulate", action="store_true", help="use a simulated printer instead of Bluetooth")
    p.add_argument("--save-dir", default="received", help="with --simulate: where to save received images")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)
    if not args.simulate and not args.printer:
        p.error("--printer is required (or use --simulate)")
    args.fake_ivy2 = FakePrinter(save_dir=args.save_dir) if args.simulate else None
    return args


def main():
    args = parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(name)s: %(message)s")
    try:
        serve(args)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
