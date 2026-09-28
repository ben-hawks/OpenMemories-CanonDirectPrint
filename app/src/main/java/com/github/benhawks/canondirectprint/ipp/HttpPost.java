package com.github.benhawks.canondirectprint.ipp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Tiny HTTP/1.1 POST client for IPP. Written on raw sockets because
 * HttpURLConnection on Android 2.3 has quirks, and we need upload progress.
 */
final class HttpPost {
    interface Progress {
        void onProgress(int sent, int total);
    }

    private HttpPost() {}

    static byte[] post(String host, int port, String path, String contentType, byte[] head, byte[] body,
                       int timeoutMs, Progress progress, Socket[] active) throws IOException {
        int length = head.length + (body != null ? body.length : 0);
        Socket socket = new Socket();
        if (active != null)
            active[0] = socket;
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            String request = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(request.getBytes("US-ASCII"));
            out.write(head);
            if (body != null) {
                for (int pos = 0; pos < body.length; pos += 16384) {
                    int n = Math.min(16384, body.length - pos);
                    out.write(body, pos, n);
                    if (progress != null)
                        progress.onProgress(pos + n, body.length);
                }
            }
            out.flush();
            return readResponse(socket.getInputStream());
        } finally {
            if (active != null)
                active[0] = null;
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    static byte[] readResponse(InputStream in) throws IOException {
        while (true) {
            String status = readLine(in);
            if (status == null)
                throw new IOException("Printer closed the connection");
            String[] parts = status.split(" ", 3);
            int code = parts.length >= 2 ? parseInt(parts[1]) : -1;
            long contentLength = -1;
            boolean chunked = false;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon < 0)
                    continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                if (key.equals("content-length"))
                    contentLength = parseInt(value);
                else if (key.equals("transfer-encoding") && value.toLowerCase(Locale.US).contains("chunked"))
                    chunked = true;
            }
            if (code == 100)
                continue; // interim response, the real one follows
            byte[] body = chunked ? readChunked(in) : contentLength >= 0 ? readFully(in, (int) contentLength) : readAll(in);
            if (code != 200)
                throw new IOException("Printer answered HTTP " + code);
            return body;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r')
                    sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192)
                throw new IOException("HTTP header line too long");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            if (line == null)
                throw new IOException("Truncated chunked response");
            int semicolon = line.indexOf(';');
            int size = Integer.parseInt((semicolon >= 0 ? line.substring(0, semicolon) : line).trim(), 16);
            if (size == 0) {
                while ((line = readLine(in)) != null && line.length() > 0) {
                    // trailers
                }
                return out.toByteArray();
            }
            byte[] chunk = readFully(in, size);
            out.write(chunk, 0, chunk.length);
            readLine(in); // CRLF after the chunk
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(b, read, n - read);
            if (r < 0)
                throw new IOException("Truncated HTTP response");
            read += r;
        }
        return b;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return out.toByteArray();
    }
}
