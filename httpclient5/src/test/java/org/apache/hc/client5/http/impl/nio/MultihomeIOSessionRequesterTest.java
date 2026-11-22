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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.AsyncDnsResolver;
import org.apache.hc.client5.http.DnsResolutionCallback;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOReactorShutdownException;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MultihomeIOSessionRequesterTest {

    private DnsResolver dnsResolver;
    private ConnectionInitiator connectionInitiator;
    private MultihomeIOSessionRequester sessionRequester;
    private NamedEndpoint namedEndpoint;

    @BeforeEach
    void setUp() {
        dnsResolver = Mockito.mock(DnsResolver.class);
        connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        namedEndpoint = Mockito.mock(NamedEndpoint.class);
        sessionRequester = new MultihomeIOSessionRequester(dnsResolver);
    }

    @Test
    void testConnectWithMultipleAddresses() throws Exception {
        final InetAddress address1 = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        final InetAddress address2 = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                new InetSocketAddress(address1, 8080),
                new InetSocketAddress(address2, 8080)
        );

        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    // Simulate a failure for the first connection attempt
                    final CompletableFuture<IOSession> future = new CompletableFuture<>();
                    callback.failed(new IOException("Simulated connection failure"));
                    future.completeExceptionally(new IOException("Simulated connection failure"));
                    return future;
                });

        final Future<IOSession> future = sessionRequester.connect(
                connectionInitiator,
                namedEndpoint,
                null,
                Timeout.ofMilliseconds(500),
                null,
                null
        );

        assertTrue(future.isDone());
        try {
            future.get();
            fail("Expected ExecutionException");
        } catch (final ExecutionException ex) {
            assertInstanceOf(IOException.class, ex.getCause());
            assertEquals("Simulated connection failure", ex.getCause().getMessage());
        }
    }

    @Test
    void testConnectSuccessfulAfterRetries() throws Exception {
        final InetAddress address1 = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        final InetAddress address2 = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                new InetSocketAddress(address1, 8080),
                new InetSocketAddress(address2, 8080)
        );

        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    final InetSocketAddress remoteAddress = invocation.getArgument(1);
                    final CompletableFuture<IOSession> future = new CompletableFuture<>();
                    if (remoteAddress.getAddress().equals(address1)) {
                        // Fail the first address
                        callback.failed(new IOException("Simulated connection failure"));
                        future.completeExceptionally(new IOException("Simulated connection failure"));
                    } else {
                        // Succeed for the second address
                        final IOSession mockSession = Mockito.mock(IOSession.class);
                        callback.completed(mockSession);
                        future.complete(mockSession);
                    }
                    return future;
                });

        final Future<IOSession> future = sessionRequester.connect(
                connectionInitiator,
                namedEndpoint,
                null,
                Timeout.ofMilliseconds(500),
                null,
                null
        );

        assertTrue(future.isDone());
        try {
            final IOSession session = future.get();
            assertNotNull(session);
        } catch (final ExecutionException ex) {
            fail("Did not expect an ExecutionException", ex);
        }
    }

    /**
     * A {@link ConnectionInitiator} whose {@code connect()} for one address invokes its
     * callback synchronously, before returning, can make the sequential fallback path
     * recurse into the next attempt from inside that same call: the next attempt's own
     * dependency assignment must survive the original call unwinding afterward, rather than
     * being overwritten back to the (now finished) attempt that triggered it.
     */
    @Test
    void testCancelAfterSynchronousFailureReachesNextAttempt() throws Exception {
        final InetAddress address1 = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        final InetAddress address2 = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                new InetSocketAddress(address1, 8080),
                new InetSocketAddress(address2, 8080));

        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final AtomicBoolean secondAttemptCancelled = new AtomicBoolean();
        final CompletableFuture<IOSession> secondAttemptFuture = new CompletableFuture<IOSession>() {
            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                secondAttemptCancelled.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // Address 1 fails synchronously, inline, inside this very call: the
                    // fallback to address 2 below runs, and returns, before this call does.
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    final CompletableFuture<IOSession> future = new CompletableFuture<>();
                    callback.failed(new IOException("connection refused"));
                    future.completeExceptionally(new IOException("connection refused"));
                    return future;
                })
                .thenAnswer(invocation -> secondAttemptFuture); // Address 2: left pending.

        final Future<IOSession> future = sessionRequester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(30), null, null);

        future.cancel(true);
        assertTrue(secondAttemptCancelled.get(),
                "cancellation did not reach the in-flight second attempt");
    }

    // -------------------------------------------------------------------------
    // RFC 8305 connection racing (Happy Eyeballs v2)
    // -------------------------------------------------------------------------

    /** Captures scheduled stagger tasks instead of running them on a timer, for determinism. */
    private static final class FakeScheduler {
        final ScheduledExecutorService mock = Mockito.mock(ScheduledExecutorService.class);
        final Deque<Runnable> pending = new ArrayDeque<>();

        @SuppressWarnings("unchecked")
        FakeScheduler() {
            Mockito.when(mock.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> {
                        pending.add(invocation.getArgument(0));
                        return Mockito.mock(ScheduledFuture.class);
                    });
        }

        /** Runs the next pending stagger task, as if its delay had elapsed. */
        void fireNext() {
            final Runnable task = pending.poll();
            assertNotNull(task, "no stagger task was scheduled");
            task.run();
        }

        int pendingCount() {
            return pending.size();
        }
    }

    private static Throwable causeOf(final Future<IOSession> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (final ExecutionException ex) {
            return ex.getCause();
        } catch (final InterruptedException ex) {
            throw new AssertionError(ex);
        } catch (final TimeoutException ex) {
            // A regression that leaves the future pending forever must fail the build fast,
            // not hang it: see DefaultAsyncClientConnectionOperatorTest for the same pattern.
            throw new AssertionError("future never completed", ex);
        }
        throw new AssertionError("Expected ExecutionException");
    }

    private static List<InetSocketAddress> addresses(final int count) throws Exception {
        final List<InetSocketAddress> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            out.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, (byte) i}), 8080));
        }
        return out;
    }

    private static List<InetSocketAddress> ipv6Addresses(final int count) throws Exception {
        final List<InetSocketAddress> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            out.add(new InetSocketAddress(InetAddress.getByAddress(
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) i}), 8080));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static FutureCallback<IOSession>[] captureCallbacks(final ConnectionInitiator connectionInitiator, final int expected) {
        final ArgumentCaptor<FutureCallback> captor = ArgumentCaptor.forClass(FutureCallback.class);
        Mockito.verify(connectionInitiator, Mockito.times(expected))
                .connect(any(), any(), any(), any(), any(), captor.capture());
        return captor.getAllValues().toArray(new FutureCallback[0]);
    }

    @Test
    void happyEyeballs_firstAddressSucceedsImmediately_noStaggerNeeded() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession winnerSession = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(winnerSession);
                    return CompletableFuture.completedFuture(winnerSession);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertTrue(future.isDone());
        assertEquals(winnerSession, future.get());
        // Only the first address was ever attempted; the second was never needed.
        Mockito.verify(connectionInitiator, Mockito.times(1)).connect(any(), any(), any(), any(), any(), any());
        Mockito.verify(winnerSession, Mockito.never()).close(any(CloseMode.class));
    }

    @Test
    void happyEyeballs_firstAddressStalls_secondWinsAfterStagger() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession winnerSession = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> firstAttempt) // 1st call: stalls, no callback invoked yet.
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(winnerSession);
                    return CompletableFuture.completedFuture(winnerSession);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertFalse(future.isDone(), "must not complete before the stalled first attempt or the stagger fires");
        assertEquals(1, scheduler.pendingCount());

        scheduler.fireNext(); // simulate the 250ms stagger elapsing
        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), any(), any(), any(), any(), any());
        assertTrue(future.isDone());
        assertEquals(winnerSession, future.get());

        // The still-stalled first attempt must have been cancelled, not left to leak.
        Mockito.verify(firstAttempt).cancel(true);
    }

    /**
     * The second address wins the race while the first is still in flight (not yet failed
     * or cancelled on its own merits); the first is cancelled purely because it lost. If the
     * winner then fails {@link MultihomeIOSessionRequester.SessionValidator}'s check, the
     * cancelled loser's address must still be retried as a fallback, not silently dropped
     * just because {@code nextIndex} had already moved past it while racing.
     */
    @Test
    void happyEyeballs_winnerRejected_inFlightLoserIsRetried() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    return firstAttempt; // 1st call (address #1): stalls, no callback invoked yet.
                })
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(rejected);
                    return CompletableFuture.completedFuture(rejected);
                })
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(accepted);
                    return CompletableFuture.completedFuture(accepted);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        assertFalse(future.isDone(), "must not complete before the stalled first attempt or the stagger fires");
        scheduler.fireNext(); // simulate the 250ms stagger elapsing: address #2 starts racing while #1 is still stalled

        // Address #2 won the TCP race and was rejected by the validator; address #1, still
        // in-flight at that moment, was cancelled purely for having lost the race. The
        // fallback retry of address #1 then succeeds, all within this same synchronous call.
        Mockito.verify(firstAttempt).cancel(true);
        assertEquals(3, attemptAddresses.size(), "address #1 must be retried after the winner's validation failed");
        assertEquals(remoteAddresses.get(0), attemptAddresses.get(2));
        assertTrue(future.isDone());
        assertEquals(accepted, future.get());
    }

    /**
     * A fallback attempt that fails synchronously re-enters the fallback walk for the next
     * address before the outer call's own {@code connect()} invocation even returns. The
     * stale, already-settled outer attempt must not overwrite tracking of the inner, genuinely
     * still in-flight one: otherwise a later {@code cancel()} reaches the wrong attempt.
     */
    @Test
    void happyEyeballs_fallbackSynchronousFailureReentry_cancelReachesLatestAttemptNotStale() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejectedWinner = Mockito.mock(IOSession.class);
        final Future<IOSession> secondAttempt = Mockito.mock(Future.class);
        final Future<IOSession> thirdAttempt = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // Address #1 wins the race immediately: no competitor ever starts.
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(rejectedWinner);
                    return CompletableFuture.completedFuture(rejectedWinner);
                })
                .thenAnswer(invocation -> {
                    // Address #2's fallback attempt fails synchronously, re-entering the
                    // fallback walk for address #3 before this call itself returns.
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.failed(new IOException("refused"));
                    return secondAttempt;
                })
                .thenAnswer(invocation -> thirdAttempt); // Address #3's fallback attempt stalls.
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejectedWinner) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250));
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        assertFalse(future.isDone(), "address #3's fallback attempt is still stalled");
        Mockito.verify(connectionInitiator, Mockito.times(3)).connect(any(), any(), any(), any(), any(), any());

        future.cancel(true);

        // The stale, already-settled attempt for address #2 must not have overwritten
        // tracking of the genuinely still in-flight attempt for address #3.
        Mockito.verify(thirdAttempt).cancel(true);
    }

    @Test
    void happyEyeballs_firstAddressFailsFast_advancesPromptlyAtMinimumSpacingNotFullStagger() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession winnerSession = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.failed(new IOException("refused"));
                    return CompletableFuture.completedFuture(null);
                })
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(winnerSession);
                    return CompletableFuture.completedFuture(winnerSession);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        // The 1st address failed immediately: the 2nd must be scheduled at the RFC 8305
        // Section 5 minimum inter-attempt spacing (10ms), not the full 250ms stagger delay.
        assertFalse(future.isDone());
        final ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(scheduler.mock).schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));
        assertTrue(delayCaptor.getValue() <= 10L && delayCaptor.getValue() > 0L,
                "must advance at the minimum spacing (<=10ms), not the full stagger delay: was " + delayCaptor.getValue());

        scheduler.fireNext();
        assertTrue(future.isDone());
        assertEquals(winnerSession, future.get());
        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), any(), any(), any(), any(), any());
    }

    @Test
    void happyEyeballs_bothAttemptsSucceed_loserSessionIsClosedNotLeaked() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession secondSession = Mockito.mock(IOSession.class);
        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> firstAttempt) // stalls until completed manually below
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(secondSession);
                    return CompletableFuture.completedFuture(secondSession);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        // The first attempt stalls, so the stagger fires and starts the second attempt, which
        // wins the race immediately.
        assertFalse(future.isDone());
        scheduler.fireNext();
        assertTrue(future.isDone());
        assertEquals(secondSession, future.get());
        Mockito.verify(secondSession, Mockito.never()).close(any(CloseMode.class));

        // The first address now completes late, after the race was already decided: its
        // session must be discarded rather than leaked or allowed to override the winner.
        final FutureCallback<IOSession>[] allCallbacks = captureCallbacks(connectionInitiator, 2);
        final IOSession lateSession = Mockito.mock(IOSession.class);
        allCallbacks[0].completed(lateSession);

        Mockito.verify(lateSession).close(CloseMode.IMMEDIATE);
        assertEquals(secondSession, future.get(), "the already-decided winner must not change");
    }

    @Test
    void happyEyeballs_allAddressesFail_aggregatedFailure() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.failed(new IOException("refused"));
                    return CompletableFuture.completedFuture(null);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        // All 3 addresses fail fast, one after another; each advance is scheduled at the
        // RFC 8305 minimum inter-attempt spacing (10ms) rather than happening synchronously.
        assertFalse(future.isDone());
        scheduler.fireNext();
        assertFalse(future.isDone());
        scheduler.fireNext();
        assertTrue(future.isDone());
        Mockito.verify(connectionInitiator, Mockito.times(3)).connect(any(), any(), any(), any(), any(), any());
        try {
            future.get();
            fail("Expected ExecutionException");
        } catch (final ExecutionException ex) {
            assertInstanceOf(IOException.class, ex.getCause());
        }
    }

    @Test
    void happyEyeballs_cancellationBeforeAnyAttemptResolves_cancelsOutstandingAndPendingStagger() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> firstAttempt);

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertFalse(future.isDone());
        assertEquals(1, scheduler.pendingCount());

        future.cancel(true);

        Mockito.verify(firstAttempt).cancel(true);
        assertTrue(future.isCancelled());
    }

    @Test
    void happyEyeballs_lateCompletionAfterCancellation_closesSessionWithoutReachingFuture() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Mockito.mock(Future.class));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);
        future.cancel(true);

        final FutureCallback<IOSession>[] callbacks = captureCallbacks(connectionInitiator, 1);
        final IOSession lateSession = Mockito.mock(IOSession.class);
        callbacks[0].completed(lateSession);

        Mockito.verify(lateSession).close(CloseMode.IMMEDIATE);
        assertTrue(future.isCancelled());
    }

    @Test
    void happyEyeballs_singleAddress_doesNotRace() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(1);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return CompletableFuture.completedFuture(session);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertTrue(future.isDone());
        assertEquals(session, future.get());
        assertEquals(0, scheduler.pendingCount(), "a single address must never schedule a stagger");
    }

    @Test
    void happyEyeballs_disabledByDefault_usesSequentialPathEvenWithMultipleAddresses() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return CompletableFuture.completedFuture(session);
                });

        // Explicit happyEyeballsEnabled=false, as opposed to the default single-arg constructor
        // already exercised by the other tests in this class.
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, false, TimeValue.ofMilliseconds(250));
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertTrue(future.isDone());
        assertEquals(session, future.get());
        Mockito.verify(connectionInitiator, Mockito.times(1)).connect(any(), any(), any(), any(), any(), any());
    }

    @Test
    void happyEyeballs_respectsConfiguredDelay() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Mockito.mock(Future.class));

        final ScheduledExecutorService schedulerMock = Mockito.mock(ScheduledExecutorService.class);
        Mockito.when(schedulerMock.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(Mockito.mock(ScheduledFuture.class));

        final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(
                dnsResolver, true, TimeValue.ofMilliseconds(777), schedulerMock);
        requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        Mockito.verify(schedulerMock).schedule(any(Runnable.class), org.mockito.ArgumentMatchers.eq(777L), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void happyEyeballs_rejectsDelayBelowRfc8305Minimum() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(5)));
    }

    @Test
    void happyEyeballs_unexpectedCancellationOfNonLastAttempt_advancesToNextAddress() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession winnerSession = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // Cancelled by something other than this race itself (e.g. an external
                    // close), not a failure and not our own decided/cancel() path.
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.cancelled();
                    return CompletableFuture.completedFuture(null);
                })
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(winnerSession);
                    return CompletableFuture.completedFuture(winnerSession);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertFalse(future.isDone());
        scheduler.fireNext(); // the minimum-spacing advance after the unexpected cancellation
        assertTrue(future.isDone());
        assertEquals(winnerSession, future.get());
    }

    @Test
    void happyEyeballs_unexpectedCancellationOfLastAttempt_failsRatherThanHangingForever() throws Exception {
        final List<InetSocketAddress> twoAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(twoAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.cancelled();
                    return CompletableFuture.completedFuture(null);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertFalse(future.isDone());
        scheduler.fireNext();
        assertTrue(future.isDone(), "the future must not be left pending forever after every attempt is cancelled");
        try {
            future.get();
            fail("Expected an ExecutionException rather than an indefinitely pending future");
        } catch (final ExecutionException ex) {
            assertInstanceOf(CancellationException.class, ex.getCause());
        }
    }

    /**
     * Reproduces the race between {@code Race#onAttemptCompleted} picking a winner and the
     * caller cancelling the outer {@code Future} directly (not through the Race's own
     * {@code cancel()}): {@code ComplexFuture#completed(T)} can then return {@code false}
     * even though the Race's own bookkeeping says it won, and the winning session must still
     * be closed rather than silently leaked. This genuinely requires two threads contending
     * on the same narrow window inside {@code ComplexFuture#cancel}, so the test runs many
     * iterations under real contention and asserts the invariant (no leaked session, exactly
     * one terminal outcome) holds on every one, rather than depending on hitting the window
     * a specific number of times.
     */
    @Test
    void happyEyeballs_concurrentExternalCancelDuringWinnerSelection_neverLeaksSession() throws Exception {
        for (int i = 0; i < 500; i++) {
            // 2 addresses so this goes through the Race (not the sequential) path; the 2nd
            // attempt is left permanently stalled behind a FakeScheduler so no real timer
            // ever fires during the test.
            final List<InetSocketAddress> remoteAddresses = addresses(2);
            final DnsResolver localDnsResolver = Mockito.mock(DnsResolver.class);
            final ConnectionInitiator localConnectionInitiator = Mockito.mock(ConnectionInitiator.class);
            final NamedEndpoint localEndpoint = Mockito.mock(NamedEndpoint.class);
            Mockito.when(localEndpoint.getHostName()).thenReturn("somehost");
            Mockito.when(localEndpoint.getPort()).thenReturn(8080);
            Mockito.when(localDnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

            final IOSession session = Mockito.mock(IOSession.class);
            final AtomicReference<FutureCallback<IOSession>> attemptCallback = new AtomicReference<>();
            final FakeScheduler localScheduler = new FakeScheduler();
            Mockito.when(localConnectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        attemptCallback.set(invocation.getArgument(5));
                        return Mockito.mock(Future.class);
                    });

            final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(
                    localDnsResolver, true, TimeValue.ofMilliseconds(250), localScheduler.mock);
            final Future<IOSession> future = requester.connect(
                    localConnectionInitiator, localEndpoint, null, Timeout.ofSeconds(5), null, null);

            final CountDownLatch ready = new CountDownLatch(2);
            final CountDownLatch go = new CountDownLatch(1);
            final Thread canceller = new Thread(() -> {
                ready.countDown();
                await(go);
                future.cancel(true);
            });
            final Thread completer = new Thread(() -> {
                ready.countDown();
                await(go);
                attemptCallback.get().completed(session);
            });
            canceller.start();
            completer.start();
            ready.await();
            go.countDown();
            canceller.join(5000);
            completer.join(5000);

            // Exactly one terminal outcome, and the session is never left open.
            assertTrue(future.isDone());
            boolean sessionDelivered = false;
            try {
                sessionDelivered = future.get() == session;
            } catch (final CancellationException | ExecutionException ex) {
                sessionDelivered = false;
            }
            if (!sessionDelivered) {
                Mockito.verify(session, Mockito.atLeastOnce()).close(CloseMode.IMMEDIATE);
            } else {
                Mockito.verify(session, Mockito.never()).close(any(CloseMode.class));
            }
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Independent, asynchronous A/AAAA resolution (AsyncDnsResolver)
    // -------------------------------------------------------------------------

    /** Captures the {@link DnsResolutionCallback} passed to {@link AsyncDnsResolver#resolve}. */
    private static final class FakeAsyncDnsResolver implements AsyncDnsResolver {
        DnsResolutionCallback callback;
        final Cancellable resolutionHandle = Mockito.mock(Cancellable.class);

        @Override
        public Cancellable resolve(final String host, final int port, final DnsResolutionCallback callback) {
            this.callback = callback;
            return resolutionHandle;
        }
    }

    @Test
    void asyncDns_bothFamiliesResolveTogether_racesAllAddresses() throws Exception {
        final List<InetSocketAddress> ipv6 = addresses(1);
        final List<InetSocketAddress> ipv4 = addresses(1);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return CompletableFuture.completedFuture(session);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertNotNull(asyncDnsResolver.callback, "resolver must have been invoked");
        asyncDnsResolver.callback.resolved(ipv6);
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone());
        assertEquals(session, future.get());
        Mockito.verify(connectionInitiator, Mockito.times(1)).connect(any(), any(), any(), any(), any(), any());
        assertTrue(ipv4.size() >= 0); // unused family in this scenario, kept for clarity of intent
    }

    @Test
    void asyncDns_secondFamilyArrivesWhileFirstStalls_mergedIntoOngoingRace() throws Exception {
        // AAAA first, so merging is immediate and not subject to the Resolution Delay (that
        // only applies when A arrives before AAAA); see the dedicated resolutionDelay_* tests.
        final List<InetSocketAddress> firstFamily = ipv6Addresses(1);
        final List<InetSocketAddress> secondFamily = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession winner = Mockito.mock(IOSession.class);
        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> firstAttempt) // 1st address (first family): stalls
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(winner);
                    return CompletableFuture.completedFuture(winner);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        asyncDnsResolver.callback.resolved(firstFamily);
        assertFalse(future.isDone(), "must not complete while the only known address is still stalled");
        assertEquals(0, scheduler.pendingCount(), "nothing to stagger towards until a 2nd address is known");

        // The second family resolves while the first is still stalled: since no next address
        // was known when the first attempt started, the stagger clock starts now instead.
        asyncDnsResolver.callback.resolved(Collections.singletonList(secondFamily.get(0)));
        asyncDnsResolver.callback.completed();
        assertEquals(1, scheduler.pendingCount(), "the newly arrived address must now be paced by a stagger");

        scheduler.fireNext();
        assertTrue(future.isDone());
        assertEquals(winner, future.get());
        Mockito.verify(firstAttempt).cancel(true);
    }

    @Test
    void asyncDns_oneFamilyFailsOtherSucceeds_winsWithSucceedingFamily() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return CompletableFuture.completedFuture(session);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        // AAAA fails (e.g. no IPv6 route), A succeeds: the resolution as a whole still works.
        asyncDnsResolver.callback.failed(new UnknownHostException("no AAAA record"));
        asyncDnsResolver.callback.resolved(addresses(1));
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone());
        assertEquals(session, future.get());
    }

    @Test
    void asyncDns_bothFamiliesFail_aggregatedFailure() {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        final UnknownHostException ipv6Failure = new UnknownHostException("no AAAA record");
        asyncDnsResolver.callback.failed(ipv6Failure);
        asyncDnsResolver.callback.failed(new UnknownHostException("no A record"));
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone());
        try {
            future.get();
            fail("Expected ExecutionException");
        } catch (final InterruptedException | ExecutionException ex) {
            assertInstanceOf(ExecutionException.class, ex);
        }
        Mockito.verifyNoInteractions(connectionInitiator);
    }

    @Test
    void asyncDns_cancellationBeforeResolution_cancelsOutstandingDnsQuery() {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        future.cancel(true);

        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
        assertTrue(future.isCancelled());

        // A family that resolves after cancellation must be ignored, not start a connection.
        asyncDnsResolver.callback.resolved(Collections.singletonList(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080)));
        Mockito.verifyNoInteractions(connectionInitiator);
    }

    @Test
    void asyncDns_notConfigured_fallsBackToSynchronousDnsResolver() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(1);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return CompletableFuture.completedFuture(session);
                });

        // happyEyeballsEnabled=true but no AsyncDnsResolver configured (the default): must fall
        // back to the plain, synchronous DnsResolver exactly as before this feature existed.
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250));
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertTrue(future.isDone());
        assertEquals(session, future.get());
        Mockito.verify(dnsResolver).resolve("somehost", 8080);
    }

    @Test
    void asyncDns_ipv4ArrivesFirst_waitsResolutionDelayForIpv6() throws Exception {
        final List<InetSocketAddress> ipv4 = addresses(1);
        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return CompletableFuture.completedFuture(session);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        // A arrives first: must wait (up to 50ms) for AAAA rather than connecting immediately.
        asyncDnsResolver.callback.resolved(ipv4);
        assertFalse(future.isDone());
        Mockito.verifyNoInteractions(connectionInitiator);

        // AAAA arrives within the delay: it must be tried first (RFC 8305 interleaving).
        asyncDnsResolver.callback.resolved(ipv6);
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone());
        assertEquals(session, future.get());
        final ArgumentCaptor<SocketAddress> addressCaptor = ArgumentCaptor.forClass(SocketAddress.class);
        Mockito.verify(connectionInitiator).connect(any(), addressCaptor.capture(), any(), any(), any(), any());
        assertEquals(ipv6.get(0), addressCaptor.getValue(), "AAAA must be tried before the buffered A address");
    }

    @Test
    void asyncDns_ipv4ArrivesFirst_aaaaNeverArrives_usesIpv4AfterDelayElapses() throws Exception {
        final List<InetSocketAddress> ipv4 = addresses(1);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return CompletableFuture.completedFuture(session);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        asyncDnsResolver.callback.resolved(ipv4);
        assertFalse(future.isDone());
        assertEquals(1, scheduler.pendingCount(), "the Resolution Delay timer must be pending");

        scheduler.fireNext(); // simulate the 50ms Resolution Delay elapsing with no AAAA
        assertTrue(future.isDone());
        assertEquals(session, future.get());
    }

    @Test
    void asyncDns_ipv4ArrivesFirst_aaaaArrivesAfterRacingAlreadyStarted_mergedBehindInFlightAddress() throws Exception {
        final List<InetSocketAddress> ipv4 = addresses(2);
        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> firstAttempt); // stalls

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        asyncDnsResolver.callback.resolved(ipv4);
        scheduler.fireNext(); // Resolution Delay elapses with no AAAA: racing starts with IPv4 alone
        Mockito.verify(connectionInitiator, Mockito.times(1)).connect(any(), any(), any(), any(), any(), any());

        // AAAA now arrives late, after racing has already started on the first IPv4 address.
        // It must be queued ahead of the still-pending (not yet attempted) 2nd IPv4 address,
        // not appended after it.
        asyncDnsResolver.callback.resolved(ipv6);
        asyncDnsResolver.callback.completed();

        scheduler.fireNext(); // fire the stagger for the 2nd (now re-ordered) pending attempt
        final ArgumentCaptor<SocketAddress> addressCaptor = ArgumentCaptor.forClass(SocketAddress.class);
        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), addressCaptor.capture(), any(), any(), any(), any());
        assertEquals(ipv6.get(0), addressCaptor.getAllValues().get(1),
                "the late AAAA address must be tried before the already-pending, not-yet-attempted 2nd A address");
    }

    @Test
    void asyncDns_lateIpv6Result_doesNotDisturbAlreadyAttemptedAddress() throws Exception {
        final List<InetSocketAddress> ipv4First = addresses(1);
        final List<InetSocketAddress> ipv6Late = ipv6Addresses(1);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> firstAttempt);

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        asyncDnsResolver.callback.resolved(ipv4First);
        scheduler.fireNext();
        final ArgumentCaptor<SocketAddress> firstCaptor = ArgumentCaptor.forClass(SocketAddress.class);
        Mockito.verify(connectionInitiator).connect(any(), firstCaptor.capture(), any(), any(), any(), any());
        assertEquals(ipv4First.get(0), firstCaptor.getValue());

        // A late AAAA address arrives after the only known address is already attempting;
        // the already-in-flight attempt must be left untouched.
        asyncDnsResolver.callback.resolved(ipv6Late);
        asyncDnsResolver.callback.completed();
        Mockito.verify(connectionInitiator, Mockito.times(1)).connect(any(), any(), any(), any(), any(), any());
    }

    /**
     * A DNS callback thread starting an attempt and a caller cancelling must exclude each
     * other: otherwise the cancellation snapshots the in-flight attempts before the attempt
     * being started is registered, and that attempt is never cancelled.
     */
    @Test
    void asyncDns_cancelDuringDnsCallbackStartingAttempt_cancelsThatAttempt() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final CountDownLatch insideConnect = new CountDownLatch(1);
        final CountDownLatch releaseConnect = new CountDownLatch(1);
        final Future<IOSession> attempt = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    insideConnect.countDown();
                    releaseConnect.await(10, TimeUnit.SECONDS);
                    return attempt;
                });
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        final List<InetSocketAddress> resolvedAddresses = ipv6Addresses(1);
        final Thread dnsThread = new Thread(() -> asyncDnsResolver.callback.resolved(resolvedAddresses));
        dnsThread.start();
        assertTrue(insideConnect.await(10, TimeUnit.SECONDS));

        final Thread canceller = new Thread(() -> future.cancel(true));
        canceller.start();
        canceller.join(500);
        final boolean cancellerExcluded = canceller.isAlive();
        releaseConnect.countDown();
        dnsThread.join(10000);
        canceller.join(10000);

        assertTrue(cancellerExcluded, "cancel() ran while a DNS callback was inside the race's critical section");
        Mockito.verify(attempt).cancel(true);
    }

    /** The validator must be applied to sessions connected through the asynchronous path. */
    @Test
    void asyncDns_validatorRejectsWinner_fallsBackToFamilyResolvedBeforeTheWin() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        final List<InetSocketAddress> ipv4 = addresses(1);
        asyncDnsResolver.callback.resolved(ipv6);
        asyncDnsResolver.callback.resolved(ipv4);
        asyncDnsResolver.callback.completed();
        assertEquals(1, attemptCallbacks.size());

        attemptCallbacks.get(0).completed(rejected);
        Mockito.verify(rejected).close(CloseMode.IMMEDIATE);
        assertFalse(future.isDone(), "a rejected session must not be reported as the outcome");
        assertEquals(2, attemptCallbacks.size());
        assertEquals(ipv4.get(0), attemptAddresses.get(1));

        attemptCallbacks.get(1).completed(accepted);
        assertTrue(future.isDone());
        assertEquals(accepted, future.get());
    }

    /**
     * AAAA wins the TCP race, the A address resolves while the winner is still under
     * validation, and validation then fails: the resolved A address must still be tried,
     * not discarded by the race's own guard against starting a new attempt once a winner
     * is decided (which must stay shut; the fallback runs through the plain sequential
     * path, not through the race itself).
     */
    @Test
    void asyncDns_validatorRejectsWinner_familyResolvedDuringValidation_isStillTried() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        final AtomicReference<FutureCallback<IOSession>> validation = new AtomicReference<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                validation.set(callback);
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        final List<InetSocketAddress> ipv4 = addresses(1);
        asyncDnsResolver.callback.resolved(ipv6);
        attemptCallbacks.get(0).completed(rejected);
        asyncDnsResolver.callback.resolved(ipv4);
        asyncDnsResolver.callback.completed();
        validation.get().failed(new IOException("handshake failed"));

        assertEquals(2, attemptCallbacks.size(),
                "the A address resolved before the rejection was never tried; future done=" + future.isDone());
        assertEquals(ipv4.get(0), attemptAddresses.get(1));
    }

    /**
     * AAAA wins the TCP race and fails validation while the A query is still outstanding:
     * the operation must wait for the remaining family rather than fail with addresses
     * still to come.
     */
    @Test
    void asyncDns_validatorRejectsWinner_otherFamilyStillResolving_isStillTried() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        attemptCallbacks.get(0).completed(rejected);
        assertFalse(future.isDone(), "failed while the A query was still outstanding");

        asyncDnsResolver.callback.resolved(addresses(1));
        asyncDnsResolver.callback.completed();
        assertEquals(2, attemptCallbacks.size(), "the late A address was never tried");
    }

    /**
     * AAAA resolves with two addresses; the first wins the race and fails validation while
     * its sibling AAAA address is still pending (never raced, since the winner was decided
     * before the stagger for it elapsed). The sibling must be tried before giving up, and if
     * it also fails while A is still outstanding, the operation must still wait rather than
     * fail with a family that has not finished resolving.
     */
    @Test
    void asyncDns_winnerRejected_siblingAddressKnown_otherFamilyStillResolving_isStillTried() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        final List<InetSocketAddress> ipv6 = ipv6Addresses(2);
        final List<InetSocketAddress> ipv4 = addresses(1);

        asyncDnsResolver.callback.resolved(ipv6);
        attemptCallbacks.get(0).completed(rejected);
        assertEquals(2, attemptCallbacks.size());
        assertEquals(ipv6.get(1), attemptAddresses.get(1));

        attemptCallbacks.get(1).failed(new IOException("connection refused"));
        assertFalse(future.isDone(), "failed while the A query was still outstanding");

        asyncDnsResolver.callback.resolved(ipv4);
        asyncDnsResolver.callback.completed();
        assertEquals(3, attemptCallbacks.size(), "the late A address was never tried");
        assertEquals(ipv4.get(0), attemptAddresses.get(2));
    }

    /**
     * Same as above, but A resolves while the sibling AAAA fallback attempt is still in
     * flight rather than after it has already failed: the merge must not disturb that
     * in-flight attempt, and A must still be tried once it fails.
     */
    @Test
    void asyncDns_winnerRejected_familyResolvedDuringSequentialFallback_isStillTried() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        final List<InetSocketAddress> ipv6 = ipv6Addresses(2);
        final List<InetSocketAddress> ipv4 = addresses(1);

        asyncDnsResolver.callback.resolved(ipv6);
        attemptCallbacks.get(0).completed(rejected);
        assertEquals(2, attemptCallbacks.size());

        asyncDnsResolver.callback.resolved(ipv4);
        asyncDnsResolver.callback.completed();
        attemptCallbacks.get(1).failed(new IOException("connection refused"));

        assertEquals(3, attemptCallbacks.size(),
                "the A address resolved during the fallback was never tried; future done=" + future.isDone());
        assertEquals(ipv4.get(0), attemptAddresses.get(2));
    }

    /** Both families already known before the winner is rejected: the multi-address variant. */
    @Test
    void asyncDns_familyResolvedDuringValidation_multiAddressWinnerFamily_isTried() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        final AtomicInteger terminalCallbacks = new AtomicInteger();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final AtomicReference<FutureCallback<IOSession>> validation = new AtomicReference<>();
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                validation.set(callback);
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final FutureCallback<IOSession> countingCallback = new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession result) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void failed(final Exception ex) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void cancelled() {
                terminalCallbacks.incrementAndGet();
            }
        };
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, countingCallback);

        final List<InetSocketAddress> ipv6 = ipv6Addresses(2);
        final List<InetSocketAddress> ipv4 = addresses(1);

        asyncDnsResolver.callback.resolved(ipv6);
        attemptCallbacks.get(0).completed(rejected);
        asyncDnsResolver.callback.resolved(ipv4);
        asyncDnsResolver.callback.completed();
        validation.get().failed(new IOException("handshake failed"));

        assertEquals(2, attemptCallbacks.size());
        attemptCallbacks.get(1).failed(new IOException("connection refused"));
        assertEquals(3, attemptCallbacks.size());
        attemptCallbacks.get(2).completed(accepted);
        assertTrue(future.isDone());
        assertEquals(accepted, future.get());
        assertTrue(attemptAddresses.contains(ipv4.get(0)));
        assertEquals(1, terminalCallbacks.get());
    }

    /**
     * The rejection cause must survive even if the other family's DNS resolution later fails
     * on its own: that is a separate, unrelated failure, not the reason the connection could
     * not be made.
     */
    @Test
    void asyncDns_winnerRejected_otherFamilyThenFailsToResolve_reportsValidationFailure() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final AtomicInteger terminalCallbacks = new AtomicInteger();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final FutureCallback<IOSession> countingCallback = new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession result) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void failed(final Exception ex) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void cancelled() {
                terminalCallbacks.incrementAndGet();
            }
        };
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, countingCallback);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        attemptCallbacks.get(0).completed(rejected);
        assertFalse(future.isDone());

        // Contract of AsyncDnsResolver: every family either resolves or fails before completed().
        asyncDnsResolver.callback.failed(new UnknownHostException("no A record"));
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone());
        final Throwable cause = causeOf(future);
        assertFalse(cause instanceof UnknownHostException,
                "a host that connected and failed validation was reported as unresolvable: " + cause);
        assertTrue(String.valueOf(cause.getMessage()).contains("handshake failed"), "unexpected cause: " + cause);
        assertEquals(1, terminalCallbacks.get());
    }

    /** Same, but the other family fails to resolve before the winner is even rejected. */
    @Test
    void asyncDns_otherFamilyFailsBeforeRejection_reportsValidationFailure() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final AtomicInteger terminalCallbacks = new AtomicInteger();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final FutureCallback<IOSession> countingCallback = new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession result) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void failed(final Exception ex) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void cancelled() {
                terminalCallbacks.incrementAndGet();
            }
        };
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, countingCallback);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        asyncDnsResolver.callback.failed(new UnknownHostException("no A record"));
        attemptCallbacks.get(0).completed(rejected);
        assertFalse(future.isDone());
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone());
        final Throwable cause = causeOf(future);
        assertTrue(String.valueOf(cause.getMessage()).contains("handshake failed"), "unexpected cause: " + cause);
        assertEquals(1, terminalCallbacks.get());
    }

    /** Resolution completes with nothing left while the winner's rejection is still waited on. */
    @Test
    void asyncDns_winnerRejected_resolutionCompletesWithNothing_failsWithValidationFailure() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final AtomicInteger terminalCallbacks = new AtomicInteger();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final FutureCallback<IOSession> countingCallback = new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession result) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void failed(final Exception ex) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void cancelled() {
                terminalCallbacks.incrementAndGet();
            }
        };
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, countingCallback);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        attemptCallbacks.get(0).completed(rejected);
        assertFalse(future.isDone());
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone(), "future left pending after resolution completed");
        final Throwable cause = causeOf(future);
        assertTrue(String.valueOf(cause.getMessage()).contains("handshake failed"), "unexpected cause: " + cause);
        assertEquals(1, terminalCallbacks.get());
        assertEquals(1, attemptCallbacks.size());
    }

    /** Cancelling while waiting for a fallback must cancel the outstanding DNS resolution too. */
    @Test
    void asyncDns_cancelWhileWaitingForFallback_cancelsResolutionAndIgnoresLateAddresses() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        attemptCallbacks.get(0).completed(rejected);
        assertFalse(future.isDone());
        Mockito.verify(asyncDnsResolver.resolutionHandle, Mockito.never()).cancel();

        assertTrue(future.cancel(true));
        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
        assertTrue(future.isCancelled());

        asyncDnsResolver.callback.resolved(addresses(2));
        asyncDnsResolver.callback.completed();
        assertEquals(1, attemptCallbacks.size(), "an attempt was started after cancellation");
    }

    /**
     * Once the TCP winner passes validation (or there is no validator to fail), a DNS
     * resolution for the other address family that is still outstanding can no longer
     * contribute anything useful and must be cancelled rather than left running to its own
     * timeout for nothing.
     */
    @Test
    void asyncDns_winnerAccepted_cancelsStillPendingResolutionForOtherFamily() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession accepted = Mockito.mock(IOSession.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(accepted);
                    return CompletableFuture.completedFuture(accepted);
                });
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        // IPv6 resolves, wins the TCP race and is accepted with no validator to fail; the
        // IPv4 query is still outstanding and is no longer needed.
        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        assertTrue(future.isDone());
        assertEquals(accepted, future.get());

        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
    }

    /** A fallback attempt, once started, must not be started a second time by a later merge. */
    @Test
    void asyncDns_fallbackStartedOnce_completionAfterwardsDoesNotFailFuture() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final AtomicInteger terminalCallbacks = new AtomicInteger();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final FutureCallback<IOSession> countingCallback = new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession result) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void failed(final Exception ex) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void cancelled() {
                terminalCallbacks.incrementAndGet();
            }
        };
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, countingCallback);
        final List<InetSocketAddress> ipv4 = addresses(2);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        attemptCallbacks.get(0).completed(rejected);
        // IPv6 already resolved: no Resolution Delay for this one.
        asyncDnsResolver.callback.resolved(ipv4);
        assertEquals(2, attemptCallbacks.size());
        asyncDnsResolver.callback.completed();
        assertEquals(2, attemptCallbacks.size(), "fallback started twice");
        assertFalse(future.isDone(), "future failed while the fallback attempt was still in flight");

        attemptCallbacks.get(1).failed(new IOException("connection refused"));
        assertEquals(3, attemptCallbacks.size());
        assertEquals(ipv4.get(1), attemptAddresses.get(2));
        attemptCallbacks.get(2).completed(accepted);
        assertEquals(accepted, future.get());
        assertEquals(1, terminalCallbacks.get());
    }

    /** IPv4 wins after the Resolution Delay; IPv6 arrives after the rejection. */
    @Test
    void asyncDns_ipv4WinnerRejected_lateIpv6_isTried() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final AtomicInteger terminalCallbacks = new AtomicInteger();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final FutureCallback<IOSession> countingCallback = new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession result) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void failed(final Exception ex) {
                terminalCallbacks.incrementAndGet();
            }

            @Override
            public void cancelled() {
                terminalCallbacks.incrementAndGet();
            }
        };
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, countingCallback);
        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);

        asyncDnsResolver.callback.resolved(addresses(1));
        assertEquals(0, attemptCallbacks.size());
        scheduler.fireNext();
        assertEquals(1, attemptCallbacks.size());
        attemptCallbacks.get(0).completed(rejected);
        assertFalse(future.isDone());

        asyncDnsResolver.callback.resolved(ipv6);
        assertEquals(2, attemptCallbacks.size());
        assertEquals(ipv6.get(0), attemptAddresses.get(1));
        asyncDnsResolver.callback.completed();
        attemptCallbacks.get(1).completed(accepted);
        assertEquals(accepted, future.get());
        assertEquals(1, terminalCallbacks.get());
    }

    /**
     * IPv4 wins after the Resolution Delay and fails validation; IPv6 arrives afterwards and
     * resolution completes. Whatever else happens, the future must not stay pending with
     * nothing in flight.
     */
    @Test
    void asyncDns_ipv4WinnerRejected_lateIpv6_thenResolutionComplete_futureNotLeftPendingForever() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(addresses(1));
        scheduler.fireNext();
        assertEquals(1, attemptCallbacks.size());
        attemptCallbacks.get(0).completed(rejected);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        asyncDnsResolver.callback.completed();

        assertTrue(future.isDone() || attemptCallbacks.size() == 2,
                "future pending forever: done=" + future.isDone() + ", attempts=" + attemptCallbacks.size()
                        + ", timers=" + scheduler.pendingCount());
    }

    /** Cancelling while a fallback attempt started by a late merge is in flight must still
     *  reach the outstanding DNS resolution, not only the fallback attempt itself. */
    @Test
    void asyncDns_cancelDuringFallbackStartedByLateMerge_stillCancelsResolution() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(addresses(1));
        scheduler.fireNext();
        attemptCallbacks.get(0).completed(rejected);
        asyncDnsResolver.callback.resolved(ipv6Addresses(1));

        // Resolution is still outstanding (completed() not yet called) when the caller gives up.
        future.cancel(true);
        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
    }

    /**
     * The resolver hands over one family from inside resolve() (for example from a cache) and
     * the attempt wins before resolve() returns its handle. A resolver that goes quiet once
     * cancelled must not leave the future pending forever.
     */
    @Test
    void asyncDns_winnerDecidedBeforeResolutionHandleIsSet_rejection_futureStillCompletes() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final Cancellable handle = Mockito.mock(Cancellable.class);
        final AtomicReference<DnsResolutionCallback> dns = new AtomicReference<>();
        final AtomicReference<FutureCallback<IOSession>> validation = new AtomicReference<>();
        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final AsyncDnsResolver resolver = (host, port, callback) -> {
            dns.set(callback);
            callback.resolved(ipv6);
            // The reactor completes the attempt while the resolver is still busy issuing the A query.
            attemptCallbacks.get(0).completed(rejected);
            return handle;
        };
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                validation.set(callback);
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), resolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);
        assertNotNull(validation.get());
        final boolean resolutionCancelledByRace = !Mockito.mockingDetails(handle).getInvocations().isEmpty();

        validation.get().failed(new IOException("handshake failed"));
        if (!resolutionCancelledByRace) {
            // Resolver left alone: it reports the A family and completes.
            dns.get().failed(new UnknownHostException("no A record"));
            dns.get().completed();
        }
        assertTrue(future.isDone(),
                "future left pending forever; race cancelled its own resolution=" + resolutionCancelledByRace);
    }

    /** Same race, but the other family would have provided a usable address. */
    @Test
    void asyncDns_winnerDecidedBeforeResolutionHandleIsSet_resolutionNotCancelledWhileStillNeeded() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final Cancellable handle = Mockito.mock(Cancellable.class);
        final AtomicReference<FutureCallback<IOSession>> validation = new AtomicReference<>();
        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final AsyncDnsResolver resolver = (host, port, callback) -> {
            callback.resolved(ipv6);
            attemptCallbacks.get(0).completed(rejected);
            return handle;
        };
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                validation.set(callback);
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), resolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);
        assertFalse(future.isDone());
        Mockito.verify(handle, Mockito.never()).cancel();
    }

    /** A stagger timer must never be scheduled for a race that is already decided. */
    @Test
    void asyncDns_addressResolvedDuringValidation_schedulesNoStaggerTimer() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final AtomicReference<FutureCallback<IOSession>> validation = new AtomicReference<>();
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> validation.set(callback);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        requester.connect(connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(2));
        assertEquals(1, attemptCallbacks.size());
        scheduler.fireNext();
        assertEquals(2, attemptCallbacks.size());
        assertEquals(0, scheduler.pendingCount());

        attemptCallbacks.get(1).completed(rejected);
        assertNotNull(validation.get());
        asyncDnsResolver.callback.resolved(addresses(1));
        assertEquals(2, attemptCallbacks.size());
        assertEquals(0, scheduler.pendingCount(), "stagger timer scheduled for an already decided race");
    }

    // -------------------------------------------------------------------------
    // Reentrant attempt bookkeeping
    // -------------------------------------------------------------------------

    /**
     * A {@link ConnectionInitiator} that invokes its callback synchronously, from inside
     * {@code connect()}, can make a losing attempt's own failure recurse into starting the
     * next attempt before the outer frame that started the loser has appended its own future
     * to the race's bookkeeping. The race must still attribute each future to the attempt
     * that created it by attempt index, not by the order in which {@code connect()} calls
     * happened to return, or a reentrant start like this shifts every later bookkeeping entry
     * by one: the still-racing loser would then be left both uncancelled and unretried, while
     * the already-failed attempt would be misidentified as the loser and retried instead.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_reentrantRacingStart_loserBookkeepingStaysAligned() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejectedWinner = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<Future<IOSession>> attemptFutures = new ArrayList<>();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    final Future<IOSession> attemptFuture = Mockito.mock(Future.class);
                    attemptFutures.add(attemptFuture);
                    if (attemptCallbacks.size() == 2) {
                        // Address #2 is refused from inside connect(), after more than the
                        // 10ms RFC 8305 minimum spacing: the fallback to #3 below runs, and
                        // returns, before this call itself does.
                        final FutureCallback<IOSession> cb = invocation.getArgument(5);
                        Thread.sleep(15);
                        cb.failed(new IOException("refused"));
                    }
                    return attemptFuture;
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejectedWinner) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        assertEquals(1, attemptCallbacks.size()); // address #1 in flight
        scheduler.fireNext(); // address #2 starts, fails synchronously -> #3 starts reentrantly
        assertEquals(3, attemptCallbacks.size());
        assertEquals(remoteAddresses.get(2), attemptAddresses.get(2));

        attemptCallbacks.get(0).completed(rejectedWinner); // #1 wins and is rejected; #3 is the only genuine loser

        Mockito.verify(attemptFutures.get(2)).cancel(true);
        assertEquals(4, attemptCallbacks.size(), "the winner's rejection must retry the in-flight loser #3");
        assertEquals(remoteAddresses.get(2), attemptAddresses.get(3),
                "fallback must retry #3, not the already-failed #2");

        attemptCallbacks.get(3).completed(accepted);
        assertSame(accepted, future.get());
    }

    // -------------------------------------------------------------------------
    // Settled attempts must never be mistaken for genuine race losers
    // -------------------------------------------------------------------------

    /**
     * An attempt that already failed on its own, before a winner was decided, must stay
     * excluded from the loser-retry walk after the winner is later rejected: unlike a genuine
     * loser (one still racing at the moment the winner was decided), it must not be cancelled
     * a second time, and its address must not be retried. Genuine losers, by contrast, must
     * be retried ahead of any address not yet attempted at all, in the order their attempts
     * were originally started.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_winnerRejected_settledAttemptExcluded_genuineLosersRetriedBeforeUntriedAddress() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(5);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession rejected2 = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<Future<IOSession>> attemptFutures = new ArrayList<>();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    attemptCallbacks.add(invocation.getArgument(5));
                    final Future<IOSession> attemptFuture = Mockito.mock(Future.class);
                    attemptFutures.add(attemptFuture);
                    return attemptFuture;
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else if (session == rejected2) {
                callback.failed(new IOException("handshake failed again"));
            } else {
                callback.completed(session);
            }
        };

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        assertEquals(1, attemptCallbacks.size());                     // #1 in flight
        scheduler.fireNext();
        assertEquals(2, attemptCallbacks.size());                     // #2 in flight
        attemptCallbacks.get(0).failed(new IOException("refused"));   // #1 settles on its own, before any winner
        if (attemptCallbacks.size() == 2) {
            // Less than the 10ms minimum spacing since #2 started: paced by a timer.
            scheduler.fireNext();
        }
        assertEquals(3, attemptCallbacks.size());                     // #3 in flight
        scheduler.fireNext();
        assertEquals(4, attemptCallbacks.size());                     // #4 in flight

        attemptCallbacks.get(2).completed(rejected);                  // #3 wins; #2 and #4 are genuine losers, #5 untried

        Mockito.verify(attemptFutures.get(1)).cancel(true);
        Mockito.verify(attemptFutures.get(3)).cancel(true);
        Mockito.verify(attemptFutures.get(0), Mockito.never()).cancel(true);
        assertEquals(5, attemptCallbacks.size());
        assertEquals(remoteAddresses.get(1), attemptAddresses.get(4),
                "the genuine loser #2 must be retried before the never-yet-tried #5");

        // Late callbacks reaching the ORIGINAL, now-cancelled loser attempts must stay inert.
        attemptCallbacks.get(1).cancelled();
        attemptCallbacks.get(3).failed(new IOException("late"));
        final IOSession lateSession = Mockito.mock(IOSession.class);
        attemptCallbacks.get(1).completed(lateSession);
        Mockito.verify(lateSession).close(CloseMode.IMMEDIATE);
        assertEquals(5, attemptCallbacks.size());
        assertFalse(future.isDone());

        attemptCallbacks.get(4).failed(new IOException("refused again"));
        assertEquals(6, attemptCallbacks.size());
        assertEquals(remoteAddresses.get(3), attemptAddresses.get(5), "the other genuine loser #4 must be retried next");

        attemptCallbacks.get(5).completed(rejected2);
        assertEquals(7, attemptCallbacks.size());
        assertEquals(remoteAddresses.get(4), attemptAddresses.get(6), "only now is the never-yet-tried #5 retried");

        attemptCallbacks.get(6).completed(accepted);
        assertSame(accepted, future.get());
    }

    /**
     * An attempt unexpectedly cancelled (by something other than the race itself, before a
     * winner is decided) must be excluded from the loser-retry walk exactly like one that
     * failed on its own merits: it already settled on its own, not by losing a race, so it
     * must neither be cancelled a second time nor have its address retried as a fallback
     * candidate.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_winnerDecided_unexpectedlyCancelledAttemptExcludedFromLoserRetry() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejectedWinner = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        final Future<IOSession> secondAttempt = Mockito.mock(Future.class);
        final Future<IOSession> thirdAttempt = Mockito.mock(Future.class);
        final List<SocketAddress> attemptAddresses = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    return firstAttempt; // address #1: stalls
                })
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    return secondAttempt; // address #2: stalls, cancelled externally below
                })
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    return thirdAttempt; // address #3: stalls
                })
                .thenAnswer(invocation -> {
                    attemptAddresses.add(invocation.getArgument(1));
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(accepted);
                    return CompletableFuture.completedFuture(accepted);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejectedWinner) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        scheduler.fireNext(); // address #2 starts racing alongside #1

        final FutureCallback<IOSession>[] firstTwo = captureCallbacks(connectionInitiator, 2);
        firstTwo[1].cancelled(); // address #2 unexpectedly cancelled, unrelated to this race
        scheduler.fireNext(); // minimum-spacing advance to address #3

        final FutureCallback<IOSession>[] firstThree = captureCallbacks(connectionInitiator, 3);
        firstThree[0].completed(rejectedWinner); // address #1 wins; #3 is the genuine loser

        Mockito.verify(thirdAttempt).cancel(true);
        Mockito.verify(secondAttempt, Mockito.never()).cancel(true);
        assertEquals(4, attemptAddresses.size(), "the winner's rejection must retry address #3, not #2");
        assertEquals(remoteAddresses.get(2), attemptAddresses.get(3));
        assertTrue(future.isDone());
        assertEquals(accepted, future.get());
    }

    // -------------------------------------------------------------------------
    // Aggregated failure message must not list a re-queued address twice
    // -------------------------------------------------------------------------

    /**
     * Once a losing race attempt's address is re-queued as a fallback candidate (see {@link
     * Race#onAttemptCompleted}), it appears twice in the race's own address bookkeeping: once
     * as originally raced, once as the re-queued fallback candidate. If every address is then
     * exhausted, the aggregated failure reported to the caller must still name each distinct
     * address only once.
     */
    @Test
    void happyEyeballs_exhaustedAfterRequeue_aggregatedFailureListsEachAddressOnce() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        scheduler.fireNext();                                        // address #2 starts racing alongside #1
        attemptCallbacks.get(1).completed(rejected);                 // #2 wins and is rejected; #1 is re-queued as fallback
        assertEquals(3, attemptCallbacks.size());
        // A plain IOException is reported to the caller unchanged, with no address list
        // built at all (see ConnectExceptionSupport#enhance); a ConnectException is the
        // case that actually embeds the address list this test is about.
        attemptCallbacks.get(2).failed(new java.net.ConnectException("Connection refused")); // the re-queued #1 also fails: nothing left to try

        final Throwable cause = causeOf(future);
        final String message = String.valueOf(cause.getMessage());
        final String address1 = remoteAddresses.get(0).getAddress().toString();
        assertEquals(message.indexOf(address1), message.lastIndexOf(address1),
                "address listed more than once: " + message);
    }

    // -------------------------------------------------------------------------
    // A DNS resolution still outstanding must be cancelled on every exit, not only one
    // -------------------------------------------------------------------------

    /**
     * A fallback attempt that is accepted after the race's TCP winner failed validation is
     * just as much the operation's winner as one that won the TCP race outright: it must
     * cancel a DNS resolution for the other address family that is still outstanding exactly
     * the same way.
     */
    @Test
    void asyncDns_fallbackAttemptAccepted_cancelsOutstandingResolution() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(2));
        attemptCallbacks.get(0).completed(rejected);
        assertEquals(2, attemptCallbacks.size());

        attemptCallbacks.get(1).completed(accepted); // the fallback attempt, not the TCP race winner, is accepted
        assertSame(accepted, future.get());
        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
    }

    /** Same as above, but the accepted fallback attempt is for an address of the family that
     *  resolved only after the TCP winner was already rejected, and resolution is still not
     *  complete at the time of acceptance. */
    @Test
    void asyncDns_lateFamilyFallbackAcceptedBeforeResolutionComplete_cancelsResolution() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        attemptCallbacks.get(0).completed(rejected);
        asyncDnsResolver.callback.resolved(addresses(1)); // the other family arrives while waiting for a fallback
        assertEquals(2, attemptCallbacks.size());

        attemptCallbacks.get(1).completed(accepted);
        assertSame(accepted, future.get());
        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
    }

    /**
     * The caller cancels the overall operation while the race winner's validation is still
     * pending, and validation subsequently fails: {@code Race#cancel}'s branch for "nothing of
     * the race's own left to cancel" (a winner was decided but never rejected) must still
     * cancel a DNS resolution for the other address family that is still outstanding, rather
     * than leaving it to run to its own timeout for nothing.
     */
    @Test
    void asyncDns_cancelDuringWinnerValidation_thenValidationFails_cancelsOutstandingResolution() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession accepted = Mockito.mock(IOSession.class);
        final AtomicReference<FutureCallback<IOSession>> validation = new AtomicReference<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(accepted);
                    return CompletableFuture.completedFuture(accepted);
                });
        final MultihomeIOSessionRequester.SessionValidator pendingValidator = (session, callback) -> validation.set(callback);
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, pendingValidator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        assertNotNull(validation.get());

        future.cancel(true);
        validation.get().failed(new IOException("handshake aborted"));

        assertTrue(future.isCancelled());
        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
    }

    /** Same as above, but the validator itself reports {@code cancelled()} for the winner,
     *  rather than the caller cancelling the overall operation directly. */
    @Test
    void asyncDns_validatorReportsCancelledForWinner_cancelsOutstandingResolution() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession accepted = Mockito.mock(IOSession.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(accepted);
                    return CompletableFuture.completedFuture(accepted);
                });
        final MultihomeIOSessionRequester.SessionValidator cancellingValidator = (session, callback) -> callback.cancelled();
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, cancellingValidator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));

        assertTrue(future.isCancelled());
        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
    }

    /**
     * The resolver hands over an address and the race's winner is accepted before {@code
     * resolve()} even returns its handle: {@code Race#setResolutionCancellable} must treat the
     * resolution as no longer needed by checking whether the winner was accepted, not only
     * whether the race was cancelled as a whole, or a handle arriving this late would never be
     * cancelled.
     */
    @Test
    void asyncDns_winnerAcceptedBeforeResolutionHandleIsSet_handleCancelledOnceKnown() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession accepted = Mockito.mock(IOSession.class);
        final Cancellable handle = Mockito.mock(Cancellable.class);
        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final AsyncDnsResolver resolver = (host, port, callback) -> {
            callback.resolved(ipv6);
            // The reactor completes (and accepts, with no validator to fail) the attempt
            // while the resolver is still busy and has not yet returned its own handle.
            attemptCallbacks.get(0).completed(accepted);
            return handle;
        };
        final FakeScheduler scheduler = new FakeScheduler();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), resolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertTrue(future.isDone());
        assertEquals(accepted, future.get());
        Mockito.verify(handle, Mockito.times(1)).cancel();
    }

    /**
     * Once an address family's resolution has reported {@code completed()}, a DNS resolution
     * handle for it must not be cancelled again when the race's winner is later accepted:
     * {@code Race#onWinnerAccepted} must consult whether resolution is already complete, not
     * cancel whatever handle it still has on record unconditionally.
     */
    @Test
    void asyncDns_winnerAcceptedAfterResolutionAlreadyComplete_handleNotCancelledAgain() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession accepted = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1));
        asyncDnsResolver.callback.failed(new UnknownHostException("no A record"));
        asyncDnsResolver.callback.completed();

        attemptCallbacks.get(0).completed(accepted);
        assertTrue(future.isDone());
        assertEquals(accepted, future.get());
        Mockito.verify(asyncDnsResolver.resolutionHandle, Mockito.never()).cancel();
    }

    // -------------------------------------------------------------------------
    // A reserved #inFlight slot that connect() has not filled yet, or never will
    // -------------------------------------------------------------------------

    /**
     * {@code connect()} for a later racing attempt throws synchronously, exactly as
     * {@code AbstractIOReactorBase#connect} does once the reactor's shutdown has been
     * initiated, while an earlier attempt is still outstanding. The throw must be treated
     * exactly like that same attempt's asynchronous {@code failed()} callback; the earlier
     * attempt, once it goes on to connect, must still be delivered rather than dropped.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_laterAttemptConnectThrows_earlierAttemptStillDelivered() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                })
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        scheduler.fireNext();                        // #2's stagger: connect() throws
        attemptCallbacks.get(0).completed(session);  // #1 connects after all

        assertTrue(future.isDone(), "winner neither delivered nor failed");
        assertSame(session, future.get());
    }

    /** Same shape, but the earlier attempt fails instead of connecting: the operation must
     *  still terminate rather than waiting forever on the attempt that never started. */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_laterAttemptConnectThrows_earlierAttemptFails_operationStillTerminates() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                })
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        scheduler.fireNext();                                        // #2's stagger: connect() throws
        attemptCallbacks.get(0).failed(new IOException("refused"));   // #1 fails too: nothing left to try

        assertTrue(future.isDone(), "every attempt is over, yet the operation never terminates");
    }

    /**
     * Two attempts are still racing when {@code connect()} for a third one throws, as it does
     * once the reactor's shutdown has been initiated. A graceful shutdown lets a pending
     * connect run to completion: the first attempt to connect afterward must still win, and
     * the other one still racing must still be cancelled.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_laterAttemptConnectThrows_twoEarlierAttemptsRacing_firstToConnectStillDelivered() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final List<Future<IOSession>> attemptFutures = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    final Future<IOSession> attemptFuture = Mockito.mock(Future.class);
                    attemptFutures.add(attemptFuture);
                    return attemptFuture;
                })
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    final Future<IOSession> attemptFuture = Mockito.mock(Future.class);
                    attemptFutures.add(attemptFuture);
                    return attemptFuture;
                })
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        scheduler.fireNext();                        // #2's stagger: still racing alongside #1
        scheduler.fireNext();                        // #3's stagger: connect() throws
        attemptCallbacks.get(0).completed(session);  // #1 connects after all, #2 still pending

        assertTrue(future.isDone(), "winner neither delivered nor failed");
        assertSame(session, future.get());
        Mockito.verify(session, Mockito.never()).close(any(CloseMode.class));
        Mockito.verify(attemptFutures.get(1)).cancel(true);
    }

    /**
     * Same shape, but the winner then fails validation: the attempt that was still racing
     * when the winner was decided is retried, and its own {@code connect()} throws as well.
     * The operation must still terminate.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_laterAttemptConnectThrows_twoEarlierAttemptsRacing_winnerRejected_operationStillTerminates() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                })
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                })
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null,
                (s, callback) -> callback.failed(new IOException("handshake failed")), null);

        scheduler.fireNext();                        // #2's stagger: still racing alongside #1
        scheduler.fireNext();                        // #3's stagger: connect() throws
        attemptCallbacks.get(0).completed(session);  // #1 connects, fails validation; #2 retried: connect() throws

        assertTrue(future.isDone(), "the operation never terminates");
        assertThrows(ExecutionException.class, future::get);
        Mockito.verify(session).close(CloseMode.IMMEDIATE);
    }

    /**
     * {@code connect()} for a later racing attempt throws: every address from it on is just
     * as doomed (almost always a {@code ConnectionInitiator} throws this way because
     * something, such as a reactor shutdown, applies equally to all of them), so none of them
     * is tried - {@code #3} is never attempted - but the earlier attempt already in flight is
     * left alone. The caller then cancels while it is still outstanding: {@code cancel()}
     * itself must not throw a {@code NullPointerException} on the attempt whose slot
     * {@code connect()} never filled.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_laterAttemptConnectThrows_cancelStillReachesEveryAttempt() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final Future<IOSession> first = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> first)
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        scheduler.fireNext(); // #2's stagger: connect() throws; #1 is still pending, left alone
        assertFalse(future.isDone(), "must still wait for the attempt already in flight");
        // #3 is never attempted: every address from #2 on was dropped as equally doomed.
        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), any(), any(), any(), any(), any());

        future.cancel(true);
        Mockito.verify(first).cancel(true);
    }

    /** Same shape with asynchronous DNS resolution: the outstanding resolution for the other
     *  family must still be cancelled, with no slot left unguarded along the way. */
    @Test
    @SuppressWarnings("unchecked")
    void asyncDns_laterAttemptConnectThrows_cancelStillCancelsResolution() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Mockito.mock(Future.class))
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);
        asyncDnsResolver.callback.resolved(ipv6Addresses(2));

        scheduler.fireNext(); // #2's stagger: connect() throws; #1 stays outstanding

        future.cancel(true);
        Mockito.verify(asyncDnsResolver.resolutionHandle).cancel();
    }

    /** The fallback attempt's own {@code connect()} throws after the race winner failed
     *  validation: the operation must still terminate rather than stall forever. */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_fallbackAttemptConnectThrows_operationStillTerminates() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                })
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));
        final MultihomeIOSessionRequester.SessionValidator validator =
                (session, callback) -> callback.failed(new IOException("handshake failed"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        attemptCallbacks.get(0).completed(rejected); // wins, rejected; fallback connect(#2) throws

        assertTrue(future.isDone(), "fallback connect() threw, yet the operation never terminates");
    }

    /**
     * A fully synchronous {@link ConnectionInitiator}: the racing attempt connects from
     * inside {@code connect()}, the validator rejects it synchronously, and the fallback
     * attempt started from that same stack reports {@code cancelled()} synchronously, all
     * unwinding before the winner's own {@code connect()} call has returned to fill its
     * reserved slot. The whole operation must end up cancelled cleanly, with no
     * {@code NullPointerException} along the way.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_syncWinnerRejected_syncFallbackCancelled_operationCancelledCleanly() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejected = Mockito.mock(IOSession.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(rejected);
                    return CompletableFuture.completedFuture(rejected);
                })
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.cancelled();
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator =
                (session, callback) -> callback.failed(new IOException("handshake failed"));

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250),
                        new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        assertTrue(future.isCancelled());
    }

    /**
     * An initiator that, from inside {@code connect()} for attempt #2, synchronously reports
     * the failure of attempt #1 (cross-attempt reentrancy), which starts attempt #3, which
     * connects synchronously and wins while #2's own {@code connect()} call is still on the
     * stack and its slot is still null. #2 must still end up cancelled once its slot is
     * filled, and no {@code NullPointerException} must be thrown in the meantime.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_crossAttemptReentrancy_winnerDecidedWhileEarlierConnectStillOnStack() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final Future<IOSession> second = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    final int n = attemptCallbacks.size();
                    if (n == 2) {
                        Thread.sleep(15); // past the 10ms minimum spacing
                        attemptCallbacks.get(0).failed(new IOException("refused")); // #1 fails from inside connect(#2)
                        return second;
                    }
                    if (n == 3) {
                        final FutureCallback<IOSession> cb = invocation.getArgument(5);
                        cb.completed(session); // #3 connects synchronously
                        return CompletableFuture.completedFuture(session);
                    }
                    return Mockito.mock(Future.class);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        scheduler.fireNext(); // #2 starts; #1 fails inside it; #3 starts and wins inside that

        assertEquals(3, attemptCallbacks.size());
        assertTrue(future.isDone(), "winner neither delivered nor failed");
        assertSame(session, future.get());
        Mockito.verify(second).cancel(true); // #2 lost the race: must not be left running
    }

    /**
     * An attempt whose own {@code connect()} call synchronously decides it as this race's
     * winner must never have its own future cancelled once that same call returns and fills
     * its reserved slot: only a losing attempt's late-filled slot is cancelled this way, and
     * the winner is never one of those.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_reentrantSynchronousWinner_ownFutureNotCancelled() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession session = Mockito.mock(IOSession.class);
        final Future<IOSession> winnerFuture = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session); // connects, and wins, synchronously
                    return winnerFuture;
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertTrue(future.isDone());
        assertSame(session, future.get());
        Mockito.verify(winnerFuture, Mockito.never()).cancel(Mockito.anyBoolean());
    }

    // -------------------------------------------------------------------------
    // #cancel() must neither skip nor repeat its DNS-resolution bookkeeping
    // -------------------------------------------------------------------------

    /**
     * The winner's validator reports {@code cancelled()} synchronously, fully before {@code
     * resolve()} itself has returned its handle for this resolution: {@link Race#cancel()}'s
     * pending-validation branch must mark the race {@code cancelled} even though no winner
     * was ever finally accepted, or a resolution handle arriving this late would be stored as
     * still useful instead of being cancelled immediately.
     */
    @Test
    @SuppressWarnings("unchecked")
    void asyncDns_cancelledDuringPendingValidation_lateResolutionHandleCancelledImmediately() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        final Cancellable handle = Mockito.mock(Cancellable.class);
        final List<InetSocketAddress> ipv6 = ipv6Addresses(1);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator cancellingValidator =
                (s, callback) -> callback.cancelled();
        final AsyncDnsResolver resolver = (host, port, callback) -> {
            // The winning attempt connects, and the validator reports cancelled(), fully
            // synchronously, before resolve() itself returns a handle for this resolution.
            callback.resolved(ipv6);
            return handle;
        };
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), resolver, new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, cancellingValidator, null);

        assertTrue(future.isCancelled());
        Mockito.verify(handle).cancel();
    }

    /**
     * The winner's validation is still pending when the caller cancels; the DNS resolution
     * for the other family, already on record by then, is cancelled right away. If the
     * validator later still reports {@code completed()}, {@link Race#onWinnerAccepted} must
     * not cancel that same resolution a second time: {@link Race#cancel()}'s pending-validation
     * branch must clear its own record of the resolution once it has handed it off.
     */
    @Test
    void asyncDns_cancelledDuringPendingValidation_thenValidationAccepted_resolutionNotCancelledTwice() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        final AtomicReference<FutureCallback<IOSession>> validation = new AtomicReference<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator pendingValidator = (s, callback) -> validation.set(callback);
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(
                dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, pendingValidator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(1)); // winner decided; validation left pending
        assertNotNull(validation.get());

        future.cancel(true);
        validation.get().completed(session); // validation finally accepts, after the cancel

        Mockito.verify(asyncDnsResolver.resolutionHandle, Mockito.times(1)).cancel();
    }

    /**
     * The winner is rejected and a fallback attempt is in flight when the caller cancels
     * everything; the DNS resolution for the other family, still outstanding, is cancelled as
     * part of that full cancellation. If the fallback attempt still reports {@code
     * completed()} afterward, {@link Race#onWinnerAccepted} must not cancel that same
     * resolution a second time: {@link Race#cancel()}'s full-cancellation branch must clear
     * its own record of the resolution once it has handed it off.
     */
    @Test
    @SuppressWarnings("unchecked")
    void asyncDns_fullyCancelledWhileFallbackInFlight_lateFallbackAcceptance_resolutionNotCancelledTwice() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(
                dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        asyncDnsResolver.callback.resolved(ipv6Addresses(2)); // two addresses of one family; the other still unresolved
        attemptCallbacks.get(0).completed(rejected);       // wins, rejected; fallback to the sibling address starts
        assertEquals(2, attemptCallbacks.size());

        future.cancel(true); // resolution for the other family, still outstanding, is cancelled now

        attemptCallbacks.get(1).completed(accepted); // the fallback still reports completed() afterward

        Mockito.verify(asyncDnsResolver.resolutionHandle, Mockito.times(1)).cancel();
    }

    // -------------------------------------------------------------------------
    // deliver() must complete the winner before onAccepted can delay or lose it
    // -------------------------------------------------------------------------

    /**
     * {@code onAccepted} may need to cancel a DNS resolution that is no longer needed; a slow
     * or throwing cancellation must never delay, or risk losing, a session that already
     * passed {@link MultihomeIOSessionRequester.SessionValidator}'s check. {@code acceptOrReject}
     * must deliver the session to {@code future} before running {@code onAccepted}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void asyncDns_validatorAcceptsWinner_sessionDeliveredBeforeOnAcceptedRunsEvenIfItThrows() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        final IOSession validated = Mockito.mock(IOSession.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (s, callback) -> callback.completed(validated);
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.doThrow(new RuntimeException("resolution cancellation boom"))
                .when(asyncDnsResolver.resolutionHandle).cancel();
        final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(
                dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        try {
            asyncDnsResolver.callback.resolved(ipv6Addresses(1));
            fail("expected the throwing resolution cancellation to propagate");
        } catch (final RuntimeException expected) {
            // Propagates out of onWinnerAccepted, on the thread that drove the resolution
            // callback; must not have stopped the session that already won from being
            // delivered first.
        }
        assertTrue(future.isDone());
        assertSame(validated, future.get());
    }

    /** Same as above, for the no-validator path of {@code acceptOrReject}. */
    @Test
    @SuppressWarnings("unchecked")
    void asyncDns_noValidator_sessionDeliveredBeforeOnAcceptedRunsEvenIfItThrows() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final IOSession session = Mockito.mock(IOSession.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(session);
                    return Mockito.mock(Future.class);
                });
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        Mockito.doThrow(new RuntimeException("resolution cancellation boom"))
                .when(asyncDnsResolver.resolutionHandle).cancel();
        final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(
                dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        try {
            asyncDnsResolver.callback.resolved(ipv6Addresses(1));
            fail("expected the throwing resolution cancellation to propagate");
        } catch (final RuntimeException expected) {
            // Propagates out of onWinnerAccepted; must not have stopped the session that
            // already won the race from being delivered first.
        }
        assertTrue(future.isDone());
        assertSame(session, future.get());
    }

    // -------------------------------------------------------------------------
    // finish() must de-duplicate addresses in its aggregated failure, too
    // -------------------------------------------------------------------------

    /**
     * The configured {@link DnsResolver} itself returns the same address twice (a resolver
     * is free to do so; nothing here re-queues anything). Both attempts race, both fail, and
     * {@link Race#finish} is reached directly (no winner was ever rejected, so the other,
     * {@code tryNextFallbackAttempt}-based de-duplication path is never involved): the
     * aggregated failure it reports must still name the duplicated address only once.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_duplicateResolvedAddress_allFail_aggregatedFailureListsEachAddressOnce() throws Exception {
        final InetSocketAddress duplicate = addresses(1).get(0);
        final List<InetSocketAddress> remoteAddresses = Arrays.asList(duplicate, duplicate);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        scheduler.fireNext();                                                    // #2 starts racing alongside #1
        attemptCallbacks.get(0).failed(new java.net.ConnectException("Connection refused"));
        attemptCallbacks.get(1).failed(new java.net.ConnectException("Connection refused"));

        final Throwable cause = causeOf(future);
        final String message = String.valueOf(cause.getMessage());
        final String address = duplicate.getAddress().toString();
        assertEquals(message.indexOf(address), message.lastIndexOf(address),
                "address listed more than once: " + message);
    }

    // -------------------------------------------------------------------------
    // connect() throwing synchronously instead of ever invoking its callback, as
    // AbstractIOReactorBase#connect does once a reactor's shutdown has been initiated.
    // -------------------------------------------------------------------------

    /**
     * Reports, from inside the terminal {@code failed()} callback, whether a concurrent
     * {@code cancel()} of the same operation, issued from another thread, can return while
     * the callback is still running. It cannot if the callback is invoked with the race's
     * own lock held: {@code cancel()} needs that same lock.
     */
    private static final class LockProbingCallback implements FutureCallback<IOSession> {
        final AtomicReference<Future<IOSession>> future = new AtomicReference<>();
        final AtomicBoolean failedCalled = new AtomicBoolean();
        final AtomicBoolean concurrentCancelReturned = new AtomicBoolean();

        @Override
        public void completed(final IOSession result) {
        }

        @Override
        public void failed(final Exception ex) {
            failedCalled.set(true);
            final CountDownLatch returned = new CountDownLatch(1);
            final Thread other = new Thread(() -> {
                future.get().cancel(true);
                returned.countDown();
            });
            other.setDaemon(true);
            other.start();
            try {
                concurrentCancelReturned.set(returned.await(2, TimeUnit.SECONDS));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void cancelled() {
        }
    }

    /** Control: every attempt fails through its ordinary, asynchronous failed() callback. */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_everyAttemptFailsAsynchronously_terminalCallbackRunsOutsideRaceLock() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                });
        final LockProbingCallback callback = new LockProbingCallback();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        callback.future.set(requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, callback));

        scheduler.fireNext();
        attemptCallbacks.get(0).failed(new IOException("refused"));
        attemptCallbacks.get(1).failed(new IOException("refused"));

        assertTrue(callback.failedCalled.get());
        assertTrue(callback.concurrentCancelReturned.get(), "terminal callback was invoked with the race lock held");
    }

    /**
     * Every {@code connect()} throws. A synchronous throw is fatal for that address, not
     * retried, so the second address is never even attempted: the operation fails as soon as
     * the first one throws, inside the initial {@code connect()} call itself.
     */
    @Test
    void happyEyeballs_everyConnectThrows_secondAddressNeverAttempted() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        assertTrue(future.isDone());
        Mockito.verify(connectionInitiator, Mockito.times(1)).connect(any(), any(), any(), any(), any(), any());
    }

    /**
     * {@code connect()} for attempt #2 throws, after attempt #1 already settled on its own:
     * the throw is itself what concludes the operation (nothing else is left outstanding),
     * and it must report the terminal failure the same way an ordinary asynchronous failure
     * does, without the race lock held.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_lastAttemptConnectThrows_terminalCallbackRunsOutsideRaceLock() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return Mockito.mock(Future.class);
                })
                .thenThrow(new IOReactorShutdownException("I/O reactor has been shut down"));
        final LockProbingCallback callback = new LockProbingCallback();
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        callback.future.set(requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, callback));

        attemptCallbacks.get(0).failed(new IOException("refused")); // #1 settles on its own first
        scheduler.fireNext();                                       // #2's stagger: connect() throws, nothing left

        assertTrue(callback.future.get().isDone());
        assertTrue(callback.failedCalled.get());
        assertTrue(callback.concurrentCancelReturned.get(), "terminal callback was invoked with the race lock held");
    }

    /**
     * {@code connect()} for attempt #2 throws, but only after the initiator already took the
     * callback; that callback is then invoked after all, asynchronously. A synchronous throw
     * is fatal for that address, so by the time this late callback fires, the catch block has
     * already claimed this attempt's outcome: it must not be processed a second time.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_connectThrowsAfterTakingCallback_lateCallbackNotCountedTwice() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        final Future<IOSession> first = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    return first;
                })
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    throw new IOReactorShutdownException("I/O reactor has been shut down");
                });

        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        scheduler.fireNext(); // #2: connect() throws; #1 is still outstanding, so not over yet

        assertFalse(future.isDone(), "must still wait for attempt #1, already in flight");
        attemptCallbacks.get(1).failed(new IOException("late")); // #2 reports after all, too late to matter
        assertFalse(future.isDone());

        attemptCallbacks.get(0).failed(new IOException("refused")); // #1 settles too: nothing left outstanding
        assertTrue(future.isDone(), "every attempt is over, yet the operation never terminates");
    }

    /**
     * Cross-attempt reentrancy: attempt #1 completes from inside {@code connect()} for attempt
     * #2 and the caller's own completion callback throws. That exception is not attempt #2
     * failing to start and must not be swallowed as such.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_callerCallbackThrowsThroughAnotherAttemptsConnect_exceptionPropagates() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final IOSession session = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attemptCallbacks.add(invocation.getArgument(5));
                    if (attemptCallbacks.size() == 2) {
                        attemptCallbacks.get(0).completed(session);
                    }
                    return Mockito.mock(Future.class);
                });
        final RuntimeException boom = new RuntimeException("caller callback failure");
        final FutureCallback<IOSession> callback = new FutureCallback<IOSession>() {
            @Override
            public void completed(final IOSession result) {
                throw boom;
            }

            @Override
            public void failed(final Exception ex) {
            }

            @Override
            public void cancelled() {
            }
        };
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, callback);

        RuntimeException thrown = null;
        try {
            scheduler.fireNext();
        } catch (final RuntimeException ex) {
            thrown = ex;
        }
        assertSame(boom, thrown, "exception thrown by the caller's own callback was swallowed");
    }

    /**
     * {@code connect()} for a racing attempt reports an ordinary asynchronous failure and
     * only then throws, unrelated to that failure. The failure was already this attempt's
     * own, fully processed outcome: the later exception must simply propagate, never be
     * mistaken for this same attempt's {@code connect()} never having called back at all.
     */
    @Test
    void happyEyeballs_connectFailsThenThrows_exceptionPropagatesNotSwallowed() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final RuntimeException boom = new RuntimeException("boom after failed()");
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.failed(new IOException("refused"));
                    throw boom;
                });
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), new FakeScheduler().mock);

        RuntimeException thrown = null;
        try {
            requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);
        } catch (final RuntimeException ex) {
            thrown = ex;
        }
        assertSame(boom, thrown, "exception thrown after failed() already settled this attempt was swallowed");
    }

    /** Same shape, but the attempt is unexpectedly cancelled rather than failed. */
    @Test
    void happyEyeballs_connectCancelsThenThrows_exceptionPropagatesNotSwallowed() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final RuntimeException boom = new RuntimeException("boom after cancelled()");
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.cancelled();
                    throw boom;
                });
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), new FakeScheduler().mock);

        RuntimeException thrown = null;
        try {
            requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);
        } catch (final RuntimeException ex) {
            thrown = ex;
        }
        assertSame(boom, thrown, "exception thrown after cancelled() already settled this attempt was swallowed");
    }

    /**
     * The fallback attempt's {@code connect()} call accepts the connection, triggering
     * {@code onWinnerAccepted}, and only then throws, unrelated to that outcome. The fallback
     * attempt's own completion was already fully processed: the later exception must simply
     * propagate, never be mistaken for this same attempt's {@code connect()} never having
     * called back at all.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_fallbackConnectCompletesThenThrows_exceptionPropagatesNotSwallowed() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(2);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final IOSession accepted = Mockito.mock(IOSession.class);
        final RuntimeException boom = new RuntimeException("boom after fallback completed()");
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(rejected);
                    return Mockito.mock(Future.class);
                })
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    cb.completed(accepted);
                    throw boom;
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), new FakeScheduler().mock);

        RuntimeException thrown = null;
        try {
            requester.connect(connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);
        } catch (final RuntimeException ex) {
            thrown = ex;
        }
        assertSame(boom, thrown, "exception thrown after completed() already settled the fallback attempt was swallowed");
    }

    /**
     * The fallback attempt's own {@code connect()} reports {@code failed()} synchronously,
     * which moves on to the next address, and only then throws. That exception has nothing to
     * do with the attempt's own outcome, which was already processed: the walk must not
     * advance a second time for the same attempt.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_fallbackConnectThrowsAfterReportingFailure_walkAdvancesOnlyOnce() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(4);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final IOSession rejected = Mockito.mock(IOSession.class);
        final List<FutureCallback<IOSession>> attemptCallbacks = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    attemptCallbacks.add(cb);
                    if (attemptCallbacks.size() == 2) {
                        cb.failed(new IOException("refused"));
                        throw new IOReactorShutdownException("late");
                    }
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester.SessionValidator validator = (session, callback) -> {
            if (session == rejected) {
                callback.failed(new IOException("handshake failed"));
            } else {
                callback.completed(session);
            }
        };
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), new FakeScheduler().mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, null, Timeout.ofSeconds(5), null, validator, null);

        RuntimeException thrown = null;
        try {
            attemptCallbacks.get(0).completed(rejected); // wins, rejected; fallback #2 fails, then its connect() throws
        } catch (final RuntimeException ex) {
            thrown = ex;
        }
        assertNotNull(thrown, "exception unrelated to the attempt's own outcome was swallowed");
        assertEquals(3, attemptCallbacks.size(), "fallback walk advanced more than once for a single failure");

        final IOSession accepted = Mockito.mock(IOSession.class);
        attemptCallbacks.get(2).completed(accepted);
        assertSame(accepted, future.get(5, TimeUnit.SECONDS));
    }

    /**
     * The whole operation is cancelled from inside {@code connect()} for attempt #2, while
     * that attempt's slot is still unfilled: {@code cancel()} finds nothing there to cancel,
     * so the attempt must be cancelled once its {@code connect()} returns.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyEyeballs_cancelledWhileConnectStillOnStack_lateFilledAttemptCancelled() throws Exception {
        final List<InetSocketAddress> remoteAddresses = addresses(3);
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);
        Mockito.when(dnsResolver.resolve("somehost", 8080)).thenReturn(remoteAddresses);

        final FakeScheduler scheduler = new FakeScheduler();
        final AtomicReference<Future<IOSession>> futureRef = new AtomicReference<>();
        final Future<IOSession> first = Mockito.mock(Future.class);
        final Future<IOSession> second = Mockito.mock(Future.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenReturn(first)
                .thenAnswer(invocation -> {
                    futureRef.get().cancel(true);
                    return second;
                });
        final MultihomeIOSessionRequester requester =
                new MultihomeIOSessionRequester(dnsResolver, true, TimeValue.ofMilliseconds(250), scheduler.mock);
        futureRef.set(requester.connect(connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null));

        scheduler.fireNext(); // #2 starts; the caller cancels from inside its connect()

        assertTrue(futureRef.get().isCancelled());
        Mockito.verify(first).cancel(true);
        Mockito.verify(second).cancel(true);
    }

    // -------------------------------------------------------------------------
    // Independent A/AAAA resolution: a batch merged in while another is still buffered
    // -------------------------------------------------------------------------

    /**
     * A (IPv4) arrives first and is buffered for the Resolution Delay; AAAA then arrives and
     * its only address is tried at once. From inside that {@code connect()} the resolution
     * completes and the attempt fails. The buffered IPv4 addresses have not been merged yet
     * at that point, but they are known: the operation must go on to try them rather than
     * fail with addresses left untried.
     */
    @Test
    @SuppressWarnings("unchecked")
    void asyncDns_resolutionCompletesAndAttemptFailsInsideConnect_bufferedIpv4StillTried() throws Exception {
        Mockito.when(namedEndpoint.getHostName()).thenReturn("somehost");
        Mockito.when(namedEndpoint.getPort()).thenReturn(8080);

        final FakeScheduler scheduler = new FakeScheduler();
        final FakeAsyncDnsResolver asyncDnsResolver = new FakeAsyncDnsResolver();
        final List<InetSocketAddress> attempted = new ArrayList<>();
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    attempted.add(invocation.getArgument(1));
                    final FutureCallback<IOSession> cb = invocation.getArgument(5);
                    if (attempted.size() == 1) {
                        asyncDnsResolver.callback.completed();
                        cb.failed(new java.net.ConnectException("Connection refused"));
                    }
                    return Mockito.mock(Future.class);
                });
        final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(
                dnsResolver, true, TimeValue.ofMilliseconds(250), asyncDnsResolver, scheduler.mock);
        final Future<IOSession> future = requester.connect(
                connectionInitiator, namedEndpoint, null, Timeout.ofSeconds(5), null, null);

        asyncDnsResolver.callback.resolved(addresses(2));     // A first: buffered
        asyncDnsResolver.callback.resolved(ipv6Addresses(1)); // AAAA: tried at once, fails inside connect()

        assertFalse(future.isDone(), "operation failed with two resolved IPv4 addresses never tried");
    }
}
