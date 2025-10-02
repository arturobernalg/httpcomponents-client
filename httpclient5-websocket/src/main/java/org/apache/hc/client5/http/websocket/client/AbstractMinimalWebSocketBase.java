package org.apache.hc.client5.http.websocket.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.protocol.HttpContext;

@Contract(threading = ThreadingBehavior.STATELESS)
abstract class AbstractMinimalWebSocketBase extends AbstractWebSocketBase {

    AbstractMinimalWebSocketBase(final HttpAsyncRequester requester, final ThreadFactory threadFactory) {
        super(requester, threadFactory);
    }

    @Override
    protected abstract CompletableFuture<WebSocket> doConnect(
            URI uri, WebSocketListener listener, WebSocketClientConfig cfg, HttpContext context);
}
