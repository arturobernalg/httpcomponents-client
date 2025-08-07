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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.util.Args;

/**
 * Counts request / response payload bytes for <b>classic</b> clients.
 * <p>
 * The counter names are
 * {@code http.client.request.bytes} and {@code http.client.response.bytes}.
 *
 * @since 5.6
 */
public final class IoByteCounterExec implements ExecChainHandler {

    private final MeterRegistry meterRegistry;
    private final Counter.Builder reqBuilder;
    private final Counter.Builder respBuilder;
    private final ObservingOptions opts;

    public IoByteCounterExec(final MeterRegistry meterRegistry,
                             final ObservingOptions opts) {
        this.meterRegistry = Args.notNull(meterRegistry, "meterRegistry");
        this.opts = Args.notNull(opts, "observingOptions");

        this.reqBuilder = Counter.builder("http.client.request.bytes")
                .baseUnit("bytes")
                .description("HTTP request payload size")
                .tag("component", "httpclient");

        this.respBuilder = Counter.builder("http.client.response.bytes")
                .baseUnit("bytes")
                .description("HTTP response payload size")
                .tag("component", "httpclient");
    }

    @Override
    public ClassicHttpResponse execute(final ClassicHttpRequest request,
                                       final ExecChain.Scope scope,
                                       final ExecChain chain) throws IOException, HttpException {

        if (!opts.spanSampling.test(request.getRequestUri())) {
            return chain.proceed(request, scope);
        }

        final long reqBytes = contentLength(request.getEntity());

        ClassicHttpResponse response = null;
        try {
            response = chain.proceed(request, scope);
            return response;
        } finally {
            final long respBytes = contentLength(response != null ? response.getEntity() : null);

            final List<Tag> tags = buildTags(request.getMethod(),
                    response != null ? response.getCode() : 599,
                    scope);

            if (reqBytes >= 0) {
                reqBuilder.tags(tags).register(meterRegistry).increment(reqBytes);
            }
            if (respBytes >= 0) {
                respBuilder.tags(tags).register(meterRegistry).increment(respBytes);
            }
        }
    }

    private static long contentLength(final HttpEntity entity) {
        if (entity == null) {
            return -1;
        }
        final long len = entity.getContentLength();
        return len >= 0 ? len : -1;
    }

    private List<Tag> buildTags(final String method,
                                final int status,
                                final ExecChain.Scope scope) {
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
