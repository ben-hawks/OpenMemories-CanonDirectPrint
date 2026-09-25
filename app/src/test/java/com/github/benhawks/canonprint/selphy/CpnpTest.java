package com.github.benhawks.canonprint.selphy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import org.junit.Test;

/** Expected bytes come from the traffic captures in selphy_go (send-protocol.txt / PROTOCOL). */
public class CpnpTest {
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b)
            sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        s = s.replace(":", "").replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void encodesStatusRequestLikeCapture() {
        // > 43:50:4e:50:01:20:00:00:00:05:00:01:00:00:00:00
        assertEquals("43504e50012000000005000100000000", hex(new Cpnp.Packet(Cpnp.CMD_STATUS, 5, 1, null).encode()));
    }

    @Test
    public void flagsPayloadMatchesCapture() {
        byte[] borderless = Cpnp.flagsPayload(false);
        assertEquals(0x40, borderless.length);
        assertTrue(hex(borderless).startsWith("00000000400000000000000001000000000002"));
        assertEquals(3, Cpnp.flagsPayload(true)[0x12]);
    }

    @Test
    public void chunkHeaderMatchesCapture() {
        // > ...:00:00:01:00:69:00:00:00:00:00:00:00:01:00:00:00:00:00:00:00:96:7a:1f:... (file size 0x1f7a96)
        byte[] file = new byte[0x1f7a96];
        byte[] c = Cpnp.chunk(file, 4000, 3000, 0, 1);
        assertEquals("0000010069000000000000000100000000000000967a1f00", hex(c).substring(0, 48));
        assertEquals(0x68 + 1, c.length);
    }

    @Test
    public void chunkCopiesDataAndPadsPastEnd() {
        byte[] file = { 1, 2, 3, 4, 5 };
        byte[] c = Cpnp.chunk(file, 1, 1, 3, 4);
        assertEquals(4, c[0x68]);
        assertEquals(5, c[0x69]);
        assertEquals(0, c[0x6a]);
        assertEquals(3, c[0x60]);
        assertEquals(4, c[0x64]);
    }

    @Test
    public void parsesDiscoverResponse() {
        Cpnp.Packet p = Cpnp.Packet.decode(unhex(
                "43504e50 8101 0000 0006 0000 00000010 00010800 06 04 180caca4b817 c0a801b5"), 0, 32);
        String[] r = Cpnp.parseDiscover(p);
        assertEquals("18:0c:ac:a4:b8:17", r[0]);
        assertEquals("192.168.1.181", r[1]);
    }

    @Test
    public void parsesTcpStatus() throws IOException {
        // Data request for 0x18f98 bytes at offset 0 (from the capture).
        byte[] status = new byte[64];
        System.arraycopy(unhex("0000011004040000 01ff000000000000 0000020000000000 00000000988f0100"), 0, status, 0, 32);
        byte[] packet = new Cpnp.Packet(Cpnp.CMD_STATUS | Cpnp.RESPONSE_FLAG, 9, 1, status).encode();
        Cpnp.Packet p = Cpnp.Packet.read(new ByteArrayInputStream(packet));
        assertEquals(9, p.sequence);
        assertEquals(1, p.jobId);
        Cpnp.JobStatus s = new Cpnp.JobStatus(p);
        assertEquals(Cpnp.STATE_SEND_DATA, s.state);
        assertEquals(0, s.dataOffset);
        assertEquals(0x18f98, s.dataLength);
    }

    @Test
    public void parsesDeviceStatusAndId() {
        byte[] payload = new byte[512];
        System.arraycopy(unhex("0101 0104 43616e6f6e20435039303000"), 0, payload, 0, 16);
        Cpnp.DeviceStatus s = new Cpnp.DeviceStatus(new Cpnp.Packet(0x8120, 1, 0, payload));
        assertEquals("Canon CP900", s.model);
        assertTrue(s.paperMissing());
        assertEquals(false, s.inkMissing());

        String id = "MFG:Canon;MDL:CP900;DES:Canon SELPHY CP900;";
        byte[] idPayload = new byte[2 + id.length()];
        idPayload[1] = (byte) id.length();
        System.arraycopy(id.getBytes(), 0, idPayload, 2, id.length());
        Map<String, String> m = Cpnp.parseDeviceId(new Cpnp.Packet(0x8130, 1, 0, idPayload));
        assertEquals("CP900", m.get("MDL"));
        assertEquals("Canon SELPHY CP900", m.get("DES"));
    }

    @Test
    public void rejectsNonCpnp() {
        assertNull(Cpnp.Packet.decode(new byte[20], 0, 20));
    }

    @Test
    public void startTcpPayloadIsUtf16() {
        byte[] b = Cpnp.startTcpPayload("A", "bc", "Job");
        assertEquals(0x188, b.length);
        assertEquals('A', b[0x009]);
        assertEquals('b', b[0x049]);
        assertEquals('J', b[0x089]);
    }
}
