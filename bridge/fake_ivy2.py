"""A software stand-in for a Canon Ivy 2 printer, for testing without hardware.

It speaks the same protocol as the real printer (see canonprint_bridge.py and
https://github.com/dtgreene/ivy2) over any connected socket, and saves the
JPEGs it receives so the output of the camera app can be inspected.
"""

import logging
import os
import socket
import struct
import time

log = logging.getLogger("fake-ivy2")

MESSAGE_LENGTH = 34
START_CODE = 0x430F
COMMAND_START_SESSION = 0
COMMAND_GET_STATUS = 257
COMMAND_SETTING_ACCESSORY = 259
COMMAND_PRINT_READY = 769
COMMAND_REBOOT = 65535


def response(ack, error=0, payload=b""):
    msg = bytearray(MESSAGE_LENGTH)
    struct.pack_into(">HhbHB", msg, 0, START_CODE, 1, 32, ack, error)
    msg[8:8 + len(payload)] = payload
    return bytes(msg)


class FakePrinter:
    def __init__(self, save_dir=None, battery=50, no_paper=False, cover_open=False):
        self.save_dir = save_dir
        self.battery = battery
        self.no_paper = no_paper
        self.cover_open = cover_open
        self.auto_power_off = 5
        self.photos_printed = 0
        self.last_image = None

    def serve(self, sock):
        """Handles one connection until the peer closes it."""
        buf = b""
        expected = 0
        image = bytearray()
        with sock:
            while True:
                try:
                    data = sock.recv(4096)
                except OSError:
                    return
                if not data:
                    return
                buf += data
                while buf:
                    if expected:
                        n = min(expected, len(buf))
                        image += buf[:n]
                        buf = buf[n:]
                        expected -= n
                        if expected == 0:
                            self._finish_print(bytes(image))
                            image = bytearray()
                            sock.sendall(response(COMMAND_PRINT_READY))
                        continue
                    if len(buf) < MESSAGE_LENGTH:
                        break
                    msg, buf = buf[:MESSAGE_LENGTH], buf[MESSAGE_LENGTH:]
                    reply, expected = self._handle(msg)
                    if reply:
                        sock.sendall(reply)

    def _handle(self, msg):
        start, command, write = struct.unpack_from(">H2xxHB", msg)
        if start != START_CODE:
            log.warning("bad start code %04x", start)
            return None, 0
        log.info("command %d%s", command, " (write)" if write else "")
        if command == COMMAND_START_SESSION:
            reply = bytearray(response(COMMAND_START_SESSION))
            struct.pack_into(">HH", reply, 9, self.battery & 0x3F, 990)
            return bytes(reply), 0
        if command == COMMAND_GET_STATUS:
            flags = (1 if self.cover_open else 0) | (2 if self.no_paper else 0)
            return response(command, payload=struct.pack(">HBBH", self.battery & 0x3F, 0, 0, flags)), 0
        if command == COMMAND_SETTING_ACCESSORY:
            if write:
                self.auto_power_off = msg[8]
            payload = struct.pack(">BBBBBBHB", self.auto_power_off, 1, 0, 0, 0, 1, self.photos_printed, 0)
            return response(command, payload=payload), 0
        if command == COMMAND_PRINT_READY:
            (length,) = struct.unpack_from(">I", msg, 8)
            log.info("expecting %d bytes of image data", length)
            return response(command), length
        return response(command), 0

    def _finish_print(self, image):
        self.photos_printed += 1
        self.last_image = image
        log.info("received image: %d bytes%s", len(image),
                 "" if image[:2] == b"\xff\xd8" else " (NOT a JPEG!)")
        if self.save_dir:
            os.makedirs(self.save_dir, exist_ok=True)
            path = os.path.join(self.save_dir, "print-%s-%d.jpg" % (time.strftime("%Y%m%d-%H%M%S"), self.photos_printed))
            with open(path, "wb") as f:
                f.write(image)
            log.info("saved %s (note: printer images are stored upside down)", path)


def connect_simulated(printer):
    """Returns a socket connected to a FakePrinter running in a background thread."""
    import threading
    ours, theirs = socket.socketpair()
    threading.Thread(target=printer.serve, args=(theirs,), daemon=True).start()
    return ours
