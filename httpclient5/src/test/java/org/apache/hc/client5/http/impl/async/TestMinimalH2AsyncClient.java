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
package org.apache.hc.client5.http.impl.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.config.Configurable;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestMinimalH2AsyncClient {

    @Test
    void testExecuteRejectsProxyConfiguration() {
        final MinimalH2AsyncClient client = HttpAsyncClients.createHttp2Minimal();
        client.start();
        try {
            final HttpClientContext context = HttpClientContext.create();
            context.setRequestConfig(RequestConfig.custom()
                    .setProxy(new HttpHost("http", "proxy.local", 3128))
                    .build());
            final CapturingExchangeHandler exchangeHandler = new CapturingExchangeHandler();

            client.execute(exchangeHandler, null, context);

            Assertions.assertNotNull(exchangeHandler.failure.get());
            Assertions.assertInstanceOf(HttpException.class, exchangeHandler.failure.get());
            Assertions.assertTrue(
                    exchangeHandler.failure.get().getMessage().contains("Proxy execution is not supported by MinimalH2AsyncClient"));
        } finally {
            client.close();
        }
    }

    @Test
    void testExecuteRejectsProxyConfigurationFromConfigurableRequest() {
        final MinimalH2AsyncClient client = HttpAsyncClients.createHttp2Minimal();
        client.start();
        try {
            final HttpClientContext context = HttpClientContext.create();
            final CapturingExchangeHandler exchangeHandler = new CapturingExchangeHandler(
                    new ConfigurableBasicHttpRequest(
                            Method.GET.name(),
                            new HttpHost("https", "example.org"),
                            "/",
                            RequestConfig.custom().setProxy(new HttpHost("http", "proxy.local", 3128)).build()));

            client.execute(exchangeHandler, null, context);

            Assertions.assertNotNull(exchangeHandler.failure.get());
            Assertions.assertInstanceOf(HttpException.class, exchangeHandler.failure.get());
            Assertions.assertTrue(
                    exchangeHandler.failure.get().getMessage().contains("Proxy execution is not supported by MinimalH2AsyncClient"));
        } finally {
            client.close();
        }
    }

    @Test
    void testExecuteFailsWhenClientNotStarted() {
        final MinimalH2AsyncClient client = HttpAsyncClients.createHttp2Minimal();
        final CapturingExchangeHandler exchangeHandler = new CapturingExchangeHandler();

        client.execute(exchangeHandler, null, HttpClientContext.create());

        Assertions.assertNotNull(exchangeHandler.failure.get());
        Assertions.assertInstanceOf(CancellationException.class, exchangeHandler.failure.get());
    }

    @Test
    void testExecutePropagatesHttpExceptionFromProduceRequest() {
        final MinimalH2AsyncClient client = HttpAsyncClients.createHttp2Minimal();
        client.start();
        try {
            final CapturingExchangeHandler exchangeHandler = new ThrowingExchangeHandler(new HttpException("boom"));
            client.execute(exchangeHandler, null, HttpClientContext.create());
            Assertions.assertNotNull(exchangeHandler.failure.get());
            Assertions.assertInstanceOf(HttpException.class, exchangeHandler.failure.get());
        } finally {
            client.close();
        }
    }

    @Test
    void testExecutePropagatesIOExceptionFromProduceRequest() {
        final MinimalH2AsyncClient client = HttpAsyncClients.createHttp2Minimal();
        client.start();
        try {
            final CapturingExchangeHandler exchangeHandler = new ThrowingExchangeHandler(new IOException("io-fail"));
            client.execute(exchangeHandler, null, HttpClientContext.create());
            Assertions.assertNotNull(exchangeHandler.failure.get());
            Assertions.assertInstanceOf(IOException.class, exchangeHandler.failure.get());
        } finally {
            client.close();
        }
    }

    @Test
    void testExecutePropagatesIllegalStateExceptionFromProduceRequest() {
        final MinimalH2AsyncClient client = HttpAsyncClients.createHttp2Minimal();
        client.start();
        try {
            final CapturingExchangeHandler exchangeHandler = new ThrowingExchangeHandler(new IllegalStateException("illegal"));
            client.execute(exchangeHandler, null, HttpClientContext.create());
            Assertions.assertNotNull(exchangeHandler.failure.get());
            Assertions.assertInstanceOf(IllegalStateException.class, exchangeHandler.failure.get());
        } finally {
            client.close();
        }
    }

    static class CapturingExchangeHandler implements AsyncClientExchangeHandler {

        final AtomicReference<Exception> failure = new AtomicReference<>();
        final BasicHttpRequest request;

        CapturingExchangeHandler() {
            this(new BasicHttpRequest(Method.GET, new HttpHost("https", "example.org"), "/"));
        }

        CapturingExchangeHandler(final BasicHttpRequest request) {
            this.request = request;
        }

        @Override
        public void releaseResources() {
        }

        @Override
        public void failed(final Exception cause) {
            failure.set(cause);
        }

        @Override
        public void produceRequest(
                final RequestChannel channel,
                final HttpContext context) throws HttpException, IOException {
            channel.sendRequest(request, null, context);
        }

        @Override
        public void consumeInformation(final HttpResponse response, final HttpContext context) {
        }

        @Override
        public void consumeResponse(
                final HttpResponse response,
                final EntityDetails entityDetails,
                final HttpContext context) {
        }

        @Override
        public void updateCapacity(final CapacityChannel capacityChannel) {
        }

        @Override
        public void consume(final ByteBuffer src) {
        }

        @Override
        public void streamEnd(final List<? extends Header> trailers) {
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public void produce(final DataStreamChannel channel) {
        }

        @Override
        public void cancel() {
        }
    }

    static class ThrowingExchangeHandler extends CapturingExchangeHandler {

        private final Exception toThrow;

        ThrowingExchangeHandler(final Exception toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void produceRequest(
                final RequestChannel channel,
                final HttpContext context) throws HttpException, IOException {
            if (toThrow instanceof HttpException) {
                throw (HttpException) toThrow;
            }
            if (toThrow instanceof IOException) {
                throw (IOException) toThrow;
            }
            if (toThrow instanceof RuntimeException) {
                throw (RuntimeException) toThrow;
            }
            throw new IllegalStateException("Unexpected test exception type", toThrow);
        }
    }

    static class ConfigurableBasicHttpRequest extends BasicHttpRequest implements Configurable {

        private final RequestConfig requestConfig;

        ConfigurableBasicHttpRequest(
                final String method,
                final HttpHost host,
                final String path,
                final RequestConfig requestConfig) {
            super(method, host, path);
            this.requestConfig = requestConfig;
        }

        @Override
        public RequestConfig getConfig() {
            return requestConfig;
        }
    }
}
