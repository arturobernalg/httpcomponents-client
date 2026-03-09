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

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.classic.ExecRuntime;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Deadline;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

@SuppressWarnings({"static-access"}) // test code
class TestInternalExecRuntime {

    @Mock
    private Logger log;
    @Mock
    private HttpClientConnectionManager mgr;
    @Mock
    private LeaseRequest leaseRequest;
    @Mock
    private HttpRequestExecutor requestExecutor;
    @Mock
    private CancellableDependency cancellableDependency;
    @Mock
    private ConnectionEndpoint connectionEndpoint;

    private HttpRoute route;
    private InternalExecRuntime execRuntime;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        route = new HttpRoute(new HttpHost("host", 80));
        execRuntime = new InternalExecRuntime(log, mgr, requestExecutor, cancellableDependency);
    }

    @Test
    void testAcquireEndpoint() throws Exception {
        final HttpClientContext context = HttpClientContext.create();
        @SuppressWarnings("deprecation")
        final RequestConfig config = RequestConfig.custom()
                .setConnectionRequestTimeout(345, TimeUnit.MILLISECONDS)
                .setConnectTimeout(123, TimeUnit.MILLISECONDS)
                .build();
        context.setRequestConfig(config);
        final HttpRoute route = new HttpRoute(new HttpHost("host", 80));

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", route, null, context);

        Assertions.assertTrue(execRuntime.isEndpointAcquired());
        Assertions.assertSame(connectionEndpoint, execRuntime.ensureValid());
        Assertions.assertFalse(execRuntime.isEndpointConnected());
        Assertions.assertFalse(execRuntime.isConnectionReusable());

        Mockito.verify(leaseRequest).get(Timeout.ofMilliseconds(345));
        Mockito.verify(cancellableDependency, Mockito.times(1)).setDependency(leaseRequest);
        Mockito.verify(cancellableDependency, Mockito.times(1)).setDependency(execRuntime);
        Mockito.verify(cancellableDependency, Mockito.times(2)).setDependency(Mockito.any());
    }

    @Test
    void testAcquireEndpointAlreadyAcquired() throws Exception {
        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", route, null, context);

        Assertions.assertTrue(execRuntime.isEndpointAcquired());
        Assertions.assertSame(connectionEndpoint, execRuntime.ensureValid());

        Assertions.assertThrows(IllegalStateException.class, () ->
                execRuntime.acquireEndpoint("some-id", route, null, context));
    }

    @Test
    void testAcquireEndpointLeaseRequestTimeout() throws Exception {
        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenThrow(new TimeoutException("timeout"));

        Assertions.assertThrows(ConnectionRequestTimeoutException.class, () ->
                execRuntime.acquireEndpoint("some-id", route, null, context));
    }

    @Test
    void testAcquireEndpointLeaseRequestFailure() throws Exception {
        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenThrow(new ExecutionException(new IllegalStateException()));

        Assertions.assertThrows(RequestFailedException.class, () ->
                execRuntime.acquireEndpoint("some-id", route, null, context));
    }

    @Test
    void testAbortEndpoint() throws Exception {
        final HttpClientContext context = HttpClientContext.create();
        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", new HttpRoute(new HttpHost("host", 80)), null, context);
        Assertions.assertTrue(execRuntime.isEndpointAcquired());
        execRuntime.discardEndpoint();

        Assertions.assertFalse(execRuntime.isEndpointAcquired());

        Mockito.verify(connectionEndpoint).close(CloseMode.IMMEDIATE);
        Mockito.verify(mgr).release(connectionEndpoint, null, TimeValue.ZERO_MILLISECONDS);

        execRuntime.discardEndpoint();

        Mockito.verify(connectionEndpoint, Mockito.times(1)).close(CloseMode.IMMEDIATE);
        Mockito.verify(mgr, Mockito.times(1)).release(
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
    }

    @Test
    void testCancell() throws Exception {
        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", route, null, context);
        Assertions.assertTrue(execRuntime.isEndpointAcquired());

        Assertions.assertTrue(execRuntime.cancel());

        Assertions.assertFalse(execRuntime.isEndpointAcquired());

        Mockito.verify(connectionEndpoint).close(CloseMode.IMMEDIATE);
        Mockito.verify(mgr).release(connectionEndpoint, null, TimeValue.ZERO_MILLISECONDS);

        Assertions.assertFalse(execRuntime.cancel());

        Mockito.verify(connectionEndpoint, Mockito.times(1)).close(CloseMode.IMMEDIATE);
        Mockito.verify(mgr, Mockito.times(1)).release(
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
    }

    @Test
    void testReleaseEndpointReusable() throws Exception {
        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", route, null, context);
        Assertions.assertTrue(execRuntime.isEndpointAcquired());

        execRuntime.markConnectionReusable("some state", TimeValue.ofMilliseconds(100000));

        execRuntime.releaseEndpoint();

        Assertions.assertFalse(execRuntime.isEndpointAcquired());

        Mockito.verify(connectionEndpoint, Mockito.never()).close();
        Mockito.verify(mgr).release(connectionEndpoint, "some state", TimeValue.ofMilliseconds(100000));

        execRuntime.releaseEndpoint();

        Mockito.verify(mgr, Mockito.times(1)).release(
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
    }

    @Test
    void testReleaseEndpointNonReusable() throws Exception {
        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", route, null, context);
        Assertions.assertTrue(execRuntime.isEndpointAcquired());

        execRuntime.markConnectionReusable("some state", TimeValue.ofMilliseconds(100000));
        execRuntime.markConnectionNonReusable();

        execRuntime.releaseEndpoint();

        Assertions.assertFalse(execRuntime.isEndpointAcquired());

        Mockito.verify(connectionEndpoint, Mockito.times(1)).close(CloseMode.IMMEDIATE);
        Mockito.verify(mgr).release(connectionEndpoint, null, TimeValue.ZERO_MILLISECONDS);

        execRuntime.releaseEndpoint();

        Mockito.verify(mgr, Mockito.times(1)).release(
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
    }

    @Test
    void testConnectEndpoint() throws Exception {
        final HttpClientContext context = HttpClientContext.create();
        @SuppressWarnings("deprecation")
        final RequestConfig config = RequestConfig.custom()
                .setConnectionRequestTimeout(345, TimeUnit.MILLISECONDS)
                .setConnectTimeout(123, TimeUnit.MILLISECONDS)
                .build();
        context.setRequestConfig(config);

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", route, null, context);
        Assertions.assertTrue(execRuntime.isEndpointAcquired());

        Mockito.when(connectionEndpoint.isConnected()).thenReturn(false);
        Assertions.assertFalse(execRuntime.isEndpointConnected());

        execRuntime.connectEndpoint(context);

        Mockito.verify(mgr).connect(connectionEndpoint, Timeout.ofMilliseconds(123), context);
    }

    @Test
    void testCheckExecutionDeadlineNotExpired() throws Exception {
        final Deadline future = Deadline.calculate(Timeout.ofSeconds(60));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, future);

        Assertions.assertTrue(runtimeWithDeadline.hasExecutionDeadline());
        Assertions.assertDoesNotThrow(runtimeWithDeadline::checkExecutionDeadline);
    }

    @Test
    void testCheckExecutionDeadlineExpired() {
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, expired);

        Assertions.assertTrue(runtimeWithDeadline.hasExecutionDeadline());
        Assertions.assertThrows(RequestExecutionTimeoutException.class,
                runtimeWithDeadline::checkExecutionDeadline);
    }

    @Test
    void testHasExecutionDeadlineFalseByDefault() {
        Assertions.assertFalse(execRuntime.hasExecutionDeadline());
    }

    @Test
    void testClampTimeoutWithRemainingBudget() throws Exception {
        // deadline 60s out → configured timeout (100ms) is smaller → returned as-is
        final Deadline farFuture = Deadline.calculate(Timeout.ofSeconds(60));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, farFuture);

        final Timeout result = runtimeWithDeadline.clampTimeout(Timeout.ofMilliseconds(100));
        Assertions.assertEquals(Timeout.ofMilliseconds(100), result);
    }

    @Test
    void testClampTimeoutDeadlineSmallerThanConfigured() throws Exception {
        // deadline 200ms out, configured timeout 10s → clamped to ~200ms
        final Deadline nearFuture = Deadline.calculate(Timeout.ofMilliseconds(200));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, nearFuture);

        final Timeout result = runtimeWithDeadline.clampTimeout(Timeout.ofSeconds(10));
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.toMilliseconds() <= 200,
                "Clamped timeout should be at most the remaining deadline budget");
    }

    @Test
    void testClampTimeoutWhenDeadlineAlreadyExpired() {
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, expired);

        Assertions.assertThrows(RequestExecutionTimeoutException.class,
                () -> runtimeWithDeadline.clampTimeout(Timeout.ofSeconds(10)));
    }

    @Test
    void testMapTimeoutExceptionWrapsWhenDeadlineExpired() {
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, expired);

        final IOException original = new SocketTimeoutException("read timed out");
        final IOException mapped = runtimeWithDeadline.mapTimeoutException(original);

        Assertions.assertInstanceOf(RequestExecutionTimeoutException.class, mapped);
        Assertions.assertSame(original, mapped.getCause());
    }

    @Test
    void testMapTimeoutExceptionPassthroughWhenNoDeadline() {
        final IOException original = new SocketTimeoutException("read timed out");
        final IOException mapped = execRuntime.mapTimeoutException(original);

        Assertions.assertSame(original, mapped);
    }

    @Test
    void testMapTimeoutExceptionAlreadyRequestExecutionTimeout() {
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, expired);

        final RequestExecutionTimeoutException already = RequestExecutionTimeoutException.from(expired);
        final IOException mapped = runtimeWithDeadline.mapTimeoutException(already);

        Assertions.assertSame(already, mapped);
    }

    @Test
    void testAcquireEndpointLeaseTimeoutWithExpiredDeadlineThrowsRequestExecutionTimeout() throws Exception {
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, expired);

        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenThrow(new TimeoutException("pool exhausted"));

        Assertions.assertThrows(RequestExecutionTimeoutException.class,
                () -> runtimeWithDeadline.acquireEndpoint("some-id", route, null, context));
    }


    @Test
    void testAcquireEndpointWithExpiredDeadlineThrowsBeforeLeasing() throws Exception {
        // When the deadline is already expired, acquireEndpoint must throw immediately
        // without ever calling manager.lease(), so no connection pool slot is wasted.
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, expired);
        final HttpClientContext context = HttpClientContext.create();

        Assertions.assertThrows(RequestExecutionTimeoutException.class,
                () -> runtimeWithDeadline.acquireEndpoint("some-id", route, null, context));

        Mockito.verify(mgr, Mockito.never()).lease(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void testAcquireEndpointWithActiveDeadlineClampsConnectionRequestTimeout() throws Exception {
        // The connection-request timeout must be clamped to the remaining deadline budget
        // so it cannot outlive the overall execution deadline.
        final Deadline nearFuture = Deadline.calculate(Timeout.ofMilliseconds(200));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, nearFuture);

        final HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(30))
                .build());

        Mockito.when(mgr.lease(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        runtimeWithDeadline.acquireEndpoint("some-id", route, null, context);

        // The effective timeout passed to leaseRequest.get() must be <= 200ms (the budget),
        // not 30s (the configured connectionRequestTimeout).
        Mockito.verify(leaseRequest).get(Mockito.argThat(
                t -> t != null && t.toMilliseconds() <= 200));
    }


    @Test
    void testHasExecutionDeadlineTrueWhenSet() {
        final Deadline future = Deadline.calculate(Timeout.ofSeconds(10));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, future);

        Assertions.assertTrue(runtimeWithDeadline.hasExecutionDeadline());
    }

    @Test
    void testHasExecutionDeadlineFalseForMaxValueDeadline() {
        // MAX_VALUE is the "no deadline" sentinel — must report as having no deadline.
        final InternalExecRuntime runtimeWithMaxDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, Deadline.MAX_VALUE);

        Assertions.assertFalse(runtimeWithMaxDeadline.hasExecutionDeadline());
    }


    @Test
    void testClampTimeoutNullConfiguredWithActiveDeadlineReturnsBudget() throws Exception {
        // If the caller passes null (no configured timeout) but a deadline is active,
        // clampTimeout must return the remaining budget as the effective timeout.
        final Deadline nearFuture = Deadline.calculate(Timeout.ofMilliseconds(500));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, nearFuture);

        final Timeout result = runtimeWithDeadline.clampTimeout(null);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.toMilliseconds() > 0);
        Assertions.assertTrue(result.toMilliseconds() <= 500);
    }

    @Test
    void testClampTimeoutNoDeadlineNullConfiguredReturnsNull() throws Exception {
        // No deadline and no configured timeout → clampTimeout must return null (unlimited).
        final Timeout result = execRuntime.clampTimeout(null);

        Assertions.assertNull(result);
    }

    @Test
    void testClampTimeoutNoDeadlineReturnConfiguredUnchanged() throws Exception {
        // When no deadline is set the configured timeout must be returned as-is.
        final Timeout configured = Timeout.ofSeconds(5);
        final Timeout result = execRuntime.clampTimeout(configured);

        Assertions.assertSame(configured, result);
    }


    @Test
    void testForkPropagatesActiveDeadlineToNewRuntime() {
        final Deadline future = Deadline.calculate(Timeout.ofSeconds(30));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, future);

        final ExecRuntime forked = runtimeWithDeadline.fork(cancellableDependency);

        Assertions.assertTrue(forked.hasExecutionDeadline(),
                "forked runtime must inherit the parent's execution deadline");
    }

    @Test
    void testForkWithNoDeadlineResultsInNoDeadlineInFork() {
        // A runtime without a deadline must produce a fork without a deadline.
        final ExecRuntime forked = execRuntime.fork(cancellableDependency);

        Assertions.assertFalse(forked.hasExecutionDeadline());
    }


    @Test
    void testMapTimeoutExceptionPassthroughWhenDeadlineNotExpired() {
        // Even with an active deadline, if the deadline has not yet expired the
        // original exception must be returned unchanged.
        final Deadline future = Deadline.calculate(Timeout.ofSeconds(60));
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, future);

        final IOException original = new SocketTimeoutException("read timed out");
        final IOException mapped = runtimeWithDeadline.mapTimeoutException(original);

        Assertions.assertSame(original, mapped);
    }

    @Test
    void testAbsoluteDeadlineEnforcedLikeRelativeTimeout() {
        final Deadline alreadyExpired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 500);
        final InternalExecRuntime runtimeWithDeadline = new InternalExecRuntime(
                log, mgr, requestExecutor, cancellableDependency, alreadyExpired);

        Assertions.assertTrue(runtimeWithDeadline.hasExecutionDeadline());
        Assertions.assertThrows(RequestExecutionTimeoutException.class,
                runtimeWithDeadline::checkExecutionDeadline);
    }

    @Test
    void testDisonnectEndpoint() throws Exception {
        final HttpClientContext context = HttpClientContext.create();

        Mockito.when(mgr.lease(Mockito.eq("some-id"), Mockito.eq(route), Mockito.any(), Mockito.any()))
                .thenReturn(leaseRequest);
        Mockito.when(leaseRequest.get(Mockito.any())).thenReturn(connectionEndpoint);

        execRuntime.acquireEndpoint("some-id", route, null, context);
        Assertions.assertTrue(execRuntime.isEndpointAcquired());

        Mockito.when(connectionEndpoint.isConnected()).thenReturn(true);
        Assertions.assertTrue(execRuntime.isEndpointConnected());

        execRuntime.connectEndpoint(context);

        Mockito.verify(mgr, Mockito.never()).connect(
                Mockito.same(connectionEndpoint), Mockito.any(), Mockito.<HttpClientContext>any());

        execRuntime.disconnectEndpoint();

        Mockito.verify(connectionEndpoint).close();
    }

}
