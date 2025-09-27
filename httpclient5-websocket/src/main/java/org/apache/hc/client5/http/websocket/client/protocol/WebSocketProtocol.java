package org.apache.hc.client5.http.websocket.client.protocol;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * Minimal pluggable protocol strategy. One impl for H1 (RFC6455),
 * one for H2 Extended CONNECT (RFC8441).
 */
public interface WebSocketProtocol {

    /**
     * Establish a WebSocket connection using a specific HTTP transport/protocol.
     *
     * @param uri      ws:// or wss:// target
     * @param listener user listener for WS events
     * @param cfg      client config (timeouts, subprotocols, PMCE offer, etc.)
     * @param context  optional HttpContext (may be {@code null})
     * @return future completing with a connected {@link WebSocket} or exceptionally on failure
     */
    CompletableFuture<WebSocket> connect(
            URI uri,
            WebSocketListener listener,
            WebSocketClientConfig cfg,
            HttpContext context);
}
