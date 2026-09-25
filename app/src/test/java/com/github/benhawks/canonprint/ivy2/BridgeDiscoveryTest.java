package com.github.benhawks.canonprint.ivy2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.junit.Test;

public class BridgeDiscoveryTest {
    @Test
    public void parsesResponse() {
        BridgeDiscovery.Bridge b = BridgeDiscovery.parseResponse("10.0.0.1", "CANONPRINT_BRIDGE port=9200 name=Pixel 8 bridge\n");
        assertNotNull(b);
        assertEquals("10.0.0.1", b.host);
        assertEquals(9200, b.port);
        assertEquals("Pixel 8 bridge", b.name);
    }

    @Test
    public void defaultsPortAndName() {
        BridgeDiscovery.Bridge b = BridgeDiscovery.parseResponse("10.0.0.1", "CANONPRINT_BRIDGE");
        assertEquals(TcpConnection.DEFAULT_PORT, b.port);
        assertEquals("10.0.0.1", b.host);
    }

    @Test
    public void ignoresOtherMessages() {
        assertNull(BridgeDiscovery.parseResponse("10.0.0.1", "HELLO"));
        assertNull(BridgeDiscovery.parseResponse("10.0.0.1", "CANONPRINT_BRIDGE port=abc"));
    }

    @Test
    public void discoversOverUdp() throws Exception {
        final DatagramSocket responder = new DatagramSocket(BridgeDiscovery.DISCOVERY_PORT, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[64];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    responder.receive(p);
                    if (new String(buf, 0, p.getLength(), "US-ASCII").equals(BridgeDiscovery.REQUEST)) {
                        byte[] reply = "CANONPRINT_BRIDGE port=9100 name=test".getBytes("US-ASCII");
                        responder.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                    }
                } catch (Exception e) {
                    // test fails by timeout
                }
            }
        });
        t.start();
        try {
            BridgeDiscovery.Bridge b = BridgeDiscovery.discover(new InetAddress[] { InetAddress.getByName("127.0.0.1") }, 3000);
            assertNotNull(b);
            assertEquals("127.0.0.1", b.host);
            assertEquals("test", b.name);
        } finally {
            responder.close();
            t.join();
        }
    }
}
