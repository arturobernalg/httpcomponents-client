/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.async;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.ws.Handshake;
import org.apache.hc.client5.http.ws.websocket.PmdNegotiator;
import org.apache.hc.client5.http.ws.websocket.PmdNegotiator.Param;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;

/**
 * Reactor-style (non-blocking) WebSocket client that upgrades an existing {@link IOSession}.
 * Public facade only; actual handshake logic lives in {@code async.internal}.
 */
public final class AsyncWebSocketClient {

    /**
     * Basic upgrade: URI + core config.
     */
    public CompletableFuture<WebSocketSession> upgradeOn(
            final IOSession ioSession,
            final URI uri,
            final WebSocketListener listener,
            final WsConfig cfg) {
        return upgradeOn(ioSession, uri, listener, cfg,
                null, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Full upgrade with Origin, subprotocols, and extra headers.
     */
    public CompletableFuture<WebSocketSession> upgradeOn(
            final IOSession ioSession,
            final URI uri,
            final WebSocketListener listener,
            final WsConfig cfg,
            final String origin,
            final List<String> subprotocols,
            final Map<String, String> extraHeaders) {

        final CompletableFuture<WebSocketSession> result = new CompletableFuture<>();

        // PMD request header via EnumSet<Param>
        final EnumSet<Param> pmdParams = EnumSet.noneOf(Param.class);
        if (cfg.isClientNoContextTakeover()) {
            pmdParams.add(Param.CLIENT_NO_CONTEXT_TAKEOVER);
        }
        if (cfg.isServerNoContextTakeover()) {
            pmdParams.add(Param.SERVER_NO_CONTEXT_TAKEOVER);
        }
        final String pmdReq = PmdNegotiator.buildRequestHeader(
                cfg.isPerMessageDeflateEnabled(), pmdParams, cfg.getClientMaxWindowBits());

        final Handshake.Request req = Handshake.buildRequest(
                uri,
                origin,
                subprotocols != null ? subprotocols : Collections.emptyList(),
                pmdReq,
                extraHeaders != null ? extraHeaders : Collections.emptyMap());

        final ByteBuffer httpOut = Http1HandshakeCodec.encodeRequest(req.methodLine, req.headers);
        final HandshakeState hs = new HandshakeState(req.secKey, listener, cfg, result);

        ioSession.upgrade(new AsyncWsHandshakeHandler(ioSession, httpOut, hs));
        return result;
    }
}
