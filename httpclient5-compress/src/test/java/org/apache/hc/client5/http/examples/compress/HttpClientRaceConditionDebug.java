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


import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.compress.CompressHttpClients;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;

/**
 * @author abernal {@literal <arturo.bernal@unifiedpost.com>}
 */
public class HttpClientRaceConditionDebug {

    public static void main(final String[] args) {

        try (final CloseableHttpClient client = CompressHttpClients.custom()
                //.setContentDecoderRegistry2(CompressionRegistry.INSTANCE.getContentDecoderMap())
                .setConnectionManager(new BasicHttpClientConnectionManager())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .build())
                .build()) {

            //     testDynamicCompression();
            executeAndLog(client);
//            executeAndLog(client);
            //  executePostAndLog(client);
        } catch (final Exception e) {
            e.printStackTrace();
        }

    }

    private static void executeAndLog(final CloseableHttpClient client) throws URISyntaxException {
        final URI uri = new URIBuilder()
                .setHost("localhost")
                .setPort(3000)
                .setScheme(URIScheme.HTTP.id)
                .setPath("/anything")
                .build();

        final HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader("Accept-Encoding", "br, gzip, deflate");
        try (final CloseableHttpResponse response = client.execute(httpGet)) {
            final HttpEntity entity = response.getEntity();
            final String responseBody = EntityUtils.toString(entity);
            System.out.println(uri + " -> " + responseBody.split("\n")[0]); // Simplified logging for demonstration
        } catch (final Exception ex) {
            System.out.println(uri + " -> " + ex.getMessage());
        }
    }

    private static void executePostAndLog(final CloseableHttpClient client) throws Exception {
        final URI uri = new URIBuilder()
                .setHost("localhost")
                .setPort(3000)
                .setScheme("http")
                .setPath("/data")
                .build();

        final HttpPost httpPost = new HttpPost(uri);
        httpPost.setHeader("Content-Encoding", "gzip");
        httpPost.setHeader("Accept-Encoding", "br, gzip, deflate");

        // Sample data to send
        final String dataToSend = "{\"message\": \"This is the data to send\"}";
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (final GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
            gzipOutputStream.write(dataToSend.getBytes(StandardCharsets.UTF_8));
        }
        final byte[] compressedData = byteArrayOutputStream.toByteArray();

        // Assuming the original content type is JSON
        final ContentType contentType = ContentType.APPLICATION_JSON;

        // Setting the compressed data and content type as the request body
        final HttpEntity entity = new ByteArrayEntity(compressedData, contentType, "gzip");

        httpPost.setEntity(entity);

        try (final CloseableHttpResponse response = client.execute(httpPost)) {
            final HttpEntity responseEntity = response.getEntity();
            final String responseBody = EntityUtils.toString(responseEntity);
            System.out.println(uri + " -> " + responseBody.split("\n")[0]); // Simplified logging for demonstration
        } catch (final Exception ex) {
            System.out.println(uri + " -> " + ex.getMessage());
        }
    }

//    private static void testDynamicCompression() throws Exception {
//        // Create a simple entity for demonstration
//        HttpEntity entity = new StringEntity("This is some text to compress", ContentType.TEXT_PLAIN);
//
//        // Specify the desired compression type
//        String ContentEncoding = "gz";
//
//
//
//        // Compress the entity using the CompressionRegistry and CompressionUtil
//        HttpEntity compressedEntity = CompressionUtil.compressEntity(entity, ContentEncoding);
//
//        // Assertions to verify the compressedEntity behavior
//        Assertions.assertTrue(compressedEntity.isChunked(), "Expected the entity to be chunked due to compression.");
//        Assertions.assertEquals(-1, compressedEntity.getContentLength(), "Expected unknown content length due to compression.");
//
//        // In this setup, you might need a way to check or store the compression type within the CompressingHttpEntity
//        // This could involve extending CompressingHttpEntity to store and return the compression type or similar.
//        // Assertions.assertEquals("gzip", compressedEntity.getContentEncoding().getValue(), "Expected content encoding to be gzip.");
//    }

}