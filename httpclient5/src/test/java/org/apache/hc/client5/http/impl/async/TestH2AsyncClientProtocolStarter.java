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

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.http2.impl.nio.ClientH2PrefaceHandler;
import org.apache.hc.core5.http2.ssl.ApplicationProtocol;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class TestH2AsyncClientProtocolStarter {

    @Test
    void testCreateHandlerForTunnelledRouteRegistersH2UpgradeAndStartsHttp1Handler() {
        final HttpProcessor httpProcessor = Mockito.mock(HttpProcessor.class);
        @SuppressWarnings("unchecked")
        final HandlerFactory<org.apache.hc.core5.http.nio.AsyncPushConsumer> exchangeHandlerFactory = Mockito.mock(HandlerFactory.class);
        final Callback<Exception> exceptionCallback = ex -> {
        };
        final H2AsyncClientProtocolStarter starter = new H2AsyncClientProtocolStarter(
                httpProcessor,
                exchangeHandlerFactory,
                H2Config.DEFAULT,
                null,
                exceptionCallback);
        final ProtocolIOSession ioSession = Mockito.mock(ProtocolIOSession.class);
        final HttpRoute tunnelledRoute = new HttpRoute(
                new HttpHost("https", "example.org", 443),
                null,
                new HttpHost("http", "proxy.local", 3128),
                true);

        final IOEventHandler handler = starter.createHandler(ioSession, tunnelledRoute);

        Assertions.assertNotNull(handler);
        Assertions.assertNotNull(handler.getClass().getName());
        Mockito.verify(ioSession).registerProtocol(
                Mockito.eq(ApplicationProtocol.HTTP_2.id),
                ArgumentMatchers.any());
    }

    @Test
    void testCreateHandlerForDirectRouteReturnsH2PrefaceHandler() {
        final HttpProcessor httpProcessor = Mockito.mock(HttpProcessor.class);
        @SuppressWarnings("unchecked")
        final HandlerFactory<org.apache.hc.core5.http.nio.AsyncPushConsumer> exchangeHandlerFactory = Mockito.mock(HandlerFactory.class);
        final H2AsyncClientProtocolStarter starter = new H2AsyncClientProtocolStarter(
                httpProcessor,
                exchangeHandlerFactory,
                null,
                null,
                null);
        final ProtocolIOSession ioSession = Mockito.mock(ProtocolIOSession.class);
        Mockito.when(ioSession.getId()).thenReturn("s1");
        final HttpRoute directRoute = new HttpRoute(new HttpHost("https", "example.org", 443));

        final IOEventHandler handler = starter.createHandler(ioSession, directRoute);

        Assertions.assertNotNull(handler);
        Assertions.assertInstanceOf(ClientH2PrefaceHandler.class, handler);
        Mockito.verify(ioSession, Mockito.never()).registerProtocol(
                Mockito.eq(ApplicationProtocol.HTTP_2.id),
                ArgumentMatchers.any());
    }

    @Test
    void testCreateHandlerWithNonRouteAttachmentReturnsH2PrefaceHandler() {
        final HttpProcessor httpProcessor = Mockito.mock(HttpProcessor.class);
        @SuppressWarnings("unchecked")
        final HandlerFactory<org.apache.hc.core5.http.nio.AsyncPushConsumer> exchangeHandlerFactory = Mockito.mock(HandlerFactory.class);
        final H2AsyncClientProtocolStarter starter = new H2AsyncClientProtocolStarter(
                httpProcessor,
                exchangeHandlerFactory,
                null,
                null,
                null);
        final ProtocolIOSession ioSession = Mockito.mock(ProtocolIOSession.class);
        Mockito.when(ioSession.getId()).thenReturn("s2");

        final IOEventHandler handler = starter.createHandler(ioSession, "not-a-route");

        Assertions.assertNotNull(handler);
        Assertions.assertInstanceOf(ClientH2PrefaceHandler.class, handler);
        Mockito.verify(ioSession, Mockito.never()).registerProtocol(
                Mockito.eq(ApplicationProtocol.HTTP_2.id),
                ArgumentMatchers.any());
    }
}
