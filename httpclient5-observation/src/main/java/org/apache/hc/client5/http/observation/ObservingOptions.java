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

import java.util.EnumSet;
import java.util.function.Predicate;

import io.micrometer.observation.ObservationPredicate;
import org.apache.hc.core5.util.Args;

public final class ObservingOptions {

    public enum MetricSet { BASIC, IO, CONN_POOL, TLS, DNS }

    public enum TagLevel { LOW, EXTENDED }

    public final EnumSet<MetricSet> metricSets;
    public final TagLevel tagLevel;
    public final ObservationPredicate micrometerFilter;    // executed inside Micrometer
    public final Predicate<String> spanSampling;           // cheap pre-filter on URI (or any predicate)

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private EnumSet<MetricSet> sets = EnumSet.of(MetricSet.BASIC);
        private TagLevel tagLevel = TagLevel.LOW;
        private ObservationPredicate micrometer = (n, c) -> true;
        private Predicate<String> spanPred = uri -> true;

        public Builder metrics(final EnumSet<MetricSet> sets) {
            this.sets = EnumSet.copyOf(sets);
            return this;
        }

        public Builder tagLevel(final TagLevel l) {
            this.tagLevel = Args.notNull(l, "Tag level");
            return this;
        }

        public Builder micrometerFilter(final ObservationPredicate p) {
            this.micrometer = Args.notNull(p, "predicate");
            return this;
        }

        public Builder spanSampling(final Predicate<String> p) {
            this.spanPred = Args.notNull(p, "span predicate");
            return this;
        }

        public ObservingOptions build() {
            return new ObservingOptions(this);
        }
    }

    public static final ObservingOptions DEFAULT = builder().build();

    private ObservingOptions(final Builder b) {
        this.metricSets = b.sets;
        this.tagLevel = b.tagLevel;
        this.micrometerFilter = b.micrometer;
        this.spanSampling = b.spanPred;
    }
}
