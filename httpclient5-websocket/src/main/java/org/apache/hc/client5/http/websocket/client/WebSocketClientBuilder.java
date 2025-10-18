package org.apache.hc.client5.http.websocket.client;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.ThreadFactory;

import org.apache.hc.client5.http.impl.DefaultClientConnectionReuseStrategy;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.client.impl.DefaultWebSocketClient;
import org.apache.hc.client5.http.websocket.client.impl.logging.WsLoggingExceptionCallback;
import org.apache.hc.core5.concurrent.DefaultThreadFactory;
import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.function.Decorator;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.HttpProcessors;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.impl.nio.ClientHttp1StreamDuplexerFactory;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.nio.ssl.BasicClientTlsStrategy;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.http2.impl.nio.ClientH2StreamMultiplexerFactory;
import org.apache.hc.core5.http2.impl.nio.ClientHttpProtocolNegotiationStarter;
import org.apache.hc.core5.http2.impl.nio.H2StreamListener;
import org.apache.hc.core5.pool.ConnPoolListener;
import org.apache.hc.core5.pool.DefaultDisposalCallback;
import org.apache.hc.core5.pool.LaxConnPool;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.pool.StrictConnPool;
import org.apache.hc.core5.reactor.IOEventHandlerFactory;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.IOSessionListener;
import org.apache.hc.core5.util.Timeout;

public final class WebSocketClientBuilder {

    private IOReactorConfig ioReactorConfig;
    private Http1Config http1Config;
    private CharCodingConfig charCodingConfig;
    private HttpProcessor httpProcessor;
    private ConnectionReuseStrategy connStrategy;
    private int defaultMaxPerRoute;
    private int maxTotal;
    private Timeout timeToLive;
    private PoolReusePolicy poolReusePolicy;
    private PoolConcurrencyPolicy poolConcurrencyPolicy;
    private TlsStrategy tlsStrategy;
    private Timeout handshakeTimeout;
    private Decorator<IOSession> ioSessionDecorator;
    private Callback<Exception> exceptionCallback;
    private IOSessionListener sessionListener;
    private org.apache.hc.core5.http.impl.Http1StreamListener streamListener;
    private ConnPoolListener<HttpHost> connPoolListener;
    private ThreadFactory threadFactory;

    private boolean systemProperties;

    private WebSocketClientConfig defaultConfig = WebSocketClientConfig.custom().build();

    private WebSocketClientBuilder() {
    }

    public static WebSocketClientBuilder create() {
        return new WebSocketClientBuilder();
    }

    public WebSocketClientBuilder defaultConfig(final WebSocketClientConfig cfg) {
        if (cfg != null) this.defaultConfig = cfg;
        return this;
    }

    public WebSocketClientBuilder setIOReactorConfig(final IOReactorConfig v) {
        this.ioReactorConfig = v;
        return this;
    }

    public WebSocketClientBuilder setHttp1Config(final Http1Config v) {
        this.http1Config = v;
        return this;
    }

    public WebSocketClientBuilder setCharCodingConfig(final CharCodingConfig v) {
        this.charCodingConfig = v;
        return this;
    }

    public WebSocketClientBuilder setHttpProcessor(final HttpProcessor v) {
        this.httpProcessor = v;
        return this;
    }

    public WebSocketClientBuilder setConnectionReuseStrategy(final ConnectionReuseStrategy v) {
        this.connStrategy = v;
        return this;
    }

    public WebSocketClientBuilder setDefaultMaxPerRoute(final int v) {
        this.defaultMaxPerRoute = v;
        return this;
    }

    public WebSocketClientBuilder setMaxTotal(final int v) {
        this.maxTotal = v;
        return this;
    }

    public WebSocketClientBuilder setTimeToLive(final Timeout v) {
        this.timeToLive = v;
        return this;
    }

    public WebSocketClientBuilder setPoolReusePolicy(final PoolReusePolicy v) {
        this.poolReusePolicy = v;
        return this;
    }

    public WebSocketClientBuilder setPoolConcurrencyPolicy(final PoolConcurrencyPolicy v) {
        this.poolConcurrencyPolicy = v;
        return this;
    }

    public WebSocketClientBuilder setTlsStrategy(final TlsStrategy v) {
        this.tlsStrategy = v;
        return this;
    }

    public WebSocketClientBuilder setTlsHandshakeTimeout(final Timeout v) {
        this.handshakeTimeout = v;
        return this;
    }

    public WebSocketClientBuilder setIOSessionDecorator(final Decorator<IOSession> v) {
        this.ioSessionDecorator = v;
        return this;
    }

    public WebSocketClientBuilder setExceptionCallback(final Callback<Exception> v) {
        this.exceptionCallback = v;
        return this;
    }

    public WebSocketClientBuilder setIOSessionListener(final IOSessionListener v) {
        this.sessionListener = v;
        return this;
    }

    public WebSocketClientBuilder setStreamListener(final org.apache.hc.core5.http.impl.Http1StreamListener v) {
        this.streamListener = v;
        return this;
    }

    public WebSocketClientBuilder setConnPoolListener(final ConnPoolListener<HttpHost> v) {
        this.connPoolListener = v;
        return this;
    }

    public WebSocketClientBuilder setThreadFactory(final ThreadFactory v) {
        this.threadFactory = v;
        return this;
    }

    public WebSocketClientBuilder useSystemProperties() {
        this.systemProperties = true;
        return this;
    }

    public CloseableWebSocketClient build() {

        // --- 1) Pool ---
        final PoolConcurrencyPolicy conc = poolConcurrencyPolicy != null ? poolConcurrencyPolicy : PoolConcurrencyPolicy.STRICT;
        final PoolReusePolicy reuse = poolReusePolicy != null ? poolReusePolicy : PoolReusePolicy.LIFO;
        final Timeout ttl = timeToLive != null ? timeToLive : Timeout.DISABLED;

        final ManagedConnPool<HttpHost, IOSession> connPool;
        if (conc == PoolConcurrencyPolicy.LAX) {
            connPool = new LaxConnPool<>(
                    defaultMaxPerRoute > 0 ? defaultMaxPerRoute : 20,
                    ttl, reuse, new DefaultDisposalCallback<IOSession>(), connPoolListener);
        } else {
            connPool = new StrictConnPool<>(
                    defaultMaxPerRoute > 0 ? defaultMaxPerRoute : 20,
                    maxTotal > 0 ? maxTotal : 50,
                    ttl, reuse, new DefaultDisposalCallback<>(), connPoolListener);
        }

        // --- 2) Common HTTP/1.1 pieces (still needed for negotiation & fallback) ---
        final HttpProcessor proc = httpProcessor != null ? httpProcessor : HttpProcessors.client();
        final Http1Config h1 = http1Config != null ? http1Config : Http1Config.DEFAULT;
        final CharCodingConfig coding = charCodingConfig != null ? charCodingConfig : CharCodingConfig.DEFAULT;

        final ConnectionReuseStrategy reuseStrategyCopy = pickReuseStrategy();

        final ClientHttp1StreamDuplexerFactory http1DuplexerFactory =
                new ClientHttp1StreamDuplexerFactory(proc, h1, coding, reuseStrategyCopy, null, null, streamListener);

        // --- 3) HTTP/2 factories (correct types & argument order) ---
        final HandlerFactory<AsyncPushConsumer> pushHandlerFactory = null; // not used by WS client
        final H2Config h2cfg = H2Config.DEFAULT;
        final H2StreamListener h2StreamListener = null; // or provide a logger impl if you want frame logs

        final ClientH2StreamMultiplexerFactory http2MuxFactory =
                new ClientH2StreamMultiplexerFactory(proc, pushHandlerFactory, h2cfg, coding, h2StreamListener); // ✔ order

        final TlsStrategy tls = tlsStrategy != null ? tlsStrategy : new BasicClientTlsStrategy();

        // Negotiate H2 via ALPN (TLS) and fall back to H1 when needed
        final Callback<Exception> excCb = exceptionCallback != null ? exceptionCallback : WsLoggingExceptionCallback.INSTANCE;
        final IOEventHandlerFactory iohFactory =
                new ClientHttpProtocolNegotiationStarter(
                        http1DuplexerFactory,
                        http2MuxFactory,
                        HttpVersionPolicy.FORCE_HTTP_2,   // or FORCE_HTTP_2 if you only want H2
                        tls,
                        handshakeTimeout,
                        excCb);

        // --- 4) Single requester that can do H1 or H2 via negotiation ---
        final HttpAsyncRequester requester = new HttpAsyncRequester(
                ioReactorConfig != null ? ioReactorConfig : IOReactorConfig.DEFAULT,
                iohFactory,
                ioSessionDecorator,
                excCb,
                sessionListener,
                connPool,
                tls,
                handshakeTimeout
        );

        final ThreadFactory tf = threadFactory != null
                ? threadFactory
                : new DefaultThreadFactory("websocket-main", true);

        return new DefaultWebSocketClient(
                requester,          // for H1
                requester,          // for H2 (same instance is OK)
                connPool,
                defaultConfig,
                tf
        );

    }

    private ConnectionReuseStrategy pickReuseStrategy() {
        ConnectionReuseStrategy reuseStrategyCopy = this.connStrategy;
        if (reuseStrategyCopy == null) {
            if (systemProperties) {
                final String s = getProperty("http.keepAlive", "true");
                reuseStrategyCopy = "true".equalsIgnoreCase(s)
                        ? DefaultClientConnectionReuseStrategy.INSTANCE
                        : (request, response, context) -> false;
            } else {
                reuseStrategyCopy = DefaultClientConnectionReuseStrategy.INSTANCE;
            }
        }
        return reuseStrategyCopy;
    }

    private String getProperty(final String key, final String def) {
        return AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getProperty(key, def));
    }
}
