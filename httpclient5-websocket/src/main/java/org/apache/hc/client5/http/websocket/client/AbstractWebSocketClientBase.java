package org.apache.hc.client5.http.websocket.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.nio.AsyncClientEndpoint;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

abstract class AbstractWebSocketClientBase extends CloseableWebSocketClient {

    protected final HttpAsyncRequester requester;
    protected final ManagedConnPool<HttpHost, IOSession> connPool;
    protected final WebSocketClientConfig defaultConfig;
    private final AtomicReference<IOReactorStatus> status = new AtomicReference<>(IOReactorStatus.INACTIVE);

    protected AbstractWebSocketClientBase(final HttpAsyncRequester requester,
                                          final ManagedConnPool<HttpHost, IOSession> connPool,
                                          final WebSocketClientConfig defaultConfig) {
        this.requester = requester;
        this.connPool = connPool;
        this.defaultConfig = defaultConfig;
    }

    @Override
    public void start() {
        requester.start();
        status.set(IOReactorStatus.ACTIVE);
    }

    @Override
    public IOReactorStatus getStatus() {
        return status.get();
    }

    @Override
    public void awaitShutdown(final TimeValue waitTime) throws InterruptedException {
        // HttpAsyncRequester does not expose await; no-op is fine, mirrors other clients.
        Thread.sleep(waitTime != null ? waitTime.toMilliseconds() : 0L);
    }

    @Override
    public void initiateShutdown() {
        requester.initiateShutdown();
    }

    @Override
    public void close(final CloseMode closeMode) {
        requester.close(closeMode != null ? closeMode : CloseMode.GRACEFUL);
        status.set(IOReactorStatus.SHUT_DOWN);
    }

    @Override
    public final CompletableFuture<WebSocket> connect(final URI uri,
                                                      final org.apache.hc.client5.http.websocket.api.WebSocketListener listener) {
        return connect(uri, listener, defaultConfig);
    }

    // Subclass must implement using your existing upgrade logic
    @Override
    public abstract CompletableFuture<WebSocket> connect(URI uri,
                                                         org.apache.hc.client5.http.websocket.api.WebSocketListener listener,
                                                         WebSocketClientConfig cfg);
}
