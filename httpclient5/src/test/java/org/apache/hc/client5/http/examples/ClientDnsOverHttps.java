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

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.DohDnsResolver;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Timeout;

/**
 * Demonstrates DNS-over-HTTPS as an opt-in {@link DnsResolver} for both classic and async connection managers.
 */
public final class ClientDnsOverHttps {

    private ClientDnsOverHttps() {
    }

    public static void main(final String[] args) throws Exception {
        final URI dohEndpoint = URI.create(args.length > 0 ? args[0] : "https://dns.google/dns-query");
        final URI targetUri = URI.create(args.length > 1 ? args[1] : "https://httpbin.org/get");
        final String targetHost = Args.notBlank(targetUri.getHost(), "Target host");

        final DnsResolver dohResolver = DohDnsResolver.builder()
                .setServiceUri(dohEndpoint)
                .setUseGet(true)
                .setConnectTimeout(Timeout.ofSeconds(3))
                .setResponseTimeout(Timeout.ofSeconds(3))
                .build();

        final InetAddress[] addresses = dohResolver.resolve(targetHost);
        System.out.println("DoH resolved " + targetHost + " to " + Arrays.toString(addresses));

        final PoolingHttpClientConnectionManager classicConnectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(dohResolver)
                        .build();

        try (CloseableHttpClient classicClient = HttpClients.custom()
                .setConnectionManager(classicConnectionManager)
                .build()) {

            final HttpGet request = new HttpGet(targetUri);
            classicClient.execute(request, response -> {
                System.out.println("Classic -> " + new StatusLine(response));
                if (response.getCode() == HttpStatus.SC_OK) {
                    EntityUtils.consume(response.getEntity());
                }
                return null;
            });
        }

        final PoolingAsyncClientConnectionManager asyncConnectionManager =
                PoolingAsyncClientConnectionManagerBuilder.create()
                        .setDnsResolver(dohResolver)
                        .build();

        try (CloseableHttpAsyncClient asyncClient = HttpAsyncClients.custom()
                .setConnectionManager(asyncConnectionManager)
                .build()) {

            asyncClient.start();

            final SimpleHttpRequest request = SimpleRequestBuilder.get(targetUri).build();
            final Future<SimpleHttpResponse> future = asyncClient.execute(
                    SimpleRequestProducer.create(request),
                    SimpleResponseConsumer.create(),
                    null);

            final SimpleHttpResponse response = future.get();
            System.out.println("Async -> " + new StatusLine(response));
        }
    }
}