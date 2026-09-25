package com.github.benhawks.ivy2print.ivy2;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Finds a Wi-Fi/Bluetooth bridge on the local network via UDP broadcast.
 *
 * Request:  "IVY2BRIDGE_DISCOVER" to UDP port 9101
 * Response: "IVY2BRIDGE port=9100 name=&lt;name&gt;"
 *
 * Implemented by bridge/ivy2_bridge.py, the Android companion app and the ESP32 sketch.
 */
public final class BridgeDiscovery {
    public static final int DISCOVERY_PORT = 9101;
    public static final String REQUEST = "IVY2BRIDGE_DISCOVER";
    public static final String RESPONSE_PREFIX = "IVY2BRIDGE";

    private BridgeDiscovery() {}

    public static final class Bridge {
        public final String host;
        public final int port;
        public final String name;

        public Bridge(String host, int port, String name) {
            this.host = host;
            this.port = port;
            this.name = name;
        }

        @Override
        public String toString() { return name + " (" + host + ":" + port + ")"; }
    }

    /** Parses a discovery response, or returns null if it is not one. */
    public static Bridge parseResponse(String host, String message) {
        String[] parts = message.trim().split(" ");
        if (parts.length == 0 || !RESPONSE_PREFIX.equals(parts[0]))
            return null;
        int port = TcpConnection.DEFAULT_PORT;
        String name = host;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].startsWith("port=")) {
                try {
                    port = Integer.parseInt(parts[i].substring(5));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (parts[i].startsWith("name=")) {
                name = parts[i].substring(5);
                for (int j = i + 1; j < parts.length; j++)
                    name += " " + parts[j];
                break;
            }
        }
        return new Bridge(host, port, name);
    }

    /**
     * Broadcasts a discovery request to each address and returns the first bridge that answers.
     *
     * @param targets broadcast (or unicast) addresses to ask
     * @return the bridge, or null if none answered within the timeout
     */
    public static Bridge discover(InetAddress[] targets, int timeoutMs) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setBroadcast(true);
            byte[] request = REQUEST.getBytes("US-ASCII");
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            byte[] buffer = new byte[512];
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= deadline)
                    return null;
                if (now >= nextSend) {
                    for (InetAddress target : targets) {
                        try {
                            socket.send(new DatagramPacket(request, request.length, target, DISCOVERY_PORT));
                        } catch (IOException e) {
                            Ivy2Log.log("Discovery send to " + target + " failed: " + e);
                        }
                    }
                    nextSend = now + 500;
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(deadline, nextSend) - now));
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), "US-ASCII");
                Bridge bridge = parseResponse(packet.getAddress().getHostAddress(), message);
                if (bridge != null)
                    return bridge;
            }
        } finally {
            socket.close();
        }
    }
}
