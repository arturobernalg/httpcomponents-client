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
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.websocket.client.impl;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.impl.protocol.Http1UpgradeProtocol;
import org.apache.hc.client5.http.websocket.client.impl.protocol.Http2ExtendedConnectProtocol;
import org.apache.hc.client5.http.websocket.client.impl.protocol.WebSocketProtocolStrategy;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal internal WS client: owns requester + pool, no extra closeables.
 */
abstract class InternalWebSocketClientBase extends AbstractWebSocketClient {

    private final WebSocketClientConfig defaultConfig;
    private final ManagedConnPool<HttpHost, IOSession> connPool;

    private final WebSocketProtocolStrategy h1;
    private final WebSocketProtocolStrategy h2; // may be null

    InternalWebSocketClientBase(
            final HttpAsyncRequester h1Requester,
            final HttpAsyncRequester h2RequesterOrNull,
            final ManagedConnPool<HttpHost, IOSession> connPool,
            final WebSocketClientConfig defaultConfig,
            final ThreadFactory threadFactory) {
        super(Args.notNull(h1Requester, "h1Requester"), threadFactory);
        this.connPool = Args.notNull(connPool, "connPool");
        this.defaultConfig = defaultConfig != null ? defaultConfig : WebSocketClientConfig.custom().build();

        this.h1 = new Http1UpgradeProtocol(h1Requester, connPool);
        this.h2 = h2RequesterOrNull != null ? new Http2ExtendedConnectProtocol(h2RequesterOrNull, connPool) : null;
    }

    @Override
    protected CompletableFuture<WebSocket> doConnect(
            final URI uri,
            final WebSocketListener listener,
            final WebSocketClientConfig cfgOrNull,
            final HttpContext context) {

        final WebSocketClientConfig cfg = cfgOrNull != null ? cfgOrNull : defaultConfig;

        // Selection policy:
        // - requireH2 => must use H2
        // - allowH2ExtendedConnect => try H2 first, fall back to H1 if allowed
        // - preferH2 => try H2, fall back to H1
        // - otherwise use H1
        final boolean mustH2 = cfg.isRequireH2();
        final boolean tryH2 = cfg.isAllowH2ExtendedConnect() || cfg.isPreferH2();

        if (mustH2) {
            if (h2 == null) {
                final CompletableFuture<WebSocket> f = new CompletableFuture<>();
                f.completeExceptionally(new Http2ExtendedConnectProtocol.H2NotAvailable("H2 not configured"));
                return f;
            }
            return h2.connect(uri, listener, cfg, context);
        }

        if (tryH2 && h2 != null) {
            final CompletableFuture<WebSocket> out = new CompletableFuture<>();
            h2.connect(uri, listener, cfg, context).whenComplete((ws, ex) -> {
                if (ws != null) {
                    out.complete(ws);
                } else if (!cfg.isDisableH1Fallback()) {
                    h1.connect(uri, listener, cfg, context).whenComplete((ws2, ex2) -> {
                        if (ws2 != null) out.complete(ws2);
                        else out.completeExceptionally(ex2 != null ? ex2 : ex != null ? ex : new IllegalStateException("Connect failed"));
                    });
                } else {
                    out.completeExceptionally(ex != null ? ex : new IllegalStateException("H2 connect failed"));
                }
            });
            return out;
        }

        return h1.connect(uri, listener, cfg, context);
    }

    @Override
    protected void internalClose(final CloseMode closeMode) {
        try { connPool.close(closeMode != null ? closeMode : CloseMode.GRACEFUL); }
        catch (final Exception ignore) { }
    }
}

