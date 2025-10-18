package org.apache.hc.client5.http.websocket.client.impl;

import java.util.concurrent.ThreadFactory;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOSession;

@Contract(threading = ThreadingBehavior.SAFE_CONDITIONAL)
@Internal
public class DefaultWebSocketClient extends InternalWebSocketClientBase {

    public DefaultWebSocketClient(
            final HttpAsyncRequester h1Requester,
            final HttpAsyncRequester h2Requester,
            final ManagedConnPool<HttpHost, IOSession> connPool,
            final WebSocketClientConfig defaultConfig,
            final ThreadFactory threadFactory) {
        super(h1Requester, h2Requester, connPool, defaultConfig, threadFactory);
    }
}
