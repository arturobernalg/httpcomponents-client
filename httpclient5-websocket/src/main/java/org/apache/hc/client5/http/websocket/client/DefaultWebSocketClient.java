package org.apache.hc.client5.http.websocket.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.protocol.H1WebSocketProtocol;
import org.apache.hc.client5.http.websocket.client.protocol.H2WebSocketProtocol;
import org.apache.hc.client5.http.websocket.client.protocol.WebSocketProtocol;
import org.apache.hc.client5.http.websocket.support.AsyncRequesterBootstrap;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOSession;

final class DefaultWebSocketClient extends AbstractWebSocketClientBase {

    private final WebSocketClientConfig defaultConfig;
    private final WebSocketProtocol h1;
    private final WebSocketProtocol h2;

    DefaultWebSocketClient(final HttpAsyncRequester requester,
                           final ManagedConnPool<HttpHost, IOSession> connPool,
                           final WebSocketClientConfig defaultConfig) {
        super(requester, connPool);
        this.defaultConfig = defaultConfig;
        this.h1 = new H1WebSocketProtocol(requester, connPool);
        this.h2 = new H2WebSocketProtocol(); // no-args per your build error
    }

    @Override
    protected CompletableFuture<WebSocket> doConnect(
            final URI uri,
            final WebSocketListener listener,
            final WebSocketClientConfig cfgOrNull,
            final HttpContext context) {

        final WebSocketClientConfig cfg = (cfgOrNull != null) ? cfgOrNull : defaultConfig;

        if (cfg.isAllowH2ExtendedConnect()) {
            // Try H2, then fallback to H1 on our marker exception
            return h2.connect(uri, listener, cfg, context)
                    .handle((ws, ex) -> {
                        if (ws != null) {
                            return CompletableFuture.completedFuture(ws);
                        }
                        return h1.connect(uri, listener, cfg, context);
                    })
                    .thenCompose(x -> x);
        } else {
            return h1.connect(uri, listener, cfg, context);
        }
    }

    static CloseableWebSocketClient buildWith(final WebSocketClientConfig defaultConfig) {
        final AsyncRequesterBootstrap.Result r = AsyncRequesterBootstrap.bootstrap().createWithPool();
        return new DefaultWebSocketClient(r.requester, r.connPool, defaultConfig);
    }
}
