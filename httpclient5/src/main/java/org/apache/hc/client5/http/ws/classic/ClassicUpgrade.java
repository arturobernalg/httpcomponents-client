/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.classic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.ws.Handshake;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.http.ProtocolException;

/**
 * Blocking HTTP/1.1 Upgrade helper for classic sockets.
 * Writes the request bytes, then reads exactly up to CRLF CRLF, parses status + headers,
 * validates 101, and returns the response head.
 */
@Internal
public final class ClassicUpgrade {

    private ClassicUpgrade() {
    }

    public static Response execute(final Socket socket,
                                   final ByteBuffer httpOut,
                                   final String secKey) throws IOException, ProtocolException {
        // write request
        final OutputStream os = socket.getOutputStream();
        if (httpOut.hasArray()) {
            os.write(httpOut.array(), httpOut.position(), httpOut.remaining());
        } else {
            final byte[] tmp = new byte[httpOut.remaining()];
            httpOut.slice().get(tmp);
            os.write(tmp);
        }
        os.flush();

        // read response head bytes (exactly up to CRLFCRLF)
        final byte[] headBytes = readHead(socket.getInputStream());

        // parse status + headers
        final int hdrEnd = indexOf(headBytes, CRLFCRLF);
        final String head = new String(headBytes, 0, hdrEnd, StandardCharsets.ISO_8859_1);
        final String[] lines = head.split("\r\n");
        final String status = lines.length > 0 ? lines[0] : null;

        final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (int i = 1; i < lines.length; i++) {
            final int c = lines[i].indexOf(':');
            if (c > 0) {
                final String name = lines[i].substring(0, c).trim();
                final String value = lines[i].substring(c + 1).trim();
                headers.computeIfAbsent(name, k -> new ArrayList<String>()).add(value);
            }
        }

        // validate 101 Switching Protocols
        Handshake.validate101(status, headers, secKey);

        return new Response(status, headers);
    }

    public static final class Response {
        public final String statusLine;
        public final Map<String, List<String>> headers;

        public Response(final String statusLine, final Map<String, List<String>> headers) {
            this.statusLine = statusLine;
            this.headers = headers;
        }
    }

    private static byte[] readHead(final InputStream is) throws IOException {
        final byte[] buf = new byte[1024];
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        int matched = 0;
        while (true) {
            final int n = is.read(buf);
            if (n < 0) {
                throw new IOException("EOF during handshake");
            }
            for (int i = 0; i < n; i++) {
                final byte b = buf[i];
                bos.write(b);
                if (b == CRLFCRLF[matched]) {
                    matched++;
                    if (matched == CRLFCRLF.length) {
                        // we've just written the final LF; return the exact head bytes
                        return bos.toByteArray();
                    }
                } else {
                    matched = (b == CRLFCRLF[0]) ? 1 : 0;
                }
            }
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
