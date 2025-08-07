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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.micrometer.core.instrument.Tag;

/**
 * Holds *all* tunables for the Micrometer / OpenTelemetry integration.
 *
 * <p>Use {@link Builder} for creation; the static factory guarantees
 * sane defaults so that an empty {@code MetricConfig} never breaks the
 * client.</p>
 *
 * @since 5.6
 */
public final class MetricConfig {

    /**
     * Prefix used by <em>every</em> meter (default {@code "httpclient"}).
     */
    public final String prefix;

    /**
     * Histogram SLO, e.g. an application’s “good is &lt; 250 ms” target.
     */
    public final Duration slo;

    /**
     * How many percentiles Micrometer calculates (0 = none).
     */
    public final int percentiles;

    /**
     * When {@code true} the IO counters are tagged by full request URI.
     */
    public final boolean perUriIo;

    /**
     * Tags that are added to <em>every</em> meter.
     */
    public final List<Tag> commonTags;

    private MetricConfig(final Builder b) {
        this.prefix = b.prefix;
        this.slo = b.slo;
        this.percentiles = b.percentiles;
        this.perUriIo = b.perUriIo;
        this.commonTags = Collections.unmodifiableList(new ArrayList<>(b.commonTags));
    }

    /**
     * Obtain a new builder with default values.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------

    /**
     * Fluent builder.
     */
    public static final class Builder {

        private String prefix = "httpclient";
        private Duration slo = Duration.ofMillis(500);
        private int percentiles = 2;               // p95 + p99
        private boolean perUriIo = false;
        private final List<Tag> commonTags = new ArrayList<>();

        public Builder prefix(final String p) {
            this.prefix = p;
            return this;
        }

        public Builder slo(final Duration d) {
            this.slo = d;
            return this;
        }

        public Builder percentiles(final int n) {
            this.percentiles = n;
            return this;
        }

        public Builder perUriIo(final boolean b) {
            this.perUriIo = b;
            return this;
        }

        public Builder addCommonTag(final String k,
                                    final String v) {
            this.commonTags.add(Tag.of(k, v));
            return this;
        }

        public MetricConfig build() {
            return new MetricConfig(this);
        }
    }
}
