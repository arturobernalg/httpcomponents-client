/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.async;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.ws.WebSocketClient;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;

/**
 * Adapts {@link AsyncWebSocketClient} to the common {@link WebSocketClient} facade.
 * Requires an {@link IOSessionSupplier} to obtain a connected IOSession for a given URI.
 */
public final class AsyncWebSocketClientAdapter implements WebSocketClient {

    @FunctionalInterface
    public interface IOSessionSupplier {
        IOSession connect(URI uri) throws IOException;
    }

    private final AsyncWebSocketClient delegate;
    private final IOSessionSupplier sessionSupplier;

    public AsyncWebSocketClientAdapter(final AsyncWebSocketClient delegate,
                                       final IOSessionSupplier sessionSupplier) {
        this.delegate = delegate;
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public CompletableFuture<WebSocketSession> open(
            final URI uri,
            final WebSocketListener listener,
            final WsConfig cfg,
            final String origin,
            final List<String> subprotocols,
            final Map<String, String> extraHeaders) {

        final CompletableFuture<WebSocketSession> promise = new CompletableFuture<>();
        final IOSession ioSession;
        try {
            ioSession = sessionSupplier.connect(uri);
        } catch (final IOException ex) {
            promise.completeExceptionally(ex);
            return promise;
        }
        delegate.upgradeOn(ioSession, uri, listener, cfg, origin, subprotocols, extraHeaders)
                .whenComplete((ws, ex) -> {
                    if (ex != null) promise.completeExceptionally(ex);
                    else promise.complete(ws);
                });
        return promise;
    }
}
