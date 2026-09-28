package com.github.benhawks.canondirectprint.selphy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * Runs {@link SelphyPrinter} against bridge/fake_selphy.py.
 * Skipped when python3 is not available.
 */
public class SelphyEndToEndTest {
    private static final int PORT = 18609;
    private static final int IPP_PORT = 18631;
    private Process printer;
    private File saveDir;

    private void startPrinter(String... extraArgs) throws Exception {
        File script = new File("../bridge/fake_selphy.py");
        assumeTrue("fake_selphy.py not found", script.exists());
        saveDir = File.createTempFile("selphy-e2e", "");
        saveDir.delete();
        saveDir.mkdirs();
        List<String> cmd = new ArrayList<String>();
        cmd.add("python3");
        cmd.add(script.getPath());
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(PORT));
        cmd.add("--save-dir");
        cmd.add(saveDir.getPath());
        cmd.add("--ipp-port");
        cmd.add(String.valueOf(IPP_PORT));
        for (String a : extraArgs)
            cmd.add(a);
        try {
            printer = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue("python3 not available", false);
        }
        drain(printer.getInputStream());
        // Wait until it answers discovery.
        for (int i = 0; i < 50; i++) {
            if (SelphyPrinter.discover(new InetAddress[] { InetAddress.getByName("127.0.0.1") }, PORT, 200) != null)
                return;
        }
        throw new IOException("fake SELPHY did not start");
    }

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
                    // stopped
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
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    @After
    public void stop() {
        if (printer != null)
            printer.destroy();
    }

    @Test
    public void discoversAndPrints() throws Exception {
        startPrinter();
        SelphyPrinter.Found found = SelphyPrinter.discover(new InetAddress[] { InetAddress.getByName("127.0.0.1") }, PORT, 2000);
        assertNotNull(found);
        assertEquals("127.0.0.1", found.host);
        assertEquals("18:0c:ac:aa:aa:aa", found.mac);

        SelphyPrinter selphy = new SelphyPrinter(found.host, PORT);
        try {
            assertEquals("CP1300", selphy.getDeviceId().get("MDL"));
            assertEquals("Canon CP1300", selphy.getStatus().model);

            // Larger than one 102296-byte chunk request, and not a multiple of 4096.
            byte[] jpeg = new byte[250001];
            for (int i = 0; i < jpeg.length; i++)
                jpeg[i] = (byte) (i * 31);
            final int[] progress = { 0 };
            selphy.print(jpeg, 1808, 1232, false, "test", new SelphyPrinter.ProgressListener() {
                @Override
                public void onTransferProgress(int sent, int total) {
                    progress[0] = sent;
                }

                @Override
                public void onState(String state) {}
            });
            assertEquals(jpeg.length, progress[0]);

            // The simulator writes the file after the job is acknowledged.
            File[] saved = null;
            for (int i = 0; i < 50 && (saved == null || saved.length == 0); i++) {
                Thread.sleep(100);
                saved = saveDir.listFiles();
            }
            assertEquals(1, saved.length);
            assertArrayEquals(jpeg, readFile(saved[0]));
        } finally {
            selphy.close();
        }
    }

    private byte[] testJpeg() {
        byte[] jpeg = new byte[123457];
        for (int i = 0; i < jpeg.length; i++)
            jpeg[i] = (byte) (i * 7);
        return jpeg;
    }

    private File[] awaitSaved(int n) throws InterruptedException {
        File[] saved = saveDir.listFiles();
        for (int i = 0; i < 50 && saved.length < n; i++) {
            Thread.sleep(100);
            saved = saveDir.listFiles();
        }
        return saved;
    }

    private static final SelphyJob.Listener QUIET = new SelphyJob.Listener() {
        @Override public void onStatus(String message) {}
        @Override public void onProgress(int value, int max) {}
    };

    private SelphyJob job() {
        return new SelphyJob("127.0.0.1", PORT, IPP_PORT, "/ipp/print");
    }

    @Test
    public void fallsBackToIppWhenCpnpConnectionIsRefused() throws Exception {
        startPrinter("--refuse-tcp");
        byte[] jpeg = testJpeg();
        SelphyJob.Protocol used = job().print(jpeg, 1808, 1232, false, "test",
                SelphyJob.Protocol.AUTO, null, QUIET);
        assertEquals(SelphyJob.Protocol.IPP, used);
        File[] saved = awaitSaved(1);
        assertEquals(1, saved.length);
        assertArrayEquals(jpeg, readFile(saved[0]));
    }

    @Test
    public void prefersProtocolThatWorkedLastTime() throws Exception {
        startPrinter();
        SelphyJob.Protocol used = job().print(testJpeg(), 1808, 1232, true, "test",
                SelphyJob.Protocol.AUTO, SelphyJob.Protocol.IPP, QUIET);
        assertEquals(SelphyJob.Protocol.IPP, used);
    }

    @Test
    public void autoUsesCpnpWhenItWorks() throws Exception {
        startPrinter();
        byte[] jpeg = testJpeg();
        assertEquals(SelphyJob.Protocol.CPNP, job().print(jpeg, 1808, 1232, false, "test",
                SelphyJob.Protocol.AUTO, null, QUIET));
        assertArrayEquals(jpeg, readFile(awaitSaved(1)[0]));
    }

    @Test
    public void forcedCpnpDoesNotFallBack() throws Exception {
        startPrinter("--refuse-tcp");
        try {
            job().print(testJpeg(), 1808, 1232, false, "test", SelphyJob.Protocol.CPNP, null, QUIET);
            fail();
        } catch (SelphyPrinter.JobConnectException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("refused"));
        }
        assertEquals(0, saveDir.listFiles().length);
    }

    @Test
    public void missingPaperIsReportedNotRetriedOverIpp() throws Exception {
        startPrinter("--no-paper");
        try {
            job().print(testJpeg(), 1808, 1232, false, "test", SelphyJob.Protocol.AUTO, null, QUIET);
            fail();
        } catch (SelphyException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("paper"));
        }
        assertEquals(0, saveDir.listFiles().length);
    }

    @Test
    public void ippReportsMissingPaper() throws Exception {
        startPrinter("--no-paper");
        try {
            job().print(testJpeg(), 1808, 1232, false, "test", SelphyJob.Protocol.IPP, null, QUIET);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("paper"));
        }
    }

    @Test
    public void reportsMissingPaper() throws Exception {
        startPrinter("--no-paper");
        SelphyPrinter selphy = new SelphyPrinter("127.0.0.1", PORT);
        try {
            selphy.print(new byte[100], 10, 10, true, "test", null);
            fail();
        } catch (SelphyException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("paper"));
        } finally {
            selphy.close();
        }
    }
}
