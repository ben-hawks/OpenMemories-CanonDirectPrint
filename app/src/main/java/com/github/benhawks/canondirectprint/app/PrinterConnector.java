package com.github.benhawks.canondirectprint.app;

import android.content.Context;

import com.github.benhawks.canondirectprint.ivy2.BridgeDiscovery;
import com.github.benhawks.canondirectprint.ivy2.Ivy2Exception;
import com.github.benhawks.canondirectprint.ivy2.Ivy2Printer;
import com.github.benhawks.canondirectprint.ivy2.Ivy2Protocol;
import com.github.benhawks.canondirectprint.ivy2.TcpConnection;

import java.io.IOException;
import java.util.LinkedHashSet;

/**
 * Gets from "nothing" to an open printer session: Wi-Fi, bridge, printer.
 * Blocking; call from a worker thread.
 */
public class PrinterConnector {
    public interface StatusListener {
        void onStatus(String message);
    }

    private static final int WIFI_TIMEOUT_MS = 30000;
    private static final int DISCOVERY_TIMEOUT_MS = 2000;
    private static final int CONNECT_TIMEOUT_MS = 4000;

    private final Context context;
    private final AppSettings settings;
    private final StatusListener listener;

    public Ivy2Protocol.SessionInfo sessionInfo;

    public PrinterConnector(Context context, AppSettings settings, StatusListener listener) {
        this.context = context;
        this.settings = settings;
        this.listener = listener;
    }

    public Ivy2Printer connect() throws IOException {
        WifiHelper wifi = new WifiHelper(context);
        listener.onStatus("Connecting to Wi-Fi...");
        wifi.awaitConnected(WIFI_TIMEOUT_MS);
        Logger.info("Wi-Fi connected: " + wifi.getSsid());

        // Candidate bridges, in order of preference.
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        if (settings.bridgeHost != null) {
            candidates.add(settings.bridgeHost + ":" + settings.bridgePort);
        } else {
            listener.onStatus("Looking for bridge on " + wifi.getSsid() + "...");
            BridgeDiscovery.Bridge found = null;
            try {
                found = BridgeDiscovery.discover(wifi.getBroadcastAddresses(), DISCOVERY_TIMEOUT_MS);
            } catch (IOException e) {
                Logger.error("Discovery failed", e);
            }
            if (found != null) {
                Logger.info("Discovered bridge " + found);
                candidates.add(found.host + ":" + found.port);
            }
            if (settings.lastBridgeHost != null)
                candidates.add(settings.lastBridgeHost + ":" + settings.lastBridgePort);
            String gateway = wifi.getGateway();
            if (gateway != null)
                candidates.add(gateway + ":" + TcpConnection.DEFAULT_PORT);
        }
        if (candidates.isEmpty())
            throw new IOException("No bridge found on Wi-Fi network " + wifi.getSsid());

        IOException lastError = null;
        for (String candidate : candidates) {
            int colon = candidate.lastIndexOf(':');
            String host = candidate.substring(0, colon);
            int port = Integer.parseInt(candidate.substring(colon + 1));
            listener.onStatus("Connecting to bridge " + host + "...");
            TcpConnection connection;
            try {
                connection = new TcpConnection(host, port, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                Logger.info("Bridge " + candidate + " not reachable: " + e);
                lastError = new IOException("Bridge not reachable at " + candidate);
                continue;
            }

            listener.onStatus("Connecting to printer...");
            Ivy2Printer printer = new Ivy2Printer(connection);
            printer.setChunkDelayMs(settings.chunkDelayMs);
            // The bridge opens the Bluetooth link when we connect, which took 5-8 s
            // on a phone in the field; allow time for that.
            printer.setResponseTimeoutMs(25000);
            try {
                sessionInfo = printer.startSession();
            } catch (Ivy2Exception e) {
                printer.close();
                if (e.reason == Ivy2Exception.Reason.DISCONNECTED || e.reason == Ivy2Exception.Reason.TIMEOUT)
                    throw new IOException("Bridge found, but it cannot reach the printer. Is the printer on and paired?");
                throw e;
            }
            printer.setResponseTimeoutMs(Ivy2Printer.DEFAULT_RESPONSE_TIMEOUT_MS);
            Logger.info("Session started via " + candidate + ": " + sessionInfo);

            settings.lastBridgeHost = host;
            settings.lastBridgePort = port;
            settings.save(context);
            return printer;
        }
        throw lastError;
    }
}
