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

package org.apache.hc.client5.http.examples;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

/**
 * Example of an HTTP/2 client with multiplexing and per-stream timeouts.
 *
 * <p>The client opens a single HTTP/2 connection to nghttp2.org, then:
 * <ul>
 *   <li>executes a warm-up request to establish the H2 connection,</li>
 *   <li>fires a fast request ({@code /httpbin/ip}) that should complete
 *       within the H2 stream timeout, and</li>
 *   <li>fires a slow request ({@code /httpbin/delay/5}) which is expected
 *       to hit the <em>per-stream</em> timeout and fail with a
 *       {@link java.util.concurrent.TimeoutException} while keeping
 *       the HTTP/2 connection alive in the pool.</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This example assumes the core / client code
 * honours {@link RequestConfig#getH2StreamTimeout()} by enforcing a
 * timeout on individual HTTP/2 streams without closing the whole
 * connection.</p>
 *
 * @since 5.6
 */
public final class AsyncClientH2StreamTimeoutExample {

    private AsyncClientH2StreamTimeoutExample() {
    }

    public static void main(final String[] args) throws Exception {

        final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setSoTimeout(Timeout.ofSeconds(10))
                .build();

        final PoolingAsyncClientConnectionManager connectionManager =
                PoolingAsyncClientConnectionManagerBuilder.create()
                        .setDefaultTlsConfig(TlsConfig.custom()
                                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                                .build())
                        .setMessageMultiplexing(true)
                        .build();

        final RequestConfig defaultRequestConfig = RequestConfig.custom()
                //  Per H2 stream timeout:
                .setH2StreamTimeout(Timeout.ofSeconds(2))
                .build();

        final CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .enableH2StreamTimeout()
                .setConnectionManager(connectionManager)
                .setIOReactorConfig(ioReactorConfig)
                .setDefaultRequestConfig(defaultRequestConfig)
                .build();

        client.start();

        // Explicit 443, otherwise HttpRoute(...) sees -1 and explodes
        final HttpHost target = new HttpHost("https", "nghttp2.org", 443);

        // --- Warm-up: make sure we have an open H2 connection in the pool
        final SimpleHttpRequest warmup = SimpleRequestBuilder.get()
                .setHttpHost(target)
                .setPath("/httpbin")
                .build();

        System.out.println("Executing warm-up request " + warmup);
        try {
            final Future<SimpleHttpResponse> warmupFuture = client.execute(
                    SimpleRequestProducer.create(warmup),
                    SimpleResponseConsumer.create(),
                    new FutureCallback<SimpleHttpResponse>() {

                        @Override
                        public void completed(final SimpleHttpResponse response) {
                            System.out.println(warmup + " -> " + new StatusLine(response));
                        }

                        @Override
                        public void failed(final Exception ex) {
                            System.out.println("[warmup] failed: " + warmup + " -> " + ex);
                        }

                        @Override
                        public void cancelled() {
                            System.out.println("[warmup] cancelled: " + warmup);
                        }

                    });
            warmupFuture.get();
        } catch (final Exception ex) {
            System.out.println("Warm-up failed: " + ex.getMessage());
            client.close(CloseMode.IMMEDIATE);
            return;
        }

        System.out.println("Pool stats after warm-up: " + connectionManager.getTotalStats());

        final CountDownLatch latch = new CountDownLatch(2);

        // --- Fast stream: should succeed well within stream timeout
        final SimpleHttpRequest fast = SimpleRequestBuilder.get()
                .setHttpHost(target)
                .setPath("/httpbin/ip")
                .build();

        client.execute(
                SimpleRequestProducer.create(fast),
                SimpleResponseConsumer.create(),
                new FutureCallback<SimpleHttpResponse>() {

                    @Override
                    public void completed(final SimpleHttpResponse response) {
                        latch.countDown();
                        System.out.println(fast + " -> " + new StatusLine(response));
                        System.out.println("[fast] body:");
                        System.out.println(response.getBody());
                    }

                    @Override
                    public void failed(final Exception ex) {
                        latch.countDown();
                        System.out.println("[fast] unexpected failure: " + fast + " -> " + ex);
                    }

                    @Override
                    public void cancelled() {
                        latch.countDown();
                        System.out.println("[fast] cancelled: " + fast);
                    }
                });

        // --- Slow stream: /delay/5 will sleep 5s server-side
        final SimpleHttpRequest slow = SimpleRequestBuilder.get()
                .setHttpHost(target)
                .setPath("/httpbin/delay/5")
                .build();

        client.execute(
                SimpleRequestProducer.create(slow),
                SimpleResponseConsumer.create(),
                new FutureCallback<SimpleHttpResponse>() {

                    @Override
                    public void completed(final SimpleHttpResponse response) {
                        latch.countDown();
                        System.out.println("[slow] UNEXPECTED success: " + slow + " -> " + new StatusLine(response));
                        System.out.println(response.getBody());
                    }

                    @Override
                    public void failed(final Exception ex) {
                        latch.countDown();
                        System.out.println("[slow] expected failure: " + slow + " -> " + ex);

                        final Throwable cause = ex.getCause();
                        if (ex instanceof TimeoutException || cause instanceof TimeoutException) {
                            System.out.println("[slow] failure is a TimeoutException (H2 stream timeout)");
                        }
                    }

                    @Override
                    public void cancelled() {
                        latch.countDown();
                        System.out.println("[slow] cancelled: " + slow);
                    }
                });

        latch.await();

        System.out.println("Pool stats at end: " + connectionManager.getTotalStats());
        System.out.println("Shutting down");
        client.close(CloseMode.GRACEFUL);
    }

}
