package com.github.benhawks.canonprint.app;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

/** Wi-Fi helpers. The camera joins networks saved in its Wi-Fi settings automatically. */
public class WifiHelper {
    private final WifiManager wifiManager;

    public WifiHelper(Context context) {
        wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public void setEnabled(boolean enabled) {
        wifiManager.setWifiEnabled(enabled);
    }

    public boolean isConnected() {
        WifiInfo info = wifiManager.getConnectionInfo();
        return info != null && info.getSupplicantState() == SupplicantState.COMPLETED && info.getIpAddress() != 0;
    }

    public String getSsid() {
        WifiInfo info = wifiManager.getConnectionInfo();
        return info != null ? info.getSSID() : null;
    }

    /** Turns Wi-Fi on and blocks until connected to a network. */
    public void awaitConnected(int timeoutMs) throws IOException {
        if (!wifiManager.isWifiEnabled())
            wifiManager.setWifiEnabled(true);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isConnected()) {
            if (System.currentTimeMillis() > deadline)
                throw new IOException("Not connected to Wi-Fi. Join the bridge's network in \"Wi-Fi settings\".");
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                throw new IOException("Interrupted");
            }
        }
    }

    private static InetAddress toAddress(int littleEndian) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {
                (byte) littleEndian, (byte) (littleEndian >> 8), (byte) (littleEndian >> 16), (byte) (littleEndian >> 24) });
    }

    /** The default gateway, which is the bridge itself when it hosts the hotspot. */
    public String getGateway() {
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        if (dhcp == null || dhcp.gateway == 0)
            return null;
        try {
            return toAddress(dhcp.gateway).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Addresses to send discovery broadcasts to. */
    public InetAddress[] getBroadcastAddresses() {
        ArrayList<InetAddress> result = new ArrayList<InetAddress>();
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        try {
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0)
                result.add(toAddress((dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask));
            result.add(InetAddress.getByName("255.255.255.255"));
        } catch (UnknownHostException e) {
            // ignore
        }
        return result.toArray(new InetAddress[result.size()]);
    }
}
