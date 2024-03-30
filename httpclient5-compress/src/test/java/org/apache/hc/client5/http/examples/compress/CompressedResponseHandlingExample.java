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

package org.apache.hc.client5.http.examples.compress;


import org.apache.hc.client5.http.compress.CompressHttpClients;
import org.apache.hc.client5.http.compress.CompressionAlgorithm;
import org.apache.hc.client5.http.compress.CompressorFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.message.StatusLine;


/**
 * Demonstrates handling of HTTP responses with content compression using Apache HttpClient.
 * <p>
 * This example sets up a local test server that simulates compressed HTTP responses. It then
 * creates a custom HttpClient configured to handle compression. The client makes a request to
 * the test server, receives a compressed response, and decompresses the content to verify the
 * process.
 * <p>
 * The main focus of this example is to illustrate the use of a custom HttpClient that can
 * handle compressed HTTP responses transparently, simulating a real-world scenario where
 * responses from a server might be compressed to reduce bandwidth usage.
 */
public class CompressedResponseHandlingExample {

    public static void main(final String[] args) {
        try (final AutoCloseableTestServer server = new AutoCloseableTestServer()) {

            // Register a handler that simulates a compressed response
            server.registerHandler("/compressed", (request, response, context) -> {
                final String uncompressedContent = "This is the uncompressed response content";
                // Compress the response content using the specified compression algorithm
                response.setEntity(compress(uncompressedContent, CompressionAlgorithm.GZIP));
                // Indicate the content encoding in the response header
                response.addHeader("Content-Encoding", CompressionAlgorithm.GZIP.getIdentifier());
            });

            final HttpHost target = server.getHttpHost();

            try (final CloseableHttpClient httpclient = CompressHttpClients
                    .custom()
                    .build()) {
                final ClassicHttpRequest httpget1 = ClassicRequestBuilder.get()
                        .setHttpHost(target)
                        .setPath("/compressed")
                        .build();

                System.out.println("Executing request " + httpget1.getMethod() + " " + httpget1.getUri());
                httpclient.execute(httpget1, response -> {
                    System.out.println("----------------------------------------");
                    System.out.println(httpget1 + "->" + new StatusLine(response));
                    final HttpEntity responseEntity = response.getEntity();
                    // Decompress and read the response content
                    final String responseBody = EntityUtils.toString(responseEntity);
                    System.out.println(responseBody.split("\n")[0]); // Simplified logging for demonstration

                    return null;
                });
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Compresses the given data using the specified compression algorithm.
     *
     * @param data      The data to compress.
     * @param algorithm The compression algorithm to use.
     * @return An HttpEntity representing the compressed data.
     */
    private static HttpEntity compress(final String data, final CompressionAlgorithm algorithm) {
        final StringEntity originalEntity = new StringEntity(data, ContentType.TEXT_PLAIN);
        return CompressorFactory.INSTANCE.compressEntity(originalEntity, algorithm.getIdentifier());
    }
}
