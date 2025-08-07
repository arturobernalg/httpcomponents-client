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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.util.Args;

/**
 * <p>Counts I/O payload (bytes sent / received) for <b>asynchronous</b>
 * {@code CloseableHttpAsyncClient}s.</p>
 *
 * <ul>
 *   <li>{@code http.client.request.bytes}</li>
 *   <li>{@code http.client.response.bytes}</li>
 * </ul>
 * <p>
 * Tag-set and sampling behaviour are controlled through
 * {@link ObservingOptions}.
 *
 * @since 5.6
 */
public final class AsyncIoByteCounterExec implements AsyncExecChainHandler {

    private final MeterRegistry meterRegistry;
    private final Counter.Builder reqBuilder;
    private final Counter.Builder respBuilder;
    private final ObservingOptions opts;

    public AsyncIoByteCounterExec(final MeterRegistry meterRegistry,
                                  final ObservingOptions opts) {
        this.meterRegistry = Args.notNull(meterRegistry, "meterRegistry");
        this.opts = Args.notNull(opts, "observingOptions");

        this.reqBuilder = Counter.builder("http.client.request.bytes")
                .description("HTTP request payload size")
                .baseUnit("bytes")
                .tag("component", "httpclient");

        this.respBuilder = Counter.builder("http.client.response.bytes")
                .description("HTTP response payload size")
                .baseUnit("bytes")
                .tag("component", "httpclient");
    }

    @Override
    public void execute(final HttpRequest request,
                        final AsyncEntityProducer entityProducer,
                        final AsyncExecChain.Scope scope,
                        final AsyncExecChain chain,
                        final AsyncExecCallback callback)
            throws HttpException, IOException {

        /* -------- cheap pre-filter on URI ---------- */
        if (!opts.spanSampling.test(request.getRequestUri())) {
            chain.proceed(request, entityProducer, scope, callback);
            return;
        }

        /* -------- request byte count (may be -1 if unknown) ------- */
        final long reqBytes = entityProducer != null
                ? entityProducer.getContentLength()
                : -1L;

        /* --- capture response + bytes inside wrapped callback ----- */
        final AtomicReference<HttpResponse> respRef = new AtomicReference<HttpResponse>();
        final AtomicLong respLen = new AtomicLong(-1L);

        final AsyncExecCallback wrapped = new AsyncExecCallback() {

            @Override
            public org.apache.hc.core5.http.nio.AsyncDataConsumer handleResponse(
                    final HttpResponse response,
                    final EntityDetails entityDetails) throws HttpException, IOException {

                respRef.set(response);
                if (entityDetails != null) {
                    respLen.set(entityDetails.getContentLength());
                }
                return callback.handleResponse(response, entityDetails);
            }

            @Override
            public void handleInformationResponse(final HttpResponse response)
                    throws HttpException, IOException {
                callback.handleInformationResponse(response);
            }

            @Override
            public void completed() {
                record(reqBytes, respLen.get(), respRef.get(), request, scope);
                callback.completed();
            }

            @Override
            public void failed(final Exception cause) {
                record(reqBytes, respLen.get(), respRef.get(), request, scope);
                callback.failed(cause);
            }
        };

        chain.proceed(request, entityProducer, scope, wrapped);
    }

    /* ------------------------------------------------------------- */

    private void record(final long reqBytes,
                        final long respBytes,
                        final HttpResponse resp,
                        final HttpRequest req,
                        final AsyncExecChain.Scope scope) {

        final int status = resp != null ? resp.getCode() : 599;
        final List<Tag> tags = buildTags(req.getMethod(), status, scope);

        if (reqBytes >= 0) {
            reqBuilder.tags(tags).register(meterRegistry).increment(reqBytes);
        }
        if (respBytes >= 0) {
            respBuilder.tags(tags).register(meterRegistry).increment(respBytes);
        }
    }

    private List<Tag> buildTags(final String method,
                                final int status,
                                final AsyncExecChain.Scope scope) {

        final List<Tag> tags = new ArrayList<Tag>(4);
        tags.add(Tag.of("method", method));
        tags.add(Tag.of("status", Integer.toString(status)));

        if (opts.tagLevel == ObservingOptions.TagLevel.EXTENDED) {
            tags.add(Tag.of("protocol", scope.route.getTargetHost().getSchemeName()));
            tags.add(Tag.of("target", scope.route.getTargetHost().getHostName()));
        }
        return tags;
    }
}
