package org.apache.hc.client5.http.websocket.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractWebSocketBase extends CloseableWebSocketClient {

    enum Status { READY, RUNNING, TERMINATED }

    private static final Logger LOG = LoggerFactory.getLogger(AbstractWebSocketBase.class);

    private final HttpAsyncRequester requester;
    private final ExecutorService executorService;
    private final AtomicReference<Status> status;

    AbstractWebSocketBase(final HttpAsyncRequester requester, final ThreadFactory threadFactory) {
        super();
        this.requester = Args.notNull(requester, "requester");
        this.executorService = Executors.newSingleThreadExecutor(threadFactory);
        this.status = new AtomicReference<>(Status.READY);
    }

    @Override
    public final void start() {
        if (status.compareAndSet(Status.READY, Status.RUNNING)) {
            executorService.execute(requester::start);
        }
    }

    boolean isRunning() {
        return status.get() == Status.RUNNING;
    }

    @Override
    public final IOReactorStatus getStatus() {
        return requester.getStatus();
    }

    @Override
    public final void awaitShutdown(final TimeValue waitTime) throws InterruptedException {
        requester.awaitShutdown(waitTime);
    }

    @Override
    public final void initiateShutdown() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Initiating shutdown");
        }
        requester.initiateShutdown();
    }

    void internalClose(final CloseMode closeMode) {
    }

    @Override
    public final void close(final CloseMode closeMode) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Shutdown {}", closeMode);
        }
        requester.initiateShutdown();
        requester.close(closeMode != null ? closeMode : CloseMode.GRACEFUL);
        executorService.shutdownNow();
        internalClose(closeMode);
    }

    @Override
    public void close() {
        close(CloseMode.GRACEFUL);
    }
}
