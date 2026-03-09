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

import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.util.Timeout;

/**
 * This example demonstrates a complete request execution timeout
 * (request deadline) as distinct from the per-read response timeout.
 * <p>
 * The /drip endpoint sends small chunks over time. A response timeout of 2 seconds
 * is not exceeded because data keeps arriving, but the total exchange lasts longer
 * than 3 seconds and is therefore terminated by the execution timeout.
 */
public class AsyncClientExecutionTimeout {

    public static void main(final String[] args) throws Exception {
        final RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(2))
                .setExecutionTimeout(Timeout.ofSeconds(3))
                .build();

        try (final CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build()) {

            client.start();

            final SimpleHttpRequest request = SimpleRequestBuilder.get()
                    .setHttpHost(new HttpHost("https", "httpbin.org"))
                    .setPath("/drip?numbytes=10&duration=10&delay=0&code=200")
                    .build();

            System.out.println("Executing request " + request);
            System.out.println("responseTimeout = 2s");
            System.out.println("executionTimeout = 3s");
            System.out.println();

            final Future<SimpleHttpResponse> future = client.execute(
                    SimpleRequestProducer.create(request),
                    SimpleResponseConsumer.create(),
                    new FutureCallback<SimpleHttpResponse>() {

                        @Override
                        public void completed(final SimpleHttpResponse response) {
                            System.out.println("----------------------------------------");
                            System.out.println(request + " -> " + new StatusLine(response));
                            System.out.println(response.getBody());
                        }

                        @Override
                        public void failed(final Exception ex) {
                            System.out.println("----------------------------------------");
                            System.out.println(request + " -> " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                            System.out.println("The response kept making progress, so responseTimeout did not fire.");
                            System.out.println("The overall request exceeded executionTimeout and was aborted.");
                        }

                        @Override
                        public void cancelled() {
                            System.out.println("----------------------------------------");
                            System.out.println(request + " cancelled");
                        }

                    });

            try {
                future.get();
            } catch (final ExecutionException ex) {
                if (!(ex.getCause() instanceof SocketTimeoutException)) {
                    throw ex;
                }
            }
        }
    }

}