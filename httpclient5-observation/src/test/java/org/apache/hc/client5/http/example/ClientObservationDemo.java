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
package org.apache.hc.client5.http.example;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.cache.CachingHttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.observation.HttpClientObservationSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.io.CloseMode;

public final class ClientObservationDemo {

    private static final URI URL1 = URI.create("https://httpbin.org/get");
    private static final URI URL2 = URI.create("https://httpbin.org/anything");

    public static void main(final String[] args) throws Exception {

        /* ---------- Micrometer bootstrap ---------- */
        final ObservationRegistry registry = ObservationRegistry.create();
        final PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(prom));

        /* ---------- CLASSIC client ---------- */
        final HttpClientBuilder cb = HttpClients.custom();
        HttpClientObservationSupport.enable(cb, registry);   // classic overload
        try (final CloseableHttpClient classic = cb.build()) {

            final ClassicHttpResponse res = classic.executeOpen(
                    null,
                    ClassicRequestBuilder.get(URL1).build(),
                    null);
            System.out.println("[classic]        " + new StatusLine(res));
            res.close();
        }

        /* ---------- CLASSIC + CACHE client ---------- */
        final CachingHttpClientBuilder ccb = CachingHttpClientBuilder.create();
        HttpClientObservationSupport.enable(ccb, registry);
        try (final CloseableHttpClient cached = ccb.build()) {

            final ClassicHttpResponse res = cached.executeOpen(
                    null,
                    ClassicRequestBuilder.get(URL2).build(),
                    null);
            System.out.println("[classic-cache]  " + new StatusLine(res));
            res.close();
        }

        /* ---------- ASYNC client ---------- */
        final HttpAsyncClientBuilder ab = HttpAsyncClients.custom();
        HttpClientObservationSupport.enable(ab, registry);   // async overload

        try (final CloseableHttpAsyncClient async = ab.build()) {
            async.start();

            final SimpleHttpRequest req = SimpleRequestBuilder.get(URL1).build();
            final Future<SimpleHttpResponse> fut = async.execute(req, null, null);
            System.out.println("[async]          "
                    + fut.get(10, TimeUnit.SECONDS).getCode());

            async.close(CloseMode.GRACEFUL);
        }

        /* ---------- Prometheus scrape ---------- */
        System.out.println("\n--- Prometheus metrics ---");
        System.out.println(prom.scrape());
    }
}
