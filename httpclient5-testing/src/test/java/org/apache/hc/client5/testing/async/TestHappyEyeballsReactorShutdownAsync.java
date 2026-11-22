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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * A request executed after the client's I/O reactor shutdown has been initiated: every
 * connection attempt is refused by the reactor itself, synchronously, before ever invoking a
 * callback. The failure must still be reported the same way an ordinary asynchronous one is:
 * without any internal lock held, and never on the process-wide thread that paces staggered
 * connection attempts for every other operation too.
 */
class TestHappyEyeballsReactorShutdownAsync {

    private CloseableHttpAsyncClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private CloseableHttpAsyncClient clientFor(final boolean happyEyeballs) throws Exception {
        final List<InetSocketAddress> remoteAddresses = Arrays.asList(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 9),
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 2}), 9));
        final DnsResolver dnsResolver = Mockito.mock(DnsResolver.class);
        Mockito.when(dnsResolver.resolve(Mockito.anyString(), Mockito.anyInt())).thenReturn(remoteAddresses);
        final PoolingAsyncClientConnectionManager connMgr = PoolingAsyncClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setHappyEyeballsEnabled(happyEyeballs)
                .setConnectionAttemptDelay(TimeValue.ofMilliseconds(50))
                .build();
        final CloseableHttpAsyncClient c = HttpAsyncClientBuilder.create()
                .setConnectionManager(connMgr)
                .disableAutomaticRetries()
                .build();
        c.start();
        return c;
    }

    /**
     * The caller cancels the request while holding a monitor its own {@code failed()} callback
     * also takes. Harmless as long as the failure is not reported with an internal lock held
     * that {@code cancel()} needs as well.
     */
    @ParameterizedTest(name = "happyEyeballs={0}")
    @ValueSource(booleans = {false, true})
    void callerCancelsUnderMonitorItsFailureCallbackAlsoTakes_noDeadlock(final boolean happyEyeballs) throws Exception {
        client = clientFor(happyEyeballs);
        client.initiateShutdown();

        final Object monitor = new Object();
        final CountDownLatch inCallback = new CountDownLatch(1);
        final CountDownLatch callbackReturned = new CountDownLatch(1);
        final CountDownLatch cancelReturned = new CountDownLatch(1);
        final AtomicReference<String> callbackThread = new AtomicReference<>();
        final AtomicReference<Future<SimpleHttpResponse>> futureRef = new AtomicReference<>();
        final Thread caller = new Thread(() -> {
            synchronized (monitor) {
                try {
                    if (!inCallback.await(5, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (final InterruptedException ex) {
                    return;
                }
                futureRef.get().cancel(true);
            }
            cancelReturned.countDown();
        }, "caller");
        caller.setDaemon(true);

        futureRef.set(client.execute(SimpleHttpRequest.create("GET", "http://somehost/"), new FutureCallback<SimpleHttpResponse>() {

            @Override
            public void completed(final SimpleHttpResponse result) {
            }

            @Override
            public void failed(final Exception ex) {
                callbackThread.set(Thread.currentThread().getName());
                inCallback.countDown();
                synchronized (monitor) {
                    // the caller's own bookkeeping
                }
                callbackReturned.countDown();
            }

            @Override
            public void cancelled() {
            }

        }));
        caller.start();

        Assertions.assertTrue(callbackReturned.await(5, TimeUnit.SECONDS),
                "failed() callback, running on thread '" + callbackThread.get() + "', is stuck behind the caller's cancel()");
        Assertions.assertTrue(cancelReturned.await(5, TimeUnit.SECONDS), "cancel() never returned");
    }

    /**
     * The failure must not be reported on the process-wide thread that paces staggered
     * connection attempts: a callback that blocks there stalls every other operation's racing.
     */
    @ParameterizedTest(name = "happyEyeballs={0}")
    @ValueSource(booleans = {false, true})
    void failureNotReportedOnSharedStaggerThread(final boolean happyEyeballs) throws Exception {
        client = clientFor(happyEyeballs);
        client.initiateShutdown();

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> callbackThread = new AtomicReference<>();
        client.execute(SimpleHttpRequest.create("GET", "http://somehost/"), new FutureCallback<SimpleHttpResponse>() {

            @Override
            public void completed(final SimpleHttpResponse result) {
                done.countDown();
            }

            @Override
            public void failed(final Exception ex) {
                callbackThread.set(Thread.currentThread().getName());
                done.countDown();
            }

            @Override
            public void cancelled() {
                done.countDown();
            }

        });
        Assertions.assertTrue(done.await(5, TimeUnit.SECONDS), "request never terminated");
        Assertions.assertNotNull(callbackThread.get());
        Assertions.assertFalse(callbackThread.get().startsWith("Connection-attempt-stagger"),
                "failure reported on " + callbackThread.get());
    }

}
