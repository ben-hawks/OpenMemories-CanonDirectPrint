package com.github.benhawks.canondirectprint.app;

import android.content.Context;

import com.github.benhawks.canondirectprint.image.PaperFormat;
import com.github.benhawks.canondirectprint.image.PrintLayout;
import com.github.benhawks.canondirectprint.selphy.Cpnp;
import com.github.benhawks.canondirectprint.ipp.Ipp;
import com.github.benhawks.canondirectprint.ipp.IppPrinter;
import com.github.benhawks.canondirectprint.selphy.SelphyJob;
import com.github.benhawks.canondirectprint.selphy.SelphyPrinter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Canon SELPHY over Wi-Fi (CPNP). No bridge needed: the camera joins the
 * printer's own "Direct Connection" network or a shared Wi-Fi network.
 */
public class SelphyBackend extends PrintBackend {
    private static final int WIFI_TIMEOUT_MS = 30000;
    private static final int DISCOVERY_TIMEOUT_MS = 3000;

    private final Context context;
    private final AppSettings settings;
    private volatile SelphyPrinter active;
    private volatile SelphyJob activeJob;
    private volatile boolean cancelled;

    SelphyBackend(Context context, AppSettings settings) {
        this.context = context;
        this.settings = settings;
    }

    /** Joins Wi-Fi and finds the printer. */
    private SelphyPrinter open(Listener listener) throws IOException {
        WifiHelper wifi = new WifiHelper(context);
        listener.onStatus("Connecting to Wi-Fi...");
        wifi.awaitConnected(WIFI_TIMEOUT_MS);
        Logger.info("Wi-Fi connected: " + wifi.getSsid());

        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        if (settings.selphyHost != null) {
            candidates.add(settings.selphyHost);
        } else {
            listener.onStatus("Looking for SELPHY on " + wifi.getSsid() + "...");
            SelphyPrinter.Found found = null;
            try {
                found = SelphyPrinter.discover(wifi.getBroadcastAddresses(), Cpnp.PORT, DISCOVERY_TIMEOUT_MS);
            } catch (IOException e) {
                Logger.error("SELPHY discovery failed", e);
            }
            if (found != null) {
                Logger.info("Discovered SELPHY " + found);
                candidates.add(found.host);
            }
            if (settings.lastSelphyHost != null)
                candidates.add(settings.lastSelphyHost);
            // In "Direct Connection" mode the printer is the access point.
            String gateway = wifi.getGateway();
            if (gateway != null)
                candidates.add(gateway);
        }

        IOException lastError = new IOException("No SELPHY found on Wi-Fi network " + wifi.getSsid());
        for (String host : candidates) {
            if (cancelled)
                throw new IOException("Cancelled");
            listener.onStatus("Connecting to SELPHY " + host + "...");
            SelphyPrinter printer = new SelphyPrinter(host, Cpnp.PORT);
            active = printer;
            try {
                Map<String, String> id = printer.getDeviceId();
                Logger.info("SELPHY at " + host + ": " + id);
                settings.lastSelphyHost = host;
                settings.save(context);
                return printer;
            } catch (IOException e) {
                Logger.info("No SELPHY at " + host + ": " + e);
                printer.close();
                active = null;
                lastError = e;
            }
        }
        throw lastError;
    }

    @Override
    public void print(byte[] jpeg, PrintLayout.Mode mode, String jobName, final Listener listener) throws IOException {
        SelphyPrinter found = open(listener);
        String host = found.getHost();
        found.close();
        active = null;

        SelphyJob job = new SelphyJob(host);
        activeJob = job;
        try {
            if (cancelled)
                throw new IOException("Cancelled");
            PaperFormat paper = PrinterType.SELPHY.paper;
            // FIT already has white borders in the image; ask for the printer's bordered layout
            // too so none of the photo is lost in the borderless bleed.
            SelphyJob.Protocol used = job.print(jpeg, paper.outputWidth, paper.outputHeight,
                    mode == PrintLayout.Mode.FIT, jobName, settings.selphyProtocol, settings.lastSelphyProtocol,
                    new SelphyJob.Listener() {
                        @Override
                        public void onStatus(String message) {
                            listener.onStatus(message);
                        }

                        @Override
                        public void onProgress(int value, int max) {
                            listener.onProgress(value, max);
                        }
                    });
            Logger.info("Printed via " + used);
            if (used != settings.lastSelphyProtocol) {
                settings.lastSelphyProtocol = used;
                settings.save(context);
            }
        } finally {
            activeJob = null;
        }
    }

    @Override
    public String describe(Listener listener) throws IOException {
        SelphyPrinter printer = open(listener);
        try {
            Map<String, String> id = printer.getDeviceId();
            Cpnp.DeviceStatus status = printer.getStatus();
            StringBuilder sb = new StringBuilder();
            String name = id.containsKey("DES") ? id.get("DES") : status.model;
            sb.append("Printer: ").append(name).append('\n');
            sb.append("Address: ").append(printer.getHost()).append('\n');
            sb.append("Paper cassette: ").append(cassette(status.paper)).append('\n');
            sb.append("Ink cassette: ").append(cassette(status.ink)).append('\n');
            sb.append("AirPrint/IPP: ").append(describeIpp(printer.getHost())).append('\n');
            if (settings.lastSelphyProtocol != null)
                sb.append("Last printed via: ").append(settings.lastSelphyProtocol == SelphyJob.Protocol.IPP ? "AirPrint/IPP" : "Canon CPNP").append('\n');
            return sb.toString();
        } finally {
            active = null;
            printer.close();
        }
    }

    private static String describeIpp(String host) {
        try {
            Ipp.Response r = new IppPrinter(host, Ipp.DEFAULT_PORT, IppPrinter.DEFAULT_PATH).getPrinterAttributes();
            boolean jpeg = r.strings("document-format-supported").contains("image/jpeg");
            return (jpeg ? "available" : "available, but no JPEG support") + " (" + r.string("printer-make-and-model") + ")";
        } catch (IOException e) {
            Logger.info("IPP probe failed: " + e);
            return "not available";
        }
    }

    private static String cassette(int state) {
        switch (state) {
            case Cpnp.DeviceStatus.CASSETTE_MISSING: return "NOT INSERTED";
            case Cpnp.DeviceStatus.CASSETTE_READY: return "OK";
            default: return "unknown (" + state + ")";
        }
    }

    @Override
    public boolean needsPauseBetweenCopies() {
        // print() only returns once the SELPHY reports the job as done.
        return false;
    }

    @Override
    public void cancel() {
        cancelled = true;
        SelphyPrinter printer = active;
        if (printer != null)
            printer.cancel();
        SelphyJob job = activeJob;
        if (job != null)
            job.cancel();
    }
}
