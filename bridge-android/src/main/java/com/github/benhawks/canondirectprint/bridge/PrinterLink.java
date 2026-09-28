package com.github.benhawks.canondirectprint.bridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opens the RFCOMM (Serial Port Profile) connection to the printer.
 *
 * The reference client (dtgreene/ivy2, PyBluez on Linux) opens a plain RFCOMM
 * connection to channel 1 without requiring authentication or encryption.
 * Android's default sockets are "secure" and SDP-based, which some devices
 * reject, so several variants are tried in turn and each outcome is logged.
 */
final class PrinterLink {
    static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int CHANNEL = 1;
    private static final int RETRY_DELAY_MS = 400;

    private PrinterLink() {}

    private interface Attempt {
        String name();
        BluetoothSocket create() throws Exception;
    }

    /** Callers must hold BLUETOOTH_CONNECT (checked by the activity before starting the service). */
    @SuppressLint("MissingPermission")
    static BluetoothSocket connect(BluetoothAdapter adapter, String address) throws IOException {
        final BluetoothDevice device = adapter.getRemoteDevice(address);
        BridgeState state = BridgeState.get();
        state.log("Printer " + address + ": " + bondState(device.getBondState()) + ", services " + describeUuids(device.getUuids()));

        // An ongoing scan makes RFCOMM connections slow or fail. Stopping it needs
        // BLUETOOTH_SCAN on Android 12+, which this app does not request; best effort.
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // no scan permission: nothing to cancel that we started
        }

        List<Attempt> attempts = new ArrayList<>();
        attempts.add(new Attempt() {
            @Override public String name() { return "SPP, insecure"; }
            @Override public BluetoothSocket create() throws IOException {
                return device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            }
        });
        attempts.add(new Attempt() {
            @Override public String name() { return "SPP, secure"; }
            @Override public BluetoothSocket create() throws IOException {
                return device.createRfcommSocketToServiceRecord(SPP_UUID);
            }
        });
        // Channel-based sockets are hidden API; they may be unavailable on new Android versions.
        attempts.add(new Attempt() {
            @Override public String name() { return "channel " + CHANNEL + ", insecure"; }
            @Override public BluetoothSocket create() throws Exception {
                Method m = device.getClass().getMethod("createInsecureRfcommSocket", int.class);
                return (BluetoothSocket) m.invoke(device, CHANNEL);
            }
        });
        attempts.add(new Attempt() {
            @Override public String name() { return "channel " + CHANNEL + ", secure"; }
            @Override public BluetoothSocket create() throws Exception {
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                return (BluetoothSocket) m.invoke(device, CHANNEL);
            }
        });

        StringBuilder failures = new StringBuilder();
        for (int i = 0; i < attempts.size(); i++) {
            Attempt attempt = attempts.get(i);
            if (i > 0)
                sleep(RETRY_DELAY_MS);
            BluetoothSocket socket;
            try {
                socket = attempt.create();
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                state.log("Bluetooth " + attempt.name() + ": not available (" + cause + ")");
                failures.append(attempt.name()).append(": unavailable; ");
                continue;
            }
            try {
                socket.connect();
                state.log("Bluetooth " + attempt.name() + ": connected");
                return socket;
            } catch (IOException e) {
                state.log("Bluetooth " + attempt.name() + ": failed (" + e.getMessage() + ")");
                failures.append(attempt.name()).append(": ").append(e.getMessage()).append("; ");
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        throw new IOException("Could not connect to the printer over Bluetooth. Is it switched on, awake "
                + "(press its power button) and not connected to another phone or the Canon app? ["
                + failures + "]");
    }

    private static String bondState(int state) {
        switch (state) {
            case BluetoothDevice.BOND_BONDED: return "paired";
            case BluetoothDevice.BOND_BONDING: return "pairing";
            default: return "NOT paired";
        }
    }

    private static String describeUuids(ParcelUuid[] uuids) {
        if (uuids == null || uuids.length == 0)
            return "unknown";
        StringBuilder sb = new StringBuilder();
        for (ParcelUuid uuid : uuids) {
            if (sb.length() > 0)
                sb.append(", ");
            String s = uuid.toString();
            // Shorten standard Bluetooth base UUIDs (0000xxxx-0000-1000-8000-00805f9b34fb).
            sb.append(s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000") ? "0x" + s.substring(4, 8) : s);
        }
        return sb.toString();
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
