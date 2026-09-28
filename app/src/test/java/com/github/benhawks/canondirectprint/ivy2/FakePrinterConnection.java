package com.github.benhawks.canondirectprint.ivy2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** In-memory Ivy 2 printer simulator for tests. */
class FakePrinterConnection implements Ivy2Connection {
    int battery = 50;
    boolean usb = false;
    boolean coverOpen = false;
    boolean noPaper = false;
    int autoPowerOff = 5;
    int photosPrinted = 42;
    /** Split responses into pieces of this size to exercise the framer. */
    int fragmentSize = Integer.MAX_VALUE;
    /** First two bytes of every reply (a real Ivy 2 does not use 0x430F here). */
    int replyStartCode = 0x430F;
    /** Stop answering (simulates a dead Bluetooth link). */
    boolean silent = false;

    final List<Integer> commands = new ArrayList<Integer>();
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    int expectedData = 0;
    boolean closed = false;

    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();
    private byte[] outgoing = new byte[0];

    static byte[] response(int ack, int error, int... payload) {
        byte[] r = new byte[Ivy2Protocol.MESSAGE_LENGTH];
        r[0] = 0x43;
        r[1] = 0x0F;
        r[3] = 1;
        r[4] = 32;
        r[5] = (byte) (ack >> 8);
        r[6] = (byte) ack;
        r[7] = (byte) error;
        for (int i = 0; i < payload.length; i++)
            r[8 + i] = (byte) payload[i];
        return r;
    }

    private synchronized void send(byte[] data) {
        if (silent)
            return;
        data[0] = (byte) (replyStartCode >> 8);
        data[1] = (byte) replyStartCode;
        byte[] n = new byte[outgoing.length + data.length];
        System.arraycopy(outgoing, 0, n, 0, outgoing.length);
        System.arraycopy(data, 0, n, outgoing.length, data.length);
        outgoing = n;
        notifyAll();
    }

    @Override
    public synchronized int read(byte[] buffer, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (outgoing.length == 0) {
            if (closed)
                return -1;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
                return 0;
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
        int n = Math.min(Math.min(buffer.length, fragmentSize), outgoing.length);
        System.arraycopy(outgoing, 0, buffer, 0, n);
        byte[] rest = new byte[outgoing.length - n];
        System.arraycopy(outgoing, n, rest, 0, rest.length);
        outgoing = rest;
        return n;
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) throws IOException {
        if (closed)
            throw new IOException("closed");
        if (expectedData > 0) {
            int n = Math.min(length, expectedData);
            received.write(data, offset, n);
            expectedData -= n;
            if (expectedData == 0) {
                photosPrinted++;
                send(response(Ivy2Protocol.COMMAND_PRINT_READY, 0));
            }
            return;
        }
        incoming.write(data, offset, length);
        byte[] buf = incoming.toByteArray();
        if (buf.length < Ivy2Protocol.MESSAGE_LENGTH)
            return;
        incoming.reset();
        incoming.write(buf, Ivy2Protocol.MESSAGE_LENGTH, buf.length - Ivy2Protocol.MESSAGE_LENGTH);
        handle(buf);
    }

    private void handle(byte[] m) {
        int command = ((m[5] & 0xFF) << 8) | (m[6] & 0xFF);
        boolean write = m[7] == 1;
        commands.add(command);
        switch (command) {
            case Ivy2Protocol.COMMAND_START_SESSION:
                byte[] r = response(0, 0);
                r[10] = (byte) battery;
                r[11] = (byte) (990 >> 8);
                r[12] = (byte) 990;
                send(r);
                break;
            case Ivy2Protocol.COMMAND_GET_STATUS:
                int flags = (coverOpen ? 1 : 0) | (noPaper ? 2 : 0);
                send(response(command, 0, 0, battery | (usb ? 0x80 : 0), 0, 0, flags >> 8, flags));
                break;
            case Ivy2Protocol.COMMAND_SETTING_ACCESSORY:
                if (write)
                    autoPowerOff = m[8];
                send(response(command, 0, autoPowerOff, 1, 2, 3, 0, 7, photosPrinted >> 8, photosPrinted, 4));
                break;
            case Ivy2Protocol.COMMAND_PRINT_READY:
                expectedData = ((m[8] & 0xFF) << 24) | ((m[9] & 0xFF) << 16) | ((m[10] & 0xFF) << 8) | (m[11] & 0xFF);
                send(response(command, 0));
                break;
            default:
                send(response(command, 0));
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
