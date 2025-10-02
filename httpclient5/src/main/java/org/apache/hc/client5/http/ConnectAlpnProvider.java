package org.apache.hc.client5.http;

import org.apache.hc.core5.http.HttpHost;

import java.util.List;

@FunctionalInterface
public interface ConnectAlpnProvider {
    /**
     * Return the list of ALPN protocol IDs to advertise for this CONNECT tunnel.
     * Return null or empty to skip the header.
     */
    List<String> getAlpnForTunnel(HttpHost target, HttpRoute route);
}