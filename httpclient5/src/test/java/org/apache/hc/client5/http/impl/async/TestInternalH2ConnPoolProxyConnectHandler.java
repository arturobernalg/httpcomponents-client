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
 */
package org.apache.hc.client5.http.impl.async;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import org.apache.hc.client5.http.auth.AuthExchange;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultAuthenticationStrategy;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.auth.AuthCacheKeeper;
import org.apache.hc.client5.http.impl.auth.AuthenticationHandler;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class TestInternalH2ConnPoolProxyConnectHandler {

    @Test
    void testProduceRequestBuildsConnectAuthorityAndHostHeader() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final AtomicReference<HttpRequest> captured = new AtomicReference<>();
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                mockProtocolSessionSwitches(ProtocolSwitchOutcome.COMPLETE),
                nextAttempt -> {
                },
                callback);

        handler.produceRequest((request, entityDetails, context) -> captured.set(request), HttpCoreContext.create());

        Assertions.assertNotNull(captured.get());
        Assertions.assertEquals("CONNECT", captured.get().getMethod());
        Assertions.assertEquals(HttpVersion.HTTP_1_1, captured.get().getVersion());
        Assertions.assertEquals("example.org:443", captured.get().getPath());
        Assertions.assertEquals("example.org:443", captured.get().getFirstHeader(HttpHeaders.HOST).getValue());
    }

    @Test
    void testConsumeResponseInformationalThrows() {
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                mockProtocolSessionSwitches(ProtocolSwitchOutcome.COMPLETE),
                nextAttempt -> {
                },
                new RecordingSessionCallback());

        Assertions.assertThrows(HttpException.class, () ->
                handler.consumeResponse(new BasicHttpResponse(101), null, HttpCoreContext.create()));
    }

    @Test
    void testConsumeResponseRefusedThrows() {
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                mockProtocolSessionSwitches(ProtocolSwitchOutcome.COMPLETE),
                nextAttempt -> {
                },
                new RecordingSessionCallback());

        Assertions.assertThrows(HttpException.class, () ->
                handler.consumeResponse(new BasicHttpResponse(HttpStatus.SC_BAD_GATEWAY), null, HttpCoreContext.create()));
    }

    @Test
    void testFinalizeExchangeSwitchesProtocolAndIsIdempotent() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final ProtocolIOSession ioSession = mockProtocolSessionSwitches(ProtocolSwitchOutcome.COMPLETE);
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                ioSession,
                nextAttempt -> {
                },
                callback);

        handler.consumeResponse(new BasicHttpResponse(HttpStatus.SC_OK), null, HttpCoreContext.create());
        handler.streamEnd(null);

        Assertions.assertNotNull(callback.completed.get());
        Assertions.assertNull(callback.failed.get());
        Assertions.assertFalse(callback.cancelled);
        Mockito.verify(ioSession, Mockito.times(1)).switchProtocol(
                Mockito.anyString(),
                ArgumentMatchers.<FutureCallback<ProtocolIOSession>>any());
    }

    @Test
    void testFinalizeExchangeRetryPathWhenChallenged() throws Exception {
        final AtomicReference<Integer> retried = new AtomicReference<>();
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                mockProtocolSessionSwitches(ProtocolSwitchOutcome.COMPLETE),
                retried::set,
                new RecordingSessionCallback());
        setBooleanField(handler, "challenged", true);

        handler.streamEnd(null);

        Assertions.assertNotNull(retried.get());
        Assertions.assertEquals(Integer.valueOf(1), retried.get());
    }

    @Test
    void testFinalizeExchangeRetryLimitExceededFailsTerminal() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final ProtocolIOSession ioSession = mockProtocolSessionSwitches(ProtocolSwitchOutcome.COMPLETE);
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                2,
                ioSession,
                nextAttempt -> {
                },
                callback);
        setBooleanField(handler, "challenged", true);

        handler.streamEnd(null);

        Assertions.assertNull(callback.completed.get());
        Assertions.assertNotNull(callback.failed.get());
        Mockito.verify(ioSession).close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
    }

    @Test
    void testSwitchProtocolFailedTriggersTerminalFailure() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final ProtocolIOSession ioSession = mockProtocolSessionSwitches(ProtocolSwitchOutcome.FAIL);
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                ioSession,
                nextAttempt -> {
                },
                callback);

        handler.consumeResponse(new BasicHttpResponse(HttpStatus.SC_OK), null, HttpCoreContext.create());

        Assertions.assertNotNull(callback.failed.get());
        Mockito.verify(ioSession).close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
    }

    @Test
    void testSwitchProtocolCancelledPropagatesCancel() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final ProtocolIOSession ioSession = mockProtocolSessionSwitches(ProtocolSwitchOutcome.CANCEL);
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                ioSession,
                nextAttempt -> {
                },
                callback);

        handler.consumeResponse(new BasicHttpResponse(HttpStatus.SC_OK), null, HttpCoreContext.create());

        Assertions.assertTrue(callback.cancelled);
        Assertions.assertNull(callback.failed.get());
    }

    @Test
    void testTlsUpgradeFailedTriggersTerminalFailure() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final ProtocolIOSession ioSession = mockTransportProtocolSession(ProtocolSwitchOutcome.COMPLETE);
        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final FutureCallback<TransportSecurityLayer> cb =
                    invocation.getArgument(4, FutureCallback.class);
            cb.failed(new IOException("tls failed"));
            return null;
        }).when(tlsStrategy).upgrade(
                ArgumentMatchers.any(TransportSecurityLayer.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.isNull(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any());
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                tlsStrategy,
                3,
                0,
                ioSession,
                nextAttempt -> {
                },
                callback);

        handler.consumeResponse(new BasicHttpResponse(HttpStatus.SC_OK), null, HttpCoreContext.create());

        Assertions.assertNotNull(callback.failed.get());
        Mockito.verify(ioSession).close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
    }

    @Test
    void testTlsUpgradeCancelledPropagatesCancel() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final ProtocolIOSession ioSession = mockTransportProtocolSession(ProtocolSwitchOutcome.COMPLETE);
        final TlsStrategy tlsStrategy = Mockito.mock(TlsStrategy.class);
        Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final FutureCallback<TransportSecurityLayer> cb =
                    invocation.getArgument(4, FutureCallback.class);
            cb.cancelled();
            return null;
        }).when(tlsStrategy).upgrade(
                ArgumentMatchers.any(TransportSecurityLayer.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.isNull(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any());
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                tlsStrategy,
                3,
                0,
                ioSession,
                nextAttempt -> {
                },
                callback);

        handler.consumeResponse(new BasicHttpResponse(HttpStatus.SC_OK), null, HttpCoreContext.create());

        Assertions.assertTrue(callback.cancelled);
        Assertions.assertNull(callback.failed.get());
    }

    @Test
    void testHandlerAuxMethods() throws Exception {
        final RecordingSessionCallback callback = new RecordingSessionCallback();
        final ProtocolIOSession ioSession = mockProtocolSessionSwitches(ProtocolSwitchOutcome.COMPLETE);
        final InternalH2ConnPool.ProxyConnectHandler handler = newHandler(
                null,
                3,
                0,
                ioSession,
                nextAttempt -> {
                },
                callback);
        final CapacityChannel capacityChannel = Mockito.mock(CapacityChannel.class);
        final ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.position(1);
        buffer.limit(6);

        Assertions.assertEquals(0, handler.available());
        handler.updateCapacity(capacityChannel);
        handler.consume(buffer);
        handler.consumeInformation(new BasicHttpResponse(HttpStatus.SC_OK), HttpCoreContext.create());
        handler.produce(null);
        handler.releaseResources();
        handler.cancel();

        Assertions.assertEquals(6, buffer.position());
        Assertions.assertTrue(callback.cancelled);
        Mockito.verify(capacityChannel).update(Integer.MAX_VALUE);
    }

    @Test
    void testNeedAuthenticationReturnsFalseWhenAuthDisabled() throws Exception {
        final HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false).build());
        final AuthenticationHandler authenticator = Mockito.mock(AuthenticationHandler.class);

        final boolean result = InternalH2ConnPool.SessionPool.needAuthentication(
                new AuthExchange(),
                new HttpHost("http", "proxy.local", 3128),
                new BasicHttpResponse(HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED),
                context,
                authenticator,
                null,
                DefaultAuthenticationStrategy.INSTANCE);

        Assertions.assertFalse(result);
        Mockito.verifyNoInteractions(authenticator);
    }

    @Test
    void testNeedAuthenticationReturnsUpdatedWhenChallengeHandled() throws Exception {
        final HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom().setAuthenticationEnabled(true).build());
        final AuthenticationHandler authenticator = Mockito.mock(AuthenticationHandler.class);
        final AuthExchange exchange = new AuthExchange();
        final HttpHost proxy = new HttpHost("http", "proxy.local", 3128);
        final HttpResponse response = new BasicHttpResponse(HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED);
        Mockito.when(authenticator.isChallenged(
                ArgumentMatchers.eq(proxy),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(response),
                ArgumentMatchers.eq(exchange),
                ArgumentMatchers.eq(context))).thenReturn(true);
        Mockito.when(authenticator.isChallengeExpected(ArgumentMatchers.eq(exchange))).thenReturn(false);
        Mockito.when(authenticator.handleResponse(
                ArgumentMatchers.eq(proxy),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(response),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(exchange),
                ArgumentMatchers.eq(context))).thenReturn(true);

        final boolean result = InternalH2ConnPool.SessionPool.needAuthentication(
                exchange,
                proxy,
                response,
                context,
                authenticator,
                new AuthCacheKeeper(DefaultSchemePortResolver.INSTANCE),
                DefaultAuthenticationStrategy.INSTANCE);

        Assertions.assertTrue(result);
    }

    private static InternalH2ConnPool.ProxyConnectHandler newHandler(
            final TlsStrategy tlsStrategy,
            final int maxAttempts,
            final int attempt,
            final ProtocolIOSession ioSession,
            final IntConsumer retryStrategy,
            final RecordingSessionCallback callback) {
        return new InternalH2ConnPool.ProxyConnectHandler(
                new HttpHost("http", "proxy.local", 3128),
                new HttpHost("https", "example.org", 443),
                443,
                Timeout.ofSeconds(3),
                tlsStrategy,
                HttpClientContext.create(),
                new AuthExchange(),
                null,
                maxAttempts,
                attempt,
                ioSession,
                retryStrategy,
                callback);
    }

    private static ProtocolIOSession mockProtocolSessionSwitches(final ProtocolSwitchOutcome outcome) {
        final ProtocolIOSession ioSession = Mockito.mock(ProtocolIOSession.class);
        Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final FutureCallback<ProtocolIOSession> cb = invocation.getArgument(1, FutureCallback.class);
            if (outcome == ProtocolSwitchOutcome.FAIL) {
                cb.failed(new IOException("switch failed"));
            } else if (outcome == ProtocolSwitchOutcome.CANCEL) {
                cb.cancelled();
            } else {
                cb.completed(ioSession);
            }
            return null;
        }).when(ioSession).switchProtocol(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        return ioSession;
    }

    private static ProtocolIOSession mockTransportProtocolSession(final ProtocolSwitchOutcome outcome) {
        final ProtocolIOSession ioSession = Mockito.mock(
                ProtocolIOSession.class,
                Mockito.withSettings().extraInterfaces(TransportSecurityLayer.class));
        Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final FutureCallback<ProtocolIOSession> cb = invocation.getArgument(1, FutureCallback.class);
            if (outcome == ProtocolSwitchOutcome.FAIL) {
                cb.failed(new IOException("switch failed"));
            } else if (outcome == ProtocolSwitchOutcome.CANCEL) {
                cb.cancelled();
            } else {
                cb.completed(ioSession);
            }
            return null;
        }).when(ioSession).switchProtocol(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        return ioSession;
    }

    private static void setBooleanField(final Object target, final String fieldName, final boolean value) throws Exception {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    enum ProtocolSwitchOutcome { COMPLETE, FAIL, CANCEL }

    static class RecordingSessionCallback implements FutureCallback<IOSession> {
        final AtomicReference<IOSession> completed = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        volatile boolean cancelled;

        @Override
        public void completed(final IOSession result) {
            completed.set(result);
        }

        @Override
        public void failed(final Exception ex) {
            failed.set(ex);
        }

        @Override
        public void cancelled() {
            cancelled = true;
        }
    }
}
