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

package org.apache.hc.client5.http.entity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Function;

import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;

/**
 * An {@link HttpEntity} wrapper that compresses the content using a specified
 * compressor function. The compression type is specified by the {@code compressionType}.
 * This class is used to apply compression to the content of an HTTP entity.
 *
 * @since 5.4
 */
public class CompressEntity extends HttpEntityWrapper {

    /**
     * Function that applies the compression to the output stream.
     */
    private final Function<OutputStream, OutputStream> compressorFunction;

    /**
     * The type of compression to be used.
     */
    private final String compressionType;

    /**
     * Constructs a new {@code CompressEntity}.
     *
     * @param wrappedEntity      the entity to be compressed
     * @param compressorFunction the function that applies the compression
     * @param compressionType    the type of compression to be used
     */
    public CompressEntity(final HttpEntity wrappedEntity,
                          final Function<OutputStream, OutputStream> compressorFunction,
                          final String compressionType) {
        super(wrappedEntity);
        this.compressorFunction = compressorFunction;
        this.compressionType = compressionType;
    }

    /**
     * Writes the compressed content to the provided output stream.
     * <p>
     * This method applies the compression function to the output stream, then writes the
     * content of the wrapped entity to the resulting compressed stream. The compressor function
     * is applied to the provided output stream to get the appropriate compressor, and the
     * wrapped entity's content is written to this compressor stream.
     * </p>
     *
     * @param outstream the output stream to write to
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void writeTo(final OutputStream outstream) throws IOException {
        final OutputStream compressorStream = compressorFunction.apply(outstream);
        super.writeTo(compressorStream);
        compressorStream.close();
    }


    /**
     * Returns the content encoding type used for compression.
     *
     * @return the content encoding type
     */
    @Override
    public String getContentEncoding() {
        return compressionType;
    }

    /**
     * Returns the content length. This implementation always returns -1, indicating
     * that the content length is unknown.
     *
     * @return the content length, which is always -1
     */
    @Override
    public long getContentLength() {
        return -1;
    }

    /**
     * Indicates whether the content is chunked. This implementation always returns
     * {@code true} as compressed content is generally chunked.
     *
     * @return {@code true} if the content is chunked, {@code false} otherwise
     */
    @Override
    public boolean isChunked() {
        return true;
    }
}

