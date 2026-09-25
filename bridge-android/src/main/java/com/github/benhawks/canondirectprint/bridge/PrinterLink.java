package com.github.benhawks.canondirectprint.bridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Opens the RFCOMM (Serial Port Profile) connection to the printer. */
final class PrinterLink {
    static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private PrinterLink() {}

    /** Callers must hold BLUETOOTH_CONNECT (checked by the activity before starting the service). */
    @SuppressLint("MissingPermission")
    static BluetoothSocket connect(BluetoothAdapter adapter, String address) throws IOException {
        BluetoothDevice device = adapter.getRemoteDevice(address);
        IOException first;
        try {
            return tryConnect(device.createRfcommSocketToServiceRecord(SPP_UUID));
        } catch (IOException e) {
            first = e;
        }
        BridgeState.get().log("SPP connect failed (" + first.getMessage() + "), trying channel 1");
        try {
            // Same as the reference Python client: RFCOMM channel 1, no SDP lookup.
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            return tryConnect((BluetoothSocket) m.invoke(device, 1));
        } catch (IOException e) {
            throw first;
        } catch (ReflectiveOperationException e) {
            throw first;
        }
    }

    @SuppressLint("MissingPermission")
    private static BluetoothSocket tryConnect(BluetoothSocket socket) throws IOException {
        try {
            socket.connect();
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
            throw e;
        }
    }
}
