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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.function.Function;

import org.apache.hc.core5.http.HttpEntity;

/**
 * Interface for providing compression and decompression capabilities for HTTP entities.
 * Implementations of this interface are responsible for supporting various compression
 * formats and handling the compression and decompression of HTTP entity streams.
 * <p>
 * This interface defines methods for obtaining the names of supported compression formats,
 * retrieving functions for compressing and decompressing streams, and compressing or
 * decompressing HTTP entities.
 * </p>
 * <p>
 * Implementations of this interface must ensure thread-safety and may utilize internal
 * caches for efficiency. The {@link #getCompressorInput(String, boolean)} and
 * {@link #getCompressorOutputStream(String)} methods return functions that wrap
 * {@link InputStream} and {@link OutputStream} objects with appropriate compression
 * or decompression streams.
 * </p>
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * CompressorProvider compressorProvider = new MyCompressorProvider();
 * Function<InputStream, InputStream> gzipDecompressor = compressorProvider.getCompressorInput("gzip", true);
 * InputStream decompressedStream = gzipDecompressor.apply(compressedInputStream);
 * }
 * </pre>
 * </p>
 * <p>
 * This interface is designed to be extensible for supporting additional compression formats
 * and customizable decompression behavior. It provides a unified API for handling compression
 * in HTTP communications, which can be particularly useful for optimizing data transfer
 * and reducing bandwidth usage.
 * </p>a
 *
 * @since 5.4
 */
public interface CompressorProvider {

    /**
     * Returns the names of the supported input stream compressors.
     *
     * @return a set of supported input stream compressor names
     */
    Set<String> getInputStreamCompressorNames();

    /**
     * Returns the names of the supported output stream compressors.
     *
     * @return a set of supported output stream compressor names
     */
    Set<String> getOutputStreamCompressorNames();

    /**
     * Retrieves a function that provides a decompressed input stream for the specified compressor name.
     *
     * @param name                   the name of the compressor to use for decompression
     * @return a function that decompresses an input stream, or {@code null} if the compressor is not supported
     */
    Function<InputStream, InputStream> getCompressorInput(final String name);

    /**
     * Retrieves a function that provides a compressed output stream for the specified compressor name.
     *
     * @param name the name of the compressor to use for compression
     * @return a function that compresses an output stream, or {@code null} if the compressor is not supported
     */
    Function<OutputStream, OutputStream> getCompressorOutputStream(String name);

    /**
     * Decompresses the given HTTP entity using the specified content encoding.
     *
     * @param entity          the HTTP entity to decompress
     * @param contentEncoding the content encoding to use for decompression
     * @return a decompressed HTTP entity, or {@code null} if the content encoding is not supported
     */
    HttpEntity decompressEntity(HttpEntity entity, String contentEncoding);

    /**
     * Compresses the given HTTP entity using the specified content encoding.
     *
     * @param entity          the HTTP entity to compress
     * @param contentEncoding the content encoding to use for compression
     * @return a compressed HTTP entity, or {@code null} if the content encoding is not supported
     */
    HttpEntity compressEntity(HttpEntity entity, String contentEncoding);
}