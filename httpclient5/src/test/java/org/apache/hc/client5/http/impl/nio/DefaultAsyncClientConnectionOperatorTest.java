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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DefaultAsyncClientConnectionOperatorTest {

    @Test
    void testCancelDuringTlsHandshakeClosesSession() throws Exception {
        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, null);

        final IOSession ioSession = Mockito.mock(IOSession.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(ioSession);
                    return CompletableFuture.completedFuture(ioSession);
                });

        // The handshake never completes: the mocked strategy does not call back.
        final HttpHost target = new HttpHost("https", InetAddress.getLoopbackAddress(), "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);
        Mockito.verify(tlsStrategy).upgrade(any(), any(), any(), any(), any());

        future.cancel(true);
        Mockito.verify(ioSession).close(CloseMode.IMMEDIATE);
    }

    /**
     * A hook that throws after the TLS handshake succeeds must not be able to orphan an
     * already-accepted result: the result is reported before the hook runs, so an exception
     * there can only ever be lost noise, never a future that hangs forever, and never a reason
     * to close the session that was already delivered to the caller.
     */
    @Test
    void testHookThrowingAfterSuccessDoesNotHangFuture() throws Exception {
        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        Mockito.doAnswer(invocation -> {
            final FutureCallback<TransportSecurityLayer> callback = invocation.getArgument(4);
            callback.completed(Mockito.mock(TransportSecurityLayer.class));
            return null;
        }).when(tlsStrategy).upgrade(any(), any(), any(), any(), any());

        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, null) {
            @Override
            protected void onAfterTlsHandshake(final HttpContext context, final HttpHost endpointHost) {
                throw new IllegalStateException("broken onAfterTlsHandshake hook");
            }
        };

        final IOSession ioSession = Mockito.mock(IOSession.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(ioSession);
                    return CompletableFuture.completedFuture(ioSession);
                });

        final HttpHost target = new HttpHost("https", InetAddress.getLoopbackAddress(), "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        // Bounded wait: if the hook's exception re-orphans the result, this times out instead
        // of hanging the test suite indefinitely, making a regression fail fast and visibly.
        final ManagedAsyncClientConnection connection = future.get(5, TimeUnit.SECONDS);
        assertNotNull(connection);
        Mockito.verify(ioSession, Mockito.never()).close(any(CloseMode.class));
    }

    /**
     * Once a TLS validator rejects the address that connected first and the session requester
     * falls back to the next address, the outer connect() future must still be able to cancel
     * that in-flight fallback attempt.
     * Before the fix, the TLS handshake setup replaced the outer future's dependency with
     * "close this one connection", permanently detaching it from the session requester, so a
     * cancellation issued after the fallback started was silently lost.
     */
    @Test
    void testCancelDuringFallbackAfterTlsRejectionCancelsNextAttempt() throws Exception {
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        final InetSocketAddress address1 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), 443);
        final InetSocketAddress address2 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 443);
        Mockito.when(dnsResolver.resolve("somehost", 443)).thenReturn(Arrays.asList(address1, address2));

        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, dnsResolver);

        final IOSession firstSession = Mockito.mock(IOSession.class);
        @SuppressWarnings("unchecked")
        final Future<IOSession> secondAttempt = Mockito.mock(Future.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // Address #1 connects at the TCP level immediately.
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(firstSession);
                    return CompletableFuture.completedFuture(firstSession);
                })
                .thenAnswer(invocation -> secondAttempt); // Address #2's TCP connect never resolves in this test.

        final HttpHost target = new HttpHost("https", null, "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        // Reject address #1's TLS handshake, triggering fallback to address #2.
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<FutureCallback<TransportSecurityLayer>> tlsCallbackCaptor = ArgumentCaptor.forClass(FutureCallback.class);
        Mockito.verify(tlsStrategy, Mockito.times(1)).upgrade(any(), any(), any(), any(), tlsCallbackCaptor.capture());
        tlsCallbackCaptor.getValue().failed(new SSLHandshakeException("untrusted certificate"));
        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), any(), any(), any(), any(), any());

        // Address #2 is now the in-flight fallback attempt; cancelling here must reach it.
        future.cancel(true);
        Mockito.verify(secondAttempt).cancel(true);
    }

    /**
     * A TLS handshake that is itself cancelled (as opposed to one that fails a check) must be
     * propagated as a cancellation, not folded into the same "rejected, try the next address"
     * path a validation failure takes: there is no reason to attempt another address after the
     * caller's own cancellation.
     */
    @Test
    void testTlsHandshakeCancellationDoesNotFallBackToNextAddress() throws Exception {
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        final InetSocketAddress address1 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), 443);
        final InetSocketAddress address2 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 443);
        Mockito.when(dnsResolver.resolve("somehost", 443)).thenReturn(Arrays.asList(address1, address2));

        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, dnsResolver);

        final IOSession firstSession = Mockito.mock(IOSession.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(firstSession);
                    return CompletableFuture.completedFuture(firstSession);
                });

        final HttpHost target = new HttpHost("https", null, "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<FutureCallback<TransportSecurityLayer>> tlsCallbackCaptor = ArgumentCaptor.forClass(FutureCallback.class);
        Mockito.verify(tlsStrategy, Mockito.times(1)).upgrade(any(), any(), any(), any(), tlsCallbackCaptor.capture());
        tlsCallbackCaptor.getValue().cancelled();

        Mockito.verify(connectionInitiator, Mockito.times(1)).connect(any(), any(), any(), any(), any(), any());
        assertTrue(future.isCancelled());
    }

    /**
     * Cancelling before the first address's TLS validation ever begins must still reach that
     * first, in-flight TCP connect attempt. The dependency tracking this relies on is set
     * before {@code sessionRequester.connect(...)} is called, not only after it returns, since
     * a synchronous {@link ConnectionInitiator} can otherwise invoke the TLS validator, and
     * move tracking on to a later stage, before this method's own call returns.
     */
    @Test
    void testCancelDuringFirstConnectAttemptReachesIt() throws Exception {
        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, null);

        @SuppressWarnings("unchecked")
        final Future<IOSession> firstAttempt = Mockito.mock(Future.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> firstAttempt); // Never calls back: the connect itself is still pending.

        final HttpHost target = new HttpHost("https", InetAddress.getLoopbackAddress(), "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        future.cancel(true);
        Mockito.verify(firstAttempt).cancel(true);
        Mockito.verify(tlsStrategy, Mockito.never()).upgrade(any(), any(), any(), any(), any());
    }

    /**
     * A {@link TlsStrategy} that reports failure through its callback and then also throws
     * out of {@code upgrade()} violates its contract, but must not be allowed to advance the
     * session requester's fallback twice for what the caller only sees as one failed attempt.
     * Three addresses are needed to observe a double-advance as an extra connect() call: with
     * only two, a second, spurious advance lands past the end of the address list and is
     * absorbed as ordinary exhaustion instead, masking the bug.
     */
    @Test
    void testTlsStrategyFailingThenThrowingAdvancesFallbackOnlyOnce() throws Exception {
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        final InetSocketAddress address1 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), 443);
        final InetSocketAddress address2 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 443);
        final InetSocketAddress address3 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 3}), 443);
        Mockito.when(dnsResolver.resolve("somehost", 443)).thenReturn(Arrays.asList(address1, address2, address3));

        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        Mockito.doAnswer(invocation -> {
            final FutureCallback<TransportSecurityLayer> callback = invocation.getArgument(4);
            callback.failed(new SSLHandshakeException("untrusted certificate"));
            throw new IllegalStateException("broken TlsStrategy: throws after reporting failure");
        }).when(tlsStrategy).upgrade(any(), any(), any(), any(), any());
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, dnsResolver);

        @SuppressWarnings("unchecked")
        final Future<IOSession> secondAttempt = Mockito.mock(Future.class);
        @SuppressWarnings("unchecked")
        final Future<IOSession> thirdAttempt = Mockito.mock(Future.class);
        final IOSession firstSession = Mockito.mock(IOSession.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // Address #1: connects, then rejected twice by the broken strategy above.
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(firstSession);
                    return CompletableFuture.completedFuture(firstSession);
                })
                .thenAnswer(invocation -> secondAttempt) // Address #2: the correct, single fallback target.
                .thenAnswer(invocation -> thirdAttempt); // Address #3: must never be reached.

        final HttpHost target = new HttpHost("https", null, "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        // One address rejected (reported twice by the broken strategy) still falls back to
        // exactly the next address, not two addresses further.
        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), any(), any(), any(), any(), any());
        assertFalse(future.isDone());
    }

    /**
     * A {@link TlsStrategy} that throws out of {@code upgrade()} without ever invoking its
     * callback must still be treated as a rejection of that address: the catch block is the
     * only thing that reports this failure, and must both fall back to the next address and
     * leave cancellation tracking pointed at it.
     */
    @Test
    void testTlsStrategyThrowingWithoutCallbackFallsBackAndStaysCancellable() throws Exception {
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        final InetSocketAddress address1 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), 443);
        final InetSocketAddress address2 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 443);
        Mockito.when(dnsResolver.resolve("somehost", 443)).thenReturn(Arrays.asList(address1, address2));

        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        Mockito.doThrow(new IllegalStateException("synchronous upgrade failure"))
                .when(tlsStrategy).upgrade(any(), any(), any(), any(), any());
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, dnsResolver);

        final IOSession firstSession = Mockito.mock(IOSession.class);
        @SuppressWarnings("unchecked")
        final Future<IOSession> secondAttempt = Mockito.mock(Future.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(firstSession);
                    return CompletableFuture.completedFuture(firstSession);
                })
                .thenAnswer(invocation -> secondAttempt); // Address #2's TCP connect never resolves in this test.

        final HttpHost target = new HttpHost("https", null, "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), any(), any(), any(), any(), any());
        future.cancel(true);
        Mockito.verify(secondAttempt).cancel(true);
    }

    /**
     * The same TLS-rejection fallback must also work after Happy Eyeballs racing decides a
     * winner, not only on the plain sequential (non-racing) path: {@link
     * MultihomeIOSessionRequester.Race#onWinnerRejected} falls back by calling the same
     * sequential machinery the other tests in this class exercise directly.
     */
    @Test
    void testCancelDuringFallbackAfterRacingWinnerTlsRejectionCancelsNextAttempt() throws Exception {
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        final InetSocketAddress address1 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), 443);
        final InetSocketAddress address2 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 443);
        Mockito.when(dnsResolver.resolve("somehost", 443)).thenReturn(Arrays.asList(address1, address2));

        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, dnsResolver,
                true, TimeValue.ofMilliseconds(250));

        final IOSession firstSession = Mockito.mock(IOSession.class);
        @SuppressWarnings("unchecked")
        final Future<IOSession> secondAttempt = Mockito.mock(Future.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // Address #1 wins the race immediately: it is the only attempt racing
                    // ever starts, since it completes before address #2's stagger delay
                    // would even be scheduled.
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(firstSession);
                    return CompletableFuture.completedFuture(firstSession);
                })
                .thenAnswer(invocation -> secondAttempt); // Address #2's TCP connect never resolves in this test.

        final HttpHost target = new HttpHost("https", null, "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        // Reject the racing winner's TLS handshake, triggering fallback to address #2.
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<FutureCallback<TransportSecurityLayer>> tlsCallbackCaptor = ArgumentCaptor.forClass(FutureCallback.class);
        Mockito.verify(tlsStrategy, Mockito.times(1)).upgrade(any(), any(), any(), any(), tlsCallbackCaptor.capture());
        tlsCallbackCaptor.getValue().failed(new SSLHandshakeException("untrusted certificate"));
        Mockito.verify(connectionInitiator, Mockito.times(2)).connect(any(), any(), any(), any(), any(), any());

        // Address #2 is now the in-flight fallback attempt; cancelling here must reach it.
        future.cancel(true);
        Mockito.verify(secondAttempt).cancel(true);
    }

    /**
     * Cancelling while the winning address's TLS handshake is still pending, before the
     * strategy reports any outcome at all, must stop the overall operation outright: once the
     * strategy later reports the handshake failed (because the connection it was using was
     * just closed), no new attempt against the next address must start.
     *
     * <p>With racing enabled, {@code Race.onWinnerRejected()} calls {@code executeNext()}
     * directly, so this variant is what exercises the {@code isDone()} guard at the top of
     * that method. Without racing, the sequential path reaches {@code onFailure()} first,
     * whose own {@code isDone()} guard is what stops it there; this variant does not, on its
     * own, exercise the top-of-{@code executeNext()} guard.
     */
    private int cancelDuringHandshakeThenReportedFailed(final boolean happyEyeballs) throws Exception {
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        final InetSocketAddress address1 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), 443);
        final InetSocketAddress address2 = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 443);
        Mockito.when(dnsResolver.resolve("somehost", 443)).thenReturn(Arrays.asList(address1, address2));

        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        final DefaultAsyncClientConnectionOperator operator = new DefaultAsyncClientConnectionOperator(
                RegistryBuilder.<TlsStrategy>create().register("https", tlsStrategy).build(), null, dnsResolver,
                happyEyeballs, TimeValue.ofMilliseconds(250));

        final IOSession firstSession = Mockito.mock(IOSession.class);
        final ConnectionInitiator connectionInitiator = Mockito.mock(ConnectionInitiator.class);
        Mockito.when(connectionInitiator.connect(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    final FutureCallback<IOSession> callback = invocation.getArgument(5);
                    callback.completed(firstSession);
                    return CompletableFuture.completedFuture(firstSession);
                });

        final HttpHost target = new HttpHost("https", null, "somehost", 443);
        final Future<ManagedAsyncClientConnection> future = operator.connect(
                connectionInitiator, target, null, null, Timeout.ofSeconds(30), null, null, null);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<FutureCallback<TransportSecurityLayer>> tlsCallbackCaptor = ArgumentCaptor.forClass(FutureCallback.class);
        Mockito.verify(tlsStrategy).upgrade(any(), any(), any(), any(), tlsCallbackCaptor.capture());

        future.cancel(true);
        assertTrue(future.isCancelled());
        // Only now does the strategy learn the handshake failed, because the cancellation
        // above closed the connection it was using.
        tlsCallbackCaptor.getValue().failed(new SSLHandshakeException("connection closed"));
        return Mockito.mockingDetails(connectionInitiator).getInvocations().size();
    }

    @Test
    void testCancelDuringHandshakeDoesNotStartNewAttemptSequential() throws Exception {
        assertEquals(1, cancelDuringHandshakeThenReportedFailed(false));
    }

    @Test
    void testCancelDuringHandshakeDoesNotStartNewAttemptRacing() throws Exception {
        assertEquals(1, cancelDuringHandshakeThenReportedFailed(true));
    }

}
