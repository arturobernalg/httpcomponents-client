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
package org.apache.hc.client5.http.observation.example;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.observation.HttpClientObservationSupport;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.io.CloseMode;

/**
 * Small command-line utility that
 * • creates an async HttpClient,
 * • enables ALL async metric sets,
 * • fires two requests,
 * • dumps the Prometheus scrape.
 * <p>
 * To run:
 * mvn -pl httpclient5-observation exec:java \
 * -Dexec.mainClass=org.apache.hc.client5.http.observation.example.AsyncMetricDemo
 */
public final class AsyncMetricDemo {

    private static final URI URL_A = URI.create("https://httpbin.org/get");
    private static final URI URL_B = URI.create("https://httpbin.org/anything");

    public static void main(final String[] args) throws Exception {

        /* ----------------------------------------------------------
         * 1)   Create Micrometer registries
         * ---------------------------------------------------------- */
        final ObservationRegistry observationRegistry = ObservationRegistry.create();

        final PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // tie Observation API to the Prometheus meter registry
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(prometheus));

        /* ----------------------------------------------------------
         * 2)   Configure what we actually want to collect
         * ---------------------------------------------------------- */
        final ObservingOptions opts = ObservingOptions.builder()
                .metrics(EnumSet.of(
                        ObservingOptions.MetricSet.BASIC,   // timers, counters
                        ObservingOptions.MetricSet.IO       // byte counters
                        /* CONN_POOL is *classic* only      */
                ))
                .tagLevel(ObservingOptions.TagLevel.EXTENDED)
                // only sample URIs that start with /anything
                .spanSampling(uri -> uri.contains("/anything"))
                .build();

        /* ----------------------------------------------------------
         * 3)   Build async client and wire the interceptors
         * ---------------------------------------------------------- */
        final HttpAsyncClientBuilder ab = HttpAsyncClients.custom();
        HttpClientObservationSupport.enable(
                ab,
                observationRegistry,          // may be null if you only want metrics
                prometheus,                   // where Timer / Counter go
                opts);

        try (final CloseableHttpAsyncClient async = ab.build()) {
            async.start();

            /* fire & forget two requests */
            final Future<SimpleHttpResponse> r1 = async.execute(
                    SimpleRequestBuilder.get(URL_A).build(), null, null);
            final Future<SimpleHttpResponse> r2 = async.execute(
                    SimpleRequestBuilder.get(URL_B).build(), null, null);

            System.out.println("[async-A] " + r1.get(10, TimeUnit.SECONDS).getCode());
            System.out.println("[async-B] " + r2.get(10, TimeUnit.SECONDS).getCode());

            /* give the registry a moment (optional) */
            Thread.sleep(Duration.ofSeconds(1).toMillis());

            async.close(CloseMode.GRACEFUL);
        }

        /* ----------------------------------------------------------
         * 4)   Dump scrape –  ready for curl :8080/actuator/prometheus
         * ---------------------------------------------------------- */
        System.out.println("\n--- Prometheus scrape ---");
        System.out.println(prometheus.scrape());
    }

    /**
     * no instantiation
     */
    private AsyncMetricDemo() {
    }
}
