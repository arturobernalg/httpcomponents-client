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
package org.apache.hc.client5.http.websocket.httpcore;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.core.extension.ExtensionChain;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.reactor.ProtocolUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges HttpCore protocol upgrade to a WebSocket {@link WsHandler}.
 *
 * Creates and installs {@link WsHandler} on the {@link ProtocolIOSession}
 * and notifies {@link WebSocketListener#onOpen(WebSocket)}.
 */
@Internal
public final class WebSocketUpgrader implements ProtocolUpgradeHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketUpgrader.class);

    private final WebSocketListener listener;
    private final WebSocketClientConfig cfg;
    private final ExtensionChain chain;

    // Expose the created WebSocket to callers after upgrade() completes
    private volatile WebSocket webSocket;

    public WebSocketUpgrader(
            final WebSocketListener listener,
            final WebSocketClientConfig cfg,
            final ExtensionChain chain) {
        this.listener = listener;
        this.cfg = cfg;
        this.chain = chain;
    }

    /** Returns the WebSocket created during {@link #upgrade}. */
    public WebSocket getWebSocket() {
        return webSocket;
    }

    @Override
    public void upgrade(final ProtocolIOSession ioSession,
                        final FutureCallback<ProtocolIOSession> callback) {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Installing WsHandler on {}", ioSession);
            }

            // Create and install the IOEventHandler
            final WsHandler handler = new WsHandler(ioSession, listener, cfg, chain);
            ioSession.upgrade(handler);

            // Expose facade and notify listener
            this.webSocket = handler.exposeWebSocket();
            try {
                listener.onOpen(this.webSocket);
            } catch (final Throwable ignore) {
                // listener exceptions must not break the upgrade
            }

            if (callback != null) {
                callback.completed(ioSession);
            }
        } catch (final Exception ex) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("WebSocket upgrade failed", ex);
            }
            if (callback != null) {
                callback.failed(ex);
            } else {
                // Keep behavior consistent with HttpCore: surface the failure
                throw ex;
            }
        }
    }
}
