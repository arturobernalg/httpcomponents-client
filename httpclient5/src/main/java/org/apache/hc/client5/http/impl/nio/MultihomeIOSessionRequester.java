/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.client5.http.impl.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.apache.hc.client5.http.ConnectExceptionSupport;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.concurrent.ComplexFuture;
import org.apache.hc.core5.concurrent.DefaultThreadFactory;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MultihomeIOSessionRequester {

    private static final Logger LOG = LoggerFactory.getLogger(MultihomeIOSessionRequester.class);

    /**
     * RFC 8305, Section 8: "When a reasonable value is unknown, implementations SHOULD use a
     * Connection Attempt Delay of 250 milliseconds".
     */
    static final TimeValue DEFAULT_CONNECTION_ATTEMPT_DELAY = TimeValue.ofMilliseconds(250);

    /**
     * RFC 8305, Section 8: "this value SHOULD NOT be below 10 ms".
     */
    private static final long MIN_CONNECTION_ATTEMPT_DELAY_MS = 10;

    /**
     * Lazily initialized, process-wide, single daemon-thread scheduler used solely to pace
     * staggered connection attempts (RFC 8305 connection racing). Shared across all instances
     * so that enabling this feature never adds a thread per connection manager, let alone per
     * connection or per request.
     */
    private static final class LazyStaggerScheduler {
        static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("Connection-attempt-stagger", true));
    }

    private final DnsResolver dnsResolver;
    private final boolean happyEyeballsEnabled;
    private final TimeValue connectionAttemptDelay;
    private final ScheduledExecutorService staggerScheduler;

    MultihomeIOSessionRequester(final DnsResolver dnsResolver) {
        this(dnsResolver, false, DEFAULT_CONNECTION_ATTEMPT_DELAY);
    }

    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, null);
    }

    // Package-private: allows unit tests to supply a deterministic, synchronous scheduler
    // instead of the real, time-based one.
    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final ScheduledExecutorService staggerScheduler) {
        this.dnsResolver = dnsResolver != null ? dnsResolver : SystemDefaultDnsResolver.INSTANCE;
        this.happyEyeballsEnabled = happyEyeballsEnabled;
        final TimeValue delay = connectionAttemptDelay != null ? connectionAttemptDelay : DEFAULT_CONNECTION_ATTEMPT_DELAY;
        if (happyEyeballsEnabled) {
            Args.check(delay.toMilliseconds() >= MIN_CONNECTION_ATTEMPT_DELAY_MS,
                    "Connection attempt delay must be at least %s ms (RFC 8305, Section 8)", MIN_CONNECTION_ATTEMPT_DELAY_MS);
        }
        this.connectionAttemptDelay = delay;
        this.staggerScheduler = staggerScheduler;
    }

    private ScheduledExecutorService staggerScheduler() {
        return staggerScheduler != null ? staggerScheduler : LazyStaggerScheduler.INSTANCE;
    }

    public Future<IOSession> connect(
            final ConnectionInitiator connectionInitiator,
            final NamedEndpoint remoteEndpoint,
            final SocketAddress remoteAddress,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final FutureCallback<IOSession> callback) {
        return connect(connectionInitiator, remoteEndpoint, remoteAddress, localAddress, connectTimeout, attachment, null, callback);
    }

    /**
     * Same as {@link #connect(ConnectionInitiator, NamedEndpoint, SocketAddress, SocketAddress,
     * Timeout, Object, FutureCallback)}, but every session this call connects is first passed to
     * {@code validator}, if one is given, before being reported as the final outcome.
     *
     * @param validator accepts or rejects a freshly connected session; may be {@code null} to
     *                   accept every connected session unconditionally, as the overload without
     *                   this parameter does.
     */
    Future<IOSession> connect(
            final ConnectionInitiator connectionInitiator,
            final NamedEndpoint remoteEndpoint,
            final SocketAddress remoteAddress,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final SessionValidator validator,
            final FutureCallback<IOSession> callback) {

        final ComplexFuture<IOSession> future = new ComplexFuture<>(callback);
        if (remoteAddress != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:{} connecting {} to {} ({})",
                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, connectTimeout);
            }
            final Future<IOSession> sessionFuture = connectionInitiator.connect(remoteEndpoint, remoteAddress, localAddress, connectTimeout, attachment, new FutureCallback<IOSession>() {
                @Override
                public void completed(final IOSession session) {
                    acceptOrReject(session, validator, future,
                            // A single, explicitly given address has no fallback to offer.
                            cause -> enhanceAndFail(future, remoteEndpoint, cause,
                                    (remoteAddress instanceof InetSocketAddress) ?
                                            new InetAddress[] { ((InetSocketAddress) remoteAddress).getAddress() } :
                                            new InetAddress[] {}));
                }

                @Override
                public void failed(final Exception cause) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("{}:{} connection to {} failed ({}); terminating operation",
                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, cause.getClass());
                    }
                    enhanceAndFail(future, remoteEndpoint, cause,
                            (remoteAddress instanceof InetSocketAddress) ?
                                    new InetAddress[] { ((InetSocketAddress) remoteAddress).getAddress() } :
                                    new InetAddress[] {});
                }

                @Override
                public void cancelled() {
                    future.cancel();
                }

            });
            future.setDependency(sessionFuture);
            return future;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("{} resolving remote address", remoteEndpoint.getHostName());
        }

        final List<InetSocketAddress> remoteAddresses;
        try {
            remoteAddresses = dnsResolver.resolve(remoteEndpoint.getHostName(), remoteEndpoint.getPort());
            if (remoteAddresses == null || remoteAddresses.isEmpty()) {
                throw new UnknownHostException(remoteEndpoint.getHostName());
            }
        } catch (final UnknownHostException ex) {
            future.failed(ex);
            return future;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("{} resolved to {}", remoteEndpoint.getHostName(), remoteAddresses);
        }

        if (happyEyeballsEnabled && remoteAddresses.size() > 1) {
            final Race race = new Race(connectionInitiator, remoteEndpoint, remoteAddresses, localAddress,
                    connectTimeout, attachment, validator, future);
            race.start();
            return future;
        }

        connectSequentially(connectionInitiator, remoteEndpoint, remoteAddresses, localAddress,
                connectTimeout, attachment, validator, future);
        return future;
    }

    public Future<IOSession> connect(
            final ConnectionInitiator connectionInitiator,
            final NamedEndpoint remoteEndpoint,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final FutureCallback<IOSession> callback) {
        return connect(connectionInitiator, remoteEndpoint, null, localAddress, connectTimeout, attachment, null, callback);
    }

    /**
     * Accepts or rejects a session that has just connected, before {@link #connect} reports it
     * as the final outcome. A rejected session is closed, and {@link #connect} tries the next
     * address, if one remains, rather than surfacing a connected session that failed a check
     * layered on top of the raw connection (for example a TLS handshake) as a successful
     * outcome. Within a race, a rejection ends racing rather than continuing it: the remaining
     * addresses, if any, are tried sequentially (see {@link Race#onWinnerRejected}).
     *
     * <p>The exception type reported to the final {@code callback} on rejection is not
     * necessarily the one this validator reported: a rejection that exhausts every address is
     * folded in with ordinary connect failures through {@link
     * org.apache.hc.client5.http.ConnectExceptionSupport#enhance}, which can, for example,
     * remap a timeout into {@code ConnectTimeoutException} regardless of whether the timeout
     * happened during the TCP connect or a validator check afterward.</p>
     */
    @FunctionalInterface
    interface SessionValidator {
        void validate(IOSession session, FutureCallback<IOSession> callback);
    }

    private static void enhanceAndFail(
            final ComplexFuture<IOSession> future,
            final NamedEndpoint remoteEndpoint,
            final Exception cause,
            final InetAddress[] addresses) {
        if (cause instanceof IOException) {
            future.failed(ConnectExceptionSupport.enhance((IOException) cause, remoteEndpoint, addresses));
        } else {
            future.failed(cause);
        }
    }

    /**
     * Runs {@code validator} against a freshly connected {@code session}, if one is given;
     * otherwise accepts the session unconditionally. On rejection, closes the session and
     * invokes {@code onRejected} with the rejection cause so the caller can fall back to
     * another address, or fail outright if none remains.
     */
    private static void acceptOrReject(
            final IOSession session,
            final SessionValidator validator,
            final ComplexFuture<IOSession> future,
            final Consumer<Exception> onRejected) {
        if (validator == null) {
            deliver(session, future);
            return;
        }
        validator.validate(session, new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession validatedSession) {
                deliver(validatedSession, future);
            }

            @Override
            public void failed(final Exception cause) {
                session.close(CloseMode.IMMEDIATE);
                onRejected.accept(cause);
            }

            @Override
            public void cancelled() {
                session.close(CloseMode.IMMEDIATE);
                future.cancel();
            }
        });
    }

    /**
     * Completes {@code future} with {@code session}, unless it was already completed or
     * cancelled by something else (for example the caller, concurrently) in the brief window
     * before this call: {@link ComplexFuture#completed} reports that by returning {@code false},
     * in which case the session must still be closed here rather than silently leaked.
     */
    private static void deliver(final IOSession session, final ComplexFuture<IOSession> future) {
        if (!future.completed(session)) {
            session.close(CloseMode.IMMEDIATE);
        }
    }

    /**
     * Connects to each of {@code remoteAddresses} in turn, stopping at the first one that
     * completes both the TCP connection and, where {@code validator} is given, its check. A
     * TCP-level failure or a validator rejection moves on to the next address; the last
     * address's failure is reported via {@code future}, enhanced via
     * {@link ConnectExceptionSupport}.
     */
    private void connectSequentially(
            final ConnectionInitiator connectionInitiator,
            final NamedEndpoint remoteEndpoint,
            final List<InetSocketAddress> remoteAddresses,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final SessionValidator validator,
            final ComplexFuture<IOSession> future) {

        final Runnable runnable = new Runnable() {

            private final AtomicInteger attempt = new AtomicInteger(0);

            // Guards the body of executeNext() against a different thread concurrently
            // rejecting the same attempt and recursing into executeNext() for the next one: an
            // unguarded reader could otherwise observe this attempt's index as still current
            // and set future's dependency back to its (already superseded) session future,
            // losing track of the one the other thread already started. A same-thread,
            // reentrant rejection (recursing into the next attempt before this call returns)
            // needs no locking to stay correct: it is covered instead by the attempt-index
            // check at the end of this method, since ReentrantLock already permits the
            // recursive re-entry on the same thread regardless.
            private final ReentrantLock lock = new ReentrantLock();

            void executeNext() {
                lock.lock();
                try {
                    executeNextLocked();
                } finally {
                    lock.unlock();
                }
            }

            private void executeNextLocked() {
                if (future.isDone()) {
                    // The caller already cancelled, or the operation otherwise already
                    // completed; starting another attempt now would only leak a connection
                    // nobody will ever use.
                    return;
                }
                final int index = attempt.getAndIncrement();
                final InetSocketAddress remoteAddress = remoteAddresses.get(index);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} connecting {}->{} ({})",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, connectTimeout);
                }

                final Future<IOSession> sessionFuture = connectionInitiator.connect(
                        remoteEndpoint,
                        remoteAddress,
                        localAddress,
                        connectTimeout,
                        attachment,
                        new FutureCallback<IOSession>() {

                            @Override
                            public void completed(final IOSession session) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("{}:{} connected {}->{} as {}",
                                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, session.getId());
                                }
                                acceptOrReject(session, validator, future, cause -> onFailure(remoteAddress, cause));
                            }

                            @Override
                            public void failed(final Exception cause) {
                                onFailure(remoteAddress, cause);
                            }

                            @Override
                            public void cancelled() {
                                future.cancel();
                            }

                        });
                // A same-thread, reentrant rejection above can already have recursed into
                // executeNext() for the next attempt and set future's dependency to it before
                // this line runs; only apply this (now stale) assignment if no later attempt
                // has started in the meantime.
                if (attempt.get() == index + 1) {
                    // ComplexFuture#setDependency checks isDone() and then sets the dependency
                    // as two separate steps, which can still interleave with a concurrent
                    // #cancel() between them; a cancellation landing in that narrow window is
                    // lost. Closing it needs a change to ComplexFuture itself.
                    future.setDependency(sessionFuture);
                }
            }

            private void onFailure(final InetSocketAddress remoteAddress, final Exception cause) {
                if (future.isDone()) {
                    // The caller already cancelled, or the operation otherwise already
                    // completed; starting another attempt now would only leak a connection
                    // nobody will ever use.
                    return;
                }
                if (attempt.get() >= remoteAddresses.size()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("{}:{} connection to {} failed ({}); terminating operation",
                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, cause.getClass());
                    }
                    final InetAddress[] addresses = remoteAddresses.stream()
                            .filter(addr -> addr instanceof InetSocketAddress)
                            .map(addr -> ((InetSocketAddress) addr).getAddress())
                            .toArray(InetAddress[]::new);
                    enhanceAndFail(future, remoteEndpoint, cause, addresses);
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("{}:{} connection to {} failed ({}); retrying connection to the next address",
                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, cause.getClass());
                    }
                    executeNext();
                }
            }

            @Override
            public void run() {
                executeNext();
            }

        };
        runnable.run();
    }

    /**
     * RFC 8305 connection racing: the first address is tried immediately; each subsequent
     * address is tried after {@link #connectionAttemptDelay} unless an outstanding attempt
     * fails first, in which case the next address is tried immediately. The first successful
     * connection wins: all other outstanding attempts are cancelled, and any connection that
     * completes successfully after a winner has already been decided is closed rather than
     * leaked. Exactly one terminal callback (success or aggregated failure) ever reaches
     * {@code future}.
     */
    private final class Race implements Cancellable {

        private final ConnectionInitiator connectionInitiator;
        private final NamedEndpoint remoteEndpoint;
        private final List<InetSocketAddress> remoteAddresses;
        private final SocketAddress localAddress;
        private final Timeout connectTimeout;
        private final Object attachment;
        private final SessionValidator validator;
        private final ComplexFuture<IOSession> future;

        private final ReentrantLock lock = new ReentrantLock();
        private final List<Future<IOSession>> inFlight = new ArrayList<>();
        private int nextIndex;
        private int outstanding;
        private boolean decided;
        private boolean cancelled;
        private ScheduledFuture<?> pendingStagger;
        private long lastAttemptStartNanos = -1L;

        Race(
                final ConnectionInitiator connectionInitiator,
                final NamedEndpoint remoteEndpoint,
                final List<InetSocketAddress> remoteAddresses,
                final SocketAddress localAddress,
                final Timeout connectTimeout,
                final Object attachment,
                final SessionValidator validator,
                final ComplexFuture<IOSession> future) {
            this.connectionInitiator = connectionInitiator;
            this.remoteEndpoint = remoteEndpoint;
            this.remoteAddresses = remoteAddresses;
            this.localAddress = localAddress;
            this.connectTimeout = connectTimeout;
            this.attachment = attachment;
            this.validator = validator;
            this.future = future;
            // A single Cancellable dependency covers every attempt this race ever starts,
            // including ones not yet started when the caller cancels (see #cancel()).
            this.future.setDependency(this);
        }

        void start() {
            lock.lock();
            try {
                scheduleNextPromptlyLocked();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Starts the next address now if at least the RFC 8305 Section 5 minimum
         * inter-attempt spacing (10ms) has passed since the last attempt was started,
         * including one that just failed or was unexpectedly cancelled; otherwise schedules
         * it for exactly the remaining part of that minimum. Used whenever an attempt should
         * advance as promptly as RFC 8305 allows, rather than waiting out the full
         * {@link #connectionAttemptDelay}.
         *
         * <p>Must be called while holding {@code lock}.</p>
         */
        private void scheduleNextPromptlyLocked() {
            if (decided || cancelled || nextIndex >= remoteAddresses.size()) {
                return;
            }
            if (lastAttemptStartNanos < 0) {
                startNextLocked();
                return;
            }
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastAttemptStartNanos);
            if (elapsedMs >= MIN_CONNECTION_ATTEMPT_DELAY_MS) {
                startNextLocked();
            } else {
                pendingStagger = staggerScheduler().schedule(
                        this::onStaggerElapsed, MIN_CONNECTION_ATTEMPT_DELAY_MS - elapsedMs, TimeUnit.MILLISECONDS);
            }
        }

        // Must be called while holding `lock`.
        private void startNextLocked() {
            if (decided || cancelled || nextIndex >= remoteAddresses.size()) {
                return;
            }
            final int index = nextIndex++;
            final InetSocketAddress remoteAddress = remoteAddresses.get(index);
            outstanding++;
            lastAttemptStartNanos = System.nanoTime();

            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:{} racing attempt {} {}->{} ({})",
                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), index, localAddress, remoteAddress, connectTimeout);
            }

            final Future<IOSession> attemptFuture = connectionInitiator.connect(
                    remoteEndpoint,
                    remoteAddress,
                    localAddress,
                    connectTimeout,
                    attachment,
                    new FutureCallback<IOSession>() {

                        @Override
                        public void completed(final IOSession session) {
                            onAttemptCompleted(remoteAddress, session);
                        }

                        @Override
                        public void failed(final Exception cause) {
                            onAttemptFailed(remoteAddress, cause);
                        }

                        @Override
                        public void cancelled() {
                            onAttemptCancelled(remoteAddress);
                        }

                    });
            inFlight.add(attemptFuture);

            // pendingStagger != null here means the attempt above already completed
            // synchronously and its own failure/cancellation handler already scheduled the
            // next advance (possibly at the shorter minimum spacing): don't overwrite it.
            if (!decided && !cancelled && pendingStagger == null && nextIndex < remoteAddresses.size()) {
                pendingStagger = staggerScheduler().schedule(
                        this::onStaggerElapsed, connectionAttemptDelay.getDuration(), connectionAttemptDelay.getTimeUnit());
            }
        }

        private void onStaggerElapsed() {
            lock.lock();
            try {
                pendingStagger = null;
                startNextLocked();
            } finally {
                lock.unlock();
            }
        }

        private void onAttemptCompleted(final InetSocketAddress remoteAddress, final IOSession session) {
            final boolean won;
            final List<Future<IOSession>> losers;
            lock.lock();
            try {
                outstanding--;
                won = !decided && !cancelled;
                if (won) {
                    decided = true;
                    cancelPendingStaggerLocked();
                    losers = new ArrayList<>(inFlight);
                } else {
                    losers = null;
                }
            } finally {
                lock.unlock();
            }
            if (won) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} race won by {}->{} as {}",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, session.getId());
                }
                for (final Future<IOSession> loser : losers) {
                    loser.cancel(true);
                }
                acceptOrReject(session, validator, future, cause -> onWinnerRejected(remoteAddress, cause));
            } else {
                // A duplicate winner (concurrent success) or a late completion after the
                // race was already decided/cancelled: never leak the socket.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} discarding late successful connection {}->{} ({})",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, session.getId());
                }
                session.close(CloseMode.IMMEDIATE);
            }
        }

        /**
         * The address that won the TCP race failed {@link #validator}'s check (for example, a
         * TLS handshake layered on top of it). Racing is over by this point: every other
         * in-flight attempt was already cancelled when the winner was decided, and any address
         * not yet attempted is simply never tried, so the remaining addresses, if any, are
         * tried sequentially, exactly as the base non-racing path tries its addresses: the
         * reliability this race was built to provide now comes from trying every address, not
         * from trying several of them at once.
         */
        private void onWinnerRejected(final InetSocketAddress winnerAddress, final Exception cause) {
            final List<InetSocketAddress> remaining = withoutAddress(remoteAddresses, winnerAddress);
            if (remaining.isEmpty()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} connection to {} failed validation after racing ({}); no address remains",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), winnerAddress, cause.getClass());
                }
                enhanceAndFail(future, remoteEndpoint, cause, new InetAddress[] { winnerAddress.getAddress() });
                return;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:{} connection to {} failed validation after racing ({}); retrying connection to the remaining address(es)",
                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), winnerAddress, cause.getClass());
            }
            connectSequentially(connectionInitiator, remoteEndpoint, remaining, localAddress,
                    connectTimeout, attachment, validator, future);
        }

        private void onAttemptFailed(final InetSocketAddress remoteAddress, final Exception cause) {
            boolean advance = false;
            boolean last = false;
            lock.lock();
            try {
                outstanding--;
                if (decided || cancelled) {
                    return;
                }
                cancelPendingStaggerLocked();
                if (nextIndex < remoteAddresses.size()) {
                    advance = true;
                } else if (outstanding == 0) {
                    decided = true;
                    last = true;
                }
                if (advance) {
                    // RFC 8305 Section 5: advance to the next address as promptly as possible
                    // after a failure, never waiting out the full connection attempt delay,
                    // but still respecting the minimum inter-attempt spacing.
                    scheduleNextPromptlyLocked();
                }
            } finally {
                lock.unlock();
            }
            if (last) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} connection to {} failed ({}); all addresses exhausted",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, cause.getClass());
                }
                finish(cause);
            }
        }

        /**
         * An attempt may be cancelled by something other than this race itself deciding a
         * winner or being cancelled as a whole, for example an external close of the
         * connection manager. Treat it exactly like a failure so the overall future never
         * stays pending forever: advance to the next address if one remains, or finish with a
         * failure if none does.
         */
        private void onAttemptCancelled(final InetSocketAddress remoteAddress) {
            boolean advance = false;
            boolean last = false;
            lock.lock();
            try {
                outstanding--;
                if (decided || cancelled) {
                    // Expected: either this race decided a winner and cancelled every other
                    // attempt itself, or the whole race was cancelled from the outside.
                    return;
                }
                cancelPendingStaggerLocked();
                if (nextIndex < remoteAddresses.size()) {
                    advance = true;
                } else if (outstanding == 0) {
                    decided = true;
                    last = true;
                }
                if (advance) {
                    scheduleNextPromptlyLocked();
                }
            } finally {
                lock.unlock();
            }
            if (last) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} connection attempt to {} was unexpectedly cancelled; all addresses exhausted",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress);
                }
                finish(new CancellationException(
                        "Connection attempt to " + remoteAddress + " was cancelled"));
            }
        }

        private void finish(final Exception cause) {
            if (cause instanceof IOException) {
                final InetAddress[] addresses = remoteAddresses.stream()
                        .map(InetSocketAddress::getAddress)
                        .toArray(InetAddress[]::new);
                future.failed(ConnectExceptionSupport.enhance((IOException) cause, remoteEndpoint, addresses));
            } else {
                future.failed(cause);
            }
        }

        @Override
        public boolean cancel() {
            final List<Future<IOSession>> toCancel;
            lock.lock();
            try {
                if (decided || cancelled) {
                    return false;
                }
                cancelled = true;
                cancelPendingStaggerLocked();
                toCancel = new ArrayList<>(inFlight);
            } finally {
                lock.unlock();
            }
            for (final Future<IOSession> attemptFuture : toCancel) {
                attemptFuture.cancel(true);
            }
            return true;
        }

        // Must be called while holding `lock`.
        private void cancelPendingStaggerLocked() {
            if (pendingStagger != null) {
                pendingStagger.cancel(false);
                pendingStagger = null;
            }
        }

    }

    /**
     * Removes the first occurrence of {@code excluded} from {@code addresses}, preserving the
     * order of the rest.
     */
    private static List<InetSocketAddress> withoutAddress(
            final List<InetSocketAddress> addresses, final InetSocketAddress excluded) {
        final List<InetSocketAddress> remaining = new ArrayList<>(addresses.size());
        boolean removedOne = false;
        for (final InetSocketAddress address : addresses) {
            if (!removedOne && address.equals(excluded)) {
                removedOne = true;
                continue;
            }
            remaining.add(address);
        }
        return remaining;
    }

}
