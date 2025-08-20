/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.async;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.core5.http.Header;

/**
 * Tiny HTTP/1.1 request encoder and response header parser for Upgrade.
 * Non-destructive parsing: reads the accumulated bytes, detects CRLFCRLF,
 * returns status line, headers, and leftover bytes (if any).
 */
public final class Http1HandshakeCodec {

    private Http1HandshakeCodec() {
    }

    public static ByteBuffer encodeRequest(final String methodLine, final List<Header> headers) {
        final StringBuilder sb = new StringBuilder();
        sb.append(methodLine).append("\r\n");
        for (final Header h : headers) {
            sb.append(h.getName()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        final byte[] b = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        final ByteBuffer bb = ByteBuffer.allocate(b.length);
        bb.put(b).flip();
        return bb;
    }

    /**
     * Try to parse a full HTTP/1.1 response head from the given buffer; return {@code null} if incomplete.
     */
    public static Response tryParse(final ByteBuffer in) {
        final int pos = in.position();
        if (pos <= 0) {
            return null;
        }

        final byte[] a = new byte[pos];
        in.flip();
        in.get(a);
        in.position(pos);
        in.limit(in.capacity());

        final int hdrEnd = indexOf(a, CRLFCRLF);
        if (hdrEnd < 0) {
            return null;
        }
        final String head = new String(a, 0, hdrEnd, StandardCharsets.ISO_8859_1);
        final String[] lines = head.split("\r\n");
        final String status = lines.length > 0 ? lines[0] : null;

        final Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            final int c = lines[i].indexOf(':');
            if (c > 0) {
                final String name = lines[i].substring(0, c).trim();
                final String value = lines[i].substring(c + 1).trim();
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }
        final byte[] leftover = Arrays.copyOfRange(a, hdrEnd + 4, a.length);
        return new Response(status, headers, leftover);
    }

    public static final class Response {
        public final String statusLine;
        public final Map<String, List<String>> headers;
        public final byte[] leftover;

        public Response(final String statusLine,
                        final Map<String, List<String>> headers,
                        final byte[] leftover) {
            this.statusLine = statusLine;
            this.headers = headers;
            this.leftover = leftover != null ? leftover : new byte[0];
        }
    }

    private static int indexOf(final byte[] hay, final byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
}
