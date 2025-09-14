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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.impl.bootstrap.AsyncRequesterBootstrap;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncClientEndpoint;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-level asynchronous WebSocket client built on Apache HttpCore 5.
 *
 * <p>Performs an HTTP/1.1 Upgrade to WebSocket (RFC&nbsp;6455), validates
 * handshake headers, optionally offers subprotocols and the permessage-deflate
 * extension (RFC&nbsp;7692), and then installs a WebSocket protocol handler
 * on the live {@link ProtocolIOSession}. Application messages are delivered
 * via {@link WebSocketListener} callbacks.</p>
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>The client owns an internal {@link HttpAsyncRequester} (I/O reactor).</li>
 *   <li>Use try-with-resources or call {@link #close()} to shut it down.</li>
 *   <li>Each {@link #connect} returns a {@link CompletableFuture} that completes
 *       with a {@link WebSocket} on successful upgrade.</li>
 * </ul>
 *
 * <h3>Thread-safety</h3>
 * <p>Instances are thread-safe. Multiple concurrent {@link #connect} calls are supported.</p>
 *
 * <h3>Compliance</h3>
 * <ul>
 *   <li>RFC&nbsp;6455 handshake checks: {@code Host}, {@code Upgrade}, {@code Connection},
 *       {@code Sec-WebSocket-Key}/Accept, and (optionally) {@code Sec-WebSocket-Protocol}.</li>
 *   <li>permessage-deflate (RFC&nbsp;7692) offer/verify: strictly validates the server’s selection.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * try (WebSocketClient client = new WebSocketClient()) {
 *   WebSocketClientConfig cfg = WebSocketClientConfig.custom()
 *       .enablePerMessageDeflate(true)
 *       .addSubprotocol("chat")
 *       .build();
 *
 *   CompletableFuture<WebSocket> f = client.connect(
 *       URI.create("wss://example.org/socket"),
 *       new WebSocketListener() {
 *         public void onOpen(WebSocket ws) { ws.sendText("hi", true); }
 *         public void onText(CharSequence text, boolean last) { /* ... *\/ }
 *       },
 *       cfg
 *   );
 *
 *   WebSocket ws = f.join();
 *   // ...
 * }
 * }</pre>
 *
 * @since 5.6
 */
public final class WebSocketClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketClient.class);

    private final HttpAsyncRequester requester;

    public WebSocketClient() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Bootstrapping requester");
        }
        this.requester = AsyncRequesterBootstrap.bootstrap().create();
        this.requester.start();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Requester started");
        }
    }

    /**
     * Shuts down the internal I/O reactor and releases resources.
     *
     * <p>Any active WebSocket connections managed by this client will be closed.</p>
     */
    @Override
    public void close() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Closing requester");
        }
        requester.close(CloseMode.GRACEFUL);
    }

    /**
     * Establishes a WebSocket connection to the given {@link URI}.
     *
     * <p>This method performs an HTTP/1.1 GET with Upgrade headers, validates the
     * server response (101 Switching Protocols + required headers), then upgrades the
     * live channel to a WebSocket protocol handler. The returned future completes with
     * a {@link WebSocket} once the pipeline is switched and {@link WebSocketListener#onOpen(WebSocket)}
     * has been called.</p>
     *
     * <h4>Configuration</h4>
     * <ul>
     *   <li>Subprotocols: if {@link WebSocketClientConfig#subprotocols} is non-empty,
     *       a {@code Sec-WebSocket-Protocol} offer header is sent and the server’s
     *       selection is verified.</li>
     *   <li>permessage-deflate: when enabled in {@code cfg}, a
     *       {@code Sec-WebSocket-Extensions} offer is sent and the server’s response
     *       is strictly validated.</li>
     * </ul>
     *
     * @param uri      {@code ws://} or {@code wss://} endpoint
     * @param listener application callbacks; must be non-{@code null}
     * @param cfg      client behavior and negotiation parameters; must be non-{@code null}
     * @return future that completes with an open {@link WebSocket} on success
     * @throws IllegalArgumentException if the URI scheme is not {@code ws} or {@code wss}
     */
    public CompletableFuture<WebSocket> connect(final URI uri,
                                                final WebSocketListener listener,
                                                final WebSocketClientConfig cfg) {
        Args.notNull(uri, "uri");
        Args.notNull(listener, "listener");
        Args.notNull(cfg, "cfg");

        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("Scheme must be ws or wss"));
            return f;
        }

        final String scheme = secure ? "https" : "http";
        final int port = uri.getPort() > 0 ? uri.getPort() : secure ? 443 : 80;
        final String host = uri.getHost();
        if (host == null) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("Host required"));
            return f;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        final String fullPath = uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;

        final HttpHost target = new HttpHost(scheme, host, port);
        final CompletableFuture<WebSocket> result = new CompletableFuture<>();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Connecting to {} and will upgrade {}", target, fullPath);
        }

        requester.connect(target, cfg.connectTimeout != null ? cfg.connectTimeout : Timeout.ofSeconds(10), null,
                new FutureCallback<AsyncClientEndpoint>() {
                    @Override
                    public void completed(final AsyncClientEndpoint endpoint) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Endpoint leased: {} (now dispatching upgrade)", endpoint);
                        }
                        try {
                            final String secKey = randomKey();
                            final BasicHttpRequest req = new BasicHttpRequest("GET", target, fullPath);
                            req.setAuthority(new URIAuthority(host, port));
                            req.addHeader("Host", port == (secure ? 443 : 80) ? host : host + ":" + port);
                            req.addHeader("Connection", "Upgrade");
                            req.addHeader("Upgrade", "websocket");
                            req.addHeader("Sec-WebSocket-Version", "13");
                            req.addHeader("Sec-WebSocket-Key", secKey);
                            req.addHeader("Origin", scheme + "://" + host + (port == (secure ? 443 : 80) ? "" : ":" + port));

                            // Subprotocol offer
                            if (!cfg.subprotocols.isEmpty()) {
                                final StringJoiner sj = new StringJoiner(", ");
                                for (final String p : cfg.subprotocols) {
                                    if (p != null && !p.isEmpty()) {
                                        sj.add(p);
                                    }
                                }
                                final String offered = sj.toString();
                                if (!offered.isEmpty()) {
                                    req.addHeader("Sec-WebSocket-Protocol", offered);
                                }
                            }

                            // RFC 7692: permessage-deflate offer
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
                                            LOG.debug("upgrade FAILED", cause);
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
                                            LOG.debug("upgrade CANCELLED");
                                        }
                                        try {
                                            endpoint.releaseAndDiscard();
                                        } catch (final Throwable ignore) {
                                        }
                                        result.cancel(true);
                                    }
                                }

                                @Override
                                public void produceRequest(final org.apache.hc.core5.http.nio.RequestChannel ch, final HttpContext hc)
                                        throws HttpException, IOException {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("upgrade: send request");
                                    }
                                    ch.sendRequest(req, null, hc);
                                }

                                @Override
                                public int available() {
                                    return 0;
                                }

                                @Override
                                public void produce(final org.apache.hc.core5.http.nio.DataStreamChannel channel) {
                                }

                                @Override
                                public void consumeInformation(final HttpResponse response, final HttpContext hc)
                                        throws HttpException, IOException {
                                    final int code = response.getCode();
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("upgrade: consumeInformation {}", code);
                                    }
                                    if (code == HttpStatus.SC_SWITCHING_PROTOCOLS && done.compareAndSet(false, true)) {
                                        finishUpgrade(response, secKey, endpoint, listener, cfg, result);
                                    }
                                }

                                @Override
                                public void consumeResponse(final HttpResponse response, final EntityDetails entityDetails, final HttpContext hc)
                                        throws HttpException, IOException {
                                    final int code = response.getCode();
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("upgrade: consumeResponse {} entity={}", code, entityDetails != null);
                                    }
                                    if (code == HttpStatus.SC_SWITCHING_PROTOCOLS && done.compareAndSet(false, true)) {
                                        finishUpgrade(response, secKey, endpoint, listener, cfg, result);
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
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("endpoint.execute(handler) returned (async)");
                            }
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

    private void finishUpgrade(final HttpResponse response,
                               final String secKey,
                               final AsyncClientEndpoint endpoint,
                               final WebSocketListener listener,
                               final WebSocketClientConfig cfg,
                               final CompletableFuture<WebSocket> result) {
        try {
            if (LOG.isDebugEnabled()) {
                for (final Header h : response.getHeaders()) {
                    LOG.debug("Hdr: {}: {}", h.getName(), h.getValue());
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

            // Subprotocol verification
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

            // --- RFC 7692: permessage-deflate negotiation -> build ExtensionChain
            final ExtensionChain chain = new ExtensionChain();
            final String ext = headerValue(response, "Sec-WebSocket-Extensions");
            if (ext != null && !ext.isEmpty()) {
                boolean sawPmce = false;
                boolean serverNoCtx = false;
                boolean clientNoCtx = false;
                Integer clientBits = null;
                Integer serverBits = null;

                for (final String rawExt : ext.split(",")) {
                    final String[] parts = rawExt.trim().split(";");
                    final String name = parts[0].trim();
                    if (!"permessage-deflate".equalsIgnoreCase(name)) {
                        // server must not select unknown/unsupported extensions
                        continue;
                    }
                    sawPmce = true;
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
                                // Accept any; inflate side doesn't require it
                            }
                        }
                    }
                    break; // consider first pmce entry only
                }

                if (sawPmce) {
                    if (!cfg.perMessageDeflateEnabled) {
                        throw new IllegalStateException("Server negotiated PMCE but client disabled it");
                    }
                    chain.add(new PerMessageDeflate(true, serverNoCtx, clientNoCtx, clientBits, serverBits));
                } else {
                    throw new IllegalStateException("Server negotiated unsupported extensions: " + ext);
                }
            }

            final ProtocolIOSession ioSession = extractProtocolIOSession(endpoint);
            if (ioSession == null) {
                throw new IllegalStateException("ProtocolIOSession not available");
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Registering websocket ProtocolUpgradeHandler and switching protocol on {}", ioSession);
            }

            final WebSocketUpgrader upgrader = new WebSocketUpgrader(listener, cfg, chain);
            ioSession.registerProtocol("websocket", upgrader);
            ioSession.switchProtocol("websocket", new FutureCallback<ProtocolIOSession>() {
                @Override
                public void completed(final ProtocolIOSession s) {
                    final WebSocket ws = upgrader.getWebSocket();
                    try {
                        listener.onOpen(ws);
                    } catch (final Throwable ignore) {
                    }
                    result.complete(ws);
                }

                @Override
                public void failed(final Exception ex) {
                    try {
                        endpoint.releaseAndDiscard();
                    } catch (final Throwable ignore) {
                    }
                    result.completeExceptionally(ex);
                }

                @Override
                public void cancelled() {
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

    private static ProtocolIOSession extractProtocolIOSession(final AsyncClientEndpoint endpoint) {
        try {
            final Field poolEntryRef = endpoint.getClass().getDeclaredField("poolEntryRef");
            poolEntryRef.setAccessible(true);
            final Object atomicRef = poolEntryRef.get(endpoint); // AtomicReference<PoolEntry<...>>
            final Method getMethod = atomicRef.getClass().getMethod("get");
            final Object poolEntry = getMethod.invoke(atomicRef);
            if (poolEntry == null) {
                return null;
            }

            final Method getConn = poolEntry.getClass().getMethod("getConnection");
            final Object ioSession = getConn.invoke(poolEntry);
            if (ioSession instanceof ProtocolIOSession) {
                return (ProtocolIOSession) ioSession;
            }

            if (ioSession instanceof IOSession) {
                final IOSession s = (IOSession) ioSession;
                final IOEventHandler h = s.getHandler();
                try {
                    final Class<?> hCls = h.getClass();
                    final Field fDuplexer = hCls.getDeclaredField("streamDuplexer");
                    fDuplexer.setAccessible(true);
                    final Object duplexer = fDuplexer.get(h);
                    final Method mGetSession = duplexer.getClass().getMethod("getSession");
                    final Object proto = mGetSession.invoke(duplexer);
                    if (proto instanceof ProtocolIOSession) {
                        return (ProtocolIOSession) proto;
                    }
                } catch (final Throwable ignore) {
                }
            }
        } catch (final Throwable t) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("extractProtocolIOSession failed", t);
            }
        }
        return null;
    }

    private static String headerValue(final HttpResponse r, final String name) {
        return r.getFirstHeader(name) != null ? r.getFirstHeader(name).getValue() : null;
    }

    private static String randomKey() {
        final byte[] nonce = new byte[16];
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
