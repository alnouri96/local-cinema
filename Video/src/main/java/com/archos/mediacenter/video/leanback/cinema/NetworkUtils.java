package com.archos.mediacenter.video.leanback.cinema;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

final class NetworkUtils {
    static String getLocalIpv4Address(android.content.Context context) {
        var manager = context.getSystemService(android.net.ConnectivityManager.class);
        if (manager != null) {
            String active = addressFor(manager, manager.getActiveNetwork());
            if (active != null) return active;
            for (var network : manager.getAllNetworks()) {
                String address = addressFor(manager, network);
                if (address != null) return address;
            }
        }
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (var network : Collections.list(interfaces)) {
                String name = network.getName();
                if ((!name.startsWith("eth") && !name.startsWith("wlan"))
                        || !network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                for (var address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            return null;
        }
        return null;
    }

    private static String addressFor(android.net.ConnectivityManager manager, android.net.Network network) {
        if (network == null) return null;
        var capabilities = manager.getNetworkCapabilities(network);
        if (capabilities == null || !(capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) return null;
        var properties = manager.getLinkProperties(network);
        if (properties == null) return null;
        for (var link : properties.getLinkAddresses()) {
            var address = link.getAddress();
            if (address instanceof Inet4Address && address.isSiteLocalAddress())
                return address.getHostAddress();
        }
        return null;
    }

    private NetworkUtils() {}
}
