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

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.core5.io.CloseMode;

/**
 * Public, user-facing WebSocket client that supports try-with-resources and
 * delegates to the internal implementation.
 *
 * <p>Prefer building via {@link #custom()} or use {@link #createDefault()}.</p>
 *
 * @since 5.6
 */
public final class WebSocketClient extends CloseableWebSocketClient {

    private final CloseableWebSocketClient delegate;

    /**
     * Convenience no-arg constructor using sensible defaults.
     * Equivalent to {@code WebSocketClient.createDefault()}.
     */
    public WebSocketClient() {
        this.delegate = WebSocketClientBuilder.create().build();
    }

    /**
     * Package-private constructor for factories/builders.
     */
    WebSocketClient(final CloseableWebSocketClient delegate) {
        this.delegate = delegate;
    }

    /**
     * Create a builder for a custom-configured client.
     */
    public static WebSocketClientBuilder custom() {
        return WebSocketClientBuilder.create();
    }

    /**
     * Create a client instance with default configuration.
     */
    public static WebSocketClient createDefault() {
        return new WebSocketClient(WebSocketClientBuilder.create().build());
    }


    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public CompletableFuture<WebSocket> connect(final URI uri, final WebSocketListener listener) {
        return delegate.connect(uri, listener);
    }

    @Override
    public CompletableFuture<WebSocket> connect(final URI uri, final WebSocketListener listener,
                                                final WebSocketClientConfig cfg) {
        return delegate.connect(uri, listener, cfg);
    }

    @Override
    public void close(final CloseMode closeMode) {
        delegate.close(closeMode);
    }
}
