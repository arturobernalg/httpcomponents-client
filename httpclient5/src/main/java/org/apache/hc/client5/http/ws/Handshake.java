package org.apache.hc.client5.http.ws;

import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.ws.handshake.WsAccept;

public final class Handshake {

    public static final String SEC_WS_VERSION = "13";

    public static class Request {
        public final String methodLine;           // "GET /path HTTP/1.1"
        public final List<Header> headers;
        public final String secKey;               // remember for validation

        Request(final String m, final List<Header> h, final String k) {
            methodLine = m;
            headers = h;
            secKey = k;
        }
    }

    public static Request buildRequest(final URI uri,
                                       final String origin,
                                       final List<String> subprotocols,
                                       final String pmdHeader,
                                       final Map<String, String> extraHeaders) {
        final String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        final String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        final String query = (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        final String requestLine = "GET " + path + query + " HTTP/1.1";

        final String secKey = randomKey();

        final List<Header> hdrs = new ArrayList<>();
        hdrs.add(new BasicHeader("Host", host));
        hdrs.add(new BasicHeader("Upgrade", "websocket"));
        hdrs.add(new BasicHeader("Connection", "Upgrade"));
        hdrs.add(new BasicHeader("Sec-WebSocket-Key", secKey));
        hdrs.add(new BasicHeader("Sec-WebSocket-Version", SEC_WS_VERSION));
        if (origin != null) hdrs.add(new BasicHeader("Origin", origin));
        if (subprotocols != null && !subprotocols.isEmpty()) {
            hdrs.add(new BasicHeader("Sec-WebSocket-Protocol", String.join(", ", subprotocols)));
        }
        if (pmdHeader != null) {
            hdrs.add(new BasicHeader("Sec-WebSocket-Extensions", pmdHeader));
        }
        if (extraHeaders != null) {
            for (final Map.Entry<String, String> e : extraHeaders.entrySet()) {
                hdrs.add(new BasicHeader(e.getKey(), e.getValue()));
            }
        }
        return new Request(requestLine, hdrs, secKey);
    }

    public static void validate101(final String statusLine,
                                   final Map<String, List<String>> headers,
                                   final String secKey) throws ProtocolException {
        if (statusLine == null || !statusLine.startsWith("HTTP/1.1 101")) {
            throw new ProtocolException("Expected 101 Switching Protocols, got: " + statusLine);
        }
        final String upgrade = first(headers, "Upgrade");
        final String connection = first(headers, "Connection");
        final String accept = first(headers, "Sec-WebSocket-Accept");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade.trim())) {
            throw new ProtocolException("Missing/invalid Upgrade");
        }
        if (connection == null || !connection.toLowerCase(Locale.ROOT).contains("upgrade")) {
            throw new ProtocolException("Missing/invalid Connection");
        }
        final String want = WsAccept.computeAccept(secKey);
        if (accept == null || !want.equals(accept.trim())) {
            throw new ProtocolException("Bad Sec-WebSocket-Accept");
        }
    }

    private static String randomKey() {
        final byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        return java.util.Base64.getEncoder().encodeToString(rnd);
    }

    private static String first(final Map<String, List<String>> map, final String name) {
        for (final Map.Entry<String, List<String>> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue().isEmpty() ? null : e.getValue().get(0);
            }
        }
        return null;
    }
}
