package org.apache.hc.client5.http.websocket.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.util.TimeValue;

public abstract class CloseableWebSocketClient implements AutoCloseable {

    // Lifecycle (mirror CloseableHttpAsyncClient style)
    public abstract void start();

    public abstract IOReactorStatus getStatus();

    public abstract void awaitShutdown(TimeValue waitTime) throws InterruptedException;

    public abstract void initiateShutdown();

    public abstract void close(CloseMode closeMode);

    @Override
    public final void close() {
        close(CloseMode.GRACEFUL);
    }

    // API
    public abstract CompletableFuture<WebSocket> connect(URI uri,
                                                         org.apache.hc.client5.http.websocket.api.WebSocketListener listener);

    public abstract CompletableFuture<WebSocket> connect(URI uri,
                                                         org.apache.hc.client5.http.websocket.api.WebSocketListener listener,
                                                         WebSocketClientConfig cfg);

}
