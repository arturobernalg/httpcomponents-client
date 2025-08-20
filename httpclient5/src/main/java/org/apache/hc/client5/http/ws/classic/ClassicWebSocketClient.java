/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.classic;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hc.client5.http.ws.Handshake;
import org.apache.hc.client5.http.ws.WebSocketClient;
import org.apache.hc.client5.http.ws.async.Http1HandshakeCodec;
import org.apache.hc.client5.http.ws.websocket.PmdNegotiator;
import org.apache.hc.client5.http.ws.websocket.PmdNegotiator.Param;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;
import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.BlockingStreamsChannel;

/**
 * Classic (blocking) WebSocket client with pluggable transport connector.
 * Uses {@link JdkTlsConnectorBuilder} by default. Synchronous connect; no sleeps or busy-waits.
 */
public final class ClassicWebSocketClient implements WebSocketClient {

    private final WebSocketConnector connector;
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Default: JSSE TLS with system defaults.
     */
    public ClassicWebSocketClient() {
        this(JdkTlsConnectorBuilder.create().build());
    }

    public ClassicWebSocketClient(final WebSocketConnector connector) {
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    /**
     * Basic connect: URI + core config. Returns after 101/upgrade and onOpen().
     */
    public WebSocketSession connect(final URI uri,
                                    final WebSocketListener listener,
                                    final WsConfig cfg) throws IOException, ProtocolException {
        return connect(uri, listener, cfg, null, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Full connect with optional Origin, subprotocols and extra headers.
     */
    public WebSocketSession connect(final URI uri,
                                    final WebSocketListener listener,
                                    final WsConfig cfg,
                                    final String origin,
                                    final List<String> subprotocols,
                                    final Map<String, String> extraHeaders) throws IOException, ProtocolException {

        final Socket socket = connector.connect(uri);
        sockets.add(socket);

        // PMD request header via enum flags
        final EnumSet<Param> pmdParams = EnumSet.noneOf(Param.class);
        if (cfg.isClientNoContextTakeover()) {
            pmdParams.add(Param.CLIENT_NO_CONTEXT_TAKEOVER);
        }
        if (cfg.isServerNoContextTakeover()) {
            pmdParams.add(Param.SERVER_NO_CONTEXT_TAKEOVER);
        }
        final String pmdReq = PmdNegotiator.buildRequestHeader(
                cfg.isPerMessageDeflateEnabled(), pmdParams, cfg.getClientMaxWindowBits());

        // Build HTTP Upgrade request
        final Handshake.Request req = Handshake.buildRequest(
                uri,
                origin,
                subprotocols != null ? subprotocols : Collections.emptyList(),
                pmdReq,
                extraHeaders != null ? extraHeaders : Collections.emptyMap());

        final ByteBuffer httpOut = Http1HandshakeCodec.encodeRequest(req.methodLine, req.headers);
        final ClassicUpgrade.Response resp = ClassicUpgrade.execute(socket, httpOut, req.secKey);

        final PmdNegotiator.Result pmd = PmdNegotiator.parseResponse(resp.headers.get("Sec-WebSocket-Extensions"));

        // Construct effective core cfg (respect negotiated PMD)
        final WsConfig effective = WsConfig.custom()
                .setAutoPong(cfg.isAutoPong())
                .setStrictTextUtf8(cfg.isStrictTextUtf8())
                .setMaxFramePayload(cfg.getMaxFramePayload())
                .setMaxMessagePayload(cfg.getMaxMessagePayload())
                .enablePerMessageDeflate(pmd.isAccepted())
                .setClientNoContextTakeover(pmd.getParams().contains(Param.CLIENT_NO_CONTEXT_TAKEOVER))
                .setServerNoContextTakeover(pmd.getParams().contains(Param.SERVER_NO_CONTEXT_TAKEOVER))
                .setClientMaxWindowBits(pmd.getClientMaxWindowBits())
                .build();

        final WsEngine engine = new WsEngine(
                new BlockingStreamsChannel(socket.getInputStream(), socket.getOutputStream()),
                listener,
                effective,
                /*clientSide*/ true);

        listener.onOpen(engine);

        // Start the blocking reader pump on a daemon thread
        final Thread reader = new Thread(new ClassicReaderPump(engine, socket, () -> sockets.remove(socket)), "ws-classic-reader");
        reader.setDaemon(true);
        reader.start();

        // Return a session that flushes after each enqueue
        return new ClassicFlushingSession(engine);
    }

    /**
     * Optional convenience: closes any sockets opened by this client.
     */
    public void close() {
        for (final Socket s : sockets) {
            try {
                s.close();
            } catch (final IOException ignore) {
            }
        }
        sockets.clear();
    }

    @Override
    public CompletableFuture<WebSocketSession> open(final URI uri,
                                                    final WebSocketListener listener,
                                                    final WsConfig cfg,
                                                    final String origin,
                                                    final List<String> subprotocols,
                                                    final Map<String, String> extraHeaders) {
        try {
            final WebSocketSession s = connect(uri, listener, cfg, origin, subprotocols, extraHeaders);
            return CompletableFuture.completedFuture(s);
        } catch (final Exception ex) {
            final CompletableFuture<WebSocketSession> f = new CompletableFuture<>();
            f.completeExceptionally(ex);
            return f;
        }
    }
}
