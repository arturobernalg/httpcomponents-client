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

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.io.CloseMode;

/**
 * Example of HTTP/2 multiplexed request execution through an HTTP CONNECT proxy tunnel.
 */
public class AsyncClientH2ViaHttpProxyTunnel {

    public static void main(final String[] args) throws Exception {
        final String proxyHost = args.length > 0 ? args[0] : "localhost";
        final int proxyPort = args.length > 1 ? Integer.parseInt(args[1]) : 8888;
        final String targetHost = args.length > 2 ? args[2] : "nghttp2.org";
        final HttpHost proxy = new HttpHost(proxyHost, proxyPort);
        final HttpHost target = new HttpHost("https", targetHost);

        final CloseableHttpAsyncClient client = HttpAsyncClients.customHttp2()
                .setRoutePlanner(new DefaultProxyRoutePlanner(proxy))
                .build();
        client.start();

        System.out.println("Testing HTTP/2 tunnel via proxy " + proxy + " to " + target);

        final String[] requestUris = new String[] {"/httpbin/ip", "/httpbin/user-agent", "/httpbin/headers"};
        final CountDownLatch latch = new CountDownLatch(requestUris.length);
        for (final String requestUri : requestUris) {
            final SimpleHttpRequest request = SimpleRequestBuilder.get()
                    .setHttpHost(target)
                    .setPath(requestUri)
                    .build();
            client.execute(
                    SimpleRequestProducer.create(request),
                    SimpleResponseConsumer.create(),
                    new FutureCallback<SimpleHttpResponse>() {
                        @Override
                        public void completed(final SimpleHttpResponse response) {
                            System.out.println(request + "->" + new StatusLine(response));
                            System.out.println(response.getBodyText());
                            latch.countDown();
                        }

                        @Override
                        public void failed(final Exception ex) {
                            System.out.println(request + "->" + ex);
                            latch.countDown();
                        }

                        @Override
                        public void cancelled() {
                            System.out.println(request + " cancelled");
                            latch.countDown();
                        }
                    });
        }

        latch.await();
        System.out.println("Tunnel test complete");
        client.close(CloseMode.GRACEFUL);
    }
}
