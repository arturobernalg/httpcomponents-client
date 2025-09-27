package org.apache.hc.client5.http.websocket.client.protocol;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * RFC 8441 (HTTP/2 Extended CONNECT) placeholder.
 * No-args ctor (matches your build error). Falls back to H1.
 */
public final class H2WebSocketProtocol implements WebSocketProtocol {

    public static final class H2NotAvailable extends RuntimeException {
        public H2NotAvailable(final String msg) {
            super(msg);
        }
    }

    public H2WebSocketProtocol() {
    }

    @Override
    public CompletableFuture<WebSocket> connect(
            final URI uri,
            final WebSocketListener listener,
            final WebSocketClientConfig cfg,
            final HttpContext context) {
        final CompletableFuture<WebSocket> f = new CompletableFuture<>();
        f.completeExceptionally(new H2NotAvailable("HTTP/2 Extended CONNECT not wired yet"));
        return f;
    }
}
