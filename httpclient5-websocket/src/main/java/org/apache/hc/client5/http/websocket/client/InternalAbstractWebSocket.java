package org.apache.hc.client5.http.websocket.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.protocol.H1WebSocketProtocol;
import org.apache.hc.client5.http.websocket.client.protocol.H2WebSocketProtocol;
import org.apache.hc.client5.http.websocket.client.protocol.WebSocketProtocol;
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
abstract class InternalAbstractWebSocket extends AbstractMinimalWebSocketBase {

    private static final Logger LOG = LoggerFactory.getLogger(InternalAbstractWebSocket.class);

    private final WebSocketClientConfig defaultConfig;
    private final ManagedConnPool<HttpHost, IOSession> connPool;

    private final WebSocketProtocol h1;
    private final WebSocketProtocol h2;

    InternalAbstractWebSocket(
            final HttpAsyncRequester requester,
            final ManagedConnPool<HttpHost, IOSession> connPool,
            final WebSocketClientConfig defaultConfig,
            final ThreadFactory threadFactory) {
        super(Args.notNull(requester, "requester"), threadFactory);
        this.connPool = Args.notNull(connPool, "connPool");
        this.defaultConfig = defaultConfig != null ? defaultConfig : WebSocketClientConfig.custom().build();

        this.h1 = newH1Protocol(requester, connPool);
        this.h2 = newH2Protocol();
    }

    /**
     * HTTP/1.1 Upgrade protocol
     */
    protected WebSocketProtocol newH1Protocol(
            final HttpAsyncRequester requester,
            final ManagedConnPool<HttpHost, IOSession> connPool) {
        return new H1WebSocketProtocol(requester, connPool);
    }

    /**
     * HTTP/2 Extended CONNECT protocol (stub by default)
     */
    protected WebSocketProtocol newH2Protocol() {
        return new H2WebSocketProtocol();
    }

    @Override
    protected CompletableFuture<WebSocket> doConnect(
            final URI uri,
            final WebSocketListener listener,
            final WebSocketClientConfig cfgOrNull,
            final HttpContext context) {

        final WebSocketClientConfig cfg = cfgOrNull != null ? cfgOrNull : defaultConfig;

        if (cfg.isAllowH2ExtendedConnect()) {
            final CompletableFuture<WebSocket> out = new CompletableFuture<>();
            h2.connect(uri, listener, cfg, context).whenComplete((ws, ex) -> {
                if (ws != null) {
                    out.complete(ws);
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("H2 extended CONNECT failed, falling back to H1: {}", ex != null ? ex.getMessage() : "unknown");
                    }
                    h1.connect(uri, listener, cfg, context).whenComplete((ws2, ex2) -> {
                        if (ws2 != null) {
                            out.complete(ws2);
                        } else {
                            out.completeExceptionally(ex2 != null ? ex2 :ex != null ? ex : new IllegalStateException("Connect failed"));
                        }
                    });
                }
            });
            return out;
        }

        return h1.connect(uri, listener, cfg, context);
    }

    @Override
    protected void internalClose(final CloseMode closeMode) {
        try {
            connPool.close(closeMode != null ? closeMode : CloseMode.GRACEFUL);
        } catch (final Exception ex) {
            LOG.warn("Error closing pool: {}", ex.getMessage(), ex);
        }
    }
}
