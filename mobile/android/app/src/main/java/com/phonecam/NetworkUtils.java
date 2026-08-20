package com.phonecam;

import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class NetworkUtils {
    private static final String TAG = "NetworkUtils";

    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        String ip = address.getHostAddress();
                        // Filtrar IPv6
                        if (ip != null && !ip.contains(":")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error obteniendo IP local", e);
        }
        return "127.0.0.1";
    }
}