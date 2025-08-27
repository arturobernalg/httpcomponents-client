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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.CookieSpecFactory;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Exception-path tests for the VT-enabled doExecute and close() branches.
 */
class TestInternalHttpClientVirtualThreadsExceptions {

    @Mock
    private HttpClientConnectionManager connManager;
    @Mock
    private HttpRequestExecutor requestExecutor;
    @Mock
    private ExecChainHandler execChain;
    @Mock
    private HttpRoutePlanner routePlanner;
    @Mock
    private Lookup<CookieSpecFactory> cookieSpecRegistry;
    @Mock
    private Lookup<AuthSchemeFactory> authSchemeRegistry;
    @Mock
    private CookieStore cookieStore;
    @Mock
    private CredentialsProvider credentialsProvider;
    @Mock
    private RequestConfig defaultConfig;

    private AutoCloseable mocks;

    @BeforeEach
    void openMocks() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void closeMocks() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
        // clear any accidental interrupt so it doesn't affect other tests
        Thread.interrupted();
    }

    @Test
    void testExecutionException_ioExceptionPropagates() throws Exception {
        final IOException cause = new IOException("boom");
        final ExecutorService exec = new ThrowingSubmitExecutor(cause, false);
        final InternalHttpClient client = new InternalHttpClient(
                connManager, requestExecutor, new ExecChainElement(execChain, null), routePlanner,
                cookieSpecRegistry, authSchemeRegistry, cookieStore, credentialsProvider,
                HttpClientContext::castOrCreate, defaultConfig, null, exec, true, null);

        final HttpGet httpget = new HttpGet("http://example/");
        final IOException thrown = assertThrows(IOException.class, () -> client.execute(httpget, rsp -> null));
        assertSame(cause, thrown);
    }

    @Test
    void testExecutionException_runtimeExceptionPropagates() throws Exception {
        final RuntimeException cause = new IllegalStateException("boom");
        final ExecutorService exec = new ThrowingSubmitExecutor(cause, false);
        final InternalHttpClient client = new InternalHttpClient(
                connManager, requestExecutor, new ExecChainElement(execChain, null), routePlanner,
                cookieSpecRegistry, authSchemeRegistry, cookieStore, credentialsProvider,
                HttpClientContext::castOrCreate, defaultConfig, null, exec, true, null);

        final HttpGet httpget = new HttpGet("http://example/");
        assertThrows(IllegalStateException.class, () -> client.execute(httpget, rsp -> null));
    }

    @Test
    void testExecutionException_errorPropagates() throws Exception {
        final Error cause = new AssertionError("boom");
        final ExecutorService exec = new ThrowingSubmitExecutor(cause, false);
        final InternalHttpClient client = new InternalHttpClient(
                connManager, requestExecutor, new ExecChainElement(execChain, null), routePlanner,
                cookieSpecRegistry, authSchemeRegistry, cookieStore, credentialsProvider,
                HttpClientContext::castOrCreate, defaultConfig, null, exec, true, null);

        final HttpGet httpget = new HttpGet("http://example/");
        assertThrows(AssertionError.class, () -> client.execute(httpget, rsp -> null));
    }

    @Test
    void testExecutionException_otherWrappedAsIOException() throws Exception {
        final Throwable cause = new Throwable("boom");
        final ExecutorService exec = new ThrowingSubmitExecutor(cause, false);
        final InternalHttpClient client = new InternalHttpClient(
                connManager, requestExecutor, new ExecChainElement(execChain, null), routePlanner,
                cookieSpecRegistry, authSchemeRegistry, cookieStore, credentialsProvider,
                HttpClientContext::castOrCreate, defaultConfig, null, exec, true, null);

        final HttpGet httpget = new HttpGet("http://example/");
        final IOException thrown = assertThrows(IOException.class, () -> client.execute(httpget, rsp -> null));
        assertSame(cause, thrown.getCause());
    }

    @Test
    void testRejectedExecutionMappedToIOException() throws Exception {
        final ExecutorService exec = new ThrowingSubmitExecutor(null, true);
        final InternalHttpClient client = new InternalHttpClient(
                connManager, requestExecutor, new ExecChainElement(execChain, null), routePlanner,
                cookieSpecRegistry, authSchemeRegistry, cookieStore, credentialsProvider,
                HttpClientContext::castOrCreate, defaultConfig, null, exec, true, null);

        final HttpGet httpget = new HttpGet("http://example/");
        final IOException thrown = assertThrows(IOException.class, () -> client.execute(httpget, rsp -> null));
        assertTrue(thrown.getMessage().contains("client closed"));
        assertTrue(thrown.getCause() instanceof RejectedExecutionException);
    }

    @Test
    void testCloseGracefulAwaitInterruptedReinterruptsCaller() throws Exception {
        final InterruptingAwaitExecutor exec = new InterruptingAwaitExecutor();
        final InternalHttpClient client = new InternalHttpClient(
                connManager, requestExecutor, new ExecChainElement(execChain, null), routePlanner,
                cookieSpecRegistry, authSchemeRegistry, cookieStore, credentialsProvider,
                HttpClientContext::castOrCreate, defaultConfig, null, exec, true, null);

        assertFalse(Thread.currentThread().isInterrupted());
        client.close(CloseMode.GRACEFUL);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    /**
     * Executor whose submit(Callable) returns a Future whose get() always throws ExecutionException(cause),
     * or whose submit throws RejectedExecutionException when reject == true.
     */
    static final class ThrowingSubmitExecutor extends AbstractExecutorService {
        private final Throwable cause;
        private final boolean reject;

        ThrowingSubmitExecutor(final Throwable cause, final boolean reject) {
            this.cause = cause;
            this.reject = reject;
        }

        @Override
        public <T> Future<T> submit(final Callable<T> task) {
            if (reject) {
                throw new RejectedExecutionException("rejected");
            }
            return new Future<T>() {
                @Override
                public boolean cancel(final boolean mayInterruptIfRunning) {
                    return false;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public T get() throws ExecutionException {
                    throw new ExecutionException(cause);
                }

                @Override
                public T get(final long timeout, final TimeUnit unit) throws ExecutionException {
                    throw new ExecutionException(cause);
                }
            };
        }

        @Override
        public void execute(final Runnable command) {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return true;
        }
    }

    /**
     * Executor whose awaitTermination always throws InterruptedException to trigger the re-interrupt branch.
     */
    static final class InterruptingAwaitExecutor extends AbstractExecutorService {
        @Override
        public void execute(final Runnable command) {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("boom");
        }
    }
}
