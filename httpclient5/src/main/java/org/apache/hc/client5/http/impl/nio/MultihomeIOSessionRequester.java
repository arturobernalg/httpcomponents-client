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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.apache.hc.client5.http.AddressOrderingStrategy;
import org.apache.hc.client5.http.AsyncDnsResolver;
import org.apache.hc.client5.http.ConnectExceptionSupport;
import org.apache.hc.client5.http.DnsResolutionCallback;
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
     * RFC 8305, Section 3: if the A (IPv4) response arrives before the AAAA (IPv6) one, wait
     * this long for AAAA before proceeding with IPv4 alone, so the preferred family is not
     * needlessly skipped because of reordering. "It is RECOMMENDED that the Resolution Delay
     * be set to 50 milliseconds." No such wait applies when AAAA arrives first or already has.
     */
    private static final TimeValue RESOLUTION_DELAY = TimeValue.ofMilliseconds(50);

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
    private final AsyncDnsResolver asyncDnsResolver;
    private final AddressOrderingStrategy addressOrderingStrategy;
    private final ScheduledExecutorService staggerScheduler;

    MultihomeIOSessionRequester(final DnsResolver dnsResolver) {
        this(dnsResolver, false, DEFAULT_CONNECTION_ATTEMPT_DELAY);
    }

    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, null, (ScheduledExecutorService) null);
    }

    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, asyncDnsResolver, (ScheduledExecutorService) null);
    }

    // Package-private: allows unit tests to supply a deterministic, synchronous scheduler
    // instead of the real, time-based one.
    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final ScheduledExecutorService staggerScheduler) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, null, staggerScheduler);
    }

    // Package-private: lets a caller configure the async-DNS merge path's address ordering
    // (see AddressOrderingStrategy) without also needing a custom stagger scheduler.
    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver,
            final AddressOrderingStrategy addressOrderingStrategy) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, asyncDnsResolver, addressOrderingStrategy, null);
    }

    // Package-private: pre-dates AddressOrderingStrategy; kept so existing call sites (and
    // tests) that control DNS resolution and stagger scheduling, but not address ordering,
    // need no change. Delegates to the full constructor with the default ordering.
    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver,
            final ScheduledExecutorService staggerScheduler) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, asyncDnsResolver, null, staggerScheduler);
    }

    // Package-private: full constructor, letting unit tests control the DNS resolution
    // strategy, the async-DNS merge path's address ordering, and stagger scheduling.
    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver,
            final AddressOrderingStrategy addressOrderingStrategy,
            final ScheduledExecutorService staggerScheduler) {
        this.dnsResolver = dnsResolver != null ? dnsResolver : SystemDefaultDnsResolver.INSTANCE;
        this.happyEyeballsEnabled = happyEyeballsEnabled;
        final TimeValue delay = connectionAttemptDelay != null ? connectionAttemptDelay : DEFAULT_CONNECTION_ATTEMPT_DELAY;
        if (happyEyeballsEnabled) {
            Args.check(delay.toMilliseconds() >= MIN_CONNECTION_ATTEMPT_DELAY_MS,
                    "Connection attempt delay must be at least %s ms (RFC 8305, Section 8)", MIN_CONNECTION_ATTEMPT_DELAY_MS);
        }
        this.connectionAttemptDelay = delay;
        this.asyncDnsResolver = asyncDnsResolver;
        this.addressOrderingStrategy = addressOrderingStrategy != null ? addressOrderingStrategy : AddressOrderingStrategy.simple();
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

        if (happyEyeballsEnabled && asyncDnsResolver != null) {
            return connectWithAsyncResolution(connectionInitiator, remoteEndpoint, localAddress, connectTimeout, attachment, validator, future);
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
     * The distinct addresses of {@code addresses}, in first-occurrence order. A losing race
     * attempt's address is re-queued as a fallback candidate (see {@link
     * Race#onAttemptCompleted}) and can therefore appear more than once by the time every
     * address is exhausted; the aggregated failure reported to the caller must still name
     * each one only once.
     */
    private static InetAddress[] distinctAddresses(final List<InetSocketAddress> addresses) {
        final Set<InetAddress> distinct = new LinkedHashSet<>();
        for (final InetSocketAddress address : addresses) {
            distinct.add(address.getAddress());
        }
        return distinct.toArray(new InetAddress[0]);
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
        acceptOrReject(session, validator, future, null, onRejected);
    }

    /**
     * Same as {@link #acceptOrReject(IOSession, SessionValidator, ComplexFuture, Consumer)},
     * but also invokes {@code onAccepted}, if one is given, the moment the session is accepted
     * (whether unconditionally, with no {@code validator}, or after passing its check). The
     * outcome is handed to {@code future} first, and {@code onAccepted} runs only afterward:
     * {@code onAccepted} may cancel a DNS resolution that is no longer needed, and a slow or
     * throwing cancellation must never delay, or risk losing, a session that already won.
     */
    private static void acceptOrReject(
            final IOSession session,
            final SessionValidator validator,
            final ComplexFuture<IOSession> future,
            final Runnable onAccepted,
            final Consumer<Exception> onRejected) {
        if (validator == null) {
            deliver(session, future);
            if (onAccepted != null) {
                onAccepted.run();
            }
            return;
        }
        validator.validate(session, new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession validatedSession) {
                // Deliver the winner before running onAccepted: onAccepted may cancel a DNS
                // resolution that is no longer needed, and a slow or throwing cancel() must
                // not delay, or risk losing, a session that has already passed validation.
                deliver(validatedSession, future);
                if (onAccepted != null) {
                    onAccepted.run();
                }
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
     * Resolves {@code remoteEndpoint} via the configured {@link AsyncDnsResolver}, racing
     * connection attempts against each address family's addresses as soon as that family
     * resolves rather than waiting for every family to complete first. If A (IPv4) arrives
     * before AAAA (IPv6), racing is held off for up to {@link #RESOLUTION_DELAY} to give the
     * preferred family a chance to arrive first (RFC 8305, Section 3). A family that resolves
     * after the race has already been decided starts no attempt of its own, but is still
     * recorded: if the winner then fails {@link SessionValidator}'s check, it is exactly what
     * {@link Race#onWinnerRejected} falls back to.
     *
     * <p>
     * Each time a family resolves, its addresses are merged into the race's still-pending list
     * via {@link #addressOrderingStrategy} (see {@link Race#mergeAddressDataLocked}). That
     * merge, not this method, is where RFC 6724-aware ordering, if configured, is applied: it
     * must still interleave families rather than pick one address ahead of racing (RFC 8305
     * Sections 3 and 6), since collapsing to a single "best" address here would defeat the
     * purpose of resolving both families independently in the first place.
     * </p>
     */
    private Future<IOSession> connectWithAsyncResolution(
            final ConnectionInitiator connectionInitiator,
            final NamedEndpoint remoteEndpoint,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final SessionValidator validator,
            final ComplexFuture<IOSession> future) {
        final Race race = new Race(connectionInitiator, remoteEndpoint, new ArrayList<>(), localAddress,
                connectTimeout, attachment, validator, future);
        race.markResolutionPending();
        race.start();
        final Cancellable resolution = asyncDnsResolver.resolve(
                remoteEndpoint.getHostName(),
                remoteEndpoint.getPort(),
                new DnsResolutionCallback() {

                    @Override
                    public void resolved(final List<InetSocketAddress> addresses) {
                        race.onAddressesResolved(addresses);
                    }

                    @Override
                    public void failed(final Exception cause) {
                        race.onResolutionFailed(cause);
                    }

                    @Override
                    public void completed() {
                        race.onResolutionComplete();
                    }

                });
        race.setResolutionCancellable(resolution);
        return future;
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
        // Slot `i` is reserved, under `lock`, the moment attempt `i` is assigned its index,
        // strictly before connectionInitiator#connect is called for it (see #startNextLocked):
        // a ConnectionInitiator is free to invoke its callback synchronously, which can
        // recurse into #startNextLocked for the next attempt before the call for this one
        // returns. Reserving the slot up front, rather than appending once connect() returns,
        // keeps inFlight.get(i) the future for attempt #i regardless of that reentrancy;
        // appending only on return would let the reentrant attempt's own append claim the
        // slot this attempt expects, shifting every later entry by one.
        private final List<Future<IOSession>> inFlight = new ArrayList<>();
        // Parallel to inFlight, same index-for-index: the address each entry in inFlight was
        // started against.
        private final List<InetSocketAddress> inFlightAddresses = new ArrayList<>();
        // Indices (into inFlight/inFlightAddresses) of attempts that have already failed or
        // been cancelled on their own merits, independently of losing a race: an attempt that
        // settles this way before a winner is decided is not a fallback candidate, unlike one
        // still genuinely racing at the moment a winner is chosen (see #onAttemptCompleted).
        private final Set<Integer> settledAttempts = new HashSet<>();
        private int nextIndex;
        private int outstanding;
        private boolean decided;
        // The index of the attempt that decided the race, set together with `decided` by
        // #onAttemptCompleted's winning branch; -1 whenever `decided` became true for any
        // other reason (every address exhausted with no winner). Lets #startNextLocked tell,
        // once a reentrantly-decided attempt's own connect() call finally returns and fills
        // its reserved #inFlight slot, whether that attempt is itself the winner (its future
        // must be left alone) or a loser the decision loop already tried and failed to cancel
        // because the slot was still null at the time (its future must be cancelled now).
        private int winnerIndex = -1;
        private boolean cancelled;
        // True only while waiting for #onAddressesResolved or #onResolutionComplete to
        // supply (or rule out) a fallback address after the TCP winner failed
        // SessionValidator's check; cleared the moment that wait is resolved, one way or
        // the other. #cancel relies on #winnerRejectedEver, not this one, to know whether
        // there is still a fallback attempt or a DNS resolution of this race's own left to
        // reach.
        private boolean winnerRejected;
        // Sticky once set: true from the TCP winner's first rejection onward for the rest of
        // the race's life, regardless of how many more times #winnerRejected itself toggles.
        // Lets #cancel tell "the winner was accepted, or its validation is still pending"
        // (nothing of this race's own left to cancel beyond its own bookkeeping) apart from
        // "the winner was rejected and this race is now driving its own fallback attempts
        // and possibly a DNS resolution", which must stay cancellable throughout.
        private boolean winnerRejectedEver;
        private Exception winnerRejectionCause;
        private ScheduledFuture<?> pendingStagger;
        private long lastAttemptStartNanos = -1L;
        // True once every address this race will ever see has been added, i.e. there is no
        // DNS resolution still in flight that might contribute further addresses. Always
        // true for a race constructed from a complete, pre-resolved address list; set to
        // false by #markResolutionPending() for a race driven by progressive, asynchronous
        // DNS resolution (see #connectWithAsyncResolution).
        private boolean resolutionComplete = true;
        private Cancellable resolution;
        // True once the TCP winner has passed #validator's check (or there was none to pass):
        // set once, together with clearing #resolution, by #onWinnerAccepted, and consulted by
        // #setResolutionCancellable in case it is called afterward with a resolution handle
        // #onWinnerAccepted never got to see, and so could not cancel itself.
        private boolean winnerAccepted;
        // The fallback attempt #tryNextFallbackAttempt currently has in flight, if any:
        // tracked separately from `inFlight` (which only ever holds racing attempts) so
        // #cancel can still reach it after a winner has been decided and racing is over.
        private Future<IOSession> fallbackAttempt;
        private Exception lastFailure;
        // RFC 8305 Section 3 Resolution Delay bookkeeping: set only while waiting up to
        // RESOLUTION_DELAY for AAAA after A arrived first.
        private boolean ipv6Resolved;
        private List<InetSocketAddress> bufferedIpv4;
        private ScheduledFuture<?> pendingResolutionDelay;

        /**
         * Wraps a {@code RuntimeException} that escapes while an attempt's completed(),
         * failed() or cancelled() callback is processing its outcome (see {@link
         * #processOutcome}): lets the {@code connect()} call still on the same stack, in
         * {@link #startNextLocked} or {@link #tryNextFallbackAttempt}, recognize such an
         * exception, whether it is this very attempt's own callback or a different attempt's
         * invoked reentrantly, and simply let it propagate, unwrapped, rather than mistaking
         * it for {@code connect()} itself never having invoked any callback at all. Never
         * escapes this class: every catch that can see one immediately unwraps it.
         */
        private final class AttemptOutcomeException extends RuntimeException {

            private static final long serialVersionUID = 1L;

            AttemptOutcomeException(final RuntimeException cause) {
                super(cause);
            }

            RuntimeException unwrap() {
                return (RuntimeException) getCause();
            }
        }

        /**
         * Runs {@code body}, which processes one attempt's outcome, wrapping any {@code
         * RuntimeException} it lets escape as an {@link AttemptOutcomeException}. See that
         * class for why.
         */
        private void processOutcome(final Runnable body) {
            try {
                body.run();
            } catch (final RuntimeException ex) {
                throw new AttemptOutcomeException(ex);
            }
        }

        /**
         * What {@link #startNextLocked} computed, under {@code lock}, when an attempt's
         * {@code connect()} call threw synchronously instead of ever invoking a callback: a
         * {@code ConnectionInitiator} that throws this way, rather than reporting failure
         * through the callback it was given, almost always does so because something applies
         * to every remaining address equally (for example {@code IOReactorShutdownException}
         * once the reactor's shutdown has been initiated). {@link #finishAbort} settles
         * {@code index} outside {@code lock}, exactly like an ordinary asynchronous failure
         * (see {@link #onAttemptFailed}) - concluding the race immediately if nothing else is
         * left outstanding, or simply leaving whatever already is to run its course - except
         * that {@link #startNextLocked} will already have dropped every address from
         * {@code index} on, under {@code lock}, so that call never retries any of them.
         */
        private final class RaceAbort {
            private final int index;
            private final InetSocketAddress remoteAddress;
            private final Exception cause;

            RaceAbort(final int index, final InetSocketAddress remoteAddress, final Exception cause) {
                this.index = index;
                this.remoteAddress = remoteAddress;
                this.cause = cause;
            }
        }

        /**
         * Must be called with {@code lock} NOT held. A no-op if {@code abort} is {@code null},
         * which it is whenever nothing synchronously threw.
         */
        private void finishAbort(final RaceAbort abort) {
            if (abort == null) {
                return;
            }
            onAttemptFailed(abort.index, abort.remoteAddress, abort.cause);
        }

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
            this.remoteAddresses = new ArrayList<>(remoteAddresses);
            this.localAddress = localAddress;
            this.connectTimeout = connectTimeout;
            this.attachment = attachment;
            this.validator = validator;
            this.future = future;
            // A single Cancellable dependency covers every attempt this race ever starts,
            // including ones not yet started when the caller cancels (see #cancel()).
            this.future.setDependency(this);
        }

        void markResolutionPending() {
            lock.lock();
            try {
                resolutionComplete = false;
            } finally {
                lock.unlock();
            }
        }

        void setResolutionCancellable(final Cancellable resolution) {
            // Not `decided`: a winner can already be chosen, with its validation still
            // pending, by the time this is called (a resolver may deliver one family and
            // have its winning attempt complete before #resolve even returns a handle to
            // pass here). Whether that pending validation will go on to need this
            // resolution is not yet known, so it must not be cancelled on that basis alone;
            // only an outright cancellation of the whole operation, or the winner going on to
            // be accepted (#onWinnerAccepted, which may itself already have run and found no
            // resolution here yet to cancel), makes it certain this resolution can never
            // matter.
            final boolean alreadyOver;
            lock.lock();
            try {
                alreadyOver = cancelled || winnerAccepted;
                if (!alreadyOver) {
                    this.resolution = resolution;
                }
            } finally {
                lock.unlock();
            }
            if (alreadyOver) {
                resolution.cancel();
            }
        }

        /**
         * Addresses resolved for one address family. RFC 8305 Section 3: if AAAA (IPv6)
         * arrives, it is merged in immediately. If A (IPv4) arrives and AAAA has neither
         * already resolved nor is ruled out, it is buffered and connecting is held off for up
         * to {@link #RESOLUTION_DELAY} to give the preferred family a chance to arrive first
         * (Section 6: the two are then interleaved); if AAAA never arrives in time, or the
         * resolution as a whole completes first, the buffered A addresses are merged in
         * without further delay.
         */
        void onAddressesResolved(final List<InetSocketAddress> addresses) {
            boolean resumeFallback = false;
            Exception resumeCause = null;
            RaceAbort abort = null;
            lock.lock();
            try {
                if (cancelled || addresses.isEmpty()) {
                    return;
                }
                final boolean isIpv6 = addresses.get(0).getAddress() instanceof Inet6Address;
                if (isIpv6) {
                    ipv6Resolved = true;
                    if (pendingResolutionDelay != null) {
                        pendingResolutionDelay.cancel(false);
                        pendingResolutionDelay = null;
                        // Both batches' data are merged in before either one is allowed to
                        // trigger starting (or exhausting) anything: merging AAAA alone first
                        // could otherwise reentrantly start an attempt that fails and
                        // concludes every address is exhausted, before the buffered IPv4
                        // addresses, merged by the next line, ever become visible to it.
                        final boolean hadNoNextAddress = nextIndex >= remoteAddresses.size();
                        RuntimeException orderingFailure = mergeAddressDataLocked(addresses, true);
                        final List<InetSocketAddress> buffered = bufferedIpv4;
                        bufferedIpv4 = null;
                        if (buffered != null) {
                            final RuntimeException bufferedFailure = mergeAddressDataLocked(buffered, false);
                            if (orderingFailure == null) {
                                orderingFailure = bufferedFailure;
                            } else if (bufferedFailure != null && LOG.isWarnEnabled()) {
                                LOG.warn("{}:{} address ordering strategy also failed merging the buffered address batch",
                                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), bufferedFailure);
                            }
                        }
                        // Trigger first, so a race that has nothing else outstanding still
                        // starts an attempt for whatever was just merged, even though the
                        // strategy failure below is about to be thrown.
                        abort = triggerAfterMergeLocked(hadNoNextAddress);
                        if (abort == null && orderingFailure != null) {
                            throw orderingFailure;
                        }
                    } else {
                        abort = mergeAddressesLocked(addresses, true);
                    }
                } else if (!ipv6Resolved && !resolutionComplete && pendingResolutionDelay == null && bufferedIpv4 == null) {
                    bufferedIpv4 = new ArrayList<>(addresses);
                    pendingResolutionDelay = staggerScheduler().schedule(
                            this::onResolutionDelayElapsed, RESOLUTION_DELAY.getDuration(), RESOLUTION_DELAY.getTimeUnit());
                    return;
                } else {
                    abort = mergeAddressesLocked(addresses, false);
                }
                if (abort == null) {
                    resumeFallback = takeFallbackIfWinnerRejectedLocked();
                    if (resumeFallback) {
                        resumeCause = winnerRejectionCause;
                    }
                }
            } finally {
                lock.unlock();
            }
            if (abort != null) {
                finishAbort(abort);
                return;
            }
            if (resumeFallback) {
                tryNextFallbackAttempt(resumeCause);
            }
        }

        private void onResolutionDelayElapsed() {
            boolean resumeFallback = false;
            Exception resumeCause = null;
            RaceAbort abort = null;
            lock.lock();
            try {
                pendingResolutionDelay = null;
                final List<InetSocketAddress> buffered = bufferedIpv4;
                bufferedIpv4 = null;
                if (cancelled || buffered == null) {
                    return;
                }
                abort = mergeAddressesLocked(buffered, false);
                if (abort == null) {
                    resumeFallback = takeFallbackIfWinnerRejectedLocked();
                    if (resumeFallback) {
                        resumeCause = winnerRejectionCause;
                    }
                }
            } finally {
                lock.unlock();
            }
            if (abort != null) {
                finishAbort(abort);
                return;
            }
            if (resumeFallback) {
                tryNextFallbackAttempt(resumeCause);
            }
        }

        /**
         * True, and clears the wait, if a merge just finished handing {@link
         * #tryNextFallbackAttempt}'s wait something to try that it did not have before. Must
         * be called while holding {@code lock}, right after a call to {@link
         * #mergeAddressesLocked}.
         */
        private boolean takeFallbackIfWinnerRejectedLocked() {
            if (!winnerRejected || nextIndex >= remoteAddresses.size()) {
                return false;
            }
            winnerRejected = false;
            return true;
        }

        /**
         * Merges newly resolved addresses of one family into the race. Addresses already
         * attempted (indices {@code < nextIndex}: in flight, succeeded or failed) are never
         * touched. The still-pending suffix is rebuilt by splitting it back into its two
         * families and re-interleaving with the new arrivals (RFC 8305 Section 4, IPv6
         * first), rather than simply appending the new addresses after whatever was already
         * pending, which would let a newly arrived, preferred AAAA address end up queued
         * behind an older, still-untried A address.
         *
         * <p>
         * {@link #triggerAfterMergeLocked} always runs, even if {@link #addressOrderingStrategy}
         * failed (see {@link #mergeAddressDataLocked}): a merge that fell back to {@link
         * AddressOrderingStrategy#simple()} must still start or pace an attempt exactly as a
         * successful merge would, rather than leaving a newly merged address stranded with
         * nothing to ever try it. The failure itself is thrown afterward, unless doing so would
         * discard a synchronous {@code connect()} failure of equal standing.
         * </p>
         *
         * <p>Must be called while holding {@code lock}.</p>
         *
         * @return what {@link #startNextLocked} computed if merging these addresses in
         * reentrantly started an attempt whose {@code connect()} call threw synchronously;
         * {@code null} otherwise. See {@link #finishAbort}.
         */
        private RaceAbort mergeAddressesLocked(final List<InetSocketAddress> newAddresses, final boolean newIsIpv6) {
            final boolean hadNoNextAddress = nextIndex >= remoteAddresses.size();
            final RuntimeException orderingFailure = mergeAddressDataLocked(newAddresses, newIsIpv6);
            final RaceAbort abort = triggerAfterMergeLocked(hadNoNextAddress);
            if (abort == null && orderingFailure != null) {
                throw orderingFailure;
            }
            return abort;
        }

        /**
         * The address-bookkeeping half of {@link #mergeAddressesLocked}, split out so a
         * caller merging more than one newly resolved batch in the same call (RFC 8305
         * Section 3's AAAA-arrives-while-A-is-still-buffered case) can merge every batch's
         * data first and decide, only once, via {@link #triggerAfterMergeLocked}, whether
         * anything needs to start. Merging each batch in through this method first, rather
         * than letting the first one's own {@link #mergeAddressesLocked} immediately decide
         * to start an attempt, matters because that attempt's {@code connect()} call can
         * itself be synchronous and can exhaust every address known so far before a second,
         * not yet merged, batch (the buffered one) ever becomes visible to it.
         *
         * <p>
         * The still-pending suffix (from {@code nextIndex} on) is rebuilt by splitting it
         * back into its two families and handing both, plus the newly arrived batch, to
         * {@link #addressOrderingStrategy}, rather than simply appending the new addresses
         * after whatever was already pending, which would let a newly arrived, preferred
         * address end up queued behind an older, still-untried one of the other family. The
         * default strategy merely alternates families; an RFC 6724-aware strategy additionally
         * orders each family by destination-selection rule, while still interleaving rather
         * than collapsing to one address, since the race, not this ordering, must stay the one
         * to decide the winner (RFC 8305 Section 3).
         * </p>
         *
         * <p>
         * {@code remoteAddresses} is mutated only after the merge has been computed, and only
         * with a result that is never {@code null}: if {@link #addressOrderingStrategy} throws
         * or returns {@code null}, {@link AddressOrderingStrategy#simple()} is used instead for
         * this merge, so that already-pending, already-resolved addresses are never dropped
         * because of a failing strategy. The original failure, if any, is returned rather than
         * thrown here, so a caller merging more than one batch in the same call can finish
         * every merge and still decide whether to start an attempt before it surfaces.
         * </p>
         *
         * <p>Must be called while holding {@code lock}.</p>
         *
         * @return the exception {@link #addressOrderingStrategy} threw, or {@code null} if it
         * returned normally; the merge itself always succeeds either way.
         */
        private RuntimeException mergeAddressDataLocked(final List<InetSocketAddress> newAddresses, final boolean newIsIpv6) {
            final int attemptedCount = Math.min(nextIndex, remoteAddresses.size());
            final List<InetSocketAddress> pendingIpv6 = new ArrayList<>();
            final List<InetSocketAddress> pendingIpv4 = new ArrayList<>();
            for (int i = attemptedCount; i < remoteAddresses.size(); i++) {
                final InetSocketAddress addr = remoteAddresses.get(i);
                (addr.getAddress() instanceof Inet6Address ? pendingIpv6 : pendingIpv4).add(addr);
            }
            (newIsIpv6 ? pendingIpv6 : pendingIpv4).addAll(newAddresses);

            List<InetSocketAddress> merged;
            RuntimeException failure;
            try {
                merged = addressOrderingStrategy.interleave(pendingIpv6, pendingIpv4);
                failure = merged != null ? null : new IllegalStateException(
                        "AddressOrderingStrategy returned null for " + addressOrderingStrategy);
            } catch (final RuntimeException ex) {
                merged = null;
                failure = ex;
            }
            if (merged == null) {
                merged = AddressOrderingStrategy.simple().interleave(pendingIpv6, pendingIpv4);
                if (LOG.isWarnEnabled()) {
                    LOG.warn("{}:{} address ordering strategy failed; falling back to the default merge order",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), failure);
                }
            }

            while (remoteAddresses.size() > attemptedCount) {
                remoteAddresses.remove(remoteAddresses.size() - 1);
            }
            remoteAddresses.addAll(merged);
            return failure;
        }

        /**
         * The attempt-starting half of {@link #mergeAddressesLocked}: decides, now that one
         * or more batches of newly resolved addresses have been merged in by {@link
         * #mergeAddressDataLocked}, whether anything needs to start as a result.
         * {@code hadNoNextAddress} must be computed by the caller before any of this call's
         * batches were merged in, exactly as {@link #mergeAddressesLocked} itself computes
         * it.
         *
         * <p>Must be called while holding {@code lock}.</p>
         */
        private RaceAbort triggerAfterMergeLocked(final boolean hadNoNextAddress) {
            if (winnerRejected) {
                // The race already has a winner that failed validation and is waiting on
                // exactly this; the caller merging this address hands it to
                // #tryNextFallbackAttempt's wait, not to this race's own attempt-starting
                // machinery below, which stays shut once a winner is `decided`.
                return null;
            }
            if (decided || cancelled) {
                // The race already has a final answer that does not need a fallback (the
                // winner was accepted, its validation is still pending, or the whole
                // operation was cancelled): these addresses are recorded above for
                // bookkeeping only. #scheduleNextPromptlyLocked/#startNextLocked already
                // refuse to start anything in this state, so this is purely to avoid
                // scheduling a stagger timer that would only fire to no-op.
                return null;
            }
            if (!hadNoNextAddress || pendingStagger != null) {
                // A next address was already known and either already started or already
                // has a stagger pacing it: these new addresses simply join the pending queue
                // in their RFC 8305 Section 4 order; nothing further to do right now.
                return null;
            }
            if (outstanding == 0) {
                // Nothing in flight at all: start as promptly as RFC 8305 allows, exactly
                // as a freshly resolved first address would (Resolution Delay, Section 3),
                // while still respecting the minimum inter-attempt spacing (Section 5) in
                // case an earlier attempt for a different family just failed.
                return scheduleNextPromptlyLocked();
            } else {
                // An attempt is outstanding, but until now there was no further address to
                // pace against; start the stagger clock now that one has arrived.
                pendingStagger = staggerScheduler().schedule(
                        this::onStaggerElapsed, connectionAttemptDelay.getDuration(), connectionAttemptDelay.getTimeUnit());
                return null;
            }
        }

        /** One address family failed to resolve; recorded as a candidate failure cause. */
        void onResolutionFailed(final Exception cause) {
            lock.lock();
            try {
                lastFailure = cause;
            } finally {
                lock.unlock();
            }
        }

        /** No further addresses will ever be added to this race. */
        void onResolutionComplete() {
            boolean last = false;
            Exception cause = null;
            boolean resumeFallback = false;
            Exception resumeCause = null;
            RaceAbort abort = null;
            lock.lock();
            try {
                resolutionComplete = true;
                if (pendingResolutionDelay != null) {
                    // AAAA will never arrive now: stop waiting for it and use IPv4 alone.
                    pendingResolutionDelay.cancel(false);
                    pendingResolutionDelay = null;
                    final List<InetSocketAddress> buffered = bufferedIpv4;
                    bufferedIpv4 = null;
                    if (buffered != null) {
                        abort = mergeAddressesLocked(buffered, false);
                        if (abort == null) {
                            resumeFallback = takeFallbackIfWinnerRejectedLocked();
                            if (resumeFallback) {
                                resumeCause = winnerRejectionCause;
                            }
                        }
                    }
                }
                if (abort != null) {
                    // Handled below, outside the lock.
                } else if (resumeFallback) {
                    // Just merged in exactly what #tryNextFallbackAttempt was waiting on;
                    // handled below, outside the lock.
                } else if (winnerRejected) {
                    // The winner failed validation while this was the last thing resolution
                    // could still have contributed, and resolution is now known to have
                    // nothing left: give up with that validation failure as the cause.
                    winnerRejected = false;
                    cause = winnerRejectionCause;
                    last = true;
                } else if (!decided && !cancelled && outstanding == 0 && nextIndex >= remoteAddresses.size()) {
                    decided = true;
                    last = true;
                    cause = lastFailure != null ? lastFailure : new UnknownHostException(remoteEndpoint.getHostName());
                }
            } finally {
                lock.unlock();
            }
            if (abort != null) {
                finishAbort(abort);
                return;
            }
            if (resumeFallback) {
                tryNextFallbackAttempt(resumeCause);
                return;
            }
            if (last) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} resolution complete with no further addresses to try ({})",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), cause.getClass());
                }
                finish(cause);
            }
        }

        void start() {
            final RaceAbort abort;
            lock.lock();
            try {
                abort = scheduleNextPromptlyLocked();
            } finally {
                lock.unlock();
            }
            finishAbort(abort);
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
         *
         * @return what {@link #startNextLocked} computed if it started an attempt whose
         * {@code connect()} call threw synchronously; {@code null} otherwise.
         */
        private RaceAbort scheduleNextPromptlyLocked() {
            if (decided || cancelled || nextIndex >= remoteAddresses.size()) {
                return null;
            }
            if (lastAttemptStartNanos < 0) {
                return startNextLocked();
            }
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastAttemptStartNanos);
            if (elapsedMs >= MIN_CONNECTION_ATTEMPT_DELAY_MS) {
                return startNextLocked();
            } else {
                pendingStagger = staggerScheduler().schedule(
                        this::onStaggerElapsed, MIN_CONNECTION_ATTEMPT_DELAY_MS - elapsedMs, TimeUnit.MILLISECONDS);
                return null;
            }
        }

        // Must be called while holding `lock`. Returns what #abortRaceLocked computed if
        // connect() threw synchronously instead of ever invoking a callback; null otherwise.
        // See #finishAbort, which the caller must invoke, outside `lock`, with whatever is
        // returned here.
        private RaceAbort startNextLocked() {
            if (decided || cancelled || nextIndex >= remoteAddresses.size()) {
                return null;
            }
            final int index = nextIndex++;
            final InetSocketAddress remoteAddress = remoteAddresses.get(index);
            outstanding++;
            lastAttemptStartNanos = System.nanoTime();

            // Reserve this attempt's slot before calling connect(): see the comment on
            // #inFlight for why this must happen first, rather than once connect() returns.
            // inFlight.size() == index here, since every earlier attempt reserved its own
            // slot the same way before nextIndex was advanced past it.
            inFlight.add(null);
            inFlightAddresses.add(remoteAddress);

            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:{} racing attempt {} {}->{} ({})",
                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), index, localAddress, remoteAddress, connectTimeout);
            }

            // `settled` is a CAS, not a plain flag, because a ConnectionInitiator is also
            // free to hand this callback to something that fires later, asynchronously, on
            // another thread, even after connect() itself goes on to throw: whichever of the
            // two - the catch block below, or this attempt's own callback, firing for the
            // very first time - gets here first wins it. Losing it means something else
            // already settled this attempt's outcome and must not be double-counted: the
            // catch block simply lets its exception propagate, and a callback that loses it
            // only skips calling through if `claimedByThrow` says the catch is what won,
            // meaning connect() itself already reported this attempt's failure. A second (or
            // later) call to this same callback, after a first one already won the CAS
            // fairly, is a late or duplicate delivery and must still reach #onAttemptCompleted
            // / #onAttemptFailed / #onAttemptCancelled exactly as before this attempt had a
            // CAS at all: their own dedup (`decided`, `settledAttempts`) is what handles that,
            // not this one.
            final AtomicBoolean settled = new AtomicBoolean();
            final AtomicBoolean claimedByThrow = new AtomicBoolean();
            final Future<IOSession> attemptFuture;
            try {
                attemptFuture = connectionInitiator.connect(
                        remoteEndpoint,
                        remoteAddress,
                        localAddress,
                        connectTimeout,
                        attachment,
                        new FutureCallback<IOSession>() {

                            @Override
                            public void completed(final IOSession session) {
                                if (settled.compareAndSet(false, true) || !claimedByThrow.get()) {
                                    processOutcome(() -> onAttemptCompleted(index, remoteAddress, session));
                                }
                            }

                            @Override
                            public void failed(final Exception cause) {
                                if (settled.compareAndSet(false, true) || !claimedByThrow.get()) {
                                    processOutcome(() -> onAttemptFailed(index, remoteAddress, cause));
                                }
                            }

                            @Override
                            public void cancelled() {
                                if (settled.compareAndSet(false, true) || !claimedByThrow.get()) {
                                    processOutcome(() -> onAttemptCancelled(index, remoteAddress));
                                }
                            }

                        });
            } catch (final AttemptOutcomeException wrapped) {
                // Originated from inside some attempt's outcome-processing code, running
                // reentrantly on this same stack - this attempt's own callback (in which
                // case `settled` is already true below), or a different attempt's: either
                // way, not this attempt's own connect() failing to call back, so simply let
                // it propagate exactly as it would have without this try/catch here.
                throw wrapped.unwrap();
            } catch (final RuntimeException ex) {
                if (!settled.compareAndSet(false, true)) {
                    // Lost the race to a callback that already ran synchronously (or is
                    // running concurrently on another thread right now): that callback, not
                    // this exception, is this attempt's real outcome.
                    throw ex;
                }
                claimedByThrow.set(true);
                // connect() itself threw instead of ever calling back, as
                // AbstractIOReactorBase#connect does once the reactor's shutdown has been
                // initiated. Whatever caused this applies equally to every remaining
                // address, so none of them is worth retrying - but whatever is already
                // racing from an earlier address is left alone; see #abortRaceLocked.
                return abortRaceLocked(index, remoteAddress, ex);
            }
            inFlight.set(index, attemptFuture);
            if ((decided || cancelled) && index != winnerIndex) {
                // The race was decided by a different, reentrant attempt (or cancelled
                // outright) while this attempt's own connect() call was still on the stack:
                // the loser-cancellation loop in #onAttemptCompleted, or #cancel's own
                // cancellation loop, already ran and found nothing to cancel here because
                // this slot was still null at the time. Finish that job now that a real
                // future finally exists for it.
                attemptFuture.cancel(true);
            }

            // pendingStagger != null here means the attempt above already completed
            // synchronously and its own failure/cancellation handler already scheduled the
            // next advance (possibly at the shorter minimum spacing): don't overwrite it.
            if (!decided && !cancelled && pendingStagger == null && nextIndex < remoteAddresses.size()) {
                pendingStagger = staggerScheduler().schedule(
                        this::onStaggerElapsed, connectionAttemptDelay.getDuration(), connectionAttemptDelay.getTimeUnit());
            }
            return null;
        }

        /**
         * Must be called while holding {@code lock}: {@code index}'s own {@code connect()}
         * call just threw synchronously, without ever invoking its callback. Drops every
         * address after {@code index} - everything still pending beyond this one - since
         * whatever made {@code connect()} throw this way almost certainly applies to all of
         * them equally; an attempt already racing from an earlier address, if any, is left
         * completely alone; see {@link #finishAbort}, which settles {@code index} itself
         * outside {@code lock}. {@code index}'s own address is kept, even though it failed:
         * {@code nextIndex} already counts it, and truncating past it would leave the two out
         * of step the next time a loser's address is re-queued. Also marks {@code index}
         * settled on its own merits right away, same as {@link #onAttemptFailed} will once
         * {@link #finishAbort} calls it: if a different, still-racing attempt goes on to win
         * before that happens, {@code index}'s address, with nothing genuinely left in flight
         * for it, must not be re-queued as a fallback candidate the way a true in-flight loser
         * is (see #onAttemptCompleted).
         */
        private RaceAbort abortRaceLocked(final int index, final InetSocketAddress remoteAddress, final Exception cause) {
            while (remoteAddresses.size() > index + 1) {
                remoteAddresses.remove(remoteAddresses.size() - 1);
            }
            settledAttempts.add(index);
            return new RaceAbort(index, remoteAddress, cause);
        }

        private void onStaggerElapsed() {
            final RaceAbort abort;
            lock.lock();
            try {
                pendingStagger = null;
                abort = startNextLocked();
            } finally {
                lock.unlock();
            }
            finishAbort(abort);
        }

        private void onAttemptCompleted(final int index, final InetSocketAddress remoteAddress, final IOSession session) {
            final boolean won;
            final List<Future<IOSession>> losers;
            lock.lock();
            try {
                outstanding--;
                won = !decided && !cancelled;
                if (won) {
                    decided = true;
                    winnerIndex = index;
                    cancelPendingStaggerLocked();
                    losers = new ArrayList<>();
                    // Every other attempt still racing at this point loses, not because its
                    // address is bad, but only because this one answered first: its address
                    // remains a perfectly good fallback candidate if the winner then fails
                    // #validator's check, so it is re-queued into the still-pending suffix
                    // (from nextIndex on) that #tryNextFallbackAttempt walks, in the order the
                    // losing attempts were originally started, ahead of anything not yet
                    // attempted at all.
                    final List<InetSocketAddress> loserAddresses = new ArrayList<>();
                    for (int i = 0; i < inFlight.size(); i++) {
                        // Excludes this winner's own entry, and any attempt that already
                        // settled (failed or was cancelled) on its own merits before this
                        // winner was decided: only an attempt that was still genuinely racing
                        // at this exact moment is a real fallback candidate.
                        if (i == index || settledAttempts.contains(i)) {
                            continue;
                        }
                        losers.add(inFlight.get(i));
                        loserAddresses.add(inFlightAddresses.get(i));
                    }
                    if (!loserAddresses.isEmpty()) {
                        remoteAddresses.addAll(nextIndex, loserAddresses);
                    }
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
                    // A loser's slot is still null if its own connect() call, invoked
                    // reentrantly from this same stack, has not returned yet to fill it (see
                    // the comment on #inFlight): #startNextLocked cancels that attempt's
                    // future itself, once connect() returns, when it notices the race was
                    // decided out from under it.
                    if (loser != null) {
                        loser.cancel(true);
                    }
                }
                acceptOrReject(session, validator, future, this::onWinnerAccepted, cause -> onWinnerRejected(remoteAddress, cause));
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
         * The TCP winner passed {@link #validator}'s check (or there was none to pass): this
         * race is over for good, so a DNS resolution for the other address family that is
         * still outstanding can no longer contribute anything and is cancelled rather than
         * left to run to its own timeout for nothing.
         */
        private void onWinnerAccepted() {
            final Cancellable resolutionToCancel;
            lock.lock();
            try {
                winnerAccepted = true;
                resolutionToCancel = resolutionComplete ? null : resolution;
                resolution = null;
            } finally {
                lock.unlock();
            }
            if (resolutionToCancel != null) {
                resolutionToCancel.cancel();
            }
        }

        /**
         * The address that won the TCP race failed {@link #validator}'s check (for example, a
         * TLS handshake layered on top of it). Racing is over by this point: every other
         * in-flight attempt was already cancelled when the winner was decided. Delegates to
         * {@link #tryNextFallbackAttempt}, which tries whatever this race already knows
         * about one address at a time, live against {@link #remoteAddresses} rather than a
         * fixed snapshot, so an address that is only merged in later (by {@link
         * #onAddressesResolved}, after this call's own addresses, if any, are exhausted) is
         * never silently dropped.
         */
        private void onWinnerRejected(final InetSocketAddress winnerAddress, final Exception cause) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:{} connection to {} failed validation after racing ({}); trying the next known address, if any",
                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), winnerAddress, cause.getClass());
            }
            tryNextFallbackAttempt(cause);
        }

        /**
         * Tries, one at a time, every address of {@link #remoteAddresses} from {@link
         * #nextIndex} on, after the race's TCP winner failed {@link #validator}'s check: the
         * same cursor {@link #startNextLocked} uses while racing, continued here now that
         * racing itself is shut by {@code decided}, so an address merged in later by {@link
         * #onAddressesResolved} is picked up exactly once, whether this call or a later one
         * reaches it. If every known address is exhausted and DNS resolution for another
         * family is still outstanding, waits instead of giving up: {@link
         * #onAddressesResolved} and {@link #onResolutionComplete} resume this same walk
         * (via {@link #takeFallbackIfWinnerRejectedLocked}) when they have something new to
         * offer, or a reason to stop waiting.
         */
        private void tryNextFallbackAttempt(final Exception priorCause) {
            final InetSocketAddress remoteAddress;
            final boolean waitForMore;
            // The index this attempt is for, used below to detect whether a same-thread,
            // reentrant fallback attempt (recursing in through a synchronous failure callback,
            // before the connect() call below even returns) has already advanced past it by
            // the time this call gets to record its own result in fallbackAttempt.
            final int myIndex;
            List<InetSocketAddress> exhausted = null;
            lock.lock();
            try {
                if (future.isDone()) {
                    // The caller already cancelled, or the operation otherwise already
                    // completed; starting another attempt now would only leak a connection
                    // nobody will ever use.
                    return;
                }
                winnerRejectedEver = true;
                if (nextIndex < remoteAddresses.size()) {
                    myIndex = nextIndex;
                    remoteAddress = remoteAddresses.get(myIndex);
                    nextIndex++;
                    waitForMore = false;
                } else {
                    myIndex = -1;
                    remoteAddress = null;
                    waitForMore = !resolutionComplete;
                    if (waitForMore) {
                        winnerRejected = true;
                        winnerRejectionCause = priorCause;
                    } else {
                        exhausted = new ArrayList<>(remoteAddresses);
                    }
                }
            } finally {
                lock.unlock();
            }
            if (remoteAddress == null) {
                if (waitForMore) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("{}:{} connection failed validation after racing ({}); waiting for DNS resolution to finish before giving up",
                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), priorCause.getClass());
                    }
                    return;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} connection failed validation after racing ({}); no address remains",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), priorCause.getClass());
                }
                enhanceAndFail(future, remoteEndpoint, priorCause, distinctAddresses(exhausted));
                return;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:{} retrying connection to {} after racing winner failed validation",
                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress);
            }
            // See the identical guard in #startNextLocked, including why `settled` is a CAS
            // and what `claimedByThrow` is for.
            final AtomicBoolean settled = new AtomicBoolean();
            final AtomicBoolean claimedByThrow = new AtomicBoolean();
            final Future<IOSession> sessionFuture;
            try {
                sessionFuture = connectionInitiator.connect(
                        remoteEndpoint,
                        remoteAddress,
                        localAddress,
                        connectTimeout,
                        attachment,
                        new FutureCallback<IOSession>() {

                            @Override
                            public void completed(final IOSession session) {
                                if (settled.compareAndSet(false, true) || !claimedByThrow.get()) {
                                    // A fallback attempt that is accepted is just as much the
                                    // race's winner as one that won the TCP race outright: it
                                    // must trigger #onWinnerAccepted exactly the same way, so
                                    // a DNS resolution for the other address family that is
                                    // still outstanding is cancelled rather than left running
                                    // to its own timeout for nothing.
                                    processOutcome(() -> acceptOrReject(session, validator, future, Race.this::onWinnerAccepted,
                                            cause -> tryNextFallbackAttempt(cause)));
                                }
                            }

                            @Override
                            public void failed(final Exception cause) {
                                if (settled.compareAndSet(false, true) || !claimedByThrow.get()) {
                                    processOutcome(() -> tryNextFallbackAttempt(cause));
                                }
                            }

                            @Override
                            public void cancelled() {
                                if (settled.compareAndSet(false, true) || !claimedByThrow.get()) {
                                    processOutcome(future::cancel);
                                }
                            }

                        });
            } catch (final AttemptOutcomeException wrapped) {
                throw wrapped.unwrap();
            } catch (final RuntimeException ex) {
                if (!settled.compareAndSet(false, true)) {
                    throw ex;
                }
                claimedByThrow.set(true);
                // connect() itself threw instead of ever calling back. Every address after
                // this one is just as doomed as this one (see the class Javadoc on Race), so
                // give up on the whole operation right now instead of uselessly walking the
                // rest of them one at a time: unlike a plain validation-rejection fallback
                // failure, this is not routed back through #tryNextFallbackAttempt at all.
                final List<InetSocketAddress> everyKnownAddress;
                lock.lock();
                try {
                    everyKnownAddress = new ArrayList<>(remoteAddresses);
                } finally {
                    lock.unlock();
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} connection attempt to {} threw instead of completing ({}); giving up without trying any further address",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, ex.getClass());
                }
                enhanceAndFail(future, remoteEndpoint, ex, distinctAddresses(everyKnownAddress));
                return;
            }
            final boolean alreadyOver;
            lock.lock();
            try {
                alreadyOver = future.isDone();
                // A same-thread, reentrant failure above can already have recursed into
                // tryNextFallbackAttempt() for the next address and stored its own, newer
                // session future here before this line runs; only record this (now stale)
                // one if no later attempt has started in the meantime, exactly as
                // executeNextLocked() guards the same reentrancy for the sequential path.
                if (!alreadyOver && nextIndex == myIndex + 1) {
                    fallbackAttempt = sessionFuture;
                }
            } finally {
                lock.unlock();
            }
            if (alreadyOver) {
                sessionFuture.cancel(true);
            }
        }

        private void onAttemptFailed(final int index, final InetSocketAddress remoteAddress, final Exception cause) {
            boolean advance = false;
            boolean last = false;
            RaceAbort abort = null;
            lock.lock();
            try {
                outstanding--;
                if (decided || cancelled) {
                    return;
                }
                settledAttempts.add(index);
                cancelPendingStaggerLocked();
                lastFailure = cause;
                if (nextIndex < remoteAddresses.size()) {
                    advance = true;
                } else if (outstanding == 0 && resolutionComplete) {
                    decided = true;
                    last = true;
                }
                if (advance) {
                    // RFC 8305 Section 5: advance to the next address as promptly as possible
                    // after a failure, never waiting out the full connection attempt delay,
                    // but still respecting the minimum inter-attempt spacing.
                    abort = scheduleNextPromptlyLocked();
                }
            } finally {
                lock.unlock();
            }
            if (abort != null) {
                finishAbort(abort);
                return;
            }
            // If neither advance nor last: every known address has been tried and failed,
            // but DNS resolution is still pending and may yet contribute more (see
            // #onAddressesResolved); wait for it rather than failing prematurely.
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
        private void onAttemptCancelled(final int index, final InetSocketAddress remoteAddress) {
            boolean advance = false;
            boolean last = false;
            RaceAbort abort = null;
            lock.lock();
            try {
                outstanding--;
                if (decided || cancelled) {
                    // Expected: either this race decided a winner and cancelled every other
                    // attempt itself, or the whole race was cancelled from the outside.
                    return;
                }
                settledAttempts.add(index);
                cancelPendingStaggerLocked();
                if (nextIndex < remoteAddresses.size()) {
                    advance = true;
                } else if (outstanding == 0 && resolutionComplete) {
                    decided = true;
                    last = true;
                }
                if (advance) {
                    abort = scheduleNextPromptlyLocked();
                }
            } finally {
                lock.unlock();
            }
            if (abort != null) {
                finishAbort(abort);
                return;
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
            if (cause instanceof IOException && !remoteAddresses.isEmpty()) {
                future.failed(ConnectExceptionSupport.enhance((IOException) cause, remoteEndpoint, distinctAddresses(remoteAddresses)));
            } else {
                future.failed(cause);
            }
        }

        @Override
        public boolean cancel() {
            final List<Future<IOSession>> toCancel;
            final Cancellable resolutionToCancel;
            final Future<IOSession> fallbackToCancel;
            final boolean fullyCancelled;
            lock.lock();
            try {
                if (cancelled) {
                    return false;
                }
                // `decided` alone does not mean nothing is left to cancel: the race's winner
                // may have since failed validation (`winnerRejectedEver`), and this race may
                // still be driving its own fallback attempts, a wait on DNS resolution, or
                // both. `winnerRejected` itself is not enough here: it is only true during
                // the wait sub-state, not while a fallback attempt is actively in flight.
                if (decided && !winnerRejectedEver) {
                    // Nothing of the race's own is left to cancel: the winner was accepted
                    // (in which case #onWinnerAccepted already cancelled and cleared
                    // #resolution), or its validation is still pending. In the latter case, a
                    // DNS resolution for the other family may still be outstanding and is now
                    // certain never to be needed, whatever the validator goes on to decide.
                    // Marking this race `cancelled` too keeps #setResolutionCancellable
                    // consistent: a resolution handle it has not seen yet is still cancelled
                    // the moment it arrives, instead of being recorded as still useful.
                    cancelled = true;
                    resolutionToCancel = resolution;
                    resolution = null;
                    toCancel = Collections.emptyList();
                    fallbackToCancel = null;
                    fullyCancelled = false;
                } else {
                    cancelled = true;
                    winnerRejected = false;
                    cancelPendingStaggerLocked();
                    if (pendingResolutionDelay != null) {
                        pendingResolutionDelay.cancel(false);
                        pendingResolutionDelay = null;
                    }
                    bufferedIpv4 = null;
                    toCancel = new ArrayList<>(inFlight);
                    resolutionToCancel = resolution;
                    resolution = null;
                    fallbackToCancel = fallbackAttempt;
                    fallbackAttempt = null;
                    fullyCancelled = true;
                }
            } finally {
                lock.unlock();
            }
            for (final Future<IOSession> attemptFuture : toCancel) {
                // Still null if that attempt's own connect() call, invoked reentrantly from
                // this same stack, has not returned yet to fill its reserved slot (see the
                // comment on #inFlight): #startNextLocked cancels it itself once connect()
                // returns and finds the race already cancelled.
                if (attemptFuture != null) {
                    attemptFuture.cancel(true);
                }
            }
            if (resolutionToCancel != null) {
                resolutionToCancel.cancel();
            }
            if (fallbackToCancel != null) {
                fallbackToCancel.cancel(true);
            }
            return fullyCancelled;
        }

        // Must be called while holding `lock`.
        private void cancelPendingStaggerLocked() {
            if (pendingStagger != null) {
                pendingStagger.cancel(false);
                pendingStagger = null;
            }
        }

    }

}
