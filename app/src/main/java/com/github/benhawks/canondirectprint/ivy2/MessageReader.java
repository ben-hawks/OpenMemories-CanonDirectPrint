package com.github.benhawks.canondirectprint.ivy2;

import com.github.benhawks.canondirectprint.util.PrintLog;

import java.io.IOException;

/**
 * Splits the byte stream coming from the printer into messages.
 *
 * RFCOMM is a stream, and the bridge may split or merge packets, so we cannot
 * rely on one read() returning exactly one message. Messages are
 * {@link Ivy2Protocol#MESSAGE_LENGTH} bytes long; shorter messages are
 * accepted once the line has been quiet for a moment.
 *
 * Replies are not checked for the 0x430F start code used in requests: a real
 * Ivy 2 answered START_SESSION with 34 bytes that start differently, and the
 * reference client (dtgreene/ivy2 utils.py) never checks it either.
 */
public class MessageReader {
    /** Once a header has been seen, this much silence ends a short message. */
    static final int QUIET_MS = 250;

    private final Ivy2Connection connection;
    private byte[] buffer = new byte[256];
    private int length = 0;

    public MessageReader(Ivy2Connection connection) {
        this.connection = connection;
    }

    public Ivy2Protocol.Response read(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] chunk = new byte[256];
        while (true) {
            if (length >= Ivy2Protocol.MESSAGE_LENGTH)
                return take(Ivy2Protocol.MESSAGE_LENGTH);

            boolean haveHeader = length >= Ivy2Protocol.HEADER_LENGTH;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                if (haveHeader)
                    return take(length);
                throw new Ivy2Exception(Ivy2Exception.Reason.TIMEOUT);
            }

            int wait = (int) (haveHeader ? Math.min(QUIET_MS, remaining) : remaining);
            int n = connection.read(chunk, wait);
            if (n < 0) {
                if (haveHeader)
                    return take(length);
                throw new Ivy2Exception(Ivy2Exception.Reason.DISCONNECTED);
            } else if (n == 0) {
                if (haveHeader)
                    return take(length);
            } else {
                append(chunk, n);
            }
        }
    }

    private void append(byte[] data, int n) {
        if (length + n > buffer.length) {
            byte[] bigger = new byte[Math.max(buffer.length * 2, length + n)];
            System.arraycopy(buffer, 0, bigger, 0, length);
            buffer = bigger;
        }
        System.arraycopy(data, 0, buffer, length, n);
        length += n;
    }

    private Ivy2Protocol.Response take(int n) {
        byte[] message = new byte[n];
        System.arraycopy(buffer, 0, message, 0, n);
        if ((message[0] & 0xFF) != 0x43 || (message[1] & 0xFF) != 0x0F)
            PrintLog.log(String.format(java.util.Locale.US, "Printer reply starts with %02x %02x (requests use 43 0f)",
                    message[0] & 0xFF, message[1] & 0xFF));
        System.arraycopy(buffer, n, buffer, 0, length - n);
        length -= n;
        return new Ivy2Protocol.Response(message);
    }
}
