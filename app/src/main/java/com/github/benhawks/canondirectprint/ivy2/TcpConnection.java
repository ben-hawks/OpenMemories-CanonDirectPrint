package com.github.benhawks.canondirectprint.ivy2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Connection to a transparent TCP-to-RFCOMM bridge. */
public class TcpConnection implements Ivy2Connection {
    public static final int DEFAULT_PORT = 9100;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public TcpConnection(String host, int port, int connectTimeoutMs) throws IOException {
        socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws IOException {
        socket.setSoTimeout(Math.max(1, timeoutMs));
        try {
            return in.read(buffer);
        } catch (SocketTimeoutException e) {
            return 0;
        }
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        out.write(data, offset, length);
        out.flush();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
