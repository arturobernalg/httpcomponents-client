/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.async;

import java.io.IOException;

import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.ws.engine.WsEngine;

/**
 * Forwards reactor readiness to the {@link WsEngine}.
 */
final class AsyncWsPumpHandler implements IOEventHandler {

    private final WsEngine engine;

    AsyncWsPumpHandler(final WsEngine engine) {
        this.engine = engine;
    }

    @Override
    public void connected(final IOSession session) {
    }

    @Override
    public void inputReady(final IOSession session, final java.nio.ByteBuffer src) throws IOException {
        engine.onReadable();
    }

    @Override
    public void outputReady(final IOSession session) throws IOException {
        engine.onWritable();
    }

    @Override
    public void timeout(final IOSession session, final Timeout t) throws IOException {
        session.close();
    }

    @Override
    public void exception(final IOSession session, final Exception cause) {
        engine.close();
    }

    @Override
    public void disconnected(final IOSession session) {
        engine.close();
    }
}
