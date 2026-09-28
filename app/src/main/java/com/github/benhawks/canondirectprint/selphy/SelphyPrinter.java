package com.github.benhawks.canondirectprint.selphy;

import com.github.benhawks.canondirectprint.util.PrintLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;

/**
 * Prints a JPEG on a Canon SELPHY over Wi-Fi using CPNP (see {@link Cpnp}).
 * The printer decodes and scales the JPEG itself. Blocking; use from a
 * worker thread. Mirrors the job flow of selphy_go:
 * GET_ID, GET_STATUS, FLUSH, START_TCP, then a status-polling loop over TCP
 * in which the printer asks for the job flags and pulls file chunks.
 */
public class SelphyPrinter {
    public interface ProgressListener {
        void onTransferProgress(int bytesSent, int bytesTotal);
        /** Human readable job state, e.g. "Printing...". */
        void onState(String state);
    }

    /** A printer that answered discovery. */
    public static final class Found {
        public final String host;
        public final String mac;

        Found(String host, String mac) {
            this.host = host;
            this.mac = mac;
        }

        @Override
        public String toString() { return host + " (" + mac + ")"; }
    }

    private static final int UDP_TIMEOUT_MS = 2000;
    private static final int UDP_RETRIES = 3;
    private static final int TCP_TIMEOUT_MS = 20000;
    private static final int JOB_CONNECT_TIMEOUT_MS = 5000;
    private static final int JOB_CONNECT_ATTEMPTS = 3;
    private static final int JOB_CONNECT_RETRY_MS = 500;
    /** Give up if the printer makes no progress for this long. */
    private static final int STALL_TIMEOUT_MS = 180000;

    private final InetAddress address;
    private final int udpPort;
    private final DatagramSocket udp;
    private int sequence = 0;
    private volatile boolean cancelled;
    private volatile Socket tcp;

    public SelphyPrinter(String host, int udpPort) throws IOException {
        this.address = InetAddress.getByName(host);
        this.udpPort = udpPort;
        this.udp = new DatagramSocket();
    }

    public String getHost() {
        return address.getHostAddress();
    }

    /**
     * Broadcasts a DISCOVER packet and returns the first printer that answers,
     * or null.
     */
    public static Found discover(InetAddress[] targets, int port, int timeoutMs) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setBroadcast(true);
            byte[] request = new Cpnp.Packet(Cpnp.CMD_DISCOVER, 1, 0, null).encode();
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            byte[] buffer = new byte[2048];
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= deadline)
                    return null;
                if (now >= nextSend) {
                    for (InetAddress target : targets) {
                        try {
                            socket.send(new DatagramPacket(request, request.length, target, port));
                        } catch (IOException e) {
                            PrintLog.log("SELPHY discovery to " + target + " failed: " + e);
                        }
                    }
                    nextSend = now + 500;
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(deadline, nextSend) - now));
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Cpnp.Packet p = Cpnp.Packet.decode(packet.getData(), packet.getOffset(), packet.getLength());
                if (p == null || p.command != (Cpnp.CMD_DISCOVER | Cpnp.RESPONSE_FLAG))
                    continue;
                String[] info = Cpnp.parseDiscover(p);
                // Trust the address the answer came from over the advertised one (NAT, hotspots).
                String host = packet.getAddress().getHostAddress();
                return new Found(host, info != null ? info[0] : "?");
            }
        } finally {
            socket.close();
        }
    }

    private Cpnp.Packet udpTransact(int command, byte[] payload) throws IOException {
        for (int attempt = 0; attempt < UDP_RETRIES; attempt++) {
            if (cancelled)
                throw new IOException("Cancelled");
            int seq = nextSequence();
            byte[] request = new Cpnp.Packet(command, seq, 0, payload).encode();
            udp.send(new DatagramPacket(request, request.length, address, udpPort));
            long deadline = System.currentTimeMillis() + UDP_TIMEOUT_MS;
            byte[] buffer = new byte[4096];
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                    break;
                udp.setSoTimeout((int) remaining);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udp.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Cpnp.Packet p = Cpnp.Packet.decode(packet.getData(), packet.getOffset(), packet.getLength());
                if (p != null && p.sequence == seq && p.command == (command | Cpnp.RESPONSE_FLAG))
                    return p;
            }
            PrintLog.log(String.format(java.util.Locale.US, "SELPHY: no reply to 0x%04x, retrying", command));
        }
        throw new IOException("SELPHY at " + getHost() + " does not respond. Is it on and connected to this Wi-Fi?");
    }

    private int nextSequence() {
        sequence = (sequence + 1) & 0xFFFF;
        return sequence;
    }

    /** IEEE 1284 device id, e.g. {MFG=Canon, MDL=CP1300, DES=Canon SELPHY CP1300}. */
    public Map<String, String> getDeviceId() throws IOException {
        return Cpnp.parseDeviceId(udpTransact(Cpnp.CMD_GET_ID, new byte[4]));
    }

    public Cpnp.DeviceStatus getStatus() throws IOException {
        return new Cpnp.DeviceStatus(udpTransact(Cpnp.CMD_STATUS, null));
    }

    public static void checkPrintable(Cpnp.DeviceStatus status) throws SelphyException {
        if (status.paperMissing())
            throw new SelphyException("SELPHY: paper cassette not inserted");
        if (status.inkMissing())
            throw new SelphyException("SELPHY: ink cassette not inserted");
    }

    /** Aborts a running job (from another thread). */
    public void cancel() {
        cancelled = true;
        Socket s = tcp;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * Prints a JPEG. Returns when the printer reports the job as done, which
     * is after the physical print (roughly a minute for a postcard).
     *
     * @param bordered true to let the printer add white borders, false for borderless
     */
    public void print(byte[] jpeg, int width, int height, boolean bordered, String jobName, ProgressListener listener) throws IOException {
        cancelled = false;
        checkPrintable(getStatus());
        udpTransact(Cpnp.CMD_FLUSH, new byte[4]);

        Cpnp.Packet start = udpTransact(Cpnp.CMD_START_TCP, Cpnp.startTcpPayload("OpenMemories", "camera", jobName));
        PrintLog.log("SELPHY START_TCP reply: " + start + " payload " + Cpnp.hex(start.payload));
        int jobId = start.jobId;
        int[] ports = Cpnp.startTcpPorts(start);
        if (ports.length == 0)
            throw new SelphyException("SELPHY is not ready to accept a job. Turn it off and on again.");

        Socket socket = connectJob(ports);
        tcp = socket;
        try {
            socket.setSoTimeout(TCP_TIMEOUT_MS);
            runJob(socket.getInputStream(), socket.getOutputStream(), jobId, jpeg, width, height, bordered, listener);
        } catch (IOException e) {
            if (cancelled)
                throw new IOException("Cancelled");
            throw e;
        } finally {
            tcp = null;
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * Opens the job's TCP connection. The CP900 capture has the port big-endian
     * in bytes 4..5 of the START_TCP reply; other models have not been
     * captured, so the byte-swapped value is tried as well, and the printer
     * gets a moment in case it starts listening late.
     *
     * @throws JobConnectException if no connection could be made (nothing has been sent yet)
     */
    private Socket connectJob(int[] ports) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < JOB_CONNECT_ATTEMPTS; attempt++) {
            for (int port : ports) {
                if (cancelled)
                    throw new IOException("Cancelled");
                Socket socket = new Socket();
                try {
                    socket.setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress(address, port), JOB_CONNECT_TIMEOUT_MS);
                    PrintLog.log("SELPHY job connection open on port " + port + " (attempt " + (attempt + 1) + ")");
                    return socket;
                } catch (IOException e) {
                    PrintLog.log("SELPHY job connection to port " + port + " failed: " + e);
                    last = e;
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
            sleep(JOB_CONNECT_RETRY_MS);
        }
        throw new JobConnectException("SELPHY refused the print connection (" + (last != null ? last.getMessage() : "?") + ")");
    }

    /** The printer accepted the job request but its print connection could not be opened. */
    public static class JobConnectException extends SelphyException {
        public JobConnectException(String message) {
            super(message);
        }
    }

    private Cpnp.Packet tcpTransact(InputStream in, OutputStream out, int jobId, int command, byte[] payload) throws IOException {
        int seq = nextSequence();
        out.write(new Cpnp.Packet(command, seq, jobId, payload).encode());
        out.flush();
        while (true) {
            Cpnp.Packet p = Cpnp.Packet.read(in);
            if (p.sequence == seq)
                return p;
            PrintLog.log("SELPHY: ignoring out-of-order " + p);
        }
    }

    private void runJob(InputStream in, OutputStream out, int jobId, byte[] jpeg, int width, int height,
                        boolean bordered, ProgressListener listener) throws IOException {
        byte[] lastStatus = null;
        long lastProgress = System.currentTimeMillis();
        int sentUpTo = 0;
        String lastState = null;
        while (true) {
            if (cancelled)
                throw new IOException("Cancelled");
            if (System.currentTimeMillis() - lastProgress > STALL_TIMEOUT_MS)
                throw new SelphyException("SELPHY stopped responding to the print job");

            Cpnp.JobStatus status = new Cpnp.JobStatus(tcpTransact(in, out, jobId, Cpnp.CMD_STATUS, null));
            // The printer often repeats its last status while busy.
            if (Arrays.equals(status.raw, lastStatus)) {
                sleep(200);
                continue;
            }
            lastStatus = status.raw;
            lastProgress = System.currentTimeMillis();

            switch (status.state) {
                case Cpnp.STATE_WAIT:
                    String state = sentUpTo > 0 ? "Printing..." : "Waiting for printer...";
                    if (!state.equals(lastState) && listener != null)
                        listener.onState(state);
                    lastState = state;
                    sleep(500);
                    break;
                case Cpnp.STATE_SEND_FLAGS:
                    tcpTransact(in, out, jobId, Cpnp.CMD_DATA, Cpnp.flagsPayload(bordered));
                    break;
                case Cpnp.STATE_SEND_DATA:
                    sendChunk(in, out, jobId, jpeg, width, height, status.dataOffset, status.dataLength);
                    sentUpTo = Math.max(sentUpTo, Math.min(jpeg.length, status.dataOffset + status.dataLength));
                    if (listener != null)
                        listener.onTransferProgress(sentUpTo, jpeg.length);
                    break;
                case Cpnp.STATE_DONE:
                    tcpTransact(in, out, jobId, Cpnp.CMD_DATA, Cpnp.endJobPayload());
                    if (listener != null)
                        listener.onTransferProgress(jpeg.length, jpeg.length);
                    PrintLog.log("SELPHY job done");
                    return;
                case Cpnp.STATE_ERROR:
                    // Out of paper/ink mid-job also end up here; the details are not decoded yet.
                    throw new SelphyException("SELPHY reported an error. Check paper and ink, then see the printer's screen.");
                default:
                    throw new SelphyException("SELPHY reported unknown state " + status.state);
            }
        }
    }

    private void sendChunk(InputStream in, OutputStream out, int jobId, byte[] jpeg, int width, int height,
                           int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || length > 16 * 1024 * 1024)
            throw new SelphyException("SELPHY requested an invalid chunk (" + offset + "+" + length + ")");
        PrintLog.log("SELPHY requests " + length + " bytes from " + offset);
        byte[] chunk = Cpnp.chunk(jpeg, width, height, offset, length);
        for (int pos = 0; pos < chunk.length; pos += Cpnp.MAX_DATA) {
            if (cancelled)
                throw new IOException("Cancelled");
            int n = Math.min(Cpnp.MAX_DATA, chunk.length - pos);
            tcpTransact(in, out, jobId, Cpnp.CMD_DATA, Arrays.copyOfRange(chunk, pos, pos + n));
        }
    }

    private static void sleep(int ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new IOException("Cancelled");
        }
    }

    public void close() {
        cancel();
        udp.close();
    }
}
