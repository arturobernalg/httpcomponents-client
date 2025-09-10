package org.apache.hc.client5.http.socket;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.socket.impl.PerMessageDeflate;
import org.apache.hc.client5.http.socket.impl.WebSocketUpgrader;
import org.apache.hc.client5.http.socket.impl.WsHandler;
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
import org.apache.hc.core5.reactor.IOSessionListener;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebSocketClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketClient.class);

    private final HttpAsyncRequester requester;

    public WebSocketClient() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Bootstrapping requester");
        }
        this.requester = AsyncRequesterBootstrap.bootstrap()
                .setIOSessionListener(new IOSessionListener() {
                    @Override
                    public void connected(final IOSession session) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("IOSession connected: {} remote={}", session, session.getRemoteAddress());
                        }
                    }

                    @Override
                    public void startTls(final IOSession session) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("startTls: {}", session);
                        }
                    }

                    @Override
                    public void disconnected(final IOSession session) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("IOSession disconnected: {} remote={}", session, session.getRemoteAddress());
                        }
                    }

                    @Override
                    public void inputReady(final IOSession session) {
                    }

                    @Override
                    public void outputReady(final IOSession session) {
                    }

                    @Override
                    public void timeout(final IOSession session) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("timeout {}", session);
                        }
                    }

                    @Override
                    public void exception(final IOSession session, final Exception ex) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("IOSession exception " + session, ex);
                        }
                    }
                })
                .create();
        this.requester.start();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Requester started");
        }
    }

    @Override
    public void close() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Closing requester");
        }
        requester.close(CloseMode.GRACEFUL);
    }

    public CompletableFuture<WebSocket> connect(final URI uri,
                                                final WebSocketListener listener,
                                                final WebSocketClientConfig cfg) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(cfg, "cfg");

        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("Scheme must be ws or wss"));
            return f;
        }

        final String scheme = secure ? "https" : "http";
        final int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
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
        final String fullPath = (uri.getRawQuery() != null) ? (path + "?" + uri.getRawQuery()) : path;

        final HttpHost target = new HttpHost(scheme, host, port);
        final CompletableFuture<WebSocket> result = new CompletableFuture<>();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Connecting to {} and will upgrade {}", target, fullPath);
        }
        requester.connect(target, (cfg.connectTimeout != null ? cfg.connectTimeout : Timeout.ofSeconds(10)), null,
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
                            req.addHeader("Host", (port == (secure ? 443 : 80)) ? host : (host + ":" + port));
                            req.addHeader("Connection", "Upgrade");
                            req.addHeader("Upgrade", "websocket");
                            req.addHeader("Sec-WebSocket-Version", "13");
                            req.addHeader("Sec-WebSocket-Key", secKey);
                            req.addHeader("Origin", scheme + "://" + host + (port == (secure ? 443 : 80) ? "" : ":" + port));

                            // Subprotocol offer
                            if (!cfg.subprotocols.isEmpty()) {
                                final StringJoiner sj = new StringJoiner(", ");
                                for (String p : cfg.subprotocols) {
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
                                        } catch (Throwable ignore) {
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
                                        } catch (Throwable ignore) {
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
                                public void consumeInformation(final HttpResponse response, final HttpContext hc) throws HttpException, IOException {
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
                                        LOG.debug("upgrade: consumeResponse {} entity={}", code, (entityDetails != null));
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
                        } catch (Exception ex) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Exception preparing upgrade", ex);
                            }
                            try {
                                endpoint.releaseAndDiscard();
                            } catch (Throwable ignore) {
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
            for (Header h : response.getHeaders()) {
                if (LOG.isDebugEnabled()) {
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

            // Subprotocol verify
            final String proto = headerValue(response, "Sec-WebSocket-Protocol");
            if (proto != null && !proto.isEmpty()) {
                if (cfg.subprotocols.isEmpty()) {
                    throw new IllegalStateException("Server selected subprotocol but none was offered: " + proto);
                }
                boolean matched = false;
                for (String p : cfg.subprotocols) {
                    if (p.equals(proto)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new IllegalStateException("Server selected subprotocol not offered: " + proto);
                }
            }

            // Extension negotiation (permessage-deflate)
            PerMessageDeflate pmce;
            final String ext = headerValue(response, "Sec-WebSocket-Extensions");
            if (ext != null && !ext.isEmpty()) {
                boolean sawPmce = false;
                boolean serverNoCtx = false;
                boolean clientNoCtx = false;
                Integer clientBits = null;
                Integer serverBits = null;

                // Parse comma-separated extensions; look for "permessage-deflate"
                for (String rawExt : ext.split(",")) {
                    final String[] parts = rawExt.trim().split(";");
                    final String name = parts[0].trim();
                    if (!"permessage-deflate".equalsIgnoreCase(name)) {
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
                                // server MUST NOT send unless we offered it
                                if (!cfg.offerClientNoContextTakeover) {
                                    throw new IllegalStateException("Server sent client_no_context_takeover but it was not offered");
                                }
                                clientNoCtx = true;
                            }
                            // else ignore unknown flag
                        } else {
                            final String k = p.substring(0, eq).trim();
                            final String v = p.substring(eq + 1).trim();
                            if ("client_max_window_bits".equalsIgnoreCase(k)) {
                                if (cfg.offerClientMaxWindowBits == null) {
                                    throw new IllegalStateException("Server sent client_max_window_bits but it was not offered");
                                }
                                try {
                                    clientBits = Integer.parseInt(v);
                                } catch (NumberFormatException ignore) {
                                }
                                if (clientBits == null || !clientBits.equals(cfg.offerClientMaxWindowBits)) {
                                    throw new IllegalStateException("Unsupported client_max_window_bits: " + v);
                                }
                            } else if ("server_max_window_bits".equalsIgnoreCase(k)) {
                                try {
                                    serverBits = Integer.parseInt(v);
                                } catch (NumberFormatException ignore) {
                                }
                                // OK to accept any; inflate doesn't require it
                            }
                        }
                    }
                    break; // only consider first permessage-deflate entry
                }

                if (sawPmce) {
                    if (!cfg.perMessageDeflateEnabled) {
                        throw new IllegalStateException("Server negotiated PMCE but client disabled it");
                    }
                    pmce = new PerMessageDeflate(true, serverNoCtx, clientNoCtx, clientBits, serverBits);
                } else {
                    throw new IllegalStateException("Server negotiated unsupported extensions: " + ext);
                }
            } else {
                pmce = null;
            }

            final ProtocolIOSession ioSession = extractProtocolIOSession(endpoint);
            if (ioSession == null) {
                throw new IllegalStateException("ProtocolIOSession not available");
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Registering websocket ProtocolUpgradeHandler and switching protocol on {}", ioSession);
            }
            final WebSocketUpgrader upgrader = new WebSocketUpgrader(listener, cfg, pmce);
            ioSession.registerProtocol("websocket", upgrader);
            ioSession.switchProtocol("websocket", new FutureCallback<ProtocolIOSession>() {
                @Override
                public void completed(final ProtocolIOSession s) {
                    final WebSocket ws = upgrader.getWebSocket() != null ? upgrader.getWebSocket() : new WsHandler(s, listener, cfg, pmce).exposeWebSocket();
                    try {
                        listener.onOpen(ws);
                    } catch (Throwable ignore) {
                    }
                    result.complete(ws);
                }

                @Override
                public void failed(final Exception ex) {
                    try {
                        endpoint.releaseAndDiscard();
                    } catch (Throwable ignore) {
                    }
                    result.completeExceptionally(ex);
                }

                @Override
                public void cancelled() {
                    try {
                        endpoint.releaseAndDiscard();
                    } catch (Throwable ignore) {
                    }
                    result.cancel(true);
                }
            });

        } catch (Exception ex) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("finishUpgrade failed", ex);
            }
            try {
                endpoint.releaseAndDiscard();
            } catch (Throwable ignore) {
            }
            result.completeExceptionally(ex);
        }
    }

    private static ProtocolIOSession extractProtocolIOSession(final AsyncClientEndpoint endpoint) {
        try {
            final Field poolEntryRef = endpoint.getClass().getDeclaredField("poolEntryRef");
            poolEntryRef.setAccessible(true);
            final Object atomicRef = poolEntryRef.get(endpoint);
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
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
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
        for (Header h : hs) {
            for (String part : h.getValue().split(",")) {
                if (part.trim().equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }
}
