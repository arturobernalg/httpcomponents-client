package org.apache.hc.client5.http.websocket.client;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.support.AsyncRequesterBootstrap;
import org.apache.hc.core5.util.Timeout;

/** Builder that configures transport (pool, reactor) — not via WebSocketClientConfig. */
public final class WebSocketClientBuilder {

    private final AsyncRequesterBootstrap bootstrap = AsyncRequesterBootstrap.bootstrap();
    private WebSocketClientConfig defaultConfig = WebSocketClientConfig.custom().build();

    private WebSocketClientBuilder() {}

    public static WebSocketClientBuilder create() { return new WebSocketClientBuilder(); }

    public WebSocketClientBuilder defaultConfig(final WebSocketClientConfig cfg) {
        if (cfg != null) {
            this.defaultConfig = cfg;
        }
        return this;
    }

    // Optional transport tuning:
    public WebSocketClientBuilder defaultMaxPerRoute(final int n) { bootstrap.setDefaultMaxPerRoute(n); return this; }
    public WebSocketClientBuilder maxTotal(final int n) { bootstrap.setMaxTotal(n); return this; }
    public WebSocketClientBuilder timeToLive(final Timeout ttl) { bootstrap.setTimeToLive(ttl); return this; }

    public CloseableWebSocketClient build() {
        final AsyncRequesterBootstrap.Result r = bootstrap.createWithPool();
        return new DefaultWebSocketClient(r.requester, r.connPool, defaultConfig);
    }
}
