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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.AsyncDataConsumer;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;

public final class AsyncTimerExec implements AsyncExecChainHandler {

    private final Timer.Builder timerBuilder;
    private final Counter.Builder counterBuilder;
    private final ObservingOptions opts;

    public AsyncTimerExec(final ObservationRegistry reg, final ObservingOptions opts) {
        this.opts = opts;
        this.timerBuilder = Timer.builder("http.client.request").tags("component", "httpclient");
        this.counterBuilder = Counter.builder("http.client.response").tags("component", "httpclient");
    }

    @Override
    public void execute(final HttpRequest request,
                        final AsyncEntityProducer entityProducer,
                        final AsyncExecChain.Scope scope,
                        final AsyncExecChain chain,
                        final AsyncExecCallback delegate) throws HttpException, IOException {

        if (!opts.spanSampling.test(request.getRequestUri())) {
            chain.proceed(request, entityProducer, scope, delegate);
            return;
        }

        final long started = System.nanoTime();

        chain.proceed(request, entityProducer, scope, new AsyncExecCallback() {

            @Override
            public AsyncDataConsumer handleResponse(final HttpResponse response,
                                                    final EntityDetails details)
                    throws HttpException, IOException {
                return delegate.handleResponse(response, details);
            }

            @Override
            public void handleInformationResponse(final HttpResponse info)
                    throws HttpException, IOException {
                delegate.handleInformationResponse(info);
            }

            @Override
            public void completed() {
                record(request, scope, 200, started);   // HTTP/2 trailer or unknown: assume OK
                delegate.completed();
            }

            @Override
            public void failed(final Exception cause) {
                record(request, scope, 599, started);
                delegate.failed(cause);
            }
        });
    }

    private void record(final HttpRequest req,
                        final AsyncExecChain.Scope scope,
                        final int status,
                        final long started) {

        final long dur = System.nanoTime() - started;

        final List<Tag> tags = new ArrayList<>(4);
        tags.add(Tag.of("method", req.getMethod()));
        tags.add(Tag.of("status", Integer.toString(status)));
        if (opts.tagLevel == ObservingOptions.TagLevel.EXTENDED) {
            tags.add(Tag.of("protocol", scope.route.getTargetHost().getSchemeName()));
            tags.add(Tag.of("target", scope.route.getTargetHost().getHostName()));
        }

        timerBuilder.tags(tags)
                .register(io.micrometer.core.instrument.Metrics.globalRegistry)
                .record(dur, TimeUnit.NANOSECONDS);

        counterBuilder.tags(tags)
                .register(io.micrometer.core.instrument.Metrics.globalRegistry)
                .increment();
    }
}
