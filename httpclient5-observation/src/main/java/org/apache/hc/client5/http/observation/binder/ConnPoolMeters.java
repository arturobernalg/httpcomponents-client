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
package org.apache.hc.client5.http.observation.binder;

import java.io.Closeable;
import java.lang.reflect.Method;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.pool.ConnPoolControl;
import org.apache.hc.core5.util.Args;

public final class ConnPoolMeters implements MeterBinder {

    private final ConnPoolControl<?> pool;

    private ConnPoolMeters(final ConnPoolControl<?> pool) {
        this.pool = pool;
    }

    @Override
    public void bindTo(final MeterRegistry registry) {
        Args.notNull(registry, "registry");
        Gauge.builder("http.client.pool.leased", pool, p -> p.getTotalStats().getLeased()).register(registry);
        Gauge.builder("http.client.pool.available", pool, p -> p.getTotalStats().getAvailable()).register(registry);
        Gauge.builder("http.client.pool.pending", pool, p -> p.getTotalStats().getPending()).register(registry);
    }

    /* helper: installs a Closeable that registers gauges AFTER build() */
    public static void bindTo(final HttpClientBuilder builder,
                              final MeterRegistry registry,
                              final org.apache.hc.client5.http.observation.ObservingOptions opts) {

        try {
            // obtain the protected addCloseable(Closeable) method reflectively
            final Method addCloseable =
                    HttpClientBuilder.class.getDeclaredMethod("addCloseable", Closeable.class);
            addCloseable.setAccessible(true);

            addCloseable.invoke(builder, (Closeable) () -> {
                if (builder.getConnManager() instanceof ConnPoolControl) {
                    new ConnPoolMeters((ConnPoolControl<?>) builder.getConnManager())
                            .bindTo(registry);
                }
            });
        } catch (final Exception ex) {
            // should never happen; if it does we simply skip pool metrics
            registry.counter("http.client.pool.error", "reason", "reflectiveAccess").increment();
        }
    }
}
