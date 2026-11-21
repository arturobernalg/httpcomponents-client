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
package org.apache.hc.client5.testing.async;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.testing.SSLTestContexts;
import org.apache.hc.client5.testing.tls.TlsHandshakeTimeoutServer;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * Regression tests for the Happy Eyeballs (RFC 8305) racing path of the async client, against a
 * real {@link CloseableHttpAsyncClient}, real local TCP/TLS servers and a real TLS handshake: a
 * TLS failure on the address that won the TCP race must still fall back to the remaining,
 * untried address, the same as the non-racing (sequential) path and the classic client's
 * {@code TestHappyEyeballsTlsFallback} already do.
 */
class TestHappyEyeballsTlsFallbackAsync {

    private HttpServer healthyServer;
    private final List<ServerSocket> tlsRejectProbes = new ArrayList<>();
    private TlsHandshakeTimeoutServer stallingServer;

    @AfterEach
    void shutDown() throws IOException {
        if (healthyServer != null) {
            healthyServer.close(CloseMode.GRACEFUL);
        }
        for (final ServerSocket probe : tlsRejectProbes) {
            probe.close();
        }
        if (stallingServer != null) {
            stallingServer.close();
        }
    }

    /**
     * Starts a listener that accepts a TCP connection, reads whatever the client sends (the
     * TLS ClientHello), and replies with a plaintext, non-TLS response: the client's TLS engine
     * fails the handshake with a genuine {@code SSLException} ("Unrecognized SSL message, got
     * plaintext connection?" or similar) once it tries to parse this as a TLS record. Unlike a
     * bare TCP reset, this exercises {@code TlsStrategy#upgrade} and its failure path, which is
     * what the TLS-fallback scenarios below are named for and must actually fail at.
     */
    private ServerSocket startTlsRejectProbe(final String threadName) throws IOException {
        final ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        tlsRejectProbes.add(probe);
        final Thread rejectThread = new Thread(() -> {
            while (!probe.isClosed()) {
                try (Socket accepted = probe.accept()) {
                    final byte[] clientHello = new byte[4096];
                    accepted.getInputStream().read(clientHello);
                    accepted.getOutputStream().write(
                            "not a TLS server\n".getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                } catch (final IOException ignore) {
                    // Expected once the probe socket is closed by the test, and tolerated if
                    // the client already moved on (e.g. a losing racing attempt cancelled
                    // first): either way this loop keeps accepting until the probe closes.
                }
            }
        }, threadName);
        rejectThread.setDaemon(true);
        rejectThread.start();
        return probe;
    }

    private HttpServer startHealthyServer() throws Exception {
        final HttpServer server = ServerBootstrap.bootstrap()
                .setLocalAddress(InetAddress.getLoopbackAddress())
                .setSslContext(SSLTestContexts.createServerSSLContext())
                .register("localhost", "*", (request, response, context) -> response.setCode(HttpStatus.SC_OK))
                .create();
        server.start();
        return server;
    }

    private CloseableHttpAsyncClient clientFor(final List<InetSocketAddress> remoteAddresses, final boolean happyEyeballs) throws Exception {
        return clientFor(remoteAddresses, happyEyeballs, Timeout.ofSeconds(5));
    }

    private CloseableHttpAsyncClient clientFor(final List<InetSocketAddress> remoteAddresses, final boolean happyEyeballs,
                                                final Timeout connectAndSocketTimeout) throws Exception {
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        Mockito.when(dnsResolver.resolve(Mockito.anyString(), Mockito.anyInt())).thenReturn(remoteAddresses);
        final PoolingAsyncClientConnectionManager connMgr = PoolingAsyncClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connectAndSocketTimeout)
                        .setSocketTimeout(connectAndSocketTimeout)
                        .build())
                .setTlsStrategy(new DefaultClientTlsStrategy(SSLTestContexts.createClientSSLContext()))
                .setHappyEyeballsEnabled(happyEyeballs)
                .setConnectionAttemptDelay(TimeValue.ofMilliseconds(50))
                .build();
        final CloseableHttpAsyncClient client = HttpAsyncClientBuilder.create()
                .setConnectionManager(connMgr)
                .disableAutomaticRetries()
                .build();
        client.start();
        return client;
    }

    private static void assertServedByHealthyServer(final CloseableHttpAsyncClient client) throws Exception {
        final SimpleHttpRequest request = SimpleHttpRequest.create("GET", "https://localhost/");
        final SimpleHttpResponse response = client.execute(request, null).get();
        Assertions.assertEquals(HttpStatus.SC_OK, response.getCode());
    }

    /**
     * Scenario 1: TCP accepted, TLS handshake fails immediately (the server accepts the TCP
     * connection and replies with a plaintext, non-TLS response to the ClientHello).
     */
    @ParameterizedTest(name = "happyEyeballs={0}")
    @ValueSource(booleans = {false, true})
    void tcpAcceptedTlsFailsImmediately_fallsBackToHealthyAddress(final boolean happyEyeballs) throws Exception {
        final int brokenPort = startTlsRejectProbe("tls-reject-probe-async").getLocalPort();

        healthyServer = startHealthyServer();
        final int healthyPort = healthyServer.getLocalPort();

        final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), brokenPort),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), healthyPort));

        try (CloseableHttpAsyncClient client = clientFor(remoteAddresses, happyEyeballs)) {
            assertServedByHealthyServer(client);
        }
    }

    /**
     * Scenario 2: TCP accepted, TLS handshake stalls until the handshake/connect timeout.
     */
    @ParameterizedTest(name = "happyEyeballs={0}")
    @ValueSource(booleans = {false, true})
    void tcpAcceptedTlsStallsUntilTimeout_fallsBackToHealthyAddress(final boolean happyEyeballs) throws Exception {
        stallingServer = new TlsHandshakeTimeoutServer(false);
        stallingServer.start();
        final int stallingPort = stallingServer.getPort();

        healthyServer = startHealthyServer();
        final int healthyPort = healthyServer.getLocalPort();

        final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), stallingPort),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), healthyPort));

        try (CloseableHttpAsyncClient client = clientFor(remoteAddresses, happyEyeballs)) {
            assertServedByHealthyServer(client);
        }
    }

    /**
     * When every address fails TLS, the final exception must still be reported (no address is
     * left silently unreported, and the connection attempt does not hang).
     */
    @ParameterizedTest(name = "happyEyeballs={0}")
    @ValueSource(booleans = {false, true})
    void allAddressesFailTls_throws(final boolean happyEyeballs) throws Exception {
        final int brokenPort1 = startTlsRejectProbe("tls-reject-probe-async-2").getLocalPort();
        final int brokenPort2 = startTlsRejectProbe("tls-reject-probe-async-3").getLocalPort();

        final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), brokenPort1),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), brokenPort2));

        try (CloseableHttpAsyncClient client = clientFor(remoteAddresses, happyEyeballs)) {
            final SimpleHttpRequest request = SimpleHttpRequest.create("GET", "https://localhost/");
            Assertions.assertThrows(ExecutionException.class, () -> client.execute(request, null).get());
        }
    }

    /**
     * Cancelling after a real TLS failure on one address has triggered fallback to the next
     * must reach that next, in-flight attempt: nothing may complete on it afterward. This is
     * checked on the SERVER side of the pending attempt, not on the client's response future:
     * cancelling a future completes it locally regardless of whether the cancellation actually
     * propagated anywhere, so only the pending attempt's own server observing the connection
     * being aborted (rather than running to completion) is real proof.
     */
    @ParameterizedTest(name = "happyEyeballs={0}")
    @ValueSource(booleans = {false, true})
    void cancelDuringFallbackAfterRealTlsFailure_abortsPendingAttempt(final boolean happyEyeballs) throws Exception {
        final int brokenPort = startTlsRejectProbe("tls-reject-probe-cancel").getLocalPort();

        try (ServerSocket pending = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final CountDownLatch pendingAccepted = new CountDownLatch(1);
            final CompletableFuture<Long> clientGoneAtNanos = new CompletableFuture<>();
            final Thread pendingThread = new Thread(() -> {
                try (Socket accepted = pending.accept()) {
                    pendingAccepted.countDown();
                    // Never answers: the handshake, and so this attempt, stays genuinely
                    // pending until the client goes away, by cancellation or its own timeout.
                    try {
                        final InputStream in = accepted.getInputStream();
                        final byte[] buf = new byte[4096];
                        while (in.read(buf) != -1) {
                            // Swallow the ClientHello; never respond.
                        }
                    } catch (final IOException ignore) {
                        // The client closed the connection: treated the same as a clean EOF.
                    }
                    clientGoneAtNanos.complete(System.nanoTime());
                } catch (final IOException ex) {
                    clientGoneAtNanos.completeExceptionally(ex);
                }
            }, "pending-attempt-probe");
            pendingThread.setDaemon(true);
            pendingThread.start();

            final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), brokenPort),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), pending.getLocalPort()));

            // Long enough that reaching it, instead of being cancelled, is unambiguous.
            try (CloseableHttpAsyncClient client = clientFor(remoteAddresses, happyEyeballs, Timeout.ofSeconds(20))) {
                final SimpleHttpRequest request = SimpleHttpRequest.create("GET", "https://localhost/");
                final Future<SimpleHttpResponse> responseFuture = client.execute(request, null);

                Assertions.assertTrue(pendingAccepted.await(5, TimeUnit.SECONDS),
                        "fallback attempt never reached the pending address");
                Assertions.assertFalse(responseFuture.isDone());
                Assertions.assertTrue(responseFuture.cancel(true));

                // If cancellation reached the pending attempt, its server sees the client gone
                // (EOF or reset) within a couple of seconds; if it was lost, the server keeps
                // waiting on it until the 20-second connect timeout above elapses instead.
                try {
                    clientGoneAtNanos.get(2, TimeUnit.SECONDS);
                } catch (final TimeoutException ex) {
                    Assertions.fail("pending attempt still open on the server 2 s after cancel(): "
                            + "cancellation was lost");
                }
            }
        }
    }
}
