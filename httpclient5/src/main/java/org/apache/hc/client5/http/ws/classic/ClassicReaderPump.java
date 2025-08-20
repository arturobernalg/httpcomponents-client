/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.classic;

import java.io.IOException;
import java.net.Socket;

import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.ws.engine.WsEngine;

/**
 * Blocking read loop for classic sockets; closes socket on exit.
 */
@Internal
public final class ClassicReaderPump implements Runnable {

    private final WsEngine engine;
    private final Socket socket;
    private final Runnable onClosed;

    public ClassicReaderPump(final WsEngine engine, final Socket socket, final Runnable onClosed) {
        this.engine = engine;
        this.socket = socket;
        this.onClosed = onClosed;
    }

    @Override
    public void run() {
        try {
            while (engine.isOpen()) {
                engine.onReadable();
            }
        } catch (final Exception ignore) {
        } finally {
            try {
                socket.close();
            } catch (final IOException ignore) {
            }
            if (onClosed != null) {
                try {
                    onClosed.run();
                } catch (final Exception ignore) {
                }
            }
        }
    }
}
