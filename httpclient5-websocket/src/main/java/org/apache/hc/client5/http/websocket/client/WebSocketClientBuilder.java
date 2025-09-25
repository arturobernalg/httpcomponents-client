package org.apache.hc.client5.http.websocket.client;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.support.AsyncRequesterBootstrap;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.pool.ConnPoolListener;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.IOSessionListener;
import org.apache.hc.core5.util.Timeout;

public final class WebSocketClientBuilder {

    private final AsyncRequesterBootstrap bootstrap = AsyncRequesterBootstrap.bootstrap();
    private WebSocketClientConfig defaultConfig = WebSocketClientConfig.custom().build();

    private WebSocketClientBuilder() {
    }

    public static WebSocketClientBuilder create() {
        return new WebSocketClientBuilder();
    }

    public WebSocketClientBuilder defaultConfig(final WebSocketClientConfig cfg) {
        if (cfg != null) defaultConfig = cfg;
        return this;
    }

    public WebSocketClientBuilder ioReactorConfig(final IOReactorConfig v) {
        bootstrap.setIOReactorConfig(v);
        return this;
    }

    public WebSocketClientBuilder http1Config(final Http1Config v) {
        bootstrap.setHttp1Config(v);
        return this;
    }

    public WebSocketClientBuilder charCoding(final CharCodingConfig v) {
        bootstrap.setCharCodingConfig(v);
        return this;
    }

    public WebSocketClientBuilder httpProcessor(final HttpProcessor v) {
        bootstrap.setHttpProcessor(v);
        return this;
    }

    public WebSocketClientBuilder defaultMaxPerRoute(final int n) {
        bootstrap.setDefaultMaxPerRoute(n);
        return this;
    }

    public WebSocketClientBuilder maxTotal(final int n) {
        bootstrap.setMaxTotal(n);
        return this;
    }

    public WebSocketClientBuilder timeToLive(final Timeout ttl) {
        bootstrap.setTimeToLive(ttl);
        return this;
    }

    public WebSocketClientBuilder poolReusePolicy(final PoolReusePolicy p) {
        bootstrap.setPoolReusePolicy(p);
        return this;
    }

    public WebSocketClientBuilder poolConcurrency(final PoolConcurrencyPolicy p) {
        bootstrap.setPoolConcurrencyPolicy(p);
        return this;
    }

    public WebSocketClientBuilder tlsStrategy(final TlsStrategy t) {
        bootstrap.setTlsStrategy(t);
        return this;
    }

    public WebSocketClientBuilder tlsHandshakeTimeout(final Timeout t) {
        bootstrap.setTlsHandshakeTimeout(t);
        return this;
    }

    public WebSocketClientBuilder ioSessionListener(final IOSessionListener l) {
        bootstrap.setIOSessionListener(l);
        return this;
    }

    public WebSocketClientBuilder connPoolListener(final ConnPoolListener<HttpHost> l) {
        bootstrap.setConnPoolListener(l);
        return this;
    }
    /** Sets the default WebSocket per-connection config used by connect(..., cfg==null). */
    public WebSocketClientBuilder setDefaultConfig(final WebSocketClientConfig cfg) {
        this.defaultConfig = cfg;
        return this;
    }

    public CloseableWebSocketClient build() {
        final AsyncRequesterBootstrap.Result r = bootstrap.createWithPool();
        final HttpAsyncRequester requester = r.requester;
        final ManagedConnPool<HttpHost, IOSession> pool = r.connPool;
        return new DefaultWebSocketClient(requester, pool, defaultConfig);
    }
}
