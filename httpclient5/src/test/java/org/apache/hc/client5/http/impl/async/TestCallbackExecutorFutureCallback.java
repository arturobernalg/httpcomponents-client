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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestCallbackExecutorFutureCallback {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "test-callback-executor");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void completedCallbackRunsOnExecutorThread() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<String> resultRef = new AtomicReference<>();

        final FutureCallback<String> inner = new FutureCallback<String>() {
            @Override
            public void completed(final String result) {
                threadName.set(Thread.currentThread().getName());
                resultRef.set(result);
                latch.countDown();
            }

            @Override
            public void failed(final Exception ex) {
            }

            @Override
            public void cancelled() {
            }
        };

        final CallbackExecutorFutureCallback<String> wrapper =
                new CallbackExecutorFutureCallback<>(inner, executor);

        wrapper.completed("OK");

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("test-callback-executor", threadName.get());
        assertEquals("OK", resultRef.get());
    }

    @Test
    void failedCallbackRunsOnExecutorThread() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<Exception> exRef = new AtomicReference<>();

        final FutureCallback<String> inner = new FutureCallback<String>() {
            @Override
            public void completed(final String result) {
            }

            @Override
            public void failed(final Exception ex) {
                threadName.set(Thread.currentThread().getName());
                exRef.set(ex);
                latch.countDown();
            }

            @Override
            public void cancelled() {
            }
        };

        final CallbackExecutorFutureCallback<String> wrapper =
                new CallbackExecutorFutureCallback<>(inner, executor);

        final IOException cause = new IOException("boom");
        wrapper.failed(cause);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("test-callback-executor", threadName.get());
        assertSame(cause, exRef.get());
    }

    @Test
    void cancelledCallbackRunsOnExecutorThread() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();

        final FutureCallback<String> inner = new FutureCallback<String>() {
            @Override
            public void completed(final String result) {
            }

            @Override
            public void failed(final Exception ex) {
            }

            @Override
            public void cancelled() {
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            }
        };

        final CallbackExecutorFutureCallback<String> wrapper =
                new CallbackExecutorFutureCallback<>(inner, executor);

        wrapper.cancelled();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("test-callback-executor", threadName.get());
    }

    @Test
    void rejectedExecutionFallsBackToCallingThread() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        final String callingThread = Thread.currentThread().getName();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<String> resultRef = new AtomicReference<>();

        final FutureCallback<String> inner = new FutureCallback<String>() {
            @Override
            public void completed(final String result) {
                threadName.set(Thread.currentThread().getName());
                resultRef.set(result);
                latch.countDown();
            }

            @Override
            public void failed(final Exception ex) {
            }

            @Override
            public void cancelled() {
            }
        };

        final CallbackExecutorFutureCallback<String> wrapper =
                new CallbackExecutorFutureCallback<>(inner, executor);

        wrapper.completed("fallback");

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(callingThread, threadName.get());
        assertEquals("fallback", resultRef.get());
    }

    @Test
    void rejectedExecutionFallsBackForFailed() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        final String callingThread = Thread.currentThread().getName();
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<Exception> exRef = new AtomicReference<>();

        final FutureCallback<String> inner = new FutureCallback<String>() {
            @Override
            public void completed(final String result) {
            }

            @Override
            public void failed(final Exception ex) {
                threadName.set(Thread.currentThread().getName());
                exRef.set(ex);
            }

            @Override
            public void cancelled() {
            }
        };

        final CallbackExecutorFutureCallback<String> wrapper =
                new CallbackExecutorFutureCallback<>(inner, executor);

        final IOException cause = new IOException("boom");
        wrapper.failed(cause);

        assertEquals(callingThread, threadName.get());
        assertSame(cause, exRef.get());
    }

    @Test
    void rejectedExecutionFallsBackForCancelled() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        final String callingThread = Thread.currentThread().getName();
        final AtomicReference<String> threadName = new AtomicReference<>();

        final FutureCallback<String> inner = new FutureCallback<String>() {
            @Override
            public void completed(final String result) {
            }

            @Override
            public void failed(final Exception ex) {
            }

            @Override
            public void cancelled() {
                threadName.set(Thread.currentThread().getName());
            }
        };

        final CallbackExecutorFutureCallback<String> wrapper =
                new CallbackExecutorFutureCallback<>(inner, executor);

        wrapper.cancelled();

        assertEquals(callingThread, threadName.get());
    }

}
