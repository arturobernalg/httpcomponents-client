/*
 * Licensed to the Apache Software Foundation (ASF) ...
 */
package org.apache.hc.client5.http.websocket.client;

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
import org.apache.hc.client5.http.websocket.core.extension.ExtensionChain;
import org.apache.hc.client5.http.websocket.core.extension.PerMessageDeflate;
import org.apache.hc.client5.http.websocket.httpcore.WebSocketUpgrader;
import org.apache.hc.client5.http.websocket.support.WebSocketRequester;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concrete client that keeps your original upgrade logic intact.
 */
final class DefaultWebSocketClient extends AbstractWebSocketClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultWebSocketClient.class);

    private final WebSocketClientConfig defaultConfig;
    private final WebSocketRequester wsRequester;

    DefaultWebSocketClient(
            final HttpAsyncRequester requester,
            final ManagedConnPool<HttpHost, IOSession> connPool,
            final WebSocketClientConfig defaultConfig) {
        super(requester, connPool);
        this.defaultConfig = defaultConfig != null ? defaultConfig : WebSocketClientConfig.custom().build();
        this.wsRequester = new WebSocketRequester(requester, connPool);
    }

    @Override
    protected CompletableFuture<WebSocket> doConnect(
            final URI uri,
            final WebSocketListener listener,
            final WebSocketClientConfig cfgOrNull,
            final HttpContext context) {

        final WebSocketClientConfig cfg = cfgOrNull != null ? cfgOrNull : defaultConfig;

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
                                public void produceRequest(final RequestChannel ch, final org.apache.hc.core5.http.protocol.HttpContext hc)
                                        throws java.io.IOException, HttpException {
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
                                public void updateCapacity(final CapacityChannel capacityChannel) {
                                }

                                @Override
                                public void consume(final ByteBuffer src) {
                                }

                                @Override
                                public void streamEnd(final java.util.List<? extends Header> trailers) {
                                }

                                @Override
                                public void consumeInformation(final HttpResponse response, final org.apache.hc.core5.http.protocol.HttpContext hc) {
                                    final int code = response.getCode();
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("consumeInformation: {}", code);
                                    }
                                    if (code == HttpStatus.SC_SWITCHING_PROTOCOLS && done.compareAndSet(false, true)) {
                                        finishUpgrade(endpoint, response, secKey, listener, cfg, result);
                                    }
                                }

                                @Override
                                public void consumeResponse(final HttpResponse response,
                                                            final org.apache.hc.core5.http.EntityDetails entity,
                                                            final org.apache.hc.core5.http.protocol.HttpContext hc) {
                                    final int code = response.getCode();
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("consumeResponse: {} entity={}", code, entity != null);
                                    }
                                    if (code == HttpStatus.SC_SWITCHING_PROTOCOLS && done.compareAndSet(false, true)) {
                                        finishUpgrade(endpoint, response, secKey, listener, cfg, result);
                                        return;
                                    }
                                    failed(new IllegalStateException("Unexpected status: " + response.getCode()));
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

    private void finishUpgrade(
            final WebSocketRequester.ProtoEndpoint endpoint,
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
                    if (!"permessage-deflate".equalsIgnoreCase(parts[0].trim())) {
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
                                    throw new IllegalStateException("client_no_context_takeover not offered");
                                }
                                clientNoCtx = true;
                            }
                        } else {
                            final String k = p.substring(0, eq).trim();
                            final String v = p.substring(eq + 1).trim();
                            if ("client_max_window_bits".equalsIgnoreCase(k)) {
                                if (cfg.offerClientMaxWindowBits == null) {
                                    throw new IllegalStateException("client_max_window_bits not offered");
                                }
                                try {
                                    clientBits = Integer.parseInt(v);
                                } catch (NumberFormatException ignore) {
                                }
                                if (clientBits == null || clientBits < 8 || clientBits > 15) {
                                    throw new IllegalStateException("Invalid client_max_window_bits: " + v);
                                }
                            } else if ("server_max_window_bits".equalsIgnoreCase(k)) {
                                try {
                                    serverBits = Integer.parseInt(v);
                                } catch (NumberFormatException ignore) {
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
            ioSession.switchProtocol("websocket", new org.apache.hc.core5.concurrent.FutureCallback<ProtocolIOSession>() {
                @Override
                public void completed(final ProtocolIOSession s) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Protocol switch completed.");
                    }
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
