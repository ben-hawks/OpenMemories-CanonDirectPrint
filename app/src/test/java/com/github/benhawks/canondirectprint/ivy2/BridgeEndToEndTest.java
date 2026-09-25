package com.github.benhawks.canondirectprint.ivy2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs the camera's protocol code against bridge/canondirectprint_bridge.py with its
 * simulated printer. Skipped when python3 is not available.
 */
public class BridgeEndToEndTest {
    private static final int PORT = 19100;
    private Process bridge;
    private File saveDir;

    @Before
    public void startBridge() throws Exception {
        File script = new File("../bridge/canondirectprint_bridge.py");
        assumeTrue("bridge script not found", script.exists());
        saveDir = File.createTempFile("canondirectprint-e2e", "");
        saveDir.delete();
        saveDir.mkdirs();
        try {
            bridge = new ProcessBuilder("python3", script.getPath(), "--simulate", "--host", "127.0.0.1",
                    "--port", String.valueOf(PORT), "--discovery-port", "0", "--save-dir", saveDir.getPath())
                    .redirectErrorStream(true)
                    .start();
            drain(bridge.getInputStream());
        } catch (IOException e) {
            assumeTrue("python3 not available", false);
        }
        for (int i = 0; i < 50; i++) {
            try {
                new Socket("127.0.0.1", PORT).close();
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IOException("bridge did not start");
    }

    /** Echoes the bridge log to stdout (and keeps its pipe from filling up). */
    private static void drain(final InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0)
                        System.out.write(buf, 0, n);
                } catch (IOException e) {
                    // bridge stopped
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    @After
    public void stopBridge() {
        if (bridge != null)
            bridge.destroy();
    }

    @Test
    public void printsThroughBridge() throws Exception {
        byte[] jpeg = new byte[20000];
        for (int i = 0; i < jpeg.length; i++)
            jpeg[i] = (byte) (i * 7);
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;

        Ivy2Printer printer = new Ivy2Printer(new TcpConnection("127.0.0.1", PORT, 2000));
        try {
            printer.setChunkDelayMs(1);
            assertEquals(50, printer.startSession().batteryLevel);
            printer.print(jpeg, null);
            printer.setAutoPowerOff(10);
            assertEquals(10, printer.getSettings().autoPowerOff);
        } finally {
            printer.close();
        }

        File[] saved = saveDir.listFiles();
        assertEquals(1, saved.length);
        assertArrayEquals(jpeg, readFile(saved[0]));
    }
}
