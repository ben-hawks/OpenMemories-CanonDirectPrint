package com.github.benhawks.canondirectprint.ivy2;

import java.io.IOException;

/** A byte stream to the printer (e.g. TCP to a Wi-Fi/Bluetooth bridge). */
public interface Ivy2Connection {
    /**
     * Reads up to {@code buffer.length} bytes, waiting at most {@code timeoutMs}.
     *
     * @return the number of bytes read, 0 if the timeout expired, -1 at end of stream
     */
    int read(byte[] buffer, int timeoutMs) throws IOException;

    void write(byte[] data, int offset, int length) throws IOException;

    void close();
}
