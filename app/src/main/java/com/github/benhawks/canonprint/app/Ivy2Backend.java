package com.github.benhawks.canonprint.app;

import android.content.Context;

import com.github.benhawks.canonprint.image.PrintLayout;
import com.github.benhawks.canonprint.ivy2.Ivy2Printer;
import com.github.benhawks.canonprint.ivy2.Ivy2Protocol;

import java.io.IOException;

/** Canon Ivy 2 through a Wi-Fi/Bluetooth bridge. */
public class Ivy2Backend extends PrintBackend {
    private final Context context;
    private final AppSettings settings;
    private volatile Ivy2Printer active;
    private volatile boolean cancelled;

    Ivy2Backend(Context context, AppSettings settings) {
        this.context = context;
        this.settings = settings;
    }

    private PrinterConnector connector(final Listener listener) {
        return new PrinterConnector(context, settings, new PrinterConnector.StatusListener() {
            @Override
            public void onStatus(String message) {
                listener.onStatus(message);
            }
        });
    }

    private Ivy2Printer open(PrinterConnector connector) throws IOException {
        Ivy2Printer printer = connector.connect();
        active = printer;
        if (cancelled) {
            printer.close();
            throw new IOException("Cancelled");
        }
        return printer;
    }

    @Override
    public void print(byte[] jpeg, PrintLayout.Mode mode, String jobName, final Listener listener) throws IOException {
        PrinterConnector connector = connector(listener);
        Ivy2Printer printer = open(connector);
        try {
            listener.onStatus("Sending photo (printer battery level " + connector.sessionInfo.batteryLevel + ")...");
            printer.print(jpeg, new Ivy2Printer.ProgressListener() {
                @Override
                public void onTransferProgress(int sent, int total) {
                    listener.onProgress(sent, total);
                }
            });
        } finally {
            active = null;
            printer.close();
        }
    }

    @Override
    public String describe(Listener listener) throws IOException {
        Ivy2Printer printer = open(connector(listener));
        try {
            return describe(printer);
        } finally {
            active = null;
            printer.close();
        }
    }

    /** Changes the auto power off time and returns the new description. */
    public String setAutoPowerOff(int minutes, Listener listener) throws IOException {
        Ivy2Printer printer = open(connector(listener));
        try {
            printer.setAutoPowerOff(minutes);
            return describe(printer);
        } finally {
            active = null;
            printer.close();
        }
    }

    /** Auto power off of the last described printer, -1 if unknown. */
    public int autoPowerOff = -1;

    private String describe(Ivy2Printer printer) throws IOException {
        Ivy2Protocol.Status status = printer.getStatus();
        Ivy2Protocol.Settings s = printer.getSettings();
        autoPowerOff = s.autoPowerOff;
        StringBuilder sb = new StringBuilder();
        sb.append("Bridge: ").append(settings.lastBridgeHost).append(':').append(settings.lastBridgePort).append('\n');
        sb.append("Battery level: ").append(status.batteryLevel).append(" / 63")
                .append(status.usbConnected ? " (charging)" : "").append('\n');
        sb.append("Paper: ").append(status.noPaper ? "EMPTY" : "OK")
                .append(status.coverOpen ? ", cover OPEN" : "")
                .append(status.wrongSmartSheet ? ", wrong Smart Sheet" : "").append('\n');
        if (status.errorCode != 0)
            sb.append("Error code: ").append(status.errorCode).append('\n');
        sb.append("Firmware: ").append(s.firmwareVersion).append('\n');
        sb.append("Photos printed: ").append(s.photosPrinted).append('\n');
        sb.append("Auto power off: ◀ ").append(s.autoPowerOff).append(" min ▶\n");
        return sb.toString();
    }

    @Override
    public boolean needsPauseBetweenCopies() {
        // The Ivy 2 acknowledges the transfer, then prints for about a minute
        // without a documented "busy" flag.
        return true;
    }

    @Override
    public void cancel() {
        cancelled = true;
        Ivy2Printer printer = active;
        if (printer != null) {
            printer.cancel();
            printer.close();
        }
    }
}
