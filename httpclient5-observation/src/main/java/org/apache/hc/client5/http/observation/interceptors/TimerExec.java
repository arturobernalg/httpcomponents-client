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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.util.Args;

/**
 * Classic blocking interceptor that records a Micrometer {@link Timer}
 * per request as well as a {@link Counter} for the response codes.
 *
 * @since 5.6
 */
public final class TimerExec implements ExecChainHandler {

    private final MeterRegistry registry;
    private final ObservingOptions cfg;

    public TimerExec(final MeterRegistry reg, final ObservingOptions cfg) {
        this.registry = Args.notNull(reg, "registry");
        this.cfg = Args.notNull(cfg, "config");
    }

    @Override
    public ClassicHttpResponse execute(final ClassicHttpRequest request,
                                       final ExecChain.Scope scope,
                                       final ExecChain chain)
            throws IOException, HttpException {

        if (!cfg.spanSampling.test(request.getRequestUri())) {
            return chain.proceed(request, scope);   // fast-path, nothing recorded
        }

        final long start = System.nanoTime();
        ClassicHttpResponse response = null;
        try {
            response = chain.proceed(request, scope);
            return response;
        } finally {
            final long durNanos = System.nanoTime() - start;
            final int status = response != null ? response.getCode() : 599;

            final List<Tag> tags = new ArrayList<>(4);
            tags.add(Tag.of("method", request.getMethod()));
            tags.add(Tag.of("status", Integer.toString(status)));

            if (cfg.tagLevel == ObservingOptions.TagLevel.EXTENDED) {
                tags.add(Tag.of("protocol", scope.route.getTargetHost().getSchemeName()));
                tags.add(Tag.of("target", scope.route.getTargetHost().getHostName()));
            }

            Timer.builder("http.client.request")
                    .tags(tags)
                    .publishPercentiles(0.9, 0.99)
                    .register(registry)
                    .record(durNanos, TimeUnit.NANOSECONDS);

            Counter.builder("http.client.response")
                    .tags(tags)
                    .register(registry)
                    .increment();
        }
    }
}
