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
package org.apache.hc.client5.http.observation.interceptors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.util.Args;

/**
 * Records request latency and result counter for <b>async</b> clients.
 *
 * @since 5.6
 */
public final class AsyncTimerExec implements AsyncExecChainHandler {

    private final MeterRegistry meterRegistry;
    private final Timer.Builder timerBuilder;
    private final Counter.Builder counterBuilder;
    private final ObservingOptions opts;

    public AsyncTimerExec(final MeterRegistry meterRegistry,
                          final ObservingOptions opts) {
        this.meterRegistry = Args.notNull(meterRegistry, "meterRegistry");
        this.opts = Args.notNull(opts, "observingOptions");

        this.timerBuilder = Timer.builder("http.client.request")
                .tag("component", "httpclient")
                .publishPercentiles(0.9, 0.99);

        this.counterBuilder = Counter.builder("http.client.response")
                .tag("component", "httpclient");
    }

    @Override
    public void execute(final HttpRequest request,
                        final org.apache.hc.core5.http.nio.AsyncEntityProducer entityProducer,
                        final AsyncExecChain.Scope scope,
                        final AsyncExecChain chain,
                        final AsyncExecCallback callback) throws HttpException, IOException {

        if (!opts.spanSampling.test(request.getRequestUri())) {
            chain.proceed(request, entityProducer, scope, callback);
            return;
        }

        final long start = System.nanoTime();
        final AtomicReference<HttpResponse> respRef = new AtomicReference<HttpResponse>();

        final AsyncExecCallback wrapped = new AsyncExecCallback() {

            @Override
            public org.apache.hc.core5.http.nio.AsyncDataConsumer handleResponse(
                    final HttpResponse response, final EntityDetails entityDetails) throws HttpException, IOException {
                respRef.set(response);
                return callback.handleResponse(response, entityDetails);
            }

            @Override
            public void handleInformationResponse(final HttpResponse response) throws HttpException, IOException {
                callback.handleInformationResponse(response);
            }

            @Override
            public void completed() {
                record(start, respRef.get());
                callback.completed();
            }

            @Override
            public void failed(final Exception cause) {
                record(start, respRef.get());
                callback.failed(cause);
            }

            private void record(final long startNanos, final HttpResponse response) {
                final long dur = System.nanoTime() - startNanos;
                final int status = response != null ? response.getCode() : 599;

                final List<Tag> tags = new ArrayList<Tag>(4);
                tags.add(Tag.of("method", request.getMethod()));
                tags.add(Tag.of("status", Integer.toString(status)));

                if (opts.tagLevel == ObservingOptions.TagLevel.EXTENDED) {
                    tags.add(Tag.of("protocol", scope.route.getTargetHost().getSchemeName()));
                    tags.add(Tag.of("target", scope.route.getTargetHost().getHostName()));
                }

                timerBuilder.tags(tags).register(meterRegistry)
                        .record(dur, TimeUnit.NANOSECONDS);

                counterBuilder.tags(tags).register(meterRegistry).increment();
            }
        };

        chain.proceed(request, entityProducer, scope, wrapped);
    }
}
