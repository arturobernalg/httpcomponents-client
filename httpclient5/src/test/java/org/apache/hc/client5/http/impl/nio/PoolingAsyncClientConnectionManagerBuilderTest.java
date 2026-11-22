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

package org.apache.hc.client5.http.impl.nio;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.hc.client5.http.AsyncDnsResolver;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.nio.AsyncClientConnectionOperator;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers {@link PoolingAsyncClientConnectionManagerBuilder#setDestinationOrderingEnabled(boolean)},
 * the entry point through which an application opts the async-DNS merge path into RFC
 * 6724-aware ordering.
 */
class PoolingAsyncClientConnectionManagerBuilderTest {

    /** Captures what {@code build()} actually hands the connection-operator hook. */
    private static final class CapturingBuilder extends PoolingAsyncClientConnectionManagerBuilder {

        DnsResolver seenDnsResolver;

        @Override
        protected AsyncClientConnectionOperator createConnectionOperator(
                final TlsStrategy tlsStrategy,
                final SchemePortResolver schemePortResolver,
                final DnsResolver dnsResolver,
                final boolean happyEyeballsEnabled,
                final TimeValue connectionAttemptDelay,
                final AsyncDnsResolver asyncDnsResolver) {
            seenDnsResolver = dnsResolver;
            return super.createConnectionOperator(
                    tlsStrategy, schemePortResolver, dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, asyncDnsResolver);
        }
    }

    @Test
    void disabledByDefault_configuresNoAddressOrderingStrategy() throws Exception {
        final CapturingBuilder builder = new CapturingBuilder();
        builder.build().close();
        assertNull(builder.addressOrderingStrategy);
    }

    @Test
    void enabledWithoutHappyEyeballs_hasNoEffect() throws Exception {
        final CapturingBuilder builder = new CapturingBuilder();
        builder.setDestinationOrderingEnabled(true);
        builder.build().close();
        assertNull(builder.addressOrderingStrategy, "the flag has no effect unless Happy Eyeballs racing is also enabled");
    }

    @Test
    void enabledWithHappyEyeballs_configuresTheAsyncMergeStrategy() throws Exception {
        final CapturingBuilder builder = new CapturingBuilder();
        builder.setHappyEyeballsEnabled(true).setDestinationOrderingEnabled(true);
        builder.build().close();
        assertNotNull(builder.addressOrderingStrategy);
    }

    @Test
    void enabledWithHappyEyeballs_neverWrapsOrReplacesTheConfiguredDnsResolver() throws Exception {
        // The synchronous path is never auto-wrapped: a resolver that defers resolution
        // (returns null, e.g. HTTPCLIENT-2350) must reach the operator exactly as given.
        final DnsResolver mine = Mockito.mock(DnsResolver.class);
        final CapturingBuilder builder = new CapturingBuilder();
        builder.setDnsResolver(mine).setHappyEyeballsEnabled(true).setDestinationOrderingEnabled(true);
        builder.build().close();
        assertSame(mine, builder.seenDnsResolver);
    }

}
