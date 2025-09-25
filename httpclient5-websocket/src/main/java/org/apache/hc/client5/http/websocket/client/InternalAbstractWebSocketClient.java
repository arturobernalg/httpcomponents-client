package org.apache.hc.client5.http.websocket.client;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.support.WebSocketRequester;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Args;

/**
 * Internal base that owns lifecycle + the core connect flow.
 */
abstract class InternalAbstractWebSocketClient extends CloseableWebSocketClient {

    enum State {NEW, RUNNING, CLOSED}

    protected final WebSocketRequester wsRequester;
    protected final WebSocketClientConfig defaultConfig;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    protected InternalAbstractWebSocketClient(
            final WebSocketRequester wsRequester,
            final WebSocketClientConfig defaultConfig) {
        this.wsRequester = Args.notNull(wsRequester, "wsRequester");
        this.defaultConfig = defaultConfig != null ? defaultConfig : WebSocketClientConfig.custom().build();
    }

    /**
     * Subclass should start underlying requester/reactor.
     */
    protected abstract void doStart();

    /**
     * Subclass should close underlying requester/reactor + pool.
     */
    protected abstract void doClose(CloseMode mode);

    @Override
    public final void start() {
        if (state.compareAndSet(State.NEW, State.RUNNING)) {
            doStart();
        }
    }

    @Override
    public final void close(final CloseMode mode) {
        if (state.getAndSet(State.CLOSED) != State.CLOSED) {
            doClose(mode != null ? mode : CloseMode.GRACEFUL);
        }
    }

    @Override
    public final CompletableFuture<WebSocket> connect(final URI uri, final WebSocketListener listener) {
        return connect(uri, listener, defaultConfig);
    }

    public final CompletableFuture<WebSocket> connect(
            final URI uri, final WebSocketListener listener, final WebSocketClientConfig cfg) {
        if (state.get() == State.NEW) start();
        if (state.get() == State.CLOSED) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Client is closed"));
            return f;
        }
        Args.notNull(uri, "uri");
        Args.notNull(listener, "listener");
        Args.notNull(cfg, "cfg");
        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
            final CompletableFuture<WebSocket> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("Scheme must be ws or wss"));
            return f;
        }
        return doConnect(uri, listener, cfg);
    }

    /**
     * Concrete class implements the actual upgrade logic (your Option A connect).
     */
    protected abstract CompletableFuture<WebSocket> doConnect(URI uri, WebSocketListener listener, WebSocketClientConfig cfg);
}
