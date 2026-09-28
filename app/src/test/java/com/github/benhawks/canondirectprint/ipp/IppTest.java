package com.github.benhawks.canondirectprint.ipp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

public class IppTest {
    @Test
    public void encodesRequestHeaderAndAttributes() {
        byte[] b = new Ipp.Request(Ipp.GET_PRINTER_ATTRIBUTES, 7)
                .operationAttributes("ipp://p/ipp/print")
                .string(Ipp.KEYWORD, "requested-attributes", "a", "b")
                .encode();
        // version 1.1, operation 0x000B, request id 7, operation group
        assertArrayEquals(new byte[] { 1, 1, 0, 0x0B, 0, 0, 0, 7, 0x01 }, Arrays.copyOf(b, 9));
        // first attribute: charset "attributes-charset" = "utf-8"
        assertEquals(Ipp.CHARSET, b[9]);
        assertEquals("attributes-charset".length(), b[11]);
        assertEquals(Ipp.TAG_END, b[b.length - 1]);
        // the second keyword value has an empty name
        String s = new String(b);
        assertTrue(s.contains("requested-attributes"));
        int second = b.length - 1 - 1 - 2 - 2 - 1; // tag, name-len 0, value-len, "b"
        assertEquals(Ipp.KEYWORD, b[second]);
        assertEquals(0, b[second + 1]);
        assertEquals(0, b[second + 2]);
    }

    private static void attr(ByteArrayOutputStream out, int tag, String name, byte[] value) {
        out.write(tag);
        out.write(name.length() >> 8);
        out.write(name.length());
        out.write(name.getBytes(), 0, name.length());
        out.write(value.length >> 8);
        out.write(value.length);
        out.write(value, 0, value.length);
    }

    @Test
    public void decodesResponse() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] { 1, 1, 0, 0, 0, 0, 0, 3, 0x01 }, 0, 9);
        attr(out, Ipp.CHARSET, "attributes-charset", "utf-8".getBytes());
        out.write(0x04);
        attr(out, Ipp.ENUM, "printer-state", new byte[] { 0, 0, 0, 3 });
        attr(out, Ipp.MIME_TYPE, "document-format-supported", "image/jpeg".getBytes());
        attr(out, Ipp.MIME_TYPE, "", "image/urf".getBytes());
        attr(out, Ipp.BOOLEAN, "color-supported", new byte[] { 1 });
        attr(out, 0x31, "printer-current-time", new byte[11]); // dateTime: kept as bytes
        out.write(0x03);
        Ipp.Response r = Ipp.decode(out.toByteArray());
        assertTrue(r.isSuccess());
        assertEquals(3, r.requestId);
        assertEquals(3, r.integer("printer-state", -1));
        assertEquals(Arrays.asList("image/jpeg", "image/urf"), r.strings("document-format-supported"));
        assertEquals(Boolean.TRUE, r.values("color-supported").get(0));
    }

    @Test
    public void rejectsTruncatedResponse() {
        try {
            Ipp.decode(new byte[] { 1, 1, 0, 0, 0, 0, 0, 1, 0x04, 0x21, 0, 5, 'a' });
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Truncated"));
        }
    }

    @Test
    public void readsChunkedHttpResponse() throws IOException {
        String http = "HTTP/1.1 100 Continue\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3\r\nabc\r\n2;ext=1\r\nde\r\n0\r\n\r\n";
        assertEquals("abcde", new String(HttpPost.readResponse(new ByteArrayInputStream(http.getBytes()))));
    }

    @Test
    public void readsContentLengthHttpResponse() throws IOException {
        String http = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nwxyzEXTRA";
        assertEquals("wxyz", new String(HttpPost.readResponse(new ByteArrayInputStream(http.getBytes()))));
    }

    @Test
    public void reportsHttpErrors() {
        try {
            HttpPost.readResponse(new ByteArrayInputStream("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes()));
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("404"));
        }
    }

    @Test
    public void mapsPrinterStateReasons() {
        try {
            IppPrinter.checkStateReasons(Arrays.asList("none", "media-empty-error"));
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("paper"));
        }
    }
}
