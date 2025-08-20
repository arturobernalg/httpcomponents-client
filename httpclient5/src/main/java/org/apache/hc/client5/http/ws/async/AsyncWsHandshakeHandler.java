/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.EnumSet;

import org.apache.hc.client5.http.ws.Handshake;
import org.apache.hc.client5.http.ws.websocket.PmdNegotiator;
import org.apache.hc.client5.http.ws.websocket.PmdNegotiator.Param;
import org.apache.hc.client5.http.ws.nio.IOSessionDuplexChannel;
import org.apache.hc.client5.http.ws.nio.PrefetchChannel;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.ws.WsConfig;
import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.DuplexChannel;

/**
 * Handles HTTP/1.1 Upgrade to WebSocket, then installs {@link AsyncWsPumpHandler}.
 */
final class AsyncWsHandshakeHandler implements IOEventHandler {

    private final IOSession session;
    private final ByteBuffer out; // HTTP request
    private final HandshakeState hs;

    AsyncWsHandshakeHandler(final IOSession session, final ByteBuffer out, final HandshakeState hs) {
        this.session = session;
        this.out = out;
        this.hs = hs;
        session.setEvent(SelectionKey.OP_READ);
        session.setEvent(SelectionKey.OP_WRITE);
    }

    @Override
    public void connected(final IOSession session) {
    }

    @Override
    public void outputReady(final IOSession session) throws IOException {
        if (out.hasRemaining()) {
            session.write(out);
        }
        if (!out.hasRemaining()) {
            session.clearEvent(SelectionKey.OP_WRITE);
            session.setEvent(SelectionKey.OP_READ);
        } else {
            session.setEvent(SelectionKey.OP_WRITE);
        }
    }

    @Override
    public void inputReady(final IOSession session, final ByteBuffer src) throws IOException {
        final int n = session.read(hs.in);
        if (n < 0) {
            fail(new IOException("EOF during handshake"));
            return;
        }
        final Http1HandshakeCodec.Response resp = Http1HandshakeCodec.tryParse(hs.in);
        if (resp == null) {
            session.setEvent(SelectionKey.OP_READ);
            return;
        }

        try {
            Handshake.validate101(resp.statusLine, resp.headers, hs.secKey);
        } catch (final ProtocolException e) {
            throw new IOException(e);
        }

        final PmdNegotiator.Result pmd = PmdNegotiator.parseResponse(resp.headers.get("Sec-WebSocket-Extensions"));
        final DuplexChannel base = new IOSessionDuplexChannel(session);
        final DuplexChannel chan = (resp.leftover.length == 0)
                ? base
                : new PrefetchChannel(ByteBuffer.wrap(resp.leftover), base);

        final EnumSet<Param> params = pmd.getParams();
        final WsConfig effective = WsConfig.custom()
                .setAutoPong(hs.cfg.isAutoPong())
                .setStrictTextUtf8(hs.cfg.isStrictTextUtf8())
                .setMaxFramePayload(hs.cfg.getMaxFramePayload())
                .setMaxMessagePayload(hs.cfg.getMaxMessagePayload())
                .enablePerMessageDeflate(pmd.isAccepted())
                .setClientNoContextTakeover(params.contains(Param.CLIENT_NO_CONTEXT_TAKEOVER))
                .setServerNoContextTakeover(params.contains(Param.SERVER_NO_CONTEXT_TAKEOVER))
                .setClientMaxWindowBits(pmd.getClientMaxWindowBits())
                .build();

        final WsEngine engine = new WsEngine(chan, hs.listener, effective, /*clientSide*/ true);
        hs.listener.onOpen(engine);

        session.upgrade(new AsyncWsPumpHandler(engine));
        session.setEvent(SelectionKey.OP_READ);
        session.setEvent(SelectionKey.OP_WRITE);

        hs.promise.complete(engine);
    }

    @Override
    public void timeout(final IOSession session, final Timeout timeout) throws IOException {
        fail(new IOException("Handshake timeout"));
    }

    @Override
    public void exception(final IOSession session, final Exception cause) {
        fail(cause instanceof IOException ? (IOException) cause : new IOException(cause));
    }

    @Override
    public void disconnected(final IOSession session) {
        fail(new IOException("Disconnected during handshake"));
    }

    private void fail(final IOException ex) {
        try {
            session.close();
        } catch (final Exception ignore) {
        }
        hs.promise.completeExceptionally(ex);
    }
}
