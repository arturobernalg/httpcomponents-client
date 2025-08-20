/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;

public interface WebSocketClient {

    default CompletableFuture<WebSocketSession> open(
            final URI uri,
            final WebSocketListener listener,
            final WsConfig cfg) {
        return open(uri, listener, cfg, null, Collections.emptyList(), Collections.emptyMap());
    }

    CompletableFuture<WebSocketSession> open(
            URI uri,
            WebSocketListener listener,
            WsConfig cfg,
            String origin,
            List<String> subprotocols,
            Map<String, String> extraHeaders);
}
