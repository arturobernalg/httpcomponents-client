/*
 * Licensed to the Apache Software Foundation (ASF) ...
 */
package org.apache.hc.client5.http.websocket.client;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle shell backed by HttpAsyncRequester.
 * No direct IOReactor access required.
 */
abstract class AbstractWebSocketClientBase extends CloseableWebSocketClient {

    enum Status {
        INACTIVE,
        ACTIVE,
        SHUT_DOWN
    }

    private static final Logger LOG = LoggerFactory.getLogger(AbstractWebSocketClientBase.class);

    protected final HttpAsyncRequester requester;
    protected final ManagedConnPool<HttpHost, IOSession> connPool;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.INACTIVE);

    protected AbstractWebSocketClientBase(
            final HttpAsyncRequester requester,
            final ManagedConnPool<HttpHost, IOSession> connPool) {
        this.requester = requester;
        this.connPool = connPool;
    }

    @Override
    public final void start() {
        requester.start();
        status.set(Status.ACTIVE);
    }

    @Override
    public final IOReactorStatus getStatus() {
        switch (status.get()) {
            case ACTIVE:
                return IOReactorStatus.ACTIVE;
            case SHUT_DOWN:
                return IOReactorStatus.SHUT_DOWN;
            default:
                return IOReactorStatus.INACTIVE;
        }
    }

    @Override
    public final void awaitShutdown(final TimeValue waitTime) throws InterruptedException {
        Thread.sleep(waitTime != null ? waitTime.toMilliseconds() : 0L);
    }

    @Override
    public final void initiateShutdown() {
        requester.initiateShutdown();
    }

    @Override
    public final void close(final CloseMode closeMode) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Shutdown {}", closeMode);
        }
        requester.close(closeMode != null ? closeMode : CloseMode.GRACEFUL);
        status.set(Status.SHUT_DOWN);
    }
}
