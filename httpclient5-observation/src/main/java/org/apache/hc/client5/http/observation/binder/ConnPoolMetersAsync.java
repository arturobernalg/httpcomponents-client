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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.hc.core5.pool.ConnPoolControl;
import org.apache.hc.core5.util.Args;

/**
 * Registers three gauges (&quot;leased&quot;, &quot;available&quot;, &quot;pending&quot;)
 * for the I/O-reactor connection pool used by an {@link HttpAsyncClientBuilder}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * HttpAsyncClientBuilder b = HttpAsyncClients.custom();
 * ConnPoolMetersAsync.bindTo(b, Metrics.globalRegistry());
 * CloseableHttpAsyncClient client = b.build();
 * }</pre>
 * <p>
 * The binder installs itself via {@code addCloseable} so it can be invoked
 * <em>before</em> {@code build()}.  If you prefer a zero-reflection variant
 * (and can call it only <em>after</em> {@code build()}), see the note in the
 * class‐level Javadoc of {@code ConnPoolMeters}.
 *
 * @since 5.6
 */
public final class ConnPoolMetersAsync implements MeterBinder {

    private final ConnPoolControl<?> pool;

    private ConnPoolMetersAsync(final ConnPoolControl<?> pool) {
        this.pool = pool;
    }

    @Override
    public void bindTo(final MeterRegistry registry) {
        Args.notNull(registry, "registry");
        Gauge.builder("http.client.pool.leased", pool, p -> p.getTotalStats().getLeased()).register(registry);
        Gauge.builder("http.client.pool.available", pool, p -> p.getTotalStats().getAvailable()).register(registry);
        Gauge.builder("http.client.pool.pending", pool, p -> p.getTotalStats().getPending()).register(registry);
    }

    /**
     * Installs a {@link Closeable} callback on the builder that registers the
     * gauges <em>after</em> the async client has been built (i.e. when the pool
     * is finally available).
     */
    public static void bindTo(final HttpAsyncClientBuilder builder, final MeterRegistry registry) {

        Args.notNull(builder, "builder");
        Args.notNull(registry, "registry");

        final AsyncClientConnectionManager cm = builder.getConnManager();
        if (cm instanceof ConnPoolControl) {
            new ConnPoolMetersAsync((ConnPoolControl<?>) cm).bindTo(registry);
        }
    }

    /**
     * No instantiation outside static helpers.
     */
    private ConnPoolMetersAsync() {
        this.pool = null; // never called
    }
}
