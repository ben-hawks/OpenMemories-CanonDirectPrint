package com.github.benhawks.ivy2print.ivy2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class MessageReaderTest {
    /** Returns scripted chunks; null in the script means "timeout", end of script means EOF. */
    private static class ScriptedConnection implements Ivy2Connection {
        final List<byte[]> script = new ArrayList<byte[]>();

        ScriptedConnection add(byte[] b) { script.add(b); return this; }

        @Override
        public int read(byte[] buffer, int timeoutMs) {
            if (script.isEmpty())
                return -1;
            byte[] chunk = script.remove(0);
            if (chunk == null)
                return 0;
            System.arraycopy(chunk, 0, buffer, 0, chunk.length);
            return chunk.length;
        }

        @Override public void write(byte[] data, int offset, int length) {}
        @Override public void close() {}
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test
    public void reassemblesFragments() throws IOException {
        byte[] m = FakePrinterConnection.response(257, 0, 1, 2, 3);
        ScriptedConnection c = new ScriptedConnection().add(slice(m, 0, 3)).add(slice(m, 3, 20)).add(slice(m, 20, 34));
        Ivy2Protocol.Response r = new MessageReader(c).read(1000);
        assertEquals(257, r.getAck());
        assertEquals(34, r.data.length);
    }

    @Test
    public void splitsMergedMessages() throws IOException {
        byte[] a = FakePrinterConnection.response(257, 0);
        byte[] b = FakePrinterConnection.response(259, 0);
        MessageReader reader = new MessageReader(new ScriptedConnection().add(concat(a, b)));
        assertEquals(257, reader.read(1000).getAck());
        assertEquals(259, reader.read(1000).getAck());
    }

    @Test
    public void skipsGarbage() throws IOException {
        byte[] m = FakePrinterConnection.response(769, 0);
        MessageReader reader = new MessageReader(new ScriptedConnection().add(concat(new byte[] { 1, 0x43, 2 }, m)));
        assertEquals(769, reader.read(1000).getAck());
    }

    @Test
    public void acceptsShortMessageAfterSilence() throws IOException {
        byte[] m = slice(FakePrinterConnection.response(769, 5), 0, 12);
        MessageReader reader = new MessageReader(new ScriptedConnection().add(m).add(null));
        Ivy2Protocol.Response r = reader.read(1000);
        assertEquals(769, r.getAck());
        assertEquals(5, r.getError());
        assertEquals(12, r.data.length);
    }

    @Test
    public void reportsDisconnect() throws IOException {
        try {
            new MessageReader(new ScriptedConnection()).read(1000);
            fail();
        } catch (Ivy2Exception e) {
            assertEquals(Ivy2Exception.Reason.DISCONNECTED, e.reason);
        }
    }

    @Test
    public void reportsTimeout() throws IOException {
        FakePrinterConnection silent = new FakePrinterConnection();
        try {
            new MessageReader(silent).read(100);
            fail();
        } catch (Ivy2Exception e) {
            assertEquals(Ivy2Exception.Reason.TIMEOUT, e.reason);
        }
    }
}
