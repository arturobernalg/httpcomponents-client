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
package org.apache.hc.client5.http.examples;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

/**
 * Demonstrates the use of
 * {@link org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder#setCallbackExecutor(java.util.concurrent.Executor)}
 * to dispatch terminal {@link FutureCallback} notifications on a dedicated,
 * fully customized application-level executor instead of an I/O dispatch thread.
 * <p>
 * The example constructs a {@link ThreadPoolExecutor} with explicit pool sizing,
 * a bounded work queue, a custom {@link ThreadFactory} with descriptive names
 * and daemon flags, and a keep-alive policy that shrinks the pool during idle
 * periods. This is representative of a real production setup where the callback
 * executor is tuned independently of the I/O reactor threads.
 * </p>
 * <p>
 * By offloading callback work onto this executor, the I/O reactor threads
 * remain free for transport-level I/O, while application logic in callbacks
 * (logging, persistence, downstream calls) runs on its own thread pool with
 * its own sizing and backpressure characteristics.
 * </p>
 */
public class AsyncClientCallbackExecutor {

    public static void main(final String[] args) throws Exception {

        // -----------------------------------------------------------
        // 1. I/O reactor configuration — transport-level threads
        // -----------------------------------------------------------
        final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setSoTimeout(Timeout.ofSeconds(5))
                .setIoThreadCount(2)
                .build();

        // -----------------------------------------------------------
        // 2. Callback executor — application-level thread pool
        //
        //    Core pool:  2 threads always warm
        //    Max pool:   4 threads under burst load
        //    Keep-alive: idle threads above core size shrink after 30 s
        //    Queue:      bounded to 64 pending callbacks
        //    Threads:    named, daemon, easy to spot in thread dumps
        // -----------------------------------------------------------
        final AtomicLong threadCounter = new AtomicLong();
        final ThreadFactory callbackThreadFactory = r -> {
            final Thread t = new Thread(r, "http-callback-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };

        final ExecutorService callbackExecutor = new ThreadPoolExecutor(
                2,                                      // corePoolSize
                4,                                      // maximumPoolSize
                30L, TimeUnit.SECONDS,                  // keepAliveTime
                new LinkedBlockingQueue<>(64),           // workQueue
                callbackThreadFactory);                  // threadFactory

        // -----------------------------------------------------------
        // 3. Build the async client — wire both together
        // -----------------------------------------------------------
        final CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .setIOReactorConfig(ioReactorConfig)
                .setCallbackExecutor(callbackExecutor)
                .build();

        client.start();

        // -----------------------------------------------------------
        // 4. Execute requests — callbacks run on the callback pool
        // -----------------------------------------------------------
        final HttpHost target = new HttpHost("https", "httpbin.org");
        final String[] requestUris = new String[] {
                "/get", "/ip", "/user-agent", "/headers"
        };

        final CountDownLatch latch = new CountDownLatch(requestUris.length);

        for (final String requestUri : requestUris) {
            final SimpleHttpRequest request = SimpleRequestBuilder.get()
                    .setHttpHost(target)
                    .setPath(requestUri)
                    .build();

            System.out.println("Executing request " + request);
            client.execute(
                    SimpleRequestProducer.create(request),
                    SimpleResponseConsumer.create(),
                    new FutureCallback<SimpleHttpResponse>() {

                        @Override
                        public void completed(final SimpleHttpResponse response) {
                            System.out.println(request + " -> " + new StatusLine(response)
                                    + " [" + Thread.currentThread().getName() + "]");
                            latch.countDown();
                        }

                        @Override
                        public void failed(final Exception ex) {
                            System.out.println(request + " -> " + ex
                                    + " [" + Thread.currentThread().getName() + "]");
                            latch.countDown();
                        }

                        @Override
                        public void cancelled() {
                            System.out.println(request + " cancelled"
                                    + " [" + Thread.currentThread().getName() + "]");
                            latch.countDown();
                        }

                    });
        }

        latch.await();

        // -----------------------------------------------------------
        // 5. Shutdown — client first, then the callback executor
        //
        //    The client does not own the executor lifecycle; the
        //    caller is responsible for shutting it down.
        // -----------------------------------------------------------
        System.out.println("Shutting down");
        client.close(CloseMode.GRACEFUL);
        callbackExecutor.shutdown();
        callbackExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }

}
