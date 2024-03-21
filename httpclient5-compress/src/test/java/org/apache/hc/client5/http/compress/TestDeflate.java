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

package org.apache.hc.client5.http.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

import org.apache.hc.client5.http.compress.util.CompressionAlgorithm;
import org.apache.hc.client5.http.compress.util.ContentEncodingUtil;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestDeflate {

    @Test
    public void testDecompress() throws Exception {

        final String s = "some kind of text";
        final byte[] input = s.getBytes(StandardCharsets.US_ASCII);

        // Compress the bytes
        final byte[] compressed = new byte[input.length * 2];
        final Deflater compresser = new Deflater();
        compresser.setInput(input);
        compresser.finish();
        final int len = compresser.deflate(compressed);

        final HttpEntity entity = ContentEncodingUtil.decompressEntity(new ByteArrayEntity(compressed, 0, len, ContentType.APPLICATION_OCTET_STREAM), CompressionAlgorithm.DEFLATE.getIdentifier());
        Assertions.assertEquals(s, EntityUtils.toString(entity));
    }

    @Test
    public void testCompress() throws Exception {
        final String originalContent = "some kind of text";
        final StringEntity originalEntity = new StringEntity(originalContent, ContentType.TEXT_PLAIN);

        // Compress the original content
        final HttpEntity compressedEntity = ContentEncodingUtil.compressEntity(originalEntity, CompressionAlgorithm.DEFLATE.getIdentifier());

        // Write the compressed data to a ByteArrayOutputStream
        final ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        compressedEntity.writeTo(compressedOut);

        // Manually decompress the compressed data
        final ByteArrayInputStream compressedIn = new ByteArrayInputStream(compressedOut.toByteArray());
        final InflaterInputStream inflaterInputStream = new InflaterInputStream(compressedIn);
        final ByteArrayOutputStream decompressedOut = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = inflaterInputStream.read(buffer)) != -1) {
            decompressedOut.write(buffer, 0, bytesRead);
        }
        final String decompressedContent = decompressedOut.toString(StandardCharsets.UTF_8.name());

        // Assertions to ensure the decompressed content matches the original content
        Assertions.assertEquals(originalContent, decompressedContent, "The decompressed content should match the original content.");
    }


    @Test
    public void testDynamicallyCompressDecompress() throws Exception {

        final String originalContent = "some kind of text";
        final StringEntity originalEntity = new StringEntity(originalContent, ContentType.TEXT_PLAIN);

        final HttpEntity compressedEntity = ContentEncodingUtil.compressEntity(originalEntity, CompressionAlgorithm.DEFLATE.getIdentifier());

        final ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        compressedEntity.writeTo(compressedOut);

        final ByteArrayEntity out = new ByteArrayEntity(compressedOut.toByteArray(), ContentType.APPLICATION_OCTET_STREAM);

        final HttpEntity decompressedEntity = ContentEncodingUtil.decompressEntity(out, CompressionAlgorithm.DEFLATE.getIdentifier());

        final String decompressedContent = EntityUtils.toString(decompressedEntity, StandardCharsets.UTF_8);

        Assertions.assertEquals(originalContent, decompressedContent, "The decompressed content should match the original content.");
    }

}
