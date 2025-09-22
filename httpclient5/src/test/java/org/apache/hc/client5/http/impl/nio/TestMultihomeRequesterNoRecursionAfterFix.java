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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

final class TestMultihomeRequesterNoRecursionAfterFix {

    static final class FakeDns implements DnsResolver {
        private final List<InetSocketAddress> addrs;

        FakeDns(final int n) {
            addrs = new ArrayList<>(n);
            final int base = 1025;
            final int span = 65535 - base + 1;
            for (int i = 0; i < n; i++) {
                final int port = base + (i % span);
                addrs.add(new InetSocketAddress("192.0.2.1", port));
            }
        }

        @Override
        public InetAddress[] resolve(final String host) throws UnknownHostException {
            return new InetAddress[0];
        }

        @Override
        public String resolveCanonicalHostname(final String host) throws UnknownHostException {
            return "";
        }

        @Override
        public List<InetSocketAddress> resolve(final String host, final int port) {
            return addrs;
        }
    }

    static final class FailingInitiator implements ConnectionInitiator {
        @Override
        public java.util.concurrent.Future<IOSession> connect(
                final NamedEndpoint remoteEndpoint,
                final java.net.SocketAddress remoteAddress,
                final java.net.SocketAddress localAddress,
                final Timeout timeout,
                final Object attachment,
                final FutureCallback<IOSession> callback) {
            callback.failed(new IOException("synthetic connect failure"));
            return new CompletableFuture<>(); // dummy
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void no_stackoverflow_after_fix() throws Exception {
        final DnsResolver dns = new FakeDns(2000);
        final MultihomeIOSessionRequester requester = new MultihomeIOSessionRequester(dns);
        final NamedEndpoint endpoint = new NamedEndpoint() {
            @Override
            public String getHostName() {
                return "example.test";
            }

            @Override
            public int getPort() {
                return 443;
            }
        };
        final ConnectionInitiator initiator = new FailingInitiator();

        final CountDownLatch done = new CountDownLatch(1);
        final java.util.concurrent.Future<IOSession> f = requester.connect(
                initiator, endpoint, null, null, Timeout.ofSeconds(5), null,
                new FutureCallback<IOSession>() {
                    @Override
                    public void completed(final IOSession result) {
                        done.countDown();
                    }

                    @Override
                    public void failed(final Exception ex) {
                        done.countDown();
                    }

                    @Override
                    public void cancelled() {
                        done.countDown();
                    }
                });

        assertNotNull(f);
        assertTrue(done.await(5, TimeUnit.SECONDS), "callback not invoked (but no SOE should occur)");
    }
}
