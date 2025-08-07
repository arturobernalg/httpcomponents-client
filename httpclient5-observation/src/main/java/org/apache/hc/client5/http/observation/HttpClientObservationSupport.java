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
package org.apache.hc.client5.http.observation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.cache.CachingHttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.cache.CachingHttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.observation.binder.ConnPoolMeters;
import org.apache.hc.client5.http.observation.binder.ConnPoolMetersAsync;
import org.apache.hc.client5.http.observation.interceptors.AsyncIoByteCounterExec;
import org.apache.hc.client5.http.observation.interceptors.AsyncTimerExec;
import org.apache.hc.client5.http.observation.interceptors.IoByteCounterExec;
import org.apache.hc.client5.http.observation.interceptors.TimerExec;
import org.apache.hc.core5.util.Args;

/**
 * Static helper that wires Micrometer metrics / observations into all
 * {@code HttpClientBuilder} variants (classic, caching, async).
 * <p>
 * <strong>Usage – classic example</strong>
 * <pre>{@code
 * ObservationRegistry observations = ObservationRegistry.create();
 * HttpClientBuilder builder = HttpClients.custom();
 * HttpClientObservationSupport.enable(builder, observations);
 * CloseableHttpClient client = builder.build();
 * }</pre>
 *
 * @since 5.6
 */
public final class HttpClientObservationSupport {

    private static final String TIMER_ID = "metric-timer";
    private static final String IO_ID = "metric-io";

    public static void enable(final HttpClientBuilder builder, final ObservationRegistry obsReg) {

        enable(builder, obsReg, Metrics.globalRegistry, ObservingOptions.DEFAULT);
    }

    public static void enable(final HttpClientBuilder builder, final ObservationRegistry obsReg, final ObservingOptions opts) {
        enable(builder, obsReg, Metrics.globalRegistry, opts);
    }

    public static void enable(final HttpClientBuilder builder, final ObservationRegistry obsReg, final MeterRegistry meterReg,
                              final ObservingOptions opts) {

        Args.notNull(builder, "builder");
        Args.notNull(meterReg, "meterRegistry");

        final ObservingOptions o = opts != null ? opts : ObservingOptions.DEFAULT;

        if (o.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            builder.addExecInterceptorFirst(TIMER_ID,
                    new TimerExec(meterReg, o));
        }
        if (o.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            builder.addExecInterceptorFirst(IO_ID,
                    new IoByteCounterExec(meterReg, o));
        }
        if (o.metricSets.contains(ObservingOptions.MetricSet.CONN_POOL)) {
            ConnPoolMeters.bindTo(builder, meterReg);
        }
    }


    public static void enable(final CachingHttpClientBuilder builder, final ObservationRegistry obsReg) {
        enable(builder, obsReg, Metrics.globalRegistry, ObservingOptions.DEFAULT);
    }

    public static void enable(final CachingHttpClientBuilder builder, final ObservationRegistry obsReg, final ObservingOptions opts) {
        enable(builder, obsReg, Metrics.globalRegistry, opts);
    }

    public static void enable(final CachingHttpClientBuilder builder, final ObservationRegistry obsReg, final MeterRegistry meterReg,
                              final ObservingOptions opts) {

        Args.notNull(builder, "builder");
        Args.notNull(meterReg, "meterRegistry");

        final ObservingOptions o = opts != null ? opts : ObservingOptions.DEFAULT;

        if (o.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            builder.addExecInterceptorAfter(ChainElement.CACHING.name(), TIMER_ID, new TimerExec(meterReg, o));
        }
        if (o.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            builder.addExecInterceptorAfter(ChainElement.CACHING.name(), IO_ID, new IoByteCounterExec(meterReg, o));
        }
        if (o.metricSets.contains(ObservingOptions.MetricSet.CONN_POOL)) {
            ConnPoolMeters.bindTo(builder, meterReg);
        }
    }


    public static void enable(final HttpAsyncClientBuilder builder, final ObservationRegistry obsReg) {
        enable(builder, obsReg, Metrics.globalRegistry, ObservingOptions.DEFAULT);
    }

    public static void enable(final HttpAsyncClientBuilder builder, final ObservationRegistry obsReg, final ObservingOptions opts) {
        enable(builder, obsReg, Metrics.globalRegistry, opts);
    }

    public static void enable(final HttpAsyncClientBuilder builder, final ObservationRegistry obsReg, final MeterRegistry meterReg,
                              final ObservingOptions opts) {

        Args.notNull(builder, "builder");
        Args.notNull(meterReg, "meterRegistry");

        final ObservingOptions o = opts != null ? opts : ObservingOptions.DEFAULT;

        if (o.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            builder.addExecInterceptorFirst(TIMER_ID, new AsyncTimerExec(meterReg, o));
        }
        if (o.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            builder.addExecInterceptorFirst(IO_ID, new AsyncIoByteCounterExec(meterReg, o));
        }
        if (o.metricSets.contains(ObservingOptions.MetricSet.CONN_POOL)) {
            ConnPoolMetersAsync.bindTo(builder, meterReg);
        }
    }

    public static void enable(final CachingHttpAsyncClientBuilder builder,
                              final ObservationRegistry obsReg) {
        enable(builder, obsReg, Metrics.globalRegistry, ObservingOptions.DEFAULT);
    }

    public static void enable(final CachingHttpAsyncClientBuilder builder, final ObservationRegistry obsReg,
                              final ObservingOptions opts) {
        enable(builder, obsReg, Metrics.globalRegistry, opts);
    }

    public static void enable(final CachingHttpAsyncClientBuilder builder, final ObservationRegistry obsReg, final MeterRegistry meterReg,
                              final ObservingOptions opts) {

        Args.notNull(builder, "builder");
        Args.notNull(meterReg, "meterRegistry");

        final ObservingOptions o = opts != null ? opts : ObservingOptions.DEFAULT;

        if (o.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            builder.addExecInterceptorAfter(ChainElement.CACHING.name(), TIMER_ID, new AsyncTimerExec(meterReg, o));
        }
        if (o.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            builder.addExecInterceptorAfter(ChainElement.CACHING.name(), IO_ID, new AsyncIoByteCounterExec(meterReg, o));
        }
    }

    /**
     * No instantiation.
     */
    private HttpClientObservationSupport() {
    }
}
