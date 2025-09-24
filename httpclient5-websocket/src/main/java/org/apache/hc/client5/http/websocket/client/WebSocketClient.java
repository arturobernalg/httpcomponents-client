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
package org.apache.hc.client5.http.websocket.client;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.core.extension.ExtensionChain;
import org.apache.hc.client5.http.websocket.core.extension.PerMessageDeflate;
import org.apache.hc.client5.http.websocket.httpcore.WebSocketUpgrader;
import org.apache.hc.client5.http.websocket.support.AsyncRequesterBootstrap;
import org.apache.hc.client5.http.websocket.support.WebSocketRequester;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.HttpProcessors;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side bootstrap + RFC6455 upgrade handshake and protocol switch.
 *
 * @since 5.6
 */
public final class WebSocketClient implements AutoCloseable {

    private static final ThreadLocal<byte[]> NONCE_BUFFER = ThreadLocal.withInitial(() -> new byte[16]);
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketClient.class);

    private final HttpAsyncRequester requester;
    private final ManagedConnPool<HttpHost, IOSession> connPool;
    private final WebSocketRequester wsRequester;


    /**
     * Creates a client with sensible defaults for transport/pooling.
     */
    public WebSocketClient() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Bootstrapping HttpAsyncRequester (defaults: perRoute=20, maxTotal=100)");
        }
        final AsyncRequesterBootstrap.Result res =
                AsyncRequesterBootstrap.bootstrap()
                        .setIOReactorConfig(IOReactorConfig.custom()
                                .setIoThreadCount(Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()), 4))
                                .setSelectInterval(TimeValue.ofMilliseconds(1))
                                .setSoTimeout(Timeout.ofSeconds(30))
                                .build())
                        .setHttp1Config(Http1Config.DEFAULT)
                        .setCharCodingConfig(CharCodingConfig.DEFAULT)
                        .setHttpProcessor(HttpProcessors.client())
                        .setDefaultMaxPerRoute(20)
                        .setMaxTotal(100)
                        .setTimeToLive(Timeout.ofMinutes(5))
                        .createWithPool();

        this.requester = res.requester;
        this.connPool = res.connPool;
        this.requester.start();
        this.wsRequester = new WebSocketRequester(this.requester, this.connPool);
        if (LOG.isTraceEnabled()) {
            LOG.trace("WebSocketRequester ready (pool class: {})", connPool.getClass().getSimpleName());
        }
    }

    /**
     * Package-level for builder/factory.
     */
    WebSocketClient(final HttpAsyncRequester requester, final ManagedConnPool<HttpHost, IOSession> connPool) {
        this.requester = Args.notNull(requester, "requester");
        this.connPool = Args.notNull(connPool, "connPool");
        this.requester.start();
        this.wsRequester = new WebSocketRequester(this.requester, this.connPool);
    }

    // ------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------

    @Override
    public void close() {
        requester.close(CloseMode.GRACEFUL);
    }

    public CompletableFuture<WebSocket> connect(final URI uri, final WebSocketListener listener, final WebSocketClientConfig cfg) {
        Args.notNull(uri, "uri");
        Args.notNull(listener, "listener");
        Args.notNull(cfg, "cfg");

        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("Scheme must be ws or wss"));
            return f;
        }

        final String scheme = secure ? URIScheme.HTTPS.id : URIScheme.HTTP.id;
        final int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        final String host = Args.notBlank(uri.getHost(), "host");
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        final String fullPath = uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
        final HttpHost target = new HttpHost(scheme, host, port);

        if (LOG.isDebugEnabled()) {
            LOG.debug("connect(): target={} uri={} timeout={} subprotocols={} pmceEnabled={}",
                    target, fullPath,
                    cfg.connectTimeout != null ? cfg.connectTimeout : Timeout.ofSeconds(10),
                    cfg.subprotocols, cfg.perMessageDeflateEnabled);
        }

        final CompletableFuture<WebSocket> result = new CompletableFuture<>();

        wsRequester.connect(target, cfg.connectTimeout != null ? cfg.connectTimeout : Timeout.ofSeconds(10), null,
                new FutureCallback<WebSocketRequester.ProtoEndpoint>() {
                    @Override
                    public void completed(final WebSocketRequester.ProtoEndpoint endpoint) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Endpoint leased: {}", endpoint);
                        }
                        try {
                            final String secKey = randomKey();
                            final BasicHttpRequest req = new BasicHttpRequest("GET", target, fullPath);

                            req.addHeader("Connection", "Upgrade");
                            req.addHeader("Upgrade", "websocket");
                            req.addHeader("Sec-WebSocket-Version", "13");
                            req.addHeader("Sec-WebSocket-Key", secKey);

                            if (!cfg.subprotocols.isEmpty()) {
                                final StringJoiner sj = new StringJoiner(", ");
                                cfg.subprotocols.stream().filter(p -> p != null && !p.isEmpty()).forEach(sj::add);
                                final String offered = sj.toString();
                                if (!offered.isEmpty()) {
                                    req.addHeader("Sec-WebSocket-Protocol", offered);
                                }
                            }

                            if (cfg.perMessageDeflateEnabled) {
                                final StringBuilder ext = new StringBuilder("permessage-deflate");
                                if (cfg.offerServerNoContextTakeover) {
                                    ext.append("; server_no_context_takeover");
                                }
                                if (cfg.offerClientNoContextTakeover) {
                                    ext.append("; client_no_context_takeover");
                                }
                                if (cfg.offerClientMaxWindowBits != null) {
                                    ext.append("; client_max_window_bits=").append(cfg.offerClientMaxWindowBits);
                                }
                                if (cfg.offerServerMaxWindowBits != null) {
                                    ext.append("; server_max_window_bits=").append(cfg.offerServerMaxWindowBits);
                                }
                                req.addHeader("Sec-WebSocket-Extensions", ext.toString());
                            }

                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Dispatching HTTP Upgrade: {} {} with headers:", "GET", fullPath);
                                for (final Header h : req.getHeaders()) {
                                    LOG.debug("  {}: {}", h.getName(), h.getValue());
                                }
                            }

                            final HttpCoreContext ctx = HttpCoreContext.create();
                            final AtomicBoolean done = new AtomicBoolean(false);

                            final AsyncClientExchangeHandler upgrade = new AsyncClientExchangeHandler() {
                                @Override
                                public void releaseResources() {
                                }

                                @Override
                                public void failed(final Exception cause) {
                                    if (done.compareAndSet(false, true)) {
                                        if (LOG.isDebugEnabled()) {
                                            LOG.debug("Upgrade FAILED", cause);
                                        }
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
                                        if (LOG.isDebugEnabled()) {
                                            LOG.debug("Upgrade CANCELLED");
                                        }
                                        try {
                                            endpoint.releaseAndDiscard();
                                        } catch (final Throwable ignore) {
                                        }
                                        result.cancel(true);
                                    }
                                }

                                @Override
                                public void produceRequest(final RequestChannel ch, final HttpContext hc) throws IOException, HttpException {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("Sending upgrade request");
                                    }
                                    ch.sendRequest(req, null, hc);
                                }

                                @Override
                                public int available() {
                                    return 0;
                                }

                                @Override
                                public void produce(final DataStreamChannel channel) {
                                }

                                @Override
                                public void consumeInformation(final HttpResponse response, final HttpContext hc) {
                                    final int code = response.getCode();
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("consumeInformation: {}", code);
                                    }
                                    if (code == HttpStatus.SC_SWITCHING_PROTOCOLS && done.compareAndSet(false, true)) {
                                        finishUpgrade(endpoint, response, secKey, listener, cfg, result);
                                    }
                                }

                                @Override
                                public void consumeResponse(final HttpResponse response, final EntityDetails entity, final HttpContext hc) {
                                    final int code = response.getCode();
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("consumeResponse: {} entity={}", code, entity != null);
                                    }
                                    if (code == HttpStatus.SC_SWITCHING_PROTOCOLS && done.compareAndSet(false, true)) {
                                        finishUpgrade(endpoint, response, secKey, listener, cfg, result);
                                        return;
                                    }
                                    failed(new IllegalStateException("Unexpected status: " + code));
                                }

                                @Override
                                public void updateCapacity(final CapacityChannel capacityChannel) {
                                }

                                @Override
                                public void consume(final ByteBuffer src) {
                                }

                                @Override
                                public void streamEnd(final java.util.List<? extends Header> trailers) {
                                }
                            };

                            endpoint.execute(upgrade, null, ctx);

                        } catch (final Exception ex) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Exception preparing upgrade", ex);
                            }
                            try {
                                endpoint.releaseAndDiscard();
                            } catch (final Throwable ignore) {
                            }
                            result.completeExceptionally(ex);
                        }
                    }

                    @Override
                    public void failed(final Exception ex) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("connect() FAILED", ex);
                        }
                        result.completeExceptionally(ex);
                    }

                    @Override
                    public void cancelled() {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("connect() CANCELLED");
                        }
                        result.cancel(true);
                    }
                });

        return result;
    }

    private void finishUpgrade(final WebSocketRequester.ProtoEndpoint endpoint,
                               final HttpResponse response,
                               final String secKey,
                               final WebSocketListener listener,
                               final WebSocketClientConfig cfg,
                               final CompletableFuture<WebSocket> result) {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Validating 101 Switching Protocols response headers:");
                for (final Header h : response.getHeaders()) {
                    LOG.debug("  {}: {}", h.getName(), h.getValue());
                }
            }

            final String accept = headerValue(response, "Sec-WebSocket-Accept");
            final String expected = expectedAccept(secKey);
            if (!expected.equals(accept)) {
                throw new IllegalStateException("Bad Sec-WebSocket-Accept");
            }
            final String upgrade = headerValue(response, "Upgrade");
            if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade.trim())) {
                throw new IllegalStateException("Missing/invalid Upgrade header: " + upgrade);
            }
            if (!containsToken(response, "Connection", "Upgrade")) {
                throw new IllegalStateException("Missing/invalid Connection header");
            }

            final String proto = headerValue(response, "Sec-WebSocket-Protocol");
            if (proto != null && !proto.isEmpty()) {
                if (cfg.subprotocols.isEmpty()) {
                    throw new IllegalStateException("Server selected subprotocol but none was offered: " + proto);
                }
                boolean matched = false;
                for (final String p : cfg.subprotocols) {
                    if (p.equals(proto)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new IllegalStateException("Server selected subprotocol not offered: " + proto);
                }
            }

            final ExtensionChain chain = new ExtensionChain();
            final String ext = headerValue(response, "Sec-WebSocket-Extensions");
            if (ext != null && !ext.isEmpty()) {
                boolean pmce = false;
                boolean serverNoCtx = false, clientNoCtx = false;
                Integer clientBits = null, serverBits = null;

                for (final String rawExt : ext.split(",")) {
                    final String[] parts = rawExt.trim().split(";");
                    final String name = parts[0].trim();
                    if (!"permessage-deflate".equalsIgnoreCase(name)) {
                        continue;
                    }
                    pmce = true;
                    for (int i = 1; i < parts.length; i++) {
                        final String p = parts[i].trim();
                        final int eq = p.indexOf('=');
                        if (eq < 0) {
                            if ("server_no_context_takeover".equalsIgnoreCase(p)) {
                                serverNoCtx = true;
                            } else if ("client_no_context_takeover".equalsIgnoreCase(p)) {
                                if (!cfg.offerClientNoContextTakeover) {
                                    throw new IllegalStateException("Server sent client_no_context_takeover but it was not offered");
                                }
                                clientNoCtx = true;
                            }
                        } else {
                            final String k = p.substring(0, eq).trim();
                            final String v = p.substring(eq + 1).trim();
                            if ("client_max_window_bits".equalsIgnoreCase(k)) {
                                if (cfg.offerClientMaxWindowBits == null) {
                                    throw new IllegalStateException("Server sent client_max_window_bits but it was not offered");
                                }
                                try {
                                    clientBits = Integer.parseInt(v);
                                } catch (final NumberFormatException ignore) {
                                }
                                if (clientBits == null || !clientBits.equals(cfg.offerClientMaxWindowBits)) {
                                    throw new IllegalStateException("Unsupported client_max_window_bits: " + v);
                                }
                            } else if ("server_max_window_bits".equalsIgnoreCase(k)) {
                                try {
                                    serverBits = Integer.parseInt(v);
                                } catch (final NumberFormatException ignore) {
                                }
                            }
                        }
                    }
                    break;
                }
                if (pmce) {
                    if (!cfg.perMessageDeflateEnabled) {
                        throw new IllegalStateException("Server negotiated PMCE but client disabled it");
                    }
                    chain.add(new PerMessageDeflate(true, serverNoCtx, clientNoCtx, clientBits, serverBits));
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("permessage-deflate negotiated: serverNoCtx={}, clientNoCtx={}, clientBits={}, serverBits={}",
                                serverNoCtx, clientNoCtx, clientBits, serverBits);
                    }
                }
            }

            final ProtocolIOSession ioSession = endpoint.getProtocolIOSession();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Switching ProtocolIOSession to 'websocket' protocol");
            }
            final WebSocketUpgrader upgrader = new WebSocketUpgrader(listener, cfg, chain);
            ioSession.registerProtocol("websocket", upgrader);
            ioSession.switchProtocol("websocket", new FutureCallback<ProtocolIOSession>() {
                @Override
                public void completed(final ProtocolIOSession s) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Protocol switch completed. Notifying onOpen()");
                    }
                    final WebSocket ws = upgrader.getWebSocket();
                    try {
                        listener.onOpen(ws);
                    } catch (final Throwable ignore) {
                    }
                    result.complete(ws);
                }

                @Override
                public void failed(final Exception ex) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Protocol switch FAILED", ex);
                    }
                    try {
                        endpoint.releaseAndDiscard();
                    } catch (final Throwable ignore) {
                    }
                    result.completeExceptionally(ex);
                }

                @Override
                public void cancelled() {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Protocol switch CANCELLED");
                    }
                    try {
                        endpoint.releaseAndDiscard();
                    } catch (final Throwable ignore) {
                    }
                    result.cancel(true);
                }
            });

        } catch (final Exception ex) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("finishUpgrade failed", ex);
            }
            try {
                endpoint.releaseAndDiscard();
            } catch (final Throwable ignore) {
            }
            result.completeExceptionally(ex);
        }
    }

    private static String headerValue(final HttpResponse r, final String name) {
        return r.getFirstHeader(name) != null ? r.getFirstHeader(name).getValue() : null;
    }

    private static String randomKey() {
        final byte[] nonce = NONCE_BUFFER.get();
        ThreadLocalRandom.current().nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    private static String expectedAccept(final String key) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
        return Base64.getEncoder().encodeToString(sha1.digest());
    }

    private static boolean containsToken(final HttpResponse r, final String header, final String token) {
        final Header[] hs = r.getHeaders(header);
        for (final Header h : hs) {
            for (final String part : h.getValue().split(",")) {
                if (part.trim().equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }
}
