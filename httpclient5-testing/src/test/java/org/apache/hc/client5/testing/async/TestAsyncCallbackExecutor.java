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

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.testing.nio.H2TestServer;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestAsyncCallbackExecutor {

    private static final Timeout TIMEOUT = Timeout.ofSeconds(30);
    private static final String CALLBACK_THREAD_PREFIX = "test-cb-exec";

    private H2TestServer server;
    private InetSocketAddress serverAddress;
    private CloseableHttpAsyncClient client;
    private ExecutorService callbackExecutor;

    @BeforeEach
    void setUp() throws Exception {
        server = new H2TestServer(
                IOReactorConfig.custom()
                        .setSoTimeout(TIMEOUT)
                        .build(),
                null, null, null);

        server.register("*", () -> new AbstractSimpleServerExchangeHandler() {
            @Override
            protected SimpleHttpResponse handle(
                    final SimpleHttpRequest request,
                    final org.apache.hc.core5.http.protocol.HttpCoreContext context) {
                final SimpleHttpResponse response = new SimpleHttpResponse(HttpStatus.SC_OK);
                response.setBody("OK", ContentType.TEXT_PLAIN);
                return response;
            }
        });

        server.configure(Http1Config.DEFAULT);
        serverAddress = server.start();

        callbackExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, CALLBACK_THREAD_PREFIX);
            t.setDaemon(true);
            return t;
        });

        client = HttpAsyncClients.custom()
                .setIOReactorConfig(IOReactorConfig.custom()
                        .setSoTimeout(TIMEOUT)
                        .build())
                .setCallbackExecutor(callbackExecutor)
                .build();

        client.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close(CloseMode.IMMEDIATE);
        }
        if (server != null) {
            server.shutdown(TimeValue.ofSeconds(5));
        }
        if (callbackExecutor != null) {
            callbackExecutor.shutdownNow();
        }
    }

    private HttpHost targetHost() {
        return new HttpHost("http", "localhost", serverAddress.getPort());
    }

    @Test
    void completedCallbackDispatchedOnExecutor() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<Integer> statusCode = new AtomicReference<>();

        final SimpleHttpRequest request = SimpleRequestBuilder.get()
                .setHttpHost(targetHost())
                .setPath("/")
                .build();

        client.execute(
                SimpleRequestProducer.create(request),
                SimpleResponseConsumer.create(),
                new FutureCallback<SimpleHttpResponse>() {

                    @Override
                    public void completed(final SimpleHttpResponse response) {
                        threadName.set(Thread.currentThread().getName());
                        statusCode.set(response.getCode());
                        latch.countDown();
                    }

                    @Override
                    public void failed(final Exception ex) {
                        latch.countDown();
                    }

                    @Override
                    public void cancelled() {
                        latch.countDown();
                    }

                });

        Assertions.assertTrue(latch.await(TIMEOUT.getDuration(), TIMEOUT.getTimeUnit()));
        Assertions.assertEquals(HttpStatus.SC_OK, statusCode.get());
        Assertions.assertEquals(CALLBACK_THREAD_PREFIX, threadName.get());
    }

    @Test
    void failedCallbackDispatchedOnExecutor() throws Exception {
        // Shut down the server so the request fails
        server.shutdown(TimeValue.ofSeconds(1));

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<Exception> exRef = new AtomicReference<>();

        // Target the now-closed server port
        final SimpleHttpRequest request = SimpleRequestBuilder.get()
                .setHttpHost(targetHost())
                .setPath("/")
                .build();

        client.execute(
                SimpleRequestProducer.create(request),
                SimpleResponseConsumer.create(),
                new FutureCallback<SimpleHttpResponse>() {

                    @Override
                    public void completed(final SimpleHttpResponse response) {
                        latch.countDown();
                    }

                    @Override
                    public void failed(final Exception ex) {
                        threadName.set(Thread.currentThread().getName());
                        exRef.set(ex);
                        latch.countDown();
                    }

                    @Override
                    public void cancelled() {
                        latch.countDown();
                    }

                });

        Assertions.assertTrue(latch.await(TIMEOUT.getDuration(), TIMEOUT.getTimeUnit()));
        Assertions.assertNotNull(exRef.get());
        Assertions.assertEquals(CALLBACK_THREAD_PREFIX, threadName.get());
    }

    @Test
    void cancelledCallbackDispatchedOnExecutor() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();

        final SimpleHttpRequest request = SimpleRequestBuilder.get()
                .setHttpHost(targetHost())
                .setPath("/")
                .build();

        final Future<SimpleHttpResponse> future = client.execute(
                SimpleRequestProducer.create(request),
                SimpleResponseConsumer.create(),
                new FutureCallback<SimpleHttpResponse>() {

                    @Override
                    public void completed(final SimpleHttpResponse response) {
                        latch.countDown();
                    }

                    @Override
                    public void failed(final Exception ex) {
                        latch.countDown();
                    }

                    @Override
                    public void cancelled() {
                        threadName.set(Thread.currentThread().getName());
                        latch.countDown();
                    }

                });

        future.cancel(true);

        Assertions.assertTrue(latch.await(TIMEOUT.getDuration(), TIMEOUT.getTimeUnit()));
        // Cancellation may race with completion; only assert thread name if cancelled won
        if (threadName.get() != null) {
            Assertions.assertEquals(CALLBACK_THREAD_PREFIX, threadName.get());
        }
    }

    @Test
    void h2BuilderCallbackDispatchedOnExecutor() throws Exception {
        // Stand up a separate H2 server
        final H2TestServer h2Server = new H2TestServer(
                IOReactorConfig.custom()
                        .setSoTimeout(TIMEOUT)
                        .build(),
                null, null, null);

        h2Server.register("*", () -> new AbstractSimpleServerExchangeHandler() {
            @Override
            protected SimpleHttpResponse handle(
                    final SimpleHttpRequest request,
                    final org.apache.hc.core5.http.protocol.HttpCoreContext context) {
                final SimpleHttpResponse response = new SimpleHttpResponse(HttpStatus.SC_OK);
                response.setBody("H2 OK", ContentType.TEXT_PLAIN);
                return response;
            }
        });

        h2Server.configure(H2Config.DEFAULT);
        final InetSocketAddress h2Address = h2Server.start();

        final ExecutorService h2CallbackExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, CALLBACK_THREAD_PREFIX + "-h2");
            t.setDaemon(true);
            return t;
        });

        try (CloseableHttpAsyncClient h2Client = HttpAsyncClients.customHttp2()
                .setIOReactorConfig(IOReactorConfig.custom()
                        .setSoTimeout(TIMEOUT)
                        .build())
                .setCallbackExecutor(h2CallbackExecutor)
                .build()) {

            h2Client.start();

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> threadName = new AtomicReference<>();
            final AtomicReference<Integer> statusCode = new AtomicReference<>();

            final HttpHost h2Target = new HttpHost("http", "localhost", h2Address.getPort());
            final SimpleHttpRequest request = SimpleRequestBuilder.get()
                    .setHttpHost(h2Target)
                    .setPath("/")
                    .build();

            h2Client.execute(
                    SimpleRequestProducer.create(request),
                    SimpleResponseConsumer.create(),
                    new FutureCallback<SimpleHttpResponse>() {

                        @Override
                        public void completed(final SimpleHttpResponse response) {
                            threadName.set(Thread.currentThread().getName());
                            statusCode.set(response.getCode());
                            latch.countDown();
                        }

                        @Override
                        public void failed(final Exception ex) {
                            latch.countDown();
                        }

                        @Override
                        public void cancelled() {
                            latch.countDown();
                        }

                    });

            Assertions.assertTrue(latch.await(TIMEOUT.getDuration(), TIMEOUT.getTimeUnit()));
            Assertions.assertEquals(HttpStatus.SC_OK, statusCode.get());
            Assertions.assertEquals(CALLBACK_THREAD_PREFIX + "-h2", threadName.get());
        } finally {
            h2CallbackExecutor.shutdownNow();
            h2Server.shutdown(TimeValue.ofSeconds(5));
        }
    }

}
