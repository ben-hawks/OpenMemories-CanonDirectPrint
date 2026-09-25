package com.github.benhawks.ivy2print.ivy2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Expected bytes were generated with the reference Python implementation (dtgreene/ivy2 task.py). */
public class Ivy2ProtocolTest {
    @Test
    public void startSession() {
        assertEquals("430fffffff0000000000000000000000000000000000000000000000000000000000", Hex.encode(Ivy2Protocol.startSession()));
    }

    @Test
    public void commandsMatchReference() {
        assertEquals("430f0001200101000000000000000000000000000000000000000000000000000000", Hex.encode(Ivy2Protocol.getStatus()));
        assertEquals("430f0001200103000000000000000000000000000000000000000000000000000000", Hex.encode(Ivy2Protocol.getSetting()));
        assertEquals("430f0001200103010500000000000000000000000000000000000000000000000000", Hex.encode(Ivy2Protocol.setAutoPowerOff(5)));
        assertEquals("430f000120ffff010100000000000000000000000000000000000000000000000000", Hex.encode(Ivy2Protocol.reboot()));
        assertEquals("430f0001200301000001e24001010000000000000000000000000000000000000000", Hex.encode(Ivy2Protocol.printReady(123456)));
    }

    @Test
    public void allMessagesAre34Bytes() {
        assertEquals(34, Ivy2Protocol.startSession().length);
        assertEquals(34, Ivy2Protocol.printReady(1).length);
    }

    @Test
    public void parsesStatus() {
        Ivy2Protocol.Response r = new Ivy2Protocol.Response(
                FakePrinterConnection.response(Ivy2Protocol.COMMAND_GET_STATUS, 0, 0, 0x80 | 55, 3, 0, 0, 2 | 16));
        Ivy2Protocol.Status s = new Ivy2Protocol.Status(r);
        assertEquals(Ivy2Protocol.COMMAND_GET_STATUS, r.getAck());
        assertEquals(55, s.batteryLevel);
        assertEquals(3, s.errorCode);
        assertTrue(s.usbConnected);
        assertFalse(s.coverOpen);
        assertTrue(s.noPaper);
        assertTrue(s.wrongSmartSheet);
    }

    @Test
    public void parsesSettings() {
        Ivy2Protocol.Settings s = new Ivy2Protocol.Settings(new Ivy2Protocol.Response(
                FakePrinterConnection.response(Ivy2Protocol.COMMAND_SETTING_ACCESSORY, 0, 10, 1, 2, 3, 0, 7, 1, 2, 4)));
        assertEquals(10, s.autoPowerOff);
        assertEquals("1.2.3", s.firmwareVersion);
        assertEquals(7, s.tmdVersion);
        assertEquals(258, s.photosPrinted);
        assertEquals(4, s.colorId);
    }

    @Test
    public void parsesSessionInfo() {
        byte[] r = FakePrinterConnection.response(0, 0);
        r[9] = (byte) 0xC0; // upper bits are not part of the battery level
        r[10] = 77;
        r[11] = 0x03;
        r[12] = (byte) 0xDE;
        Ivy2Protocol.SessionInfo info = new Ivy2Protocol.SessionInfo(new Ivy2Protocol.Response(r));
        assertEquals(77 & 0x3F, info.batteryLevel);
        assertEquals(990, info.mtu);
    }

    @Test
    public void shortResponsesReadAsZero() {
        Ivy2Protocol.Response r = new Ivy2Protocol.Response(new byte[] { 0x43, 0x0F, 0, 1, 32, 1, 1, 0 });
        assertEquals(0, r.payload(5));
    }
}
