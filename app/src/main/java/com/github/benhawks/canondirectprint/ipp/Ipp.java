package com.github.benhawks.canondirectprint.ipp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal IPP/1.1 message encoding (RFC 8010), just enough for
 * Get-Printer-Attributes, Print-Job and Get-Job-Attributes.
 */
public final class Ipp {
    private Ipp() {}

    public static final int DEFAULT_PORT = 631;

    // Operations
    public static final int PRINT_JOB = 0x0002;
    public static final int GET_JOB_ATTRIBUTES = 0x0009;
    public static final int GET_PRINTER_ATTRIBUTES = 0x000B;

    // Delimiter tags
    public static final int TAG_OPERATION = 0x01;
    public static final int TAG_JOB = 0x02;
    public static final int TAG_END = 0x03;
    public static final int TAG_PRINTER = 0x04;

    // Value tags
    public static final int INTEGER = 0x21;
    public static final int BOOLEAN = 0x22;
    public static final int ENUM = 0x23;
    public static final int TEXT = 0x41;
    public static final int NAME = 0x42;
    public static final int KEYWORD = 0x44;
    public static final int URI = 0x45;
    public static final int CHARSET = 0x47;
    public static final int LANGUAGE = 0x48;
    public static final int MIME_TYPE = 0x49;

    // job-state values
    public static final int JOB_PENDING = 3;
    public static final int JOB_HELD = 4;
    public static final int JOB_PROCESSING = 5;
    public static final int JOB_STOPPED = 6;
    public static final int JOB_CANCELED = 7;
    public static final int JOB_ABORTED = 8;
    public static final int JOB_COMPLETED = 9;

    /** Builds a request. Call {@link #group} before adding attributes to it. */
    public static final class Request {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Request(int operation, int requestId) {
            out.write(1); // IPP 1.1: the most widely supported version
            out.write(1);
            writeShort(operation);
            writeInt(requestId);
        }

        public Request group(int tag) {
            out.write(tag);
            return this;
        }

        public Request string(int valueTag, String name, String... values) {
            for (int i = 0; i < values.length; i++)
                attribute(valueTag, i == 0 ? name : "", utf8(values[i]));
            return this;
        }

        public Request integer(int valueTag, String name, int value) {
            byte[] v = { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
            return attribute(valueTag, name, v);
        }

        public Request bool(String name, boolean value) {
            return attribute(BOOLEAN, name, new byte[] { (byte) (value ? 1 : 0) });
        }

        private Request attribute(int valueTag, String name, byte[] value) {
            byte[] n = utf8(name);
            out.write(valueTag);
            writeShort(n.length);
            out.write(n, 0, n.length);
            writeShort(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        /** Standard operation attributes every request starts with. */
        public Request operationAttributes(String printerUri) {
            return group(TAG_OPERATION)
                    .string(CHARSET, "attributes-charset", "utf-8")
                    .string(LANGUAGE, "attributes-natural-language", "en")
                    .string(URI, "printer-uri", printerUri);
        }

        public byte[] encode() {
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            byte[] body = out.toByteArray();
            copy.write(body, 0, body.length);
            copy.write(TAG_END);
            return copy.toByteArray();
        }

        private void writeShort(int v) {
            out.write(v >>> 8);
            out.write(v);
        }

        private void writeInt(int v) {
            writeShort(v >>> 16);
            writeShort(v & 0xFFFF);
        }
    }

    /** A decoded response. Attributes of all groups are merged by name. */
    public static final class Response {
        public final int version;
        public final int status;
        public final int requestId;
        public final Map<String, List<Object>> attributes;

        Response(int version, int status, int requestId, Map<String, List<Object>> attributes) {
            this.version = version;
            this.status = status;
            this.requestId = requestId;
            this.attributes = attributes;
        }

        /** successful-ok and its variants (0x0000..0x00FF). */
        public boolean isSuccess() {
            return status < 0x0100;
        }

        public List<Object> values(String name) {
            List<Object> v = attributes.get(name);
            return v != null ? v : new ArrayList<Object>();
        }

        public String string(String name) {
            for (Object o : values(name))
                if (o instanceof String)
                    return (String) o;
            return null;
        }

        public int integer(String name, int defaultValue) {
            for (Object o : values(name))
                if (o instanceof Integer)
                    return (Integer) o;
            return defaultValue;
        }

        public List<String> strings(String name) {
            List<String> result = new ArrayList<String>();
            for (Object o : values(name))
                if (o instanceof String)
                    result.add((String) o);
            return result;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "IPP response status=0x%04x %s", status, attributes.keySet());
        }
    }

    public static Response decode(byte[] b) throws IOException {
        Reader r = new Reader(b);
        int version = r.u16();
        int status = r.u16();
        int requestId = r.s32();
        Map<String, List<Object>> attributes = new LinkedHashMap<String, List<Object>>();
        String current = null;
        while (r.remaining() > 0) {
            int tag = r.u8();
            if (tag == TAG_END)
                break;
            if (tag < 0x10) // another group delimiter
                continue;
            if (tag == 0x7F) // extended tag: 4-byte type follows the name
                throw new IOException("Unsupported extended IPP tag");
            String name = r.string(r.u16());
            byte[] value = r.bytes(r.u16());
            if (name.length() > 0)
                current = name;
            if (current == null)
                throw new IOException("IPP value without attribute name");
            List<Object> list = attributes.get(current);
            if (list == null) {
                list = new ArrayList<Object>();
                attributes.put(current, list);
            }
            list.add(decodeValue(tag, value));
        }
        return new Response(version, status, requestId, attributes);
    }

    private static Object decodeValue(int tag, byte[] v) throws UnsupportedEncodingException {
        switch (tag) {
            case INTEGER:
            case ENUM:
                if (v.length == 4)
                    return ((v[0] & 0xFF) << 24) | ((v[1] & 0xFF) << 16) | ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
                return v;
            case BOOLEAN:
                return v.length > 0 && v[0] != 0;
            default:
                if ((tag >= 0x40 && tag <= 0x49) || tag == 0x4A)
                    return new String(v, "UTF-8");
                return v; // dateTime, resolution, ranges, collections, out-of-band...
        }
    }

    static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class Reader {
        private final byte[] b;
        private int pos;

        Reader(byte[] b) { this.b = b; }

        int remaining() { return b.length - pos; }

        int u8() throws IOException {
            need(1);
            return b[pos++] & 0xFF;
        }

        int u16() throws IOException {
            return (u8() << 8) | u8();
        }

        int s32() throws IOException {
            return (u16() << 16) | u16();
        }

        byte[] bytes(int n) throws IOException {
            need(n);
            byte[] r = new byte[n];
            System.arraycopy(b, pos, r, 0, n);
            pos += n;
            return r;
        }

        String string(int n) throws IOException {
            return new String(bytes(n), "UTF-8");
        }

        private void need(int n) throws IOException {
            if (pos + n > b.length)
                throw new IOException("Truncated IPP response");
        }
    }
}
