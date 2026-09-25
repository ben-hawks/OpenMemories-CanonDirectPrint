package com.github.benhawks.canondirectprint.bridge;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that relays TCP connections from the camera app to the
 * printer's Bluetooth RFCOMM channel, byte for byte. Same behaviour as
 * bridge/canondirectprint_bridge.py, including UDP discovery.
 */
public class BridgeService extends Service {
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";

    public static final int TCP_PORT = 9100;
    public static final int DISCOVERY_PORT = 9101;
    static final String DISCOVERY_REQUEST = "CANONDIRECTPRINT_BRIDGE_DISCOVER";
    /** The printer's reference client writes at most 990 bytes at a time. */
    private static final int RFCOMM_CHUNK = 990;
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIFICATION_ID = 1;

    private final BridgeState state = BridgeState.get();
    private volatile boolean stopping;
    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private volatile Socket activeClient;
    private volatile BluetoothSocket activePrinter;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String address;
    private String name;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra(EXTRA_ADDRESS) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (serverSocket != null)
            return START_NOT_STICKY; // already running
        address = intent.getStringExtra(EXTRA_ADDRESS);
        name = intent.getStringExtra(EXTRA_NAME);
        startInForeground("Waiting for camera on port " + TCP_PORT);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "canondirectprint:relay");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "canondirectprint:relay");
        wifiLock.acquire();

        stopping = false;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(TCP_PORT));
        } catch (IOException e) {
            state.setRunning(false, "Cannot listen on port " + TCP_PORT + ": " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(this::acceptLoop, "accept").start();
        new Thread(this::discoveryLoop, "discovery").start();
        state.setRunning(true, "Waiting for camera (printer " + name + ")");
        return START_NOT_STICKY;
    }

    private void startInForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else
            startForeground(NOTIFICATION_ID, notification);
    }

    private void acceptLoop() {
        while (!stopping) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (!stopping)
                    state.log("Accept failed: " + e.getMessage());
                return;
            }
            // The printer takes one connection at a time: serve cameras sequentially.
            handleClient(client);
            if (!stopping)
                state.setStatus("Waiting for camera (printer " + name + ")");
        }
    }

    @SuppressLint("MissingPermission")
    private void handleClient(Socket client) {
        activeClient = client;
        String from = client.getInetAddress().getHostAddress();
        state.setStatus("Camera connected from " + from + ", connecting to printer...");
        BluetoothSocket printer = null;
        try {
            client.setTcpNoDelay(true);
            BluetoothAdapter adapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
            if (adapter == null || !adapter.isEnabled())
                throw new IOException("Bluetooth is off");
            printer = PrinterLink.connect(adapter, address);
            activePrinter = printer;
            state.setStatus("Relaying camera " + from + " ↔ " + name);
            relay(client, printer);
        } catch (IOException e) {
            // Closing the TCP connection without data tells the camera the printer is unreachable.
            state.log("Printer connection failed: " + e.getMessage());
        } finally {
            closeQuietly(client);
            if (printer != null)
                closeQuietly(printer);
            activeClient = null;
            activePrinter = null;
        }
    }

    private void relay(final Socket client, final BluetoothSocket printer) throws IOException {
        final long[] toCamera = { 0 };
        Thread back = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                InputStream in = printer.getInputStream();
                OutputStream out = client.getOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    toCamera[0] += n;
                }
            } catch (IOException e) {
                // connection closed
            } finally {
                closeQuietly(client);
            }
        }, "printer->camera");
        back.start();

        long toPrinter = 0;
        byte[] buf = new byte[RFCOMM_CHUNK];
        try {
            InputStream in = client.getInputStream();
            OutputStream out = printer.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                toPrinter += n;
            }
        } catch (IOException e) {
            // connection closed
        } finally {
            closeQuietly(printer);
            try {
                back.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        state.log("Done: " + toPrinter + " bytes to printer, " + toCamera[0] + " bytes to camera");
    }

    private void discoveryLoop() {
        try {
            discoverySocket = new DatagramSocket(null);
            discoverySocket.setReuseAddress(true);
            discoverySocket.setBroadcast(true);
            discoverySocket.bind(new InetSocketAddress(DISCOVERY_PORT));
        } catch (SocketException e) {
            state.log("Discovery disabled: " + e.getMessage());
            return;
        }
        byte[] reply = ("CANONDIRECTPRINT_BRIDGE port=" + TCP_PORT + " name=" + Build.MODEL + " (" + name + ")").getBytes(StandardCharsets.US_ASCII);
        byte[] buf = new byte[512];
        while (!stopping) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                discoverySocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII).trim();
                if (DISCOVERY_REQUEST.equals(msg)) {
                    state.log("Discovery request from " + packet.getAddress().getHostAddress());
                    discoverySocket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                }
            } catch (IOException e) {
                if (!stopping)
                    state.log("Discovery error: " + e.getMessage());
                return;
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (serverSocket != null)
            closeQuietly(serverSocket);
        if (discoverySocket != null)
            discoverySocket.close();
        Socket c = activeClient;
        if (c != null)
            closeQuietly(c);
        BluetoothSocket p = activePrinter;
        if (p != null)
            closeQuietly(p);
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld())
            wifiLock.release();
        serverSocket = null;
        state.setRunning(false, "Stopped");
        super.onDestroy();
    }
}
