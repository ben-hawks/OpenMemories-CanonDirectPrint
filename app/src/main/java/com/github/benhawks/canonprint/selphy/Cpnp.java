package com.github.benhawks.canonprint.selphy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canon CPNP, the network protocol of the SELPHY CP900 and later Wi-Fi models.
 *
 * Every packet has a 16-byte big-endian header:
 * <pre>
 *   0..3   "CPNP"
 *   4..5   command (bit 15 set in responses)
 *   6..7   0
 *   8..9   sequence number (responses echo it)
 *   10..11 job id (assigned by START_TCP, 0 on UDP)
 *   12..15 payload length
 * </pre>
 * Control messages go over UDP port 8609; START_TCP then gives a TCP port on
 * which the printer pulls the JPEG in chunks. Several payloads (job flags,
 * chunk headers, status) use little-endian fields.
 *
 * Written from the protocol notes in https://github.com/tbleher/selphy_go
 * (PROTOCOL, send-protocol.txt), originally by Wilmer van der Gaast.
 */
public final class Cpnp {
    private Cpnp() {}

    public static final int PORT = 8609;
    public static final int HEADER_LENGTH = 16;
    public static final int RESPONSE_FLAG = 0x8000;

    public static final int CMD_DISCOVER = 0x101;
    public static final int CMD_START_TCP = 0x110;
    public static final int CMD_STATUS = 0x120;
    public static final int CMD_DATA = 0x121;
    public static final int CMD_GET_ID = 0x130;
    public static final int CMD_FLUSH = 0x151;

    /** Job states reported by GET_STATUS over TCP (payload byte 0x12). */
    public static final int STATE_WAIT = 0;
    public static final int STATE_SEND_FLAGS = 1;
    public static final int STATE_SEND_DATA = 2;
    public static final int STATE_DONE = 3;
    public static final int STATE_ERROR = 4;

    /** DATA packets carry at most this much payload. */
    public static final int MAX_DATA = 4096;
    /** Length of the header in front of every requested file chunk. */
    public static final int CHUNK_HEADER_LENGTH = 0x68;

    private static final byte[] MAGIC = { 'C', 'P', 'N', 'P' };

    public static final class Packet {
        public final int command;
        public final int sequence;
        public final int jobId;
        public final byte[] payload;

        public Packet(int command, int sequence, int jobId, byte[] payload) {
            this.command = command;
            this.sequence = sequence;
            this.jobId = jobId;
            this.payload = payload != null ? payload : new byte[0];
        }

        public byte[] encode() {
            byte[] b = new byte[HEADER_LENGTH + payload.length];
            System.arraycopy(MAGIC, 0, b, 0, 4);
            putU16BE(b, 4, command);
            putU16BE(b, 8, sequence);
            putU16BE(b, 10, jobId);
            putU32BE(b, 12, payload.length);
            System.arraycopy(payload, 0, b, HEADER_LENGTH, payload.length);
            return b;
        }

        /** Parses a complete packet (e.g. one UDP datagram); null if it is not CPNP. */
        public static Packet decode(byte[] data, int offset, int length) {
            if (length < HEADER_LENGTH)
                return null;
            for (int i = 0; i < 4; i++)
                if (data[offset + i] != MAGIC[i])
                    return null;
            long payloadLength = u32BE(data, offset + 12);
            if (payloadLength > length - HEADER_LENGTH)
                payloadLength = length - HEADER_LENGTH;
            byte[] payload = new byte[(int) payloadLength];
            System.arraycopy(data, offset + HEADER_LENGTH, payload, 0, payload.length);
            return new Packet(u16BE(data, offset + 4), u16BE(data, offset + 8), u16BE(data, offset + 10), payload);
        }

        /** Reads one packet from a TCP stream. */
        public static Packet read(InputStream in) throws IOException {
            byte[] header = readFully(in, HEADER_LENGTH);
            for (int i = 0; i < 4; i++)
                if (header[i] != MAGIC[i])
                    throw new IOException("Not a CPNP packet");
            long length = u32BE(header, 12);
            if (length > 1024 * 1024)
                throw new IOException("CPNP packet too long: " + length);
            byte[] payload = readFully(in, (int) length);
            return new Packet(u16BE(header, 4), u16BE(header, 8), u16BE(header, 10), payload);
        }

        public int u8(int offset) {
            return offset < payload.length ? payload[offset] & 0xFF : 0;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "CPNP(cmd=0x%04x seq=%d job=%d len=%d)", command, sequence, jobId, payload.length);
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(b, read, n - read);
            if (r < 0)
                throw new EOFException("Connection closed by printer");
            read += r;
        }
        return b;
    }

    // ---- payload builders -------------------------------------------------

    /** START_TCP payload: client name, user name and job name as UTF-16BE strings. */
    public static byte[] startTcpPayload(String clientName, String userName, String jobName) {
        byte[] b = new byte[0x188];
        putUtf16(b, 0x008, 0x048, clientName);
        putUtf16(b, 0x048, 0x088, userName);
        putUtf16(b, 0x088, 0x188, jobName);
        return b;
    }

    /** Job flags, sent when the printer is in {@link #STATE_SEND_FLAGS}. */
    public static byte[] flagsPayload(boolean bordered) {
        byte[] b = new byte[0x40];
        putU32LE(b, 0x04, b.length);
        putU32LE(b, 0x0c, 1);
        putU32LE(b, 0x12, bordered ? 3 : 2);
        return b;
    }

    /** Sent when the printer reports {@link #STATE_DONE}. */
    public static byte[] endJobPayload() {
        byte[] b = new byte[0x40];
        b[2] = 3;
        putU32LE(b, 0x04, b.length);
        return b;
    }

    /**
     * A requested file chunk: 0x68-byte header followed by {@code length}
     * bytes of the JPEG from {@code offset} (zero padded past the end).
     */
    public static byte[] chunk(byte[] file, int width, int height, int offset, int length) {
        byte[] b = new byte[CHUNK_HEADER_LENGTH + length];
        b[0x02] = 1;
        putU32LE(b, 0x04, b.length);
        b[0x0c] = 1;
        putU32LE(b, 0x14, file.length);
        putU32LE(b, 0x18, width);
        putU32LE(b, 0x1c, height);
        putU32LE(b, 0x60, offset);
        putU32LE(b, 0x64, length);
        if (offset < file.length)
            System.arraycopy(file, offset, b, CHUNK_HEADER_LENGTH, Math.min(length, file.length - offset));
        return b;
    }

    // ---- response parsers -------------------------------------------------

    /** DISCOVER response: returns {mac, ip} as strings, or null. */
    public static String[] parseDiscover(Packet p) {
        if (p.payload.length < 6)
            return null;
        int macLength = p.u8(4);
        int ipLength = p.u8(5);
        if (p.payload.length < 6 + macLength + ipLength)
            return null;
        StringBuilder mac = new StringBuilder();
        for (int i = 0; i < macLength; i++)
            mac.append(i > 0 ? ":" : "").append(String.format(java.util.Locale.US, "%02x", p.u8(6 + i)));
        StringBuilder ip = new StringBuilder();
        for (int i = 0; i < ipLength; i++)
            ip.append(i > 0 ? "." : "").append(p.u8(6 + macLength + i));
        return new String[] { mac.toString(), ip.toString() };
    }

    /** GET_ID response: IEEE 1284 device id ("MFG:Canon;MDL:CP900;DES:...;") as a map. */
    public static Map<String, String> parseDeviceId(Packet p) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (p.payload.length < 2)
            return result;
        int length = Math.min((p.u8(0) << 8) | p.u8(1), p.payload.length - 2);
        String id;
        try {
            id = new String(p.payload, 2, length, "US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        for (String part : id.split(";")) {
            int colon = part.indexOf(':');
            if (colon > 0)
                result.put(part.substring(0, colon).trim(), part.substring(colon + 1).trim());
        }
        return result;
    }

    /** GET_STATUS over UDP: paper and ink cassette state. */
    public static final class DeviceStatus {
        public static final int CASSETTE_MISSING = 1;
        public static final int CASSETTE_READY = 4;

        public final int paper;
        public final int ink;
        public final String model;

        public DeviceStatus(Packet p) {
            paper = p.u8(2);
            ink = p.u8(3);
            StringBuilder sb = new StringBuilder();
            for (int i = 4; i < p.payload.length && p.payload[i] != 0; i++)
                sb.append((char) (p.payload[i] & 0x7F));
            model = sb.toString();
        }

        public boolean paperMissing() { return paper == CASSETTE_MISSING; }
        public boolean inkMissing() { return ink == CASSETTE_MISSING; }

        @Override
        public String toString() { return "model=" + model + " paper=" + paper + " ink=" + ink; }
    }

    /** GET_STATUS over TCP during a job. */
    public static final class JobStatus {
        public final int state;
        public final int dataOffset;
        public final int dataLength;
        public final byte[] raw;

        public JobStatus(Packet p) {
            raw = p.payload;
            state = p.u8(0x12);
            dataOffset = (int) u32LE(p.payload, 0x18);
            dataLength = (int) u32LE(p.payload, 0x1c);
        }
    }

    // ---- byte helpers -------------------------------------------------------

    static void putU16BE(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }

    static void putU32BE(byte[] b, int o, long v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    static void putU32LE(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    static int u16BE(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    static long u32BE(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    static long u32LE(byte[] b, int o) {
        if (o + 4 > b.length)
            return 0;
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((long) (b[o + 3] & 0xFF) << 24);
    }

    static void putUtf16(byte[] b, int from, int to, String s) {
        int o = from;
        for (int i = 0; i < s.length() && o + 2 <= to - 2; i++, o += 2)
            putU16BE(b, o, s.charAt(i));
    }
}
