/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.async;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;

/**
 * Mutable state kept during the HTTP/1.1 upgrade.
 */
final class HandshakeState {
    final String secKey;
    final WebSocketListener listener;
    final WsConfig cfg;
    final CompletableFuture<WebSocketSession> promise;
    final ByteBuffer in = ByteBuffer.allocate(8192);

    HandshakeState(final String secKey,
                   final WebSocketListener listener,
                   final WsConfig cfg,
                   final CompletableFuture<WebSocketSession> promise) {
        this.secKey = secKey;
        this.listener = listener;
        this.cfg = cfg;
        this.promise = promise;
    }
}
