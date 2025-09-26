/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 */
package org.apache.hc.client5.http.websocket.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.support.WebSocketRequester;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.util.Args;

/**
 * Internal base implementation that manages lifecycle and delegates to transport.
 *
 * <p><b>NOTE:</b> This class is intentionally package-private and does <em>not</em>
 * extend the public {@link CloseableWebSocketClient}. The public client is a
 * final wrapper that composes an instance of this class.</p>
 */
abstract class InternalAbstractWebSocketClient implements ModalCloseable {

    enum State {NEW, RUNNING, CLOSED}

    protected final WebSocketRequester wsRequester;
    protected final WebSocketClientConfig defaultConfig;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    protected InternalAbstractWebSocketClient(
            final WebSocketRequester wsRequester,
            final WebSocketClientConfig defaultConfig) {
        this.wsRequester = Args.notNull(wsRequester, "wsRequester");
        this.defaultConfig = defaultConfig != null ? defaultConfig : WebSocketClientConfig.custom().build();
    }

    /**
     * Subclass should start underlying requester/reactor.
     */
    protected abstract void doStart();

    /**
     * Subclass should close underlying requester/reactor + pool.
     */
    protected abstract void doClose(CloseMode mode);

    /**
     * Starts underlying resources (idempotent).
     */
    public final void start() {
        if (state.compareAndSet(State.NEW, State.RUNNING)) {
            doStart();
        }
    }

    /**
     * Close with the given mode.
     */
    @Override
    public final void close(final CloseMode mode) {
        if (state.getAndSet(State.CLOSED) != State.CLOSED) {
            doClose(mode != null ? mode : CloseMode.GRACEFUL);
        }
    }

    /**
     * Connect using the client’s default configuration.
     */
    public final CompletableFuture<WebSocket> connect(final URI uri, final WebSocketListener listener) {
        return connect(uri, listener, defaultConfig);
    }

    /**
     * Connect with a per-call configuration override.
     */
    public final CompletableFuture<WebSocket> connect(
            final URI uri, final WebSocketListener listener, final WebSocketClientConfig cfg) {
        if (state.get() == State.NEW) start();
        if (state.get() == State.CLOSED) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Client is closed"));
            return f;
        }
        Args.notNull(uri, "uri");
        Args.notNull(listener, "listener");
        Args.notNull(cfg, "cfg");
        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("Scheme must be ws or wss"));
            return f;
        }
        return doConnect(uri, listener, cfg);
    }

    /**
     * Concrete subclass implements the actual upgrade logic.
     */
    protected abstract CompletableFuture<WebSocket> doConnect(URI uri, WebSocketListener listener, WebSocketClientConfig cfg);
}
