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
package org.apache.hc.client5.http.websocket.client.impl.protocol;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.impl.connector.WebSocketEndpointConnector;
import org.apache.hc.client5.http.websocket.core.extension.ExtensionChain;
import org.apache.hc.client5.http.websocket.core.extension.PerMessageDeflate;
import org.apache.hc.client5.http.websocket.transport.WebSocketUpgrader;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RFC 8441 (HTTP/2 Extended CONNECT for WebSockets).
 * <p>
 * Requires an H2-capable requester + pool. On success (200),
 * the H2 stream becomes a raw tunnel carrying RFC6455 frames;
 * we switch that stream's ProtocolIOSession to the WebSocket handler.
 */
@Internal
public final class Http2ExtendedConnectProtocol implements WebSocketProtocolStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(Http2ExtendedConnectProtocol.class);

    private final org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester h2Requester;
    private final ManagedConnPool<HttpHost, IOSession> h2ConnPool;

    public static final class H2NotAvailable extends RuntimeException {
        public H2NotAvailable(final String msg) {
            super(msg);
        }
    }

    public Http2ExtendedConnectProtocol(
            final org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester h2Requester,
            final ManagedConnPool<HttpHost, IOSession> h2ConnPool) {
        this.h2Requester = Args.notNull(h2Requester, "h2Requester");
        this.h2ConnPool = Args.notNull(h2ConnPool, "h2ConnPool");
    }

    @Override
    public CompletableFuture<WebSocket> connect(
            final URI uri,
            final WebSocketListener listener,
            final WebSocketClientConfig cfg,
            final HttpContext context) {

        Args.notNull(uri, "uri");
        Args.notNull(listener, "listener");
        Args.notNull(cfg, "cfg");

        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("Scheme must be ws or wss"));
            return f;
        }

        // IMPORTANT: require an H2 pipeline on the client
        if (h2Requester == null || h2ConnPool == null) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new H2NotAvailable("HTTP/2 requester/pool not configured"));
            return f;
        }

        final String scheme = secure ? URIScheme.HTTPS.id : URIScheme.HTTP.id;
        final int port = uri.getPort() > 0 ? uri.getPort() : secure ? 443 : 80;
        final String host = Args.notBlank(uri.getHost(), "host");
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        final String fullPath = uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
        final HttpHost target = new HttpHost(scheme, host, port);

        final CompletableFuture<WebSocket> result = new CompletableFuture<>();

        final WebSocketEndpointConnector wsRequester = new WebSocketEndpointConnector(h2Requester, h2ConnPool);
        final Timeout timeout = cfg.getConnectTimeout() != null ? cfg.getConnectTimeout() : Timeout.ofSeconds(10);

        wsRequester.connect(target, timeout, null, new FutureCallback<WebSocketEndpointConnector.ProtoEndpoint>() {
            @Override
            public void completed(final WebSocketEndpointConnector.ProtoEndpoint endpoint) {
                try {
                    final String secKey = randomKey();

                    // Build RFC 8441 Extended CONNECT request
                    final BasicHttpRequest req = new BasicHttpRequest("CONNECT", target, fullPath);
                    // Pseudo-headers are derived by HttpCore from target + path; add :protocol via helper header
                    // HttpCore maps "Protocol" header to :protocol
                    req.addHeader("Protocol", "websocket");
                    req.addHeader("Sec-WebSocket-Version", "13");
                    req.addHeader("Sec-WebSocket-Key", secKey);

                    // subprotocols
                    if (cfg.getSubprotocols() != null && !cfg.getSubprotocols().isEmpty()) {
                        final StringJoiner sj = new StringJoiner(", ");
                        for (final String p : cfg.getSubprotocols()) {
                            if (p != null && !p.isEmpty()) sj.add(p);
                        }
                        final String offered = sj.toString();
                        if (!offered.isEmpty()) {
                            req.addHeader("Sec-WebSocket-Protocol", offered);
                        }
                    }

                    // PMCE offer (same as H1; server echoes if accepted)
                    if (cfg.isPerMessageDeflateEnabled()) {
                        final StringBuilder ext = new StringBuilder("permessage-deflate");
                        if (cfg.isOfferServerNoContextTakeover()) ext.append("; server_no_context_takeover");
                        if (cfg.isOfferClientNoContextTakeover()) ext.append("; client_no_context_takeover");
                        if (cfg.getOfferClientMaxWindowBits() != null) {
                            ext.append("; client_max_window_bits=").append(cfg.getOfferClientMaxWindowBits());
                        }
                        if (cfg.getOfferServerMaxWindowBits() != null) {
                            ext.append("; server_max_window_bits=").append(cfg.getOfferServerMaxWindowBits());
                        }
                        req.addHeader("Sec-WebSocket-Extensions", ext.toString());
                    }

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Dispatching H2 Extended CONNECT :protocol=websocket {} with headers:", fullPath);
                        for (final Header h : req.getHeaders()) {
                            LOG.debug("  {}: {}", h.getName(), h.getValue());
                        }
                    }

                    final AtomicBoolean done = new AtomicBoolean(false);

                    final AsyncClientExchangeHandler xh = new AsyncClientExchangeHandler() {
                        @Override
                        public void releaseResources() {
                        }

                        @Override
                        public void failed(final Exception cause) {
                            if (done.compareAndSet(false, true)) {
                                try {
                                    endpoint.releaseAndDiscard();
                                } catch (final Throwable ignore) {
                                }
                                result.completeExceptionally(cause);
                            }
                        }

                        @Override
                        public void cancel() {
                            if (done.compareAndSet(false, true)) {
                                try {
                                    endpoint.releaseAndDiscard();
                                } catch (final Throwable ignore) {
                                }
                                result.cancel(true);
                            }
                        }

                        @Override
                        public void produceRequest(final RequestChannel ch, final HttpContext hc)
                                throws java.io.IOException, HttpException {
                            ch.sendRequest(req, null, hc);
                        }

                        @Override
                        public int available() {
                            return 0;
                        }

                        @Override
                        public void produce(final DataStreamChannel channel) { /* tunnel starts after 200 */ }

                        @Override
                        public void updateCapacity(final CapacityChannel capacityChannel) {
                        }

                        @Override
                        public void consume(final ByteBuffer src) { /* will be handled by WS after switch */ }

                        @Override
                        public void streamEnd(final java.util.List<? extends Header> trailers) {
                        }

                        @Override
                        public void consumeInformation(final HttpResponse response, final HttpContext hc) {
                            // H2 can send interim; ignore
                        }

                        @Override
                        public void consumeResponse(final HttpResponse response, final EntityDetails entity, final HttpContext hc) {
                            final int code = response.getCode();
                            if (code == HttpStatus.SC_OK && done.compareAndSet(false, true)) {
                                finishTunnel(endpoint, response, secKey, listener, cfg, result);
                                return;
                            }
                            failed(new IllegalStateException("Unexpected status for Extended CONNECT: " + code));
                        }
                    };

                    endpoint.execute(xh, null, context);
                } catch (final Exception ex) {
                    try {
                        endpoint.releaseAndDiscard();
                    } catch (final Throwable ignore) {
                    }
                    result.completeExceptionally(ex);
                }
            }

            @Override
            public void failed(final Exception ex) {
                result.completeExceptionally(ex);
            }

            @Override
            public void cancelled() {
                result.cancel(true);
            }
        });

        return result;
    }

    private void finishTunnel(
            final WebSocketEndpointConnector.ProtoEndpoint endpoint,
            final HttpResponse response,
            final String secKey,
            final WebSocketListener listener,
            final WebSocketClientConfig cfg,
            final CompletableFuture<WebSocket> result) {
        try {
            // Validate WebSocket handshake headers inside CONNECT
            final String accept = headerValue(response, "Sec-WebSocket-Accept");
            final String expected = expectedAccept(secKey);
            if (!expected.equals(accept)) {
                throw new IllegalStateException("Bad Sec-WebSocket-Accept");
            }

            final String proto = headerValue(response, "Sec-WebSocket-Protocol");
            if (proto != null && !proto.isEmpty()) {
                boolean matched = false;
                if (cfg.getSubprotocols() != null) {
                    for (final String p : cfg.getSubprotocols()) {
                        if (p.equals(proto)) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (!matched) {
                    throw new IllegalStateException("Server selected subprotocol not offered: " + proto);
                }
            }

            // Extensions (permessage-deflate)
            final ExtensionChain chain = new ExtensionChain();
            final String ext = headerValue(response, "Sec-WebSocket-Extensions");
            if (ext != null && !ext.isEmpty()) {
                boolean pmce = false, serverNoCtx = false, clientNoCtx = false;
                Integer clientBits = null, serverBits = null;

                for (final String raw : ext.split(",")) {
                    final String[] parts = raw.trim().split(";");
                    if (!"permessage-deflate".equalsIgnoreCase(parts[0].trim())) continue;
                    pmce = true;
                    for (int i = 1; i < parts.length; i++) {
                        final String p = parts[i].trim();
                        final int eq = p.indexOf('=');
                        if (eq < 0) {
                            if ("server_no_context_takeover".equalsIgnoreCase(p)) serverNoCtx = true;
                            else if ("client_no_context_takeover".equalsIgnoreCase(p)) clientNoCtx = true;
                        } else {
                            final String k = p.substring(0, eq).trim(), v = p.substring(eq + 1).trim();
                            try {
                                if ("client_max_window_bits".equalsIgnoreCase(k)) clientBits = Integer.parseInt(v);
                                else if ("server_max_window_bits".equalsIgnoreCase(k)) serverBits = Integer.parseInt(v);
                            } catch (final NumberFormatException ignore) {
                            }
                        }
                    }
                    break;
                }

                if (pmce) {
                    if (!cfg.isPerMessageDeflateEnabled()) {
                        throw new IllegalStateException("Server negotiated PMCE but client disabled it");
                    }
                    chain.add(new PerMessageDeflate(true, serverNoCtx, clientNoCtx, clientBits, serverBits));
                }
            }

            // Switch the *stream* to the WebSocket protocol handler.
            final ProtocolIOSession connSession = endpoint.getProtocolIOSession();
            final WebSocketUpgrader upgrader = new WebSocketUpgrader(listener, cfg, chain);

            // Register + switch by stream-id "tunnel" – on H2 the underlying implementation
            // exposes the CONNECTed stream as a child ProtocolIOSession behind the scenes.
            connSession.registerProtocol("websocket", upgrader);
            connSession.switchProtocol("websocket", new FutureCallback<ProtocolIOSession>() {
                @Override
                public void completed(final ProtocolIOSession s) {
                    s.setSocketTimeout(Timeout.DISABLED);
                    final WebSocket ws = upgrader.getWebSocket();
                    try {
                        listener.onOpen(ws);
                    } catch (final Throwable ignore) {
                    }
                    result.complete(ws);
                }

                @Override
                public void failed(final Exception ex) {
                    result.completeExceptionally(ex);
                }

                @Override
                public void cancelled() {
                    result.cancel(true);
                }
            });

        } catch (final Exception ex) {
            result.completeExceptionally(ex);
        }
    }

    private static String headerValue(final HttpResponse r, final String name) {
        final Header h = r.getFirstHeader(name);
        return h != null ? h.getValue() : null;
    }

    private static String randomKey() {
        final byte[] nonce = new byte[16];
        ThreadLocalRandom.current().nextBytes(nonce);
        return java.util.Base64.getEncoder().encodeToString(nonce);
    }

    private static String expectedAccept(final String key) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
        return java.util.Base64.getEncoder().encodeToString(sha1.digest());
    }
}
