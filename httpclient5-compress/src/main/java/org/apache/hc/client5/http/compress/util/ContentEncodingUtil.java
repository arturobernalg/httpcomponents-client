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

package org.apache.hc.client5.http.compress.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;

import org.apache.hc.client5.http.compress.CompressorFactory;
import org.apache.hc.client5.http.compress.entity.CompressingEntity;
import org.apache.hc.client5.http.compress.entity.DecompressingEntity;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ContentEncodingUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ContentEncodingUtil.class);

    public static HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding, final boolean decompressConcatenated) {
        Args.notNull(entity, "Entity");
        Args.notNull(contentEncoding, "Content Encoding");

        if (!CompressorFactory.INSTANCE.isSupportedInput(contentEncoding)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unsupported decompression type: {}", contentEncoding);
            }
            return null;
        }

        final Function<InputStream, InputStream> decompressorFunction = CompressorFactory.INSTANCE.getCompressorInput(contentEncoding, decompressConcatenated);
        return new DecompressingEntity(entity, decompressorFunction);
    }

    public static HttpEntity compressEntity(final HttpEntity entity, final String contentEncoding) {
        Args.notNull(entity, "Entity");
        Args.notNull(contentEncoding, "Content Encoding");

        if (!CompressorFactory.INSTANCE.isSupportedOutput(contentEncoding)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unsupported compression type: {}", contentEncoding);
            }
            return null;
        }

        final Function<OutputStream, OutputStream> compressorFunction = CompressorFactory.INSTANCE.getCompressorOutputStream(contentEncoding);
        return new CompressingEntity(entity, compressorFunction, contentEncoding);
    }


    public static HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding) {
        return decompressEntity(entity, contentEncoding, true);
    }

}
