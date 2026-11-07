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
package org.apache.hc.client5.testing.sync;

import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link SocketConfig} is applied to sockets of the classic client.
 */
class TestSocketConfig {

    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        this.server = ServerBootstrap.bootstrap()
                .setLocalAddress(InetAddress.getLoopbackAddress())
                .register("*", (request, response, context) -> response.setCode(200))
                .create();
        this.server.start();
    }

    @AfterEach
    void shutDown() {
        if (this.server != null) {
            this.server.close(CloseMode.GRACEFUL);
        }
    }

    @Test
    void testSocketConfigApplied() throws Exception {
        final AtomicReference<Socket> socketRef = new AtomicReference<>();
        final DetachedSocketFactory socketFactory = socksProxy -> {
            final Socket socket = socksProxy != null ? new Socket(socksProxy) : new Socket();
            socketRef.set(socket);
            return socket;
        };
        final PoolingHttpClientConnectionManagerBuilder connManagerBuilder = new PoolingHttpClientConnectionManagerBuilder() {

            @Override
            protected HttpClientConnectionOperator createConnectionOperator(
                    final org.apache.hc.client5.http.SchemePortResolver schemePortResolver,
                    final org.apache.hc.client5.http.DnsResolver dnsResolver,
                    final org.apache.hc.client5.http.ssl.TlsSocketStrategy tlsSocketStrategy) {
                return new DefaultHttpClientConnectionOperator(
                        socketFactory, schemePortResolver, dnsResolver,
                        name -> URIScheme.HTTPS.same(name) ? tlsSocketStrategy : null);
            }

        };
        connManagerBuilder.setDefaultSocketConfig(SocketConfig.custom()
                .setSoLinger(TimeValue.ofSeconds(7))
                .setTcpNoDelay(false)
                .setSoKeepAlive(true)
                .setSoReuseAddress(true)
                .build());

        final HttpHost target = new HttpHost("http", InetAddress.getLoopbackAddress().getHostAddress(), server.getLocalPort());
        try (final CloseableHttpClient client = HttpClientBuilder.create()
                .setConnectionManager(connManagerBuilder.build())
                .build()) {
            // The socket is closed once the connection is released, so inspect it within the exchange
            final String actual = client.execute(target, new HttpGet("/"), response -> {
                final Socket socket = socketRef.get();
                final String options = socket.getSoLinger() + "/" + socket.getTcpNoDelay() + "/" + socket.getKeepAlive()
                        + "/" + socket.getReuseAddress();
                EntityUtils.consume(response.getEntity());
                return options;
            });
            Assertions.assertEquals("7/false/true/true", actual);
        }
    }

}
