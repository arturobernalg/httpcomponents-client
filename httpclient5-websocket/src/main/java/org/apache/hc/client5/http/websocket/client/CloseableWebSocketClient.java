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
import org.apache.hc.core5.io.ModalCloseable;

/**
 * Public base API for WebSocket clients, mirroring {@code CloseableHttpAsyncClient}.
 *
 * @since 5.6
 */
public abstract class CloseableWebSocketClient implements ModalCloseable, AutoCloseable {

    /**
     * Start underlying resources (idempotent).
     */
    public abstract void start();

    /**
     * Connect using the client’s default configuration.
     */
    public abstract CompletableFuture<WebSocket> connect(URI uri, WebSocketListener listener);

    /**
     * Connect with a per-call configuration override.
     */
    public abstract CompletableFuture<WebSocket> connect(URI uri, WebSocketListener listener, WebSocketClientConfig cfg);

    /**
     * Graceful close.
     */
    @Override
    public final void close() {
        close(CloseMode.GRACEFUL);
    }

    /**
     * Close with the given mode.
     */
    @Override
    public abstract void close(CloseMode closeMode);
}
