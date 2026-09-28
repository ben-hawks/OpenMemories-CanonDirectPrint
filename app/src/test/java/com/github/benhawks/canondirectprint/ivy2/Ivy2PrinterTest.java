package com.github.benhawks.canondirectprint.ivy2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public class Ivy2PrinterTest {
    private FakePrinterConnection fake;
    private Ivy2Printer printer;

    @Before
    public void setUp() {
        fake = new FakePrinterConnection();
        fake.fragmentSize = 7;
        printer = new Ivy2Printer(fake);
        printer.setChunkDelayMs(0);
        printer.setResponseTimeoutMs(1000);
        printer.setTransferTimeoutMs(1000);
    }

    @Test
    public void startsSession() throws IOException {
        Ivy2Protocol.SessionInfo info = printer.startSession();
        assertEquals(50, info.batteryLevel);
        assertEquals(990, info.mtu);
    }

    @Test
    public void printsImage() throws IOException {
        byte[] jpeg = new byte[5000];
        for (int i = 0; i < jpeg.length; i++)
            jpeg[i] = (byte) i;
        final AtomicInteger progress = new AtomicInteger();
        printer.startSession();
        printer.print(jpeg, new Ivy2Printer.ProgressListener() {
            @Override
            public void onTransferProgress(int sent, int total) {
                assertEquals(5000, total);
                progress.set(sent);
            }
        });
        assertEquals(5000, progress.get());
        assertArrayEquals(jpeg, fake.received.toByteArray());
        assertEquals(Arrays.asList(Ivy2Protocol.COMMAND_START_SESSION, Ivy2Protocol.COMMAND_GET_STATUS,
                Ivy2Protocol.COMMAND_SETTING_ACCESSORY, Ivy2Protocol.COMMAND_PRINT_READY), fake.commands);
        assertEquals(43, fake.photosPrinted);
    }

    @Test
    public void printsWhenRepliesUseAnotherStartCode() throws IOException {
        fake.replyStartCode = 0x5100;
        byte[] jpeg = new byte[3000];
        assertEquals(50, printer.startSession().batteryLevel);
        printer.print(jpeg, null);
        assertEquals(3000, fake.received.size());
    }

    private void assertPrintFails(Ivy2Exception.Reason reason) throws IOException {
        try {
            printer.print(new byte[100], null);
            fail();
        } catch (Ivy2Exception e) {
            assertEquals(reason, e.reason);
        }
        assertEquals(0, fake.received.size());
    }

    @Test
    public void refusesWithoutPaper() throws IOException {
        fake.noPaper = true;
        assertPrintFails(Ivy2Exception.Reason.NO_PAPER);
    }

    @Test
    public void refusesWithCoverOpen() throws IOException {
        fake.coverOpen = true;
        assertPrintFails(Ivy2Exception.Reason.COVER_OPEN);
    }

    @Test
    public void refusesWithLowBattery() throws IOException {
        fake.battery = 20;
        assertPrintFails(Ivy2Exception.Reason.LOW_BATTERY);
    }

    @Test
    public void setsAutoPowerOff() throws IOException {
        printer.setAutoPowerOff(10);
        assertEquals(10, fake.autoPowerOff);
        assertEquals(10, printer.getSettings().autoPowerOff);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidAutoPowerOff() throws IOException {
        printer.setAutoPowerOff(7);
    }

    @Test
    public void timesOutWhenPrinterIsSilent() throws IOException {
        fake.silent = true;
        printer.setResponseTimeoutMs(200);
        try {
            printer.startSession();
            fail();
        } catch (Ivy2Exception e) {
            assertEquals(Ivy2Exception.Reason.TIMEOUT, e.reason);
        }
    }
}
