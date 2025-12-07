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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecRuntime;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class H2StreamTimeoutAsyncExecTest {

    private H2StreamTimeoutAsyncExec handler;

    private AsyncExecChain chain;
    private AsyncEntityProducer entityProducer;
    private AsyncExecRuntime execRuntime;
    private AsyncExecCallback callback;
    private CancellableDependency cancellableDependency;
    private AsyncExecChain.Scheduler scheduler;

    private HttpRequest request;
    private HttpRoute route;
    private HttpClientContext context;
    private AtomicInteger execCount;

    @BeforeEach
    void setUp() {
        handler = new H2StreamTimeoutAsyncExec();

        chain = mock(AsyncExecChain.class);
        entityProducer = mock(AsyncEntityProducer.class);
        execRuntime = mock(AsyncExecRuntime.class);
        callback = mock(AsyncExecCallback.class);
        cancellableDependency = mock(CancellableDependency.class);
        scheduler = mock(AsyncExecChain.Scheduler.class);

        request = new BasicHttpRequest("GET", "/");
        route = new HttpRoute(new HttpHost("https", "example.com", 443));
        context = HttpClientContext.create();
        execCount = new AtomicInteger(1);
    }

    private AsyncExecChain.Scope newScope(final RequestConfig requestConfig) {
        context.setRequestConfig(requestConfig);
        return new AsyncExecChain.Scope(
                "exchange-1",
                route,
                request,
                cancellableDependency,
                context,
                execRuntime,
                scheduler,
                execCount);
    }

    @Test
    void noH2StreamTimeout_delegatesDirectly() throws IOException, HttpException {
        final RequestConfig config = RequestConfig.custom().build();
        final AsyncExecChain.Scope scope = newScope(config);

        handler.execute(request, entityProducer, scope, chain, callback);

        // Interceptor should just pass everything through.
        verify(chain).proceed(
                same(request),
                same(entityProducer),
                same(scope),
                same(callback));

        // No timeout logic armed.
        verifyNoInteractions(scheduler);
        verifyNoInteractions(cancellableDependency);
    }

    @Test
    void configuredStreamTimeout_wrapsCallbackAndDelegates() throws IOException, HttpException {
        final Timeout streamTimeout = Timeout.ofSeconds(1);
        final RequestConfig config = RequestConfig.custom()
                .setH2StreamTimeout(streamTimeout)
                .build();
        final AsyncExecChain.Scope scope = newScope(config);

        final ArgumentCaptor<AsyncExecCallback> callbackCaptor =
                ArgumentCaptor.forClass(AsyncExecCallback.class);

        handler.execute(request, entityProducer, scope, chain, callback);

        // Must delegate to the next handler with a wrapped callback.
        verify(chain).proceed(
                same(request),
                same(entityProducer),
                same(scope),
                callbackCaptor.capture());

        final AsyncExecCallback wrapped = callbackCaptor.getValue();
        assertNotNull(wrapped, "wrapped callback");
        assertNotSame(callback, wrapped, "callback should be wrapped");

        // We do NOT expect any direct interaction with CancellableDependency at this layer.
        verifyNoInteractions(cancellableDependency);

        // Optional: sanity check that the wrapper delegates to the original callback.
        wrapped.completed();
        verify(callback).completed();
    }

}
