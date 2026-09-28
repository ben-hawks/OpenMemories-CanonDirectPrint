#!/usr/bin/env python3
"""A software stand-in for a Canon SELPHY Wi-Fi photo printer (CPNP protocol).

Run it on a computer on the same network as the camera to try printing
without a printer. Received JPEGs are saved to --save-dir.

Protocol notes: https://github.com/tbleher/selphy_go (PROTOCOL).
"""

import argparse
import http.server
import logging
import os
import socket
import struct
import threading
import time

log = logging.getLogger("fake-selphy")

CPNP_PORT = 8609
RESPONSE = 0x8000
CMD_DISCOVER = 0x101
CMD_START_TCP = 0x110
CMD_STATUS = 0x120
CMD_DATA = 0x121
CMD_GET_ID = 0x130
CMD_FLUSH = 0x151

STATE_WAIT, STATE_FLAGS, STATE_DATA, STATE_DONE, STATE_ERROR = range(5)
CHUNK_HEADER = 0x68
CHUNK_SIZE = 102296  # what a CP900 asked for in the captured traffic


def packet(command, seq, job, payload=b""):
    return b"CPNP" + struct.pack(">HHHHI", command, 0, seq, job, len(payload)) + payload


def parse_header(data):
    if data[:4] != b"CPNP":
        raise ValueError("not CPNP")
    command, _, seq, job, length = struct.unpack_from(">HHHHI", data, 4)
    return command, seq, job, length


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


class FakeSelphy:
    def __init__(self, host="0.0.0.0", port=CPNP_PORT, save_dir=None, model="CP1300",
                 no_paper=False, no_ink=False, fail_job=False, refuse_tcp=False, ipp_port=None):
        self.host = host
        self.port = port
        self.save_dir = save_dir
        self.model = model
        self.no_paper = no_paper
        self.no_ink = no_ink
        self.fail_job = fail_job
        self.refuse_tcp = refuse_tcp
        self.ipp_jobs = {}  # job id -> number of status polls so far
        self.ipp_server = None
        if ipp_port is not None:
            self.ipp_server = IppServer((host, ipp_port), self)
            threading.Thread(target=self.ipp_server.serve_forever, daemon=True).start()
            log.info("simulated AirPrint/IPP on %s:%d/ipp/print", host, self.ipp_server.server_address[1])
        self.jobs = []  # (jpeg bytes, width, height, bordered)
        self.job_counter = 0
        self.stop = threading.Event()
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.udp.bind((host, port))
        self.udp.settimeout(0.5)
        self.port = self.udp.getsockname()[1]

    def serve_forever(self):
        log.info("simulated SELPHY %s on UDP %s:%d", self.model, self.host, self.port)
        with self.udp:
            while not self.stop.is_set():
                try:
                    data, addr = self.udp.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:
                    return
                try:
                    command, seq, job, _ = parse_header(data)
                except (ValueError, struct.error):
                    continue
                reply = self.handle_udp(command, seq, addr)
                if reply:
                    self.udp.sendto(reply, addr)

    def handle_udp(self, command, seq, addr):
        log.info("UDP command 0x%04x from %s", command, addr[0])
        if command == CMD_DISCOVER:
            ip = socket.inet_aton(addr[0] if self.host in ("0.0.0.0", "") else self.host)
            payload = b"\x00\x01\x08\x00" + bytes([6, 4]) + bytes.fromhex("180cacaaaaaa") + ip
            return packet(CMD_DISCOVER | RESPONSE, seq, 0, payload)
        if command == CMD_GET_ID:
            ident = ("MFG:Canon;CMD:CPNP;MDL:%s;CLS:IMG;DES:Canon SELPHY %s (simulated);" % (self.model, self.model)).encode()
            return packet(CMD_GET_ID | RESPONSE, seq, 0, struct.pack(">H", len(ident)) + ident)
        if command == CMD_STATUS:
            payload = bytearray(512)
            payload[0:2] = b"\x01\x01"
            payload[2] = 1 if self.no_paper else 4
            payload[3] = 1 if self.no_ink else 4
            name = ("Canon %s" % self.model).encode()
            payload[4:4 + len(name)] = name
            return packet(CMD_STATUS | RESPONSE, seq, 0, bytes(payload))
        if command == CMD_FLUSH:
            return packet(CMD_FLUSH | RESPONSE, seq, 0, b"\x00\x00\x10\x00")
        if command == CMD_START_TCP:
            self.job_counter += 1
            if self.refuse_tcp:
                # Announce a port nobody listens on, like the CP1300 in the field.
                probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                probe.bind((self.host, 0))
                port = probe.getsockname()[1]
                probe.close()
                log.info("job %d: announcing TCP port %d but refusing connections", self.job_counter, port)
                return packet(CMD_START_TCP | RESPONSE, seq, self.job_counter, b"0000" + struct.pack(">HH", port, 0))
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.bind((self.host, 0))
            server.listen(1)
            port = server.getsockname()[1]
            threading.Thread(target=self.run_job, args=(server, self.job_counter), daemon=True).start()
            return packet(CMD_START_TCP | RESPONSE, seq, self.job_counter, b"0000" + struct.pack(">HH", port, 0))
        return packet(command | RESPONSE, seq, 0, b"")

    def run_job(self, server, job_id):
        server.settimeout(10)
        try:
            conn, _ = server.accept()
        except socket.timeout:
            log.warning("job %d: client never connected", job_id)
            return
        finally:
            server.close()
        with conn:
            conn.settimeout(30)
            try:
                self.job_loop(conn, job_id)
            except (EOFError, OSError) as e:
                log.warning("job %d aborted: %s", job_id, e)

    def job_loop(self, conn, job_id):
        state = STATE_FLAGS
        counter = 0
        offset = 0
        fsize = None
        width = height = 0
        bordered = None
        received = bytearray()
        pending = b""  # current chunk being assembled
        pending_len = 0
        finishing = 0
        while True:
            head = recv_exact(conn, 16)
            command, seq, job, length = parse_header(head)
            payload = recv_exact(conn, length)
            if command == CMD_STATUS:
                counter += 1
                status = bytearray(64)
                status[0:6] = b"\x00\x00\x01\x10\x04\x04"
                status[8] = counter & 0xFF  # changes every poll, like the real printer
                status[9] = 0xFF
                status[0x12] = state
                if state == STATE_DATA:
                    struct.pack_into("<II", status, 0x18, offset, min(CHUNK_SIZE, fsize - offset) if fsize else CHUNK_SIZE)
                conn.sendall(packet(CMD_STATUS | RESPONSE, seq, job_id, bytes(status)))
                if state == STATE_WAIT and finishing:
                    finishing -= 1
                    if finishing == 0:
                        state = STATE_ERROR if self.fail_job else STATE_DONE
            elif command == CMD_DATA:
                conn.sendall(packet(CMD_DATA | RESPONSE, seq, job_id, struct.pack(">I", length)))
                if state == STATE_FLAGS:
                    (flag,) = struct.unpack_from("<I", payload, 0x12)
                    bordered = flag == 3
                    log.info("job %d: flags, %s", job_id, "bordered" if bordered else "borderless")
                    state = STATE_DATA
                elif state == STATE_DATA:
                    if not pending:
                        if payload[2] != 1:
                            raise ValueError("expected chunk header")
                        total, = struct.unpack_from("<I", payload, 4)
                        fsize, width, height = struct.unpack_from("<III", payload, 0x14)
                        pending_len = total
                    pending += payload
                    if len(pending) >= pending_len:
                        chunk_offset, chunk_length = struct.unpack_from("<II", pending, 0x60)
                        data = pending[CHUNK_HEADER:CHUNK_HEADER + chunk_length]
                        received[chunk_offset:chunk_offset + len(data)] = data
                        offset = chunk_offset + chunk_length
                        pending = b""
                        if offset >= fsize:
                            state = STATE_WAIT
                            finishing = 3  # "printing" for a few polls
                elif state == STATE_DONE:
                    jpeg = bytes(received[:fsize])
                    self.jobs.append((jpeg, width, height, bordered))
                    self.save(jpeg, job_id)
                    log.info("job %d done: %d bytes, %dx%d", job_id, len(jpeg), width, height)
                    return
            else:
                conn.sendall(packet(command | RESPONSE, seq, job_id, b""))

    def save(self, jpeg, job_id):
        if not self.save_dir:
            return
        os.makedirs(self.save_dir, exist_ok=True)
        path = os.path.join(self.save_dir, "selphy-%s-%d.jpg" % (time.strftime("%Y%m%d-%H%M%S"), job_id))
        with open(path, "wb") as f:
            f.write(jpeg)
        log.info("saved %s", path)


# ---- minimal IPP server ---------------------------------------------------

IPP_OK = 0x0000
IPP_NOT_FOUND = 0x0406
IPP_BAD_REQUEST = 0x0400
TAG_OPERATION, TAG_JOB, TAG_END, TAG_PRINTER = 0x01, 0x02, 0x03, 0x04
V_INTEGER, V_BOOLEAN, V_ENUM = 0x21, 0x22, 0x23
V_TEXT, V_NAME, V_KEYWORD, V_URI, V_CHARSET, V_LANGUAGE, V_MIME = 0x41, 0x42, 0x44, 0x45, 0x47, 0x48, 0x49


def ipp_decode(data):
    """Returns (operation, request_id, {name: [values]}, document bytes)."""
    _, operation, request_id = struct.unpack_from(">HHI", data, 0)
    pos = 8
    attrs = {}
    name = None
    while pos < len(data):
        tag = data[pos]
        pos += 1
        if tag == TAG_END:
            break
        if tag < 0x10:
            continue
        (nlen,) = struct.unpack_from(">H", data, pos)
        pos += 2
        n = data[pos:pos + nlen].decode()
        pos += nlen
        (vlen,) = struct.unpack_from(">H", data, pos)
        pos += 2
        raw = data[pos:pos + vlen]
        pos += vlen
        if n:
            name = n
        if tag in (V_INTEGER, V_ENUM):
            value = struct.unpack(">i", raw)[0]
        elif tag == V_BOOLEAN:
            value = raw != b"\x00"
        else:
            value = raw.decode("utf-8", "replace")
        attrs.setdefault(name, []).append(value)
    return operation, request_id, attrs, data[pos:]


def ipp_attr(tag, name, *values):
    out = b""
    for i, v in enumerate(values):
        n = name.encode() if i == 0 else b""
        raw = struct.pack(">i", v) if tag in (V_INTEGER, V_ENUM) else v.encode()
        out += bytes([tag]) + struct.pack(">H", len(n)) + n + struct.pack(">H", len(raw)) + raw
    return out


def ipp_response(status, request_id, groups):
    out = struct.pack(">BBHI", 1, 1, status, request_id)
    out += bytes([TAG_OPERATION]) + ipp_attr(V_CHARSET, "attributes-charset", "utf-8") \
        + ipp_attr(V_LANGUAGE, "attributes-natural-language", "en")
    for tag, body in groups:
        out += bytes([tag]) + body
    return out + bytes([TAG_END])


class IppServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, printer):
        self.printer = printer
        super().__init__(address, IppHandler)


class IppHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log.debug("IPP HTTP: " + fmt, *args)

    def do_POST(self):
        if self.path not in ("/ipp/print", "/ipp/print/"):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(length)
        body = self.server.printer.handle_ipp(data)
        # Answer chunked, as many real printers do.
        self.send_response(200)
        self.send_header("Content-Type", "application/ipp")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for i in range(0, len(body), 100):
            chunk = body[i:i + 100]
            self.wfile.write(b"%x\r\n" % len(chunk) + chunk + b"\r\n")
        self.wfile.write(b"0\r\n\r\n")


def _handle_ipp(self, data):
    operation, request_id, attrs, document = ipp_decode(data)
    log.info("IPP operation 0x%04x %s", operation, sorted(attrs))
    if operation == 0x000B:  # Get-Printer-Attributes
        reasons = "media-empty-error" if self.no_paper else ("marker-supply-empty-error" if self.no_ink else "none")
        body = ipp_attr(V_TEXT, "printer-make-and-model", "Canon SELPHY %s (simulated)" % self.model) \
            + ipp_attr(V_ENUM, "printer-state", 3) \
            + ipp_attr(V_KEYWORD, "printer-state-reasons", reasons) \
            + ipp_attr(V_MIME, "document-format-supported", "image/jpeg", "image/urf", "application/octet-stream") \
            + ipp_attr(V_KEYWORD, "print-scaling-supported", "auto", "fill", "fit")
        return ipp_response(IPP_OK, request_id, [(TAG_PRINTER, body)])
    if operation == 0x0002:  # Print-Job
        if attrs.get("document-format", [""])[0] != "image/jpeg":
            return ipp_response(IPP_BAD_REQUEST, request_id, [])
        self.job_counter += 1
        job_id = self.job_counter
        scaling = attrs.get("print-scaling", ["?"])[0]
        self.jobs.append((document, 0, 0, scaling == "fit"))
        self.ipp_jobs[job_id] = 0
        self.save(document, job_id)
        log.info("IPP job %d: %d bytes, print-scaling=%s", job_id, len(document), scaling)
        body = ipp_attr(V_INTEGER, "job-id", job_id) + ipp_attr(V_ENUM, "job-state", 3)
        return ipp_response(IPP_OK, request_id, [(TAG_JOB, body)])
    if operation == 0x0009:  # Get-Job-Attributes
        job_id = attrs.get("job-id", [0])[0]
        if job_id not in self.ipp_jobs:
            return ipp_response(IPP_NOT_FOUND, request_id, [])
        self.ipp_jobs[job_id] += 1
        state = 5 if self.ipp_jobs[job_id] < 2 else 9
        body = ipp_attr(V_INTEGER, "job-id", job_id) + ipp_attr(V_ENUM, "job-state", state) \
            + ipp_attr(V_KEYWORD, "job-state-reasons", "job-completed-successfully" if state == 9 else "job-printing")
        return ipp_response(IPP_OK, request_id, [(TAG_JOB, body)])
    return ipp_response(0x0501, request_id, [])  # server-error-operation-not-supported


FakeSelphy.handle_ipp = _handle_ipp


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=CPNP_PORT)
    p.add_argument("--save-dir", default="received")
    p.add_argument("--model", default="CP1300")
    p.add_argument("--no-paper", action="store_true")
    p.add_argument("--no-ink", action="store_true")
    p.add_argument("--refuse-tcp", action="store_true",
                   help="announce a CPNP job port but refuse the connection (as seen on a CP1300)")
    p.add_argument("--ipp-port", type=int, default=631, help="AirPrint/IPP port (default 631)")
    p.add_argument("--no-ipp", action="store_true", help="do not offer AirPrint/IPP")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")
    printer = FakeSelphy(args.host, args.port, args.save_dir, args.model, args.no_paper, args.no_ink,
                         refuse_tcp=args.refuse_tcp, ipp_port=None if args.no_ipp else args.ipp_port)
    try:
        printer.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
