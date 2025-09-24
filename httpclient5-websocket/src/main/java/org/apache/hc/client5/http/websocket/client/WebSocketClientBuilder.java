package org.apache.hc.client5.http.websocket.client;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.support.AsyncRequesterBootstrap;
import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.function.Decorator;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.Http1StreamListener;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.pool.ConnPoolListener;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.IOSessionListener;
import org.apache.hc.core5.util.Timeout;

public final class WebSocketClientBuilder {

    private final AsyncRequesterBootstrap bootstrap = AsyncRequesterBootstrap.bootstrap();
    private WebSocketClientConfig.Builder cfg = WebSocketClientConfig.custom();

    public static WebSocketClientBuilder create() {
        return new WebSocketClientBuilder();
    }

    private WebSocketClientBuilder() {
    }

    public WebSocketClientBuilder setIOReactorConfig(final IOReactorConfig c) {
        bootstrap.setIOReactorConfig(c);
        return this;
    }

    public WebSocketClientBuilder setHttp1Config(final Http1Config c) {
        bootstrap.setHttp1Config(c);
        return this;
    }

    public WebSocketClientBuilder setCharCoding(final CharCodingConfig c) {
        bootstrap.setCharCodingConfig(c);
        return this;
    }

    public WebSocketClientBuilder setDefaultMaxPerRoute(final int n) {
        bootstrap.setDefaultMaxPerRoute(n);
        return this;
    }

    public WebSocketClientBuilder setMaxTotal(final int n) {
        bootstrap.setMaxTotal(n);
        return this;
    }

    public WebSocketClientBuilder setTimeToLive(final Timeout ttl) {
        bootstrap.setTimeToLive(ttl);
        return this;
    }

    public WebSocketClientBuilder setPoolReusePolicy(final PoolReusePolicy p) {
        bootstrap.setPoolReusePolicy(p);
        return this;
    }

    public WebSocketClientBuilder setPoolConcurrencyPolicy(final PoolConcurrencyPolicy p) {
        bootstrap.setPoolConcurrencyPolicy(p);
        return this;
    }

    public WebSocketClientBuilder setTlsStrategy(final TlsStrategy t) {
        bootstrap.setTlsStrategy(t);
        return this;
    }

    public WebSocketClientBuilder setTlsHandshakeTimeout(final Timeout t) {
        bootstrap.setTlsHandshakeTimeout(t);
        return this;
    }

    public WebSocketClientBuilder setIOSessionDecorator(final Decorator<IOSession> d) {
        bootstrap.setIOSessionDecorator(d);
        return this;
    }

    public WebSocketClientBuilder setExceptionCallback(final Callback<Exception> cb) {
        bootstrap.setExceptionCallback(cb);
        return this;
    }

    public WebSocketClientBuilder setIOSessionListener(final IOSessionListener l) {
        bootstrap.setIOSessionListener(l);
        return this;
    }

    public WebSocketClientBuilder setStreamListener(final Http1StreamListener l) {
        bootstrap.setStreamListener(l);
        return this;
    }

    public WebSocketClientBuilder setConnPoolListener(final ConnPoolListener<HttpHost> l) {
        bootstrap.setConnPoolListener(l);
        return this;
    }

    // ------------ Default WebSocket config (RFC6455 / RFC7692) -------------

    /**
     * Replace the default config entirely (power users).
     */
    public WebSocketClientBuilder setDefaultConfig(final WebSocketClientConfig c) {
        this.cfg = WebSocketClientConfig.custom()
                .setMaxFrameSize(c.maxFrameSize)
                .setMaxMessageSize(c.maxMessageSize)
                .setConnectTimeout(c.connectTimeout)
                .setExchangeTimeout(c.exchangeTimeout)
                .setCloseWaitTimeout(c.closeWaitTimeout)
                .setAutoPong(c.autoPong)
                .setOutgoingChunkSize(c.outgoingChunkSize)
                .setMaxFramesPerTick(c.maxFramesPerTick)
                .setIoPoolCapacity(c.ioPoolCapacity)
                .setDirectBuffers(c.directBuffers)
                .enablePerMessageDeflate(c.perMessageDeflateEnabled)
                .offerServerNoContextTakeover(c.offerServerNoContextTakeover)
                .offerClientNoContextTakeover(c.offerClientNoContextTakeover)
                .offerClientMaxWindowBits(c.offerClientMaxWindowBits)
                .offerServerMaxWindowBits(c.offerServerMaxWindowBits)
                .setSubprotocols(c.subprotocols);
        return this;
    }

    // Handy pass-throughs so callers can keep everything in this one builder:
    public WebSocketClientBuilder enablePerMessageDeflate(final boolean v) {
        cfg.enablePerMessageDeflate(v);
        return this;
    }

    public WebSocketClientBuilder offerServerNoContextTakeover(final boolean v) {
        cfg.offerServerNoContextTakeover(v);
        return this;
    }

    public WebSocketClientBuilder offerClientNoContextTakeover(final boolean v) {
        cfg.offerClientNoContextTakeover(v);
        return this;
    }

    public WebSocketClientBuilder offerClientMaxWindowBits(final Integer v) {
        cfg.offerClientMaxWindowBits(v);
        return this;
    }

    public WebSocketClientBuilder offerServerMaxWindowBits(final Integer v) {
        cfg.offerServerMaxWindowBits(v);
        return this;
    }

    public WebSocketClientBuilder setConnectTimeout(final Timeout t) {
        cfg.setConnectTimeout(t);
        return this;
    }

    public WebSocketClientBuilder setCloseWaitTimeout(final Timeout t) {
        cfg.setCloseWaitTimeout(t);
        return this;
    }

    public WebSocketClientBuilder addSubprotocol(final String p) {
        cfg.addSubprotocol(p);
        return this;
    }

    public WebSocketClient build() {
        final AsyncRequesterBootstrap.Result r = bootstrap.createWithPool();
        final WebSocketClient client = new WebSocketClient(r.requester, r.connPool);
        return client;
    }
}
