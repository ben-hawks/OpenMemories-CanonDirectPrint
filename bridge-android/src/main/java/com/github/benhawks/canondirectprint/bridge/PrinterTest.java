package com.github.benhawks.canondirectprint.bridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Checks the Bluetooth side on its own: connects to the printer, sends the
 * Ivy 2 START_SESSION command and reports the reply. No camera or Wi-Fi needed.
 */
final class PrinterTest {
    private static final int REPLY_TIMEOUT_MS = 5000;
    private static final int MESSAGE_LENGTH = 34;

    private PrinterTest() {}

    /** START_SESSION: 0x430F, -1 (int16), -1 (int8), command 0, flag 0, zero padded to 34 bytes. */
    static byte[] startSession() {
        byte[] m = new byte[MESSAGE_LENGTH];
        m[0] = 0x43;
        m[1] = 0x0F;
        m[2] = (byte) 0xFF;
        m[3] = (byte) 0xFF;
        m[4] = (byte) 0xFF;
        return m;
    }

    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append(String.format(java.util.Locale.US, "%02x", b[i] & 0xFF)).append(i % 2 == 1 ? " " : "");
        return sb.toString().trim();
    }

    /** Runs the test on a background thread, reporting to the shared log. */
    static void start(final BluetoothAdapter adapter, final String address, final String name) {
        new Thread(() -> run(adapter, address, name), "printer-test").start();
    }

    private static void run(BluetoothAdapter adapter, String address, String name) {
        BridgeState state = BridgeState.get();
        state.log("Test: connecting to " + name + "...");
        final BluetoothSocket socket;
        try {
            socket = PrinterLink.connect(adapter, address);
        } catch (IOException | SecurityException e) {
            state.log("Test FAILED: " + e.getMessage());
            return;
        }
        // Reads on a BluetoothSocket cannot time out; close it from a watchdog instead.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(REPLY_TIMEOUT_MS);
                socket.close();
            } catch (InterruptedException | IOException ignored) {
                // done or closed
            }
        });
        try {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(startSession());
            out.flush();
            watchdog.start();
            byte[] reply = new byte[MESSAGE_LENGTH];
            int n = 0;
            while (n < MESSAGE_LENGTH) {
                int r = in.read(reply, n, MESSAGE_LENGTH - n);
                if (r < 0)
                    break;
                n += r;
            }
            state.log("Test: printer replied " + n + " bytes: " + hex(reply, n));
            // Like the reference client, look at the command echo (bytes 5-6) rather than
            // the start code: the Ivy 2 does not start its replies with 43 0f.
            int ack = n >= 7 ? ((reply[5] & 0xFF) << 8) | (reply[6] & 0xFF) : -1;
            if (n >= 13 && ack == 0) {
                int battery = (((reply[9] & 0xFF) << 8) | (reply[10] & 0xFF)) & 0x3F;
                int mtu = ((reply[11] & 0xFF) << 8) | (reply[12] & 0xFF);
                state.log("Test OK: the printer answered START_SESSION (battery level " + battery + "/63, MTU " + mtu
                        + ", error " + (reply[7] & 0xFF) + ").");
            } else {
                state.log("Test: connected, but the reply is not a START_SESSION answer (command " + ack + ").");
            }
        } catch (IOException e) {
            state.log("Test: connected, but no reply within " + (REPLY_TIMEOUT_MS / 1000) + " s (" + e.getMessage() + ")");
        } finally {
            watchdog.interrupt();
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
