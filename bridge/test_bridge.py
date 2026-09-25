"""Tests for the bridge, using the simulated printer. Run: python3 -m unittest -v test_bridge"""

import socket
import struct
import tempfile
import threading
import unittest

import fake_printer
import ivy2_bridge


def command(cmd, start_session=False, payload=b""):
    msg = bytearray(fake_printer.MESSAGE_LENGTH)
    if start_session:
        struct.pack_into(">HhbHB", msg, 0, 0x430F, -1, -1, cmd, 0)
    else:
        struct.pack_into(">HhbHB", msg, 0, 0x430F, 1, 32, cmd, 0)
    msg[8:8 + len(payload)] = payload
    return bytes(msg)


def recv_message(sock):
    data = b""
    while len(data) < fake_printer.MESSAGE_LENGTH:
        chunk = sock.recv(fake_printer.MESSAGE_LENGTH - len(data))
        if not chunk:
            raise EOFError
        data += chunk
    return data


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.args = ivy2_bridge.parse_args(
            ["--simulate", "--host", "127.0.0.1", "--port", "0", "--discovery-port", "0",
             "--name", "test bridge", "--save-dir", self.tmp.name])
        self.stop = threading.Event()
        ready = threading.Event()
        self.thread = threading.Thread(target=ivy2_bridge.serve, args=(self.args, ready, self.stop), daemon=True)
        self.thread.start()
        self.assertTrue(ready.wait(5))

    def tearDown(self):
        self.stop.set()
        self.thread.join(5)
        self.tmp.cleanup()

    def connect(self):
        s = socket.create_connection(("127.0.0.1", self.args.port), timeout=5)
        self.addCleanup(s.close)
        return s

    def test_session_and_status(self):
        s = self.connect()
        s.sendall(command(fake_printer.COMMAND_START_SESSION, start_session=True))
        reply = recv_message(s)
        self.assertEqual(struct.unpack_from(">H", reply, 5)[0], fake_printer.COMMAND_START_SESSION)
        s.sendall(command(fake_printer.COMMAND_GET_STATUS))
        reply = recv_message(s)
        self.assertEqual(struct.unpack_from(">H", reply, 5)[0], fake_printer.COMMAND_GET_STATUS)
        self.assertEqual(reply[9] & 0x3F, 50)

    def test_print_transfers_image(self):
        image = b"\xff\xd8" + bytes(range(256)) * 40 + b"\xff\xd9"
        s = self.connect()
        s.sendall(command(fake_printer.COMMAND_PRINT_READY, payload=struct.pack(">IBB", len(image), 1, 1)))
        recv_message(s)
        for i in range(0, len(image), 990):
            s.sendall(image[i:i + 990])
        reply = recv_message(s)
        self.assertEqual(struct.unpack_from(">H", reply, 5)[0], fake_printer.COMMAND_PRINT_READY)
        self.assertEqual(self.args.fake_printer.last_image, image)

    def test_sequential_clients(self):
        for _ in range(2):
            s = self.connect()
            s.sendall(command(fake_printer.COMMAND_GET_STATUS))
            recv_message(s)
            s.close()


class DiscoveryTest(unittest.TestCase):
    def test_responds_to_discovery(self):
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()

        args = ivy2_bridge.parse_args(["--simulate", "--host", "127.0.0.1", "--port", "9123",
                                       "--discovery-port", str(port), "--name", "my bridge"])
        stop = threading.Event()
        t = threading.Thread(target=ivy2_bridge.discovery_responder, args=(args, stop), daemon=True)
        t.start()
        try:
            client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            client.settimeout(1)
            with client:
                for _ in range(5):
                    client.sendto(ivy2_bridge.DISCOVERY_REQUEST, ("127.0.0.1", port))
                    try:
                        data, _ = client.recvfrom(512)
                        break
                    except socket.timeout:
                        continue
            self.assertEqual(data, b"IVY2BRIDGE port=9123 name=my bridge")
        finally:
            stop.set()
            t.join(2)


if __name__ == "__main__":
    unittest.main()
