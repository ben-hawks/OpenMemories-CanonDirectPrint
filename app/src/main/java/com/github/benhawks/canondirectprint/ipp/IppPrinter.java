package com.github.benhawks.canondirectprint.ipp;

import com.github.benhawks.canondirectprint.util.PrintLog;

import java.io.IOException;
import java.net.Socket;
import java.util.List;

/**
 * Prints a JPEG over IPP (AirPrint / Mopria / IPP Everywhere).
 * Newer SELPHYs such as the CP1300 support this besides Canon's CPNP.
 * Blocking; use from a worker thread.
 */
public class IppPrinter {
    public interface Listener {
        void onTransferProgress(int sent, int total);
        void onState(String state);
    }

    public static final String DEFAULT_PATH = "/ipp/print";
    private static final int TIMEOUT_MS = 20000;
    private static final int POLL_INTERVAL_MS = 2000;
    /** Give up if the job does not finish within this time. */
    private static final int JOB_TIMEOUT_MS = 5 * 60 * 1000;

    private final String host;
    private final int port;
    private final String path;
    private int requestId = 0;
    private volatile boolean cancelled;
    private final Socket[] active = new Socket[1];

    public IppPrinter(String host, int port, String path) {
        this.host = host;
        this.port = port;
        this.path = path;
    }

    public String getUri() {
        return "ipp://" + host + ":" + port + path;
    }

    private Ipp.Response send(Ipp.Request request, byte[] document, HttpPost.Progress progress) throws IOException {
        byte[] response = HttpPost.post(host, port, path, "application/ipp", request.encode(), document,
                TIMEOUT_MS, progress, active);
        if (cancelled)
            throw new IOException("Cancelled");
        return Ipp.decode(response);
    }

    public Ipp.Response getPrinterAttributes() throws IOException {
        Ipp.Request request = new Ipp.Request(Ipp.GET_PRINTER_ATTRIBUTES, ++requestId)
                .operationAttributes(getUri())
                .string(Ipp.KEYWORD, "requested-attributes",
                        "printer-make-and-model", "printer-state", "printer-state-reasons",
                        "document-format-supported", "print-scaling-supported", "media-ready");
        Ipp.Response response = send(request, null, null);
        if (!response.isSuccess())
            throw new IOException(String.format(java.util.Locale.US, "Printer rejected Get-Printer-Attributes (0x%04x)", response.status));
        return response;
    }

    /** Throws with a readable message if the printer reports a problem that stops printing. */
    static void checkStateReasons(List<String> reasons) throws IOException {
        for (String reason : reasons) {
            if (reason.startsWith("media-empty") || reason.startsWith("media-needed"))
                throw new IOException("Printer is out of paper (or the paper cassette is missing)");
            if (reason.startsWith("marker-supply-empty") || reason.startsWith("toner-empty"))
                throw new IOException("Printer is out of ink (or the ink cassette is missing)");
            if (reason.startsWith("media-jam"))
                throw new IOException("Paper jam");
            if (reason.startsWith("door-open") || reason.startsWith("cover-open"))
                throw new IOException("Printer cover is open");
        }
    }

    /**
     * Prints a JPEG and waits for the printer to finish.
     *
     * @param fill true to fill the page (crop), false to fit it (borders)
     */
    public void print(byte[] jpeg, String jobName, boolean fill, final Listener listener) throws IOException {
        cancelled = false;
        Ipp.Response printer;
        try {
            printer = getPrinterAttributes();
        } catch (IOException e) {
            if (cancelled)
                throw e;
            throw new UnavailableException("AirPrint/IPP not available at " + getUri() + ": " + e.getMessage());
        }
        PrintLog.log("IPP printer " + printer.string("printer-make-and-model")
                + " state=" + printer.integer("printer-state", -1)
                + " reasons=" + printer.strings("printer-state-reasons")
                + " formats=" + printer.strings("document-format-supported"));
        List<String> formats = printer.strings("document-format-supported");
        if (!formats.isEmpty() && !formats.contains("image/jpeg"))
            throw new UnavailableException("Printer does not accept JPEG over IPP (formats: " + formats + ")");
        checkStateReasons(printer.strings("printer-state-reasons"));

        Ipp.Request request = new Ipp.Request(Ipp.PRINT_JOB, ++requestId)
                .operationAttributes(getUri())
                .string(Ipp.NAME, "requesting-user-name", "camera")
                .string(Ipp.NAME, "job-name", jobName)
                .string(Ipp.MIME_TYPE, "document-format", "image/jpeg");
        List<String> scaling = printer.strings("print-scaling-supported");
        String wanted = fill ? "fill" : "fit";
        if (scaling.contains(wanted))
            request.group(Ipp.TAG_JOB).string(Ipp.KEYWORD, "print-scaling", wanted);

        if (listener != null)
            listener.onState("Sending photo (AirPrint/IPP)...");
        Ipp.Response created = send(request, jpeg, new HttpPost.Progress() {
            @Override
            public void onProgress(int sent, int total) {
                if (listener != null)
                    listener.onTransferProgress(sent, total);
            }
        });
        if (!created.isSuccess())
            throw new IOException(String.format(java.util.Locale.US, "Printer rejected the print job (IPP status 0x%04x)", created.status));
        int jobId = created.integer("job-id", -1);
        PrintLog.log("IPP job " + jobId + " created, state " + created.integer("job-state", -1));
        if (jobId < 0)
            return; // cannot track it; the printer accepted it
        waitForJob(jobId, listener);
    }

    private void waitForJob(int jobId, Listener listener) throws IOException {
        long deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS;
        int lastState = -1;
        while (true) {
            if (cancelled)
                throw new IOException("Cancelled");
            Ipp.Request request = new Ipp.Request(Ipp.GET_JOB_ATTRIBUTES, ++requestId)
                    .operationAttributes(getUri())
                    .integer(Ipp.INTEGER, "job-id", jobId)
                    .string(Ipp.KEYWORD, "requested-attributes", "job-state", "job-state-reasons");
            Ipp.Response job = send(request, null, null);
            int state = job.integer("job-state", -1);
            if (state != lastState) {
                PrintLog.log("IPP job " + jobId + " state " + state + " " + job.strings("job-state-reasons"));
                lastState = state;
                if (listener != null && (state == Ipp.JOB_PROCESSING || state == Ipp.JOB_PENDING))
                    listener.onState(state == Ipp.JOB_PROCESSING ? "Printing..." : "Waiting for printer...");
            }
            switch (state) {
                case Ipp.JOB_COMPLETED:
                    return;
                case Ipp.JOB_CANCELED:
                    throw new IOException("Print job was canceled on the printer");
                case Ipp.JOB_ABORTED:
                    checkStateReasons(getPrinterAttributes().strings("printer-state-reasons"));
                    throw new IOException("Printer aborted the job " + job.strings("job-state-reasons"));
                case Ipp.JOB_STOPPED:
                case Ipp.JOB_HELD:
                    checkStateReasons(getPrinterAttributes().strings("printer-state-reasons"));
                    break;
                default:
                    break;
            }
            if (!job.isSuccess() && state < 0)
                return; // job already gone from the printer's list: treat as done
            if (System.currentTimeMillis() > deadline)
                throw new IOException("Printer did not finish the job in time");
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                throw new IOException("Cancelled");
            }
        }
    }

    /** IPP could not be used at all (nothing was printed); another protocol may work. */
    public static class UnavailableException extends IOException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    public void cancel() {
        cancelled = true;
        Socket s = active[0];
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
