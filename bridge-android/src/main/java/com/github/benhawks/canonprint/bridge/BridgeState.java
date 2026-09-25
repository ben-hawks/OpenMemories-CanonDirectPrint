package com.github.benhawks.canonprint.bridge;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Status and log shared between the service and the UI (same process). */
public final class BridgeState {
    public interface Listener {
        void onStateChanged();
    }

    private static final int MAX_LOG_LINES = 200;
    private static final BridgeState INSTANCE = new BridgeState();

    public static BridgeState get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final LinkedList<String> log = new LinkedList<>();
    private boolean running;
    private String status = "Stopped";

    private BridgeState() {}

    public synchronized boolean isRunning() { return running; }
    public synchronized String getStatus() { return status; }

    public synchronized String getLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : log)
            sb.append(line).append('\n');
        return sb.toString();
    }

    public void setRunning(boolean running, String status) {
        synchronized (this) {
            this.running = running;
            this.status = status;
        }
        log(status);
    }

    public void setStatus(String status) {
        synchronized (this) {
            this.status = status;
        }
        log(status);
    }

    public void log(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + message;
        android.util.Log.i("CanonPrintBridge", message);
        synchronized (this) {
            log.add(line);
            while (log.size() > MAX_LOG_LINES)
                log.removeFirst();
        }
        notifyListeners();
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void notifyListeners() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                List<Listener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<>(listeners);
                }
                for (Listener l : copy)
                    l.onStateChanged();
            }
        });
    }
}
