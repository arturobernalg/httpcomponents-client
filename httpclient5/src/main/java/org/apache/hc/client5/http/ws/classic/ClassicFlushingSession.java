/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.classic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.engine.WsEngine;

/**
 * Proxy session that nudges writes after each enqueue to keep the pipe flowing in classic mode.
 */
public final class ClassicFlushingSession implements WebSocketSession {

    private final WsEngine engine;

    public ClassicFlushingSession(final WsEngine engine) {
        this.engine = engine;
    }

    @Override
    public CompletableFuture<Void> sendText(final CharSequence data, final boolean last) {
        final CompletableFuture<Void> f = engine.sendText(data, last);
        try {
            engine.onWritable();
        } catch (final IOException ignore) {
        }
        return f;
    }

    @Override
    public CompletableFuture<Void> sendBinary(final ByteBuffer data, final boolean last) {
        final CompletableFuture<Void> f = engine.sendBinary(data, last);
        try {
            engine.onWritable();
        } catch (final IOException ignore) {
        }
        return f;
    }

    @Override
    public CompletableFuture<Void> sendPing(final ByteBuffer data) {
        final CompletableFuture<Void> f = engine.sendPing(data);
        try {
            engine.onWritable();
        } catch (final IOException ignore) {
        }
        return f;
    }

    @Override
    public CompletableFuture<Void> sendPong(final ByteBuffer data) {
        final CompletableFuture<Void> f = engine.sendPong(data);
        try {
            engine.onWritable();
        } catch (final IOException ignore) {
        }
        return f;
    }

    @Override
    public CompletableFuture<Void> close(final int code, final String reason) {
        final CompletableFuture<Void> f = engine.close(code, reason);
        try {
            engine.onWritable();
        } catch (final IOException ignore) {
        }
        return f;
    }

    @Override
    public boolean isOpen() {
        return engine.isOpen();
    }

    @Override
    public void close() {
        engine.close();
    }
}
