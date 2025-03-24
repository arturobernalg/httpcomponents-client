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

package org.apache.hc.client5.http.impl.classic;

import java.io.ByteArrayInputStream;

import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.UserTokenHandler;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecRuntime;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.EntityBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for HTTPCLIENT-1482: Expect: 100-continue for potentially stale connections.
 */
public class MainClientExecStaleConnectionTest {

    @Mock
    private HttpClientConnectionManager connectionManager;
    @Mock
    private HttpProcessor httpProcessor;
    @Mock
    private ConnectionReuseStrategy reuseStrategy;
    @Mock
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    @Mock
    private UserTokenHandler userTokenHandler;
    @Mock
    private ExecRuntime execRuntime;

    private MainClientExec mainClientExec;
    private HttpHost target;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        mainClientExec = new MainClientExec(connectionManager, httpProcessor, reuseStrategy, keepAliveStrategy, userTokenHandler);
        target = new HttpHost("foo", 80);
    }

    // [Existing tests unchanged: testFundamentals, testExecRequestNonPersistentConnection, etc.]

    @Test
    void testExpectContinueOnStaleEnabledWithStaleConnectionNonIdempotent() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = HttpClientContext.create();
        final RequestConfig config = RequestConfig.custom()
                .setExpectContinueOnStaleEnabled(true)
                .build();
        context.setRequestConfig(config);

        final ClassicHttpRequest request = new HttpPost("http://bar/test");
        request.setEntity(EntityBuilder.create()
                .setStream(new ByteArrayInputStream("test".getBytes()))
                .build());

        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(execRuntime.isEndpointPotentiallyStale()).thenReturn(true);
        Mockito.when(execRuntime.isEndpointConnected()).thenReturn(true);
        Mockito.when(execRuntime.execute(
                Mockito.anyString(),
                Mockito.same(request),
                Mockito.any(),
                Mockito.any())).thenReturn(response);

        final ExecChain.Scope scope = new ExecChain.Scope("test", route, request, execRuntime, context);
        final ClassicHttpResponse finalResponse = mainClientExec.execute(request, scope, null);

        final Header expectHeader = request.getFirstHeader(HttpHeaders.EXPECT);
        Assertions.assertNotNull(expectHeader, "Expect header should be present for non-idempotent request");
        Assertions.assertEquals("100-continue", expectHeader.getValue());
        Assertions.assertNotNull(finalResponse);
    }

    @Test
    void testExpectContinueOnStaleEnabledWithFreshConnectionNonIdempotent() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = HttpClientContext.create();
        final RequestConfig config = RequestConfig.custom()
                .setExpectContinueOnStaleEnabled(true)
                .build();
        context.setRequestConfig(config);

        final ClassicHttpRequest request = new HttpPost("http://bar/test");
        request.setEntity(EntityBuilder.create()
                .setStream(new ByteArrayInputStream("test".getBytes()))
                .build());

        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(execRuntime.isEndpointPotentiallyStale()).thenReturn(false);
        Mockito.when(execRuntime.isEndpointConnected()).thenReturn(true);
        Mockito.when(execRuntime.execute(
                Mockito.anyString(),
                Mockito.same(request),
                Mockito.any(),
                Mockito.any())).thenReturn(response);

        final ExecChain.Scope scope = new ExecChain.Scope("test", route, request, execRuntime, context);
        final ClassicHttpResponse finalResponse = mainClientExec.execute(request, scope, null);

        final Header expectHeader = request.getFirstHeader(HttpHeaders.EXPECT);
        Assertions.assertNull(expectHeader, "Expect header should not be present for fresh connection");
        Assertions.assertNotNull(finalResponse);
    }

    @Test
    void testExpectContinueEnabledAlwaysNonIdempotent() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = HttpClientContext.create();
        final RequestConfig config = RequestConfig.custom()
                .setExpectContinueEnabled(true)
                .setExpectContinueOnStaleEnabled(false)
                .build();
        context.setRequestConfig(config);

        final ClassicHttpRequest request = new HttpPost("http://bar/test");
        request.setEntity(EntityBuilder.create()
                .setStream(new ByteArrayInputStream("test".getBytes()))
                .build());

        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(execRuntime.isEndpointPotentiallyStale()).thenReturn(false);
        Mockito.when(execRuntime.isEndpointConnected()).thenReturn(true);
        Mockito.when(execRuntime.execute(
                Mockito.anyString(),
                Mockito.same(request),
                Mockito.any(),
                Mockito.any())).thenReturn(response);

        final ExecChain.Scope scope = new ExecChain.Scope("test", route, request, execRuntime, context);
        final ClassicHttpResponse finalResponse = mainClientExec.execute(request, scope, null);

        final Header expectHeader = request.getFirstHeader(HttpHeaders.EXPECT);
        Assertions.assertNotNull(expectHeader, "Expect header should be present for non-idempotent request");
        Assertions.assertEquals("100-continue", expectHeader.getValue());
        Assertions.assertNotNull(finalResponse);
    }

    @Test
    void testNoExpectContinueWithoutEntity() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = HttpClientContext.create();
        final RequestConfig config = RequestConfig.custom()
                .setExpectContinueOnStaleEnabled(true)
                .build();
        context.setRequestConfig(config);

        final ClassicHttpRequest request = new HttpGet("http://bar/test");
        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(execRuntime.isEndpointPotentiallyStale()).thenReturn(true);
        Mockito.when(execRuntime.isEndpointConnected()).thenReturn(true);
        Mockito.when(execRuntime.execute(
                Mockito.anyString(),
                Mockito.same(request),
                Mockito.any(),
                Mockito.any())).thenReturn(response);

        final ExecChain.Scope scope = new ExecChain.Scope("test", route, request, execRuntime, context);
        final ClassicHttpResponse finalResponse = mainClientExec.execute(request, scope, null);

        final Header expectHeader = request.getFirstHeader(HttpHeaders.EXPECT);
        Assertions.assertNull(expectHeader, "Expect header should not be present without entity");
        Assertions.assertNotNull(finalResponse);
    }

    @Test
    void testNoExpectContinueWithIdempotentRequest() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = HttpClientContext.create();
        final RequestConfig config = RequestConfig.custom()
                .setExpectContinueOnStaleEnabled(true)
                .build();
        context.setRequestConfig(config);

        final ClassicHttpRequest request = new BasicClassicHttpRequest("PUT", "http://bar/test");
        request.setEntity(EntityBuilder.create()
                .setStream(new ByteArrayInputStream("test".getBytes()))
                .build());

        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(execRuntime.isEndpointPotentiallyStale()).thenReturn(true);
        Mockito.when(execRuntime.isEndpointConnected()).thenReturn(true);
        Mockito.when(execRuntime.execute(
                Mockito.anyString(),
                Mockito.same(request),
                Mockito.any(),
                Mockito.any())).thenReturn(response);

        final ExecChain.Scope scope = new ExecChain.Scope("test", route, request, execRuntime, context);
        final ClassicHttpResponse finalResponse = mainClientExec.execute(request, scope, null);

        final Header expectHeader = request.getFirstHeader(HttpHeaders.EXPECT);
        Assertions.assertNull(expectHeader, "Expect header should not be present for idempotent request");
        Assertions.assertNotNull(finalResponse);
    }

    @Test
    void testExpectContinueOnStaleWithIdempotentVsNonIdempotent() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = HttpClientContext.create();
        final RequestConfig config = RequestConfig.custom()
                .setExpectContinueOnStaleEnabled(true)
                .build();
        context.setRequestConfig(config);

        // Non-idempotent request (POST)
        final ClassicHttpRequest postRequest = new HttpPost("http://bar/test");
        postRequest.setEntity(EntityBuilder.create()
                .setStream(new ByteArrayInputStream("test".getBytes()))
                .build());

        // Idempotent request (PUT)
        final ClassicHttpRequest putRequest = new BasicClassicHttpRequest("PUT", "http://bar/test");
        putRequest.setEntity(EntityBuilder.create()
                .setStream(new ByteArrayInputStream("test".getBytes()))
                .build());

        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(execRuntime.isEndpointPotentiallyStale()).thenReturn(true);
        Mockito.when(execRuntime.isEndpointConnected()).thenReturn(true);
        Mockito.when(execRuntime.execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any())).thenReturn(response);

        // Test POST (non-idempotent)
        final ExecChain.Scope postScope = new ExecChain.Scope("test-post", route, postRequest, execRuntime, context);
        final ClassicHttpResponse postResponse = mainClientExec.execute(postRequest, postScope, null);
        final Header postExpectHeader = postRequest.getFirstHeader(HttpHeaders.EXPECT);
        Assertions.assertNotNull(postExpectHeader, "Expect header should be present for non-idempotent POST");
        Assertions.assertEquals("100-continue", postExpectHeader.getValue());
        Assertions.assertNotNull(postResponse);

        // Test PUT (idempotent)
        final ExecChain.Scope putScope = new ExecChain.Scope("test-put", route, putRequest, execRuntime, context);
        final ClassicHttpResponse putResponse = mainClientExec.execute(putRequest, putScope, null);
        final Header putExpectHeader = putRequest.getFirstHeader(HttpHeaders.EXPECT);
        Assertions.assertNull(putExpectHeader, "Expect header should not be present for idempotent PUT");
        Assertions.assertNotNull(putResponse);
    }
}