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
package org.apache.hc.client5.http.impl.async;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.AuthenticationStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.RouteInfo;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultAuthenticationStrategy;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.function.Resolver;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestInternalH2ConnPool {

    @Test
    void testLegacyConstructorWorks() {
        final ConnectionInitiator connectionInitiator = new RecordingConnectionInitiator(Mockito.mock(IOSession.class));
        final Resolver<HttpHost, InetSocketAddress> addressResolver = host -> new InetSocketAddress("localhost", 0);
        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);

        final InternalH2ConnPool connPool = new InternalH2ConnPool(connectionInitiator, addressResolver, tlsStrategy);
        Assertions.assertNotNull(connPool);
        Assertions.assertEquals(TimeValue.NEG_ONE_MILLISECOND, connPool.getValidateAfterInactivity());

        final TimeValue validationInterval = TimeValue.ofSeconds(7);
        connPool.setValidateAfterInactivity(validationInterval);
        Assertions.assertEquals(validationInterval, connPool.getValidateAfterInactivity());
    }

    @Test
    void testFullConstructorAcceptsNullSchemeResolverWhenAuthCacheEnabled() {
        final ConnectionInitiator connectionInitiator = new RecordingConnectionInitiator(Mockito.mock(IOSession.class));
        final Resolver<HttpHost, InetSocketAddress> addressResolver = host -> new InetSocketAddress("localhost", 0);
        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        final HttpProcessor proxyHttpProcessor = Mockito.mock(HttpProcessor.class);
        final AuthenticationStrategy proxyAuthStrategy = DefaultAuthenticationStrategy.INSTANCE;

        final InternalH2ConnPool connPool = new InternalH2ConnPool(
                connectionInitiator,
                addressResolver,
                tlsStrategy,
                proxyHttpProcessor,
                proxyAuthStrategy,
                RequestConfig.DEFAULT,
                null,
                null,
                null,
                false);
        Assertions.assertNotNull(connPool);
    }

    @Test
    void testGetSessionUsesExplicitConnectTimeoutAndAppliesSocketTimeout() {
        final IOSession session = Mockito.mock(IOSession.class);
        final RecordingConnectionInitiator connectionInitiator = new RecordingConnectionInitiator(session);
        final InternalH2ConnPool connPool = new InternalH2ConnPool(
                connectionInitiator,
                host -> new InetSocketAddress("localhost", 0),
                null);
        try {
            connPool.setConnectionConfigResolver(host -> ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(9))
                    .setSocketTimeout(Timeout.ofSeconds(4))
                    .build());
            final RecordingSessionCallback callback = new RecordingSessionCallback();

            connPool.getSession(new HttpRoute(new HttpHost("http", "example.org", 80)), Timeout.ofSeconds(2), callback);

            Assertions.assertNull(callback.failed);
            Assertions.assertNotNull(callback.completed);
            Assertions.assertEquals(Timeout.ofSeconds(2), connectionInitiator.lastTimeout);
            Mockito.verify(session).setSocketTimeout(Timeout.ofSeconds(4));
        } finally {
            connPool.close();
        }
    }

    @Test
    void testGetSessionUsesConnectionConfigTimeoutWhenExplicitIsNull() {
        final IOSession session = Mockito.mock(IOSession.class);
        final RecordingConnectionInitiator connectionInitiator = new RecordingConnectionInitiator(session);
        final InternalH2ConnPool connPool = new InternalH2ConnPool(
                connectionInitiator,
                host -> new InetSocketAddress("localhost", 0),
                null);
        try {
            connPool.setConnectionConfigResolver(host -> ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(11))
                    .build());
            final RecordingSessionCallback callback = new RecordingSessionCallback();

            connPool.getSession(new HttpRoute(new HttpHost("http", "example.org", 80)), null, callback);

            Assertions.assertNull(callback.failed);
            Assertions.assertNotNull(callback.completed);
            Assertions.assertEquals(Timeout.ofSeconds(11), connectionInitiator.lastTimeout);
        } finally {
            connPool.close();
        }
    }

    @Test
    void testTunnelRouteWithoutProxyFails() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new HttpRoute(
                new HttpHost("https", "example.org", 443),
                null,
                (HttpHost[]) null,
                true,
                RouteInfo.TunnelType.TUNNELLED,
                RouteInfo.LayerType.LAYERED));
    }

    @Test
    void testTunnelRouteWithProxyButPlainSessionFails() {
        final IOSession session = Mockito.mock(IOSession.class);
        final RecordingConnectionInitiator connectionInitiator = new RecordingConnectionInitiator(session);
        final InternalH2ConnPool connPool = new InternalH2ConnPool(
                connectionInitiator,
                host -> new InetSocketAddress("localhost", 0),
                null);
        try {
            final HttpRoute route = new HttpRoute(
                    new HttpHost("https", "example.org", 443),
                    null,
                    new HttpHost("http", "proxy.local", 3128),
                    true);
            final RecordingSessionCallback callback = new RecordingSessionCallback();

            connPool.getSession(route, Timeout.ofSeconds(2), callback);

            Assertions.assertNull(callback.completed);
            Assertions.assertInstanceOf(IllegalStateException.class, callback.failed);
        } finally {
            connPool.close();
        }
    }

    @Test
    void testTunnelRouteWithUnknownSchemeAndNoPortFails() {
        final ProtocolIOSession session = Mockito.mock(ProtocolIOSession.class);
        Mockito.when(session.getSocketTimeout()).thenReturn(Timeout.ofSeconds(30));
        final RecordingConnectionInitiator connectionInitiator = new RecordingConnectionInitiator(session);
        final InternalH2ConnPool connPool = new InternalH2ConnPool(
                connectionInitiator,
                host -> new InetSocketAddress("localhost", 0),
                null);
        try {
            final HttpRoute route = new HttpRoute(
                    new HttpHost("custom", "target.local", 0),
                    null,
                    new HttpHost[] {new HttpHost("http", "proxy.local", 3128)},
                    false,
                    RouteInfo.TunnelType.TUNNELLED,
                    RouteInfo.LayerType.PLAIN);
            final RecordingSessionCallback callback = new RecordingSessionCallback();

            connPool.getSession(route, Timeout.ofSeconds(2), callback);

            Assertions.assertNull(callback.completed);
            Assertions.assertInstanceOf(HttpException.class, callback.failed);
            Mockito.verify(session, Mockito.atLeastOnce()).setSocketTimeout(Mockito.any());
            Mockito.verify(session, Mockito.never()).enqueue(Mockito.any(), Mockito.any());
        } finally {
            connPool.close();
        }
    }

    static class RecordingConnectionInitiator implements ConnectionInitiator {

        private final IOSession session;
        private Timeout lastTimeout;

        RecordingConnectionInitiator(final IOSession session) {
            this.session = session;
        }

        @Override
        public Future<IOSession> connect(
                final org.apache.hc.core5.net.NamedEndpoint remoteEndpoint,
                final SocketAddress remoteAddress,
                final SocketAddress localAddress,
                final Timeout timeout,
                final Object attachment,
                final FutureCallback<IOSession> callback) {
            this.lastTimeout = timeout;
            if (callback != null) {
                callback.completed(session);
            }
            return CompletableFuture.completedFuture(session);
        }
    }

    static class RecordingSessionCallback implements FutureCallback<IOSession> {

        private IOSession completed;
        private Exception failed;

        @Override
        public void completed(final IOSession result) {
            this.completed = result;
        }

        @Override
        public void failed(final Exception ex) {
            this.failed = ex;
        }

        @Override
        public void cancelled() {
        }
    }
}
