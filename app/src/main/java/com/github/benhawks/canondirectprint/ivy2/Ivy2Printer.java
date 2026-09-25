package com.github.benhawks.canondirectprint.ivy2;

import com.github.benhawks.canondirectprint.util.PrintLog;

import java.io.IOException;

/**
 * High level Ivy 2 printer session over an {@link Ivy2Connection}.
 * Mirrors the flow of Ivy2Printer in https://github.com/dtgreene/ivy2.
 * Not thread safe; use from a single worker thread.
 */
public class Ivy2Printer {
    public interface ProgressListener {
        /** Called while image data is sent. */
        void onTransferProgress(int bytesSent, int bytesTotal);
    }

    public static final int DEFAULT_RESPONSE_TIMEOUT_MS = 5000;
    public static final int DEFAULT_TRANSFER_TIMEOUT_MS = 120000;
    /** Pause after each data chunk, as in the reference implementation. */
    public static final int DEFAULT_CHUNK_DELAY_MS = 20;

    private final Ivy2Connection connection;
    private final MessageReader reader;
    private int responseTimeoutMs = DEFAULT_RESPONSE_TIMEOUT_MS;
    private int transferTimeoutMs = DEFAULT_TRANSFER_TIMEOUT_MS;
    private int chunkDelayMs = DEFAULT_CHUNK_DELAY_MS;
    private volatile boolean cancelled;

    public Ivy2Printer(Ivy2Connection connection) {
        this.connection = connection;
        this.reader = new MessageReader(connection);
    }

    public void setResponseTimeoutMs(int ms) { responseTimeoutMs = ms; }
    public void setTransferTimeoutMs(int ms) { transferTimeoutMs = ms; }
    public void setChunkDelayMs(int ms) { chunkDelayMs = ms; }

    /** Aborts a running transfer (from another thread). */
    public void cancel() { cancelled = true; }

    public Ivy2Protocol.SessionInfo startSession() throws IOException {
        return new Ivy2Protocol.SessionInfo(transact(Ivy2Protocol.startSession(), Ivy2Protocol.COMMAND_START_SESSION));
    }

    public Ivy2Protocol.Status getStatus() throws IOException {
        return new Ivy2Protocol.Status(transact(Ivy2Protocol.getStatus(), Ivy2Protocol.COMMAND_GET_STATUS));
    }

    public Ivy2Protocol.Settings getSettings() throws IOException {
        return new Ivy2Protocol.Settings(transact(Ivy2Protocol.getSetting(), Ivy2Protocol.COMMAND_SETTING_ACCESSORY));
    }

    /** @param minutes 3, 5 or 10 */
    public void setAutoPowerOff(int minutes) throws IOException {
        boolean valid = false;
        for (int v : Ivy2Protocol.AUTO_POWER_OFF_VALUES)
            valid |= v == minutes;
        if (!valid)
            throw new IllegalArgumentException("Unsupported auto power off value: " + minutes);
        transact(Ivy2Protocol.setAutoPowerOff(minutes), Ivy2Protocol.COMMAND_SETTING_ACCESSORY);
    }

    public void reboot() throws IOException {
        transact(Ivy2Protocol.reboot(), Ivy2Protocol.COMMAND_REBOOT);
    }

    /** Throws if the printer is not able to print right now. */
    public static void checkPrintable(Ivy2Protocol.Status status) throws Ivy2Exception {
        if (status.errorCode != 0)
            PrintLog.log("Status contains non-zero error code " + status.errorCode);
        if (status.batteryLevel < Ivy2Protocol.PRINT_BATTERY_MIN)
            throw new Ivy2Exception(Ivy2Exception.Reason.LOW_BATTERY,
                    "Printer battery too low (level " + status.batteryLevel + ", need " + Ivy2Protocol.PRINT_BATTERY_MIN + "). Charge the printer.");
        if (status.coverOpen)
            throw new Ivy2Exception(Ivy2Exception.Reason.COVER_OPEN);
        if (status.noPaper)
            throw new Ivy2Exception(Ivy2Exception.Reason.NO_PAPER);
        if (status.wrongSmartSheet)
            throw new Ivy2Exception(Ivy2Exception.Reason.WRONG_SMART_SHEET);
    }

    /**
     * Prints a JPEG that has already been prepared for the printer
     * (640x1616, rotated by 180 degrees, see PrintLayout).
     * Returns once the printer has acknowledged the transfer; the physical
     * print takes a further ~50 seconds.
     */
    public void print(byte[] jpeg, ProgressListener listener) throws IOException {
        cancelled = false;
        checkPrintable(getStatus());
        getSettings();

        Ivy2Protocol.PrintReady ready = new Ivy2Protocol.PrintReady(
                transact(Ivy2Protocol.printReady(jpeg.length), Ivy2Protocol.COMMAND_PRINT_READY));
        if (ready.errorCode != 0)
            PrintLog.log("PRINT_READY returned error code " + ready.errorCode);

        PrintLog.log("Sending " + jpeg.length + " bytes of image data");
        int sent = 0;
        while (sent < jpeg.length) {
            if (cancelled)
                throw new IOException("Cancelled");
            int n = Math.min(Ivy2Protocol.PRINT_DATA_CHUNK, jpeg.length - sent);
            connection.write(jpeg, sent, n);
            sent += n;
            if (listener != null)
                listener.onTransferProgress(sent, jpeg.length);
            sleep(chunkDelayMs);
        }

        Ivy2Protocol.Response done = reader.read(transferTimeoutMs);
        PrintLog.log("Transfer complete: " + done);
        if (done.getError() != 0)
            throw new Ivy2Exception(Ivy2Exception.Reason.PROTOCOL,
                    "Printer reported error " + done.getError() + " after transfer");
    }

    private Ivy2Protocol.Response transact(byte[] message, int expectedAck) throws IOException {
        PrintLog.log("> " + Hex.encode(message));
        connection.write(message, 0, message.length);
        sleep(chunkDelayMs);
        Ivy2Protocol.Response response = reader.read(responseTimeoutMs);
        PrintLog.log("< " + response);
        if (response.getAck() != expectedAck)
            throw new Ivy2Exception(Ivy2Exception.Reason.PROTOCOL,
                    "Unexpected reply from printer (expected " + expectedAck + ", got " + response.getAck() + ")");
        return response;
    }

    private static void sleep(int ms) {
        if (ms <= 0)
            return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        connection.close();
    }
}
