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
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ScheduledExecutorService staggerScheduler;

    MultihomeIOSessionRequester(final DnsResolver dnsResolver) {
        this(dnsResolver, false, DEFAULT_CONNECTION_ATTEMPT_DELAY);
    }

    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, null, null);
    }

    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver) {
        this(dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, asyncDnsResolver, null);
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

    // Package-private: full constructor, letting unit tests control both the DNS
    // resolution strategy and stagger scheduling.
    MultihomeIOSessionRequester(
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver,
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

        final ComplexFuture<IOSession> future = new ComplexFuture<>(callback);
        if (remoteAddress != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:{} connecting {} to {} ({})",
                        remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, connectTimeout);
            }
            final Future<IOSession> sessionFuture = connectionInitiator.connect(remoteEndpoint, remoteAddress, localAddress, connectTimeout, attachment, new FutureCallback<IOSession>() {
                @Override
                public void completed(final IOSession session) {
                    future.completed(session);
                }

                @Override
                public void failed(final Exception cause) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("{}:{} connection to {} failed ({}); terminating operation",
                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, cause.getClass());
                    }
                    if (cause instanceof IOException) {
                        future.failed(ConnectExceptionSupport.enhance((IOException) cause, remoteEndpoint,
                                (remoteAddress instanceof InetSocketAddress) ?
                                        new InetAddress[] { ((InetSocketAddress) remoteAddress).getAddress() } :
                                        new InetAddress[] {}));
                    } else {
                        future.failed(cause);
                    }
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
            return connectWithAsyncResolution(connectionInitiator, remoteEndpoint, localAddress, connectTimeout, attachment, future);
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
                    connectTimeout, attachment, future);
            race.start();
            return future;
        }

        final Runnable runnable = new Runnable() {

            private final AtomicInteger attempt = new AtomicInteger(0);

            void executeNext() {
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
                                future.completed(session);
                            }

                            @Override
                            public void failed(final Exception cause) {
                                if (attempt.get() >= remoteAddresses.size()) {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("{}:{} connection to {} failed ({}); terminating operation",
                                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, cause.getClass());
                                    }
                                    if (cause instanceof IOException) {
                                        final InetAddress[] addresses = remoteAddresses.stream()
                                                .filter(addr -> addr instanceof InetSocketAddress)
                                                .map(addr -> ((InetSocketAddress) addr).getAddress())
                                                .toArray(InetAddress[]::new);
                                        future.failed(ConnectExceptionSupport.enhance((IOException) cause, remoteEndpoint, addresses));
                                    } else {
                                        future.failed(cause);
                                    }
                                } else {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("{}:{} connection to {} failed ({}); retrying connection to the next address",
                                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), remoteAddress, cause.getClass());
                                    }
                                    executeNext();
                                }
                            }

                            @Override
                            public void cancelled() {
                                future.cancel();
                            }

                        });
                future.setDependency(sessionFuture);
            }

            @Override
            public void run() {
                executeNext();
            }

        };
        runnable.run();
        return future;
    }

    public Future<IOSession> connect(
            final ConnectionInitiator connectionInitiator,
            final NamedEndpoint remoteEndpoint,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final FutureCallback<IOSession> callback) {
        return connect(connectionInitiator, remoteEndpoint, null, localAddress, connectTimeout, attachment, callback);
    }

    /**
     * Resolves {@code remoteEndpoint} via the configured {@link AsyncDnsResolver}, racing
     * connection attempts against each address family's addresses as soon as that family
     * resolves rather than waiting for every family to complete first (RFC 8305, Section 3:
     * a Resolution Delay of 0ms trivially satisfies the "SHOULD NOT wait more than 50ms"
     * recommendation, since connections begin immediately upon the first family's result).
     * A family that resolves after the race has already been decided contributes nothing
     * and is otherwise ignored.
     */
    private Future<IOSession> connectWithAsyncResolution(
            final ConnectionInitiator connectionInitiator,
            final NamedEndpoint remoteEndpoint,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final ComplexFuture<IOSession> future) {
        final Race race = new Race(connectionInitiator, remoteEndpoint, new ArrayList<>(), localAddress,
                connectTimeout, attachment, future);
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
        private final ComplexFuture<IOSession> future;

        private final Object lock = new Object();
        private final List<Future<IOSession>> inFlight = new ArrayList<>();
        private int nextIndex;
        private int outstanding;
        private boolean decided;
        private boolean cancelled;
        private ScheduledFuture<?> pendingStagger;
        private long lastAttemptStartNanos = -1L;
        // True once every address this race will ever see has been added, i.e. there is no
        // DNS resolution still in flight that might contribute further addresses. Always
        // true for a race constructed from a complete, pre-resolved address list; set to
        // false by #markResolutionPending() for a race driven by progressive, asynchronous
        // DNS resolution (see #connectWithAsyncResolution).
        private boolean resolutionComplete = true;
        private Cancellable resolution;
        private Exception lastFailure;
        // RFC 8305 Section 3 Resolution Delay bookkeeping: set only while waiting up to
        // RESOLUTION_DELAY for AAAA after A arrived first.
        private boolean ipv6Resolved;
        private List<InetSocketAddress> bufferedIpv4;
        private ScheduledFuture<?> pendingResolutionDelay;

        Race(
                final ConnectionInitiator connectionInitiator,
                final NamedEndpoint remoteEndpoint,
                final List<InetSocketAddress> remoteAddresses,
                final SocketAddress localAddress,
                final Timeout connectTimeout,
                final Object attachment,
                final ComplexFuture<IOSession> future) {
            this.connectionInitiator = connectionInitiator;
            this.remoteEndpoint = remoteEndpoint;
            this.remoteAddresses = new ArrayList<>(remoteAddresses);
            this.localAddress = localAddress;
            this.connectTimeout = connectTimeout;
            this.attachment = attachment;
            this.future = future;
            // A single Cancellable dependency covers every attempt this race ever starts,
            // including ones not yet started when the caller cancels (see #cancel()).
            this.future.setDependency(this);
        }

        void markResolutionPending() {
            synchronized (lock) {
                resolutionComplete = false;
            }
        }

        void setResolutionCancellable(final Cancellable resolution) {
            final boolean alreadyOver;
            synchronized (lock) {
                alreadyOver = decided || cancelled;
                if (!alreadyOver) {
                    this.resolution = resolution;
                }
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
            synchronized (lock) {
                if (decided || cancelled || addresses.isEmpty()) {
                    return;
                }
                final boolean isIpv6 = addresses.get(0).getAddress() instanceof Inet6Address;
                if (isIpv6) {
                    ipv6Resolved = true;
                    if (pendingResolutionDelay != null) {
                        pendingResolutionDelay.cancel(false);
                        pendingResolutionDelay = null;
                        mergeAddressesLocked(addresses, true);
                        final List<InetSocketAddress> buffered = bufferedIpv4;
                        bufferedIpv4 = null;
                        if (buffered != null) {
                            mergeAddressesLocked(buffered, false);
                        }
                    } else {
                        mergeAddressesLocked(addresses, true);
                    }
                    return;
                }
                if (!ipv6Resolved && !resolutionComplete && pendingResolutionDelay == null && bufferedIpv4 == null) {
                    bufferedIpv4 = new ArrayList<>(addresses);
                    pendingResolutionDelay = staggerScheduler().schedule(
                            this::onResolutionDelayElapsed, RESOLUTION_DELAY.getDuration(), RESOLUTION_DELAY.getTimeUnit());
                    return;
                }
                mergeAddressesLocked(addresses, false);
            }
        }

        private void onResolutionDelayElapsed() {
            synchronized (lock) {
                pendingResolutionDelay = null;
                final List<InetSocketAddress> buffered = bufferedIpv4;
                bufferedIpv4 = null;
                if (decided || cancelled || buffered == null) {
                    return;
                }
                mergeAddressesLocked(buffered, false);
            }
        }

        /**
         * Merges newly resolved addresses of one family into the race. Addresses already
         * attempted (indices {@code < nextIndex}: in flight, succeeded or failed) are never
         * touched. The still-pending suffix is rebuilt by splitting it back into its two
         * families and re-interleaving with the new arrivals (RFC 8305 Section 6, IPv6
         * first), rather than simply appending the new addresses after whatever was already
         * pending, which would let a newly arrived, preferred AAAA address end up queued
         * behind an older, still-untried A address.
         *
         * <p>Must be called while holding {@code lock}.</p>
         */
        private void mergeAddressesLocked(final List<InetSocketAddress> newAddresses, final boolean newIsIpv6) {
            final int attemptedCount = Math.min(nextIndex, remoteAddresses.size());
            final boolean hadNoNextAddress = attemptedCount >= remoteAddresses.size();
            final List<InetSocketAddress> pendingIpv6 = new ArrayList<>();
            final List<InetSocketAddress> pendingIpv4 = new ArrayList<>();
            for (int i = attemptedCount; i < remoteAddresses.size(); i++) {
                final InetSocketAddress addr = remoteAddresses.get(i);
                (addr.getAddress() instanceof Inet6Address ? pendingIpv6 : pendingIpv4).add(addr);
            }
            (newIsIpv6 ? pendingIpv6 : pendingIpv4).addAll(newAddresses);

            while (remoteAddresses.size() > attemptedCount) {
                remoteAddresses.remove(remoteAddresses.size() - 1);
            }
            remoteAddresses.addAll(interleave(pendingIpv6, pendingIpv4));

            if (!hadNoNextAddress || pendingStagger != null) {
                // A next address was already known and either already started or already
                // has a stagger pacing it: these new addresses simply join the pending queue
                // in their RFC 8305 Section 6 order; nothing further to do right now.
                return;
            }
            if (outstanding == 0) {
                // Nothing in flight at all: start as promptly as RFC 8305 allows, exactly
                // as a freshly resolved first address would (Resolution Delay, Section 3),
                // while still respecting the minimum inter-attempt spacing (Section 5) in
                // case an earlier attempt for a different family just failed.
                scheduleNextPromptlyLocked();
            } else {
                // An attempt is outstanding, but until now there was no further address to
                // pace against; start the stagger clock now that one has arrived.
                pendingStagger = staggerScheduler().schedule(
                        this::onStaggerElapsed, connectionAttemptDelay.getDuration(), connectionAttemptDelay.getTimeUnit());
            }
        }

        /** Alternates between the two families (RFC 8305 Section 6), AAAA first. */
        private List<InetSocketAddress> interleave(
                final List<InetSocketAddress> ipv6, final List<InetSocketAddress> ipv4) {
            final List<InetSocketAddress> result = new ArrayList<>(ipv6.size() + ipv4.size());
            final int max = Math.max(ipv6.size(), ipv4.size());
            for (int i = 0; i < max; i++) {
                if (i < ipv6.size()) {
                    result.add(ipv6.get(i));
                }
                if (i < ipv4.size()) {
                    result.add(ipv4.get(i));
                }
            }
            return result;
        }

        /** One address family failed to resolve; recorded as a candidate failure cause. */
        void onResolutionFailed(final Exception cause) {
            synchronized (lock) {
                lastFailure = cause;
            }
        }

        /** No further addresses will ever be added to this race. */
        void onResolutionComplete() {
            boolean last = false;
            Exception cause = null;
            synchronized (lock) {
                resolutionComplete = true;
                if (pendingResolutionDelay != null) {
                    // AAAA will never arrive now: stop waiting for it and use IPv4 alone.
                    pendingResolutionDelay.cancel(false);
                    pendingResolutionDelay = null;
                    final List<InetSocketAddress> buffered = bufferedIpv4;
                    bufferedIpv4 = null;
                    if (buffered != null) {
                        mergeAddressesLocked(buffered, false);
                    }
                }
                if (!decided && !cancelled && outstanding == 0 && nextIndex >= remoteAddresses.size()) {
                    decided = true;
                    last = true;
                    cause = lastFailure != null ? lastFailure : new UnknownHostException(remoteEndpoint.getHostName());
                }
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
            synchronized (lock) {
                scheduleNextPromptlyLocked();
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
            synchronized (lock) {
                pendingStagger = null;
                startNextLocked();
            }
        }

        private void onAttemptCompleted(final InetSocketAddress remoteAddress, final IOSession session) {
            final boolean won;
            final List<Future<IOSession>> losers;
            synchronized (lock) {
                outstanding--;
                won = !decided && !cancelled;
                if (won) {
                    decided = true;
                    cancelPendingStaggerLocked();
                    losers = new ArrayList<>(inFlight);
                } else {
                    losers = null;
                }
            }
            if (won) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}:{} race won by {}->{} as {}",
                            remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, session.getId());
                }
                for (final Future<IOSession> loser : losers) {
                    loser.cancel(true);
                }
                // future may already have been cancelled directly by the caller (not through
                // this Race's own #cancel()) in the brief window between the checks above and
                // here: ComplexFuture#completed() returns false in that case, and the session
                // must still be closed rather than silently leaked.
                if (!future.completed(session)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("{}:{} race won by {}->{} as {} but the future was already done; closing",
                                remoteEndpoint.getHostName(), remoteEndpoint.getPort(), localAddress, remoteAddress, session.getId());
                    }
                    session.close(CloseMode.IMMEDIATE);
                }
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

        private void onAttemptFailed(final InetSocketAddress remoteAddress, final Exception cause) {
            boolean advance = false;
            boolean last = false;
            synchronized (lock) {
                outstanding--;
                if (decided || cancelled) {
                    return;
                }
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
                    scheduleNextPromptlyLocked();
                }
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
        private void onAttemptCancelled(final InetSocketAddress remoteAddress) {
            boolean advance = false;
            boolean last = false;
            synchronized (lock) {
                outstanding--;
                if (decided || cancelled) {
                    // Expected: either this race decided a winner and cancelled every other
                    // attempt itself, or the whole race was cancelled from the outside.
                    return;
                }
                cancelPendingStaggerLocked();
                if (nextIndex < remoteAddresses.size()) {
                    advance = true;
                } else if (outstanding == 0 && resolutionComplete) {
                    decided = true;
                    last = true;
                }
                if (advance) {
                    scheduleNextPromptlyLocked();
                }
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
            final Cancellable resolutionToCancel;
            synchronized (lock) {
                if (decided || cancelled) {
                    return false;
                }
                cancelled = true;
                cancelPendingStaggerLocked();
                if (pendingResolutionDelay != null) {
                    pendingResolutionDelay.cancel(false);
                    pendingResolutionDelay = null;
                }
                bufferedIpv4 = null;
                toCancel = new ArrayList<>(inFlight);
                resolutionToCancel = resolution;
            }
            for (final Future<IOSession> attemptFuture : toCancel) {
                attemptFuture.cancel(true);
            }
            if (resolutionToCancel != null) {
                resolutionToCancel.cancel();
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

}
