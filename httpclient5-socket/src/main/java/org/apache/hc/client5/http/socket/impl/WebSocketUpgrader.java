package org.apache.hc.client5.http.socket.impl;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.socket.WebSocket;
import org.apache.hc.client5.http.socket.WebSocketClientConfig;
import org.apache.hc.client5.http.socket.WebSocketListener;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.reactor.EventMask;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.reactor.ProtocolUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs WsHandler via HttpCore protocol-upgrade API (optionally with PMCE).
 */
public final class WebSocketUpgrader implements ProtocolUpgradeHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketUpgrader.class);

    private final WebSocketListener listener;
    private final WebSocketClientConfig cfg;
    private final PerMessageDeflate pmce; // may be null
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();

    public WebSocketUpgrader(final WebSocketListener listener, final WebSocketClientConfig cfg, final PerMessageDeflate pmce) {
        this.listener = listener;
        this.cfg = cfg;
        this.pmce = pmce;
    }

    @Override
    public void upgrade(final ProtocolIOSession ioSession, final FutureCallback<ProtocolIOSession> callback) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Installing WsHandler on {}", ioSession);
        }
        final WsHandler handler = new WsHandler(ioSession, listener, cfg, pmce);
        ioSession.upgrade(handler);
        ioSession.setEventMask(EventMask.READ | EventMask.WRITE);
        wsRef.set(handler.exposeWebSocket());
        if (callback != null) {
            callback.completed(ioSession);
        }
    }

    public WebSocket getWebSocket() {
        return wsRef.get();
    }
}
