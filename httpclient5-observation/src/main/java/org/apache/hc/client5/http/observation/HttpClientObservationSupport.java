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

import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.cache.CachingHttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.cache.CachingHttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.observation.binder.ConnPoolMeters;
import org.apache.hc.client5.http.observation.interceptors.AsyncIoByteCounterExec;
import org.apache.hc.client5.http.observation.interceptors.AsyncTimerExec;
import org.apache.hc.client5.http.observation.interceptors.IoByteCounterExec;
import org.apache.hc.client5.http.observation.interceptors.TimerExec;

public final class HttpClientObservationSupport {

    private static final String TIMER = "metric-timer";
    private static final String IO = "metric-io";

    /* ------------ classic ------------ */

    public static void enable(final HttpClientBuilder b,
                              final ObservationRegistry reg) {
        enable(b, reg, ObservingOptions.DEFAULT);
    }

    public static void enable(final HttpClientBuilder b,
                              final ObservationRegistry reg,
                              final ObservingOptions opt) {
        if (reg == null) {
            return;
        }

        if (opt.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            b.addExecInterceptorFirst(TIMER, new TimerExec(reg, opt));
        }
        if (opt.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            b.addExecInterceptorFirst(IO, new IoByteCounterExec(reg, opt));
        }
        if (opt.metricSets.contains(ObservingOptions.MetricSet.CONN_POOL)) {
            ConnPoolMeters.bindTo(b, Metrics.globalRegistry, opt);
        }
    }

    /* ------ classic + cache ------ */

    public static void enable(final CachingHttpClientBuilder b,
                              final ObservationRegistry reg,
                              final ObservingOptions opt) {
        if (reg == null) {
            return;
        }

        if (opt.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            b.addExecInterceptorAfter(ChainElement.CACHING.name(), TIMER, new TimerExec(reg, opt));
        }
        if (opt.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            b.addExecInterceptorAfter(ChainElement.CACHING.name(), IO, new IoByteCounterExec(reg, opt));
        }
        if (opt.metricSets.contains(ObservingOptions.MetricSet.CONN_POOL)) {
            ConnPoolMeters.bindTo(b, Metrics.globalRegistry, opt);
        }
    }

    /* ------------ async ------------ */

    public static void enable(final HttpAsyncClientBuilder b,
                              final ObservationRegistry reg,
                              final ObservingOptions opt) {
        if (reg == null) {
            return;
        }

        if (opt.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            b.addExecInterceptorFirst(TIMER, new AsyncTimerExec(reg, opt));
        }
        if (opt.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            b.addExecInterceptorFirst(IO, new AsyncIoByteCounterExec(reg, opt));
        }
    }

    // in HttpClientObservationSupport.java
    public static void enable(final HttpAsyncClientBuilder b,
                              final ObservationRegistry reg) {
        enable(b, reg, ObservingOptions.DEFAULT);
    }

    public static void enable(final CachingHttpAsyncClientBuilder b,
                              final ObservationRegistry reg) {
        enable(b, reg, ObservingOptions.DEFAULT);
    }


    public static void enable(final CachingHttpAsyncClientBuilder b,
                              final ObservationRegistry reg,
                              final ObservingOptions opt) {
        if (reg == null) {
            return;
        }

        if (opt.metricSets.contains(ObservingOptions.MetricSet.BASIC)) {
            b.addExecInterceptorAfter(ChainElement.CACHING.name(), TIMER, new AsyncTimerExec(reg, opt));
        }
        if (opt.metricSets.contains(ObservingOptions.MetricSet.IO)) {
            b.addExecInterceptorAfter(ChainElement.CACHING.name(), IO, new AsyncIoByteCounterExec(reg, opt));
        }
    }

    private HttpClientObservationSupport() {
    }   // no-instantiation
}
