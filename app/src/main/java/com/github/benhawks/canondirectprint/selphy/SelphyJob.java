package com.github.benhawks.canondirectprint.selphy;

import com.github.benhawks.canondirectprint.ipp.Ipp;
import com.github.benhawks.canondirectprint.ipp.IppPrinter;
import com.github.benhawks.canondirectprint.util.PrintLog;

import java.io.IOException;

/**
 * Prints one JPEG on a SELPHY, choosing between Canon's CPNP protocol and
 * AirPrint/IPP. In AUTO mode the preferred protocol is tried first and the
 * other one only if the first could not start a job at all (so a real printer
 * problem such as missing paper is reported, not hidden by a retry).
 */
public class SelphyJob {
    public enum Protocol { AUTO, CPNP, IPP }

    public interface Listener {
        void onStatus(String message);
        void onProgress(int value, int max);
    }

    private final String host;
    private final int cpnpPort;
    private final int ippPort;
    private final String ippPath;
    private volatile SelphyPrinter cpnp;
    private volatile IppPrinter ipp;
    private volatile boolean cancelled;

    public SelphyJob(String host, int cpnpPort, int ippPort, String ippPath) {
        this.host = host;
        this.cpnpPort = cpnpPort;
        this.ippPort = ippPort;
        this.ippPath = ippPath;
    }

    public SelphyJob(String host) {
        this(host, Cpnp.PORT, Ipp.DEFAULT_PORT, IppPrinter.DEFAULT_PATH);
    }

    /**
     * @param protocol  AUTO, or a protocol to use exclusively
     * @param preferred protocol to try first in AUTO mode (e.g. the one that worked last time)
     * @return the protocol that printed the photo
     */
    public Protocol print(byte[] jpeg, int width, int height, boolean bordered, String jobName,
                          Protocol protocol, Protocol preferred, Listener listener) throws IOException {
        cancelled = false;
        if (protocol == Protocol.CPNP || protocol == Protocol.IPP) {
            run(protocol, jpeg, width, height, bordered, jobName, listener);
            return protocol;
        }
        Protocol first = preferred == Protocol.IPP ? Protocol.IPP : Protocol.CPNP;
        Protocol second = first == Protocol.IPP ? Protocol.CPNP : Protocol.IPP;
        try {
            run(first, jpeg, width, height, bordered, jobName, listener);
            return first;
        } catch (SelphyPrinter.JobConnectException e) {
            PrintLog.log("CPNP could not start the job, trying IPP: " + e.getMessage());
            fallback(listener);
        } catch (IppPrinter.UnavailableException e) {
            PrintLog.log("IPP not usable, trying CPNP: " + e.getMessage());
            fallback(listener);
        }
        try {
            run(second, jpeg, width, height, bordered, jobName, listener);
            return second;
        } catch (SelphyPrinter.JobConnectException e) {
            throw new IOException("Neither AirPrint/IPP nor Canon's protocol worked: " + e.getMessage());
        } catch (IppPrinter.UnavailableException e) {
            throw new IOException("Neither Canon's protocol nor AirPrint/IPP worked: " + e.getMessage());
        }
    }

    private void fallback(Listener listener) throws IOException {
        if (cancelled)
            throw new IOException("Cancelled");
        listener.onStatus("Trying another way to reach the SELPHY...");
    }

    private void run(Protocol protocol, byte[] jpeg, int width, int height, boolean bordered, String jobName,
                     final Listener listener) throws IOException {
        if (protocol == Protocol.IPP) {
            IppPrinter printer = new IppPrinter(host, ippPort, ippPath);
            ipp = printer;
            try {
                printer.print(jpeg, jobName, !bordered, new IppPrinter.Listener() {
                    @Override
                    public void onTransferProgress(int sent, int total) {
                        listener.onProgress(sent, total);
                    }

                    @Override
                    public void onState(String state) {
                        listener.onStatus(state);
                    }
                });
            } finally {
                ipp = null;
            }
        } else {
            SelphyPrinter printer = new SelphyPrinter(host, cpnpPort);
            cpnp = printer;
            try {
                listener.onStatus("Sending photo to SELPHY...");
                printer.print(jpeg, width, height, bordered, jobName, new SelphyPrinter.ProgressListener() {
                    @Override
                    public void onTransferProgress(int sent, int total) {
                        listener.onProgress(sent, total);
                    }

                    @Override
                    public void onState(String state) {
                        listener.onStatus(state);
                    }
                });
            } finally {
                cpnp = null;
                printer.close();
            }
        }
    }

    public void cancel() {
        cancelled = true;
        SelphyPrinter c = cpnp;
        if (c != null)
            c.cancel();
        IppPrinter i = ipp;
        if (i != null)
            i.cancel();
    }
}
