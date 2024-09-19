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
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorInputStream;
import org.apache.commons.compress.compressors.deflate.DeflateParameters;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory class for managing compression and decompression of HTTP entities using different compression formats.
 * <p>
 * This factory uses a cache to optimize access to available input and output stream providers for compression formats.
 * It also allows the use of aliases (e.g., "gzip" and "x-gzip") and automatically formats the compression names
 * to ensure consistency.
 * </p>
 *
 * <p>
 * Supported compression formats include gzip, deflate, and other available formats provided by the
 * {@link CompressorStreamFactory}.
 * </p>
 *
 * <p>
 * This class is thread-safe and uses {@link AtomicReference} to cache the available input and output stream providers.
 * </p>
 */
public class CompressorFactory {

    private static final Logger LOG = LoggerFactory.getLogger(CompressorFactory.class);
    /**
     * Singleton instance of the factory.
     */
    public static final CompressorFactory INSTANCE = new CompressorFactory();

    private final CompressorStreamFactory compressorStreamFactory = new CompressorStreamFactory();
    private final AtomicReference<Set<String>> inputProvidersCache = new AtomicReference<>();
    private final AtomicReference<Set<String>> outputProvidersCache = new AtomicReference<>();
    private final Map<String, String> formattedNameCache = new ConcurrentHashMap<>();

    private static final Map<String, String> COMPRESSION_ALIASES;
    static {
        final Map<String, String> aliases = new HashMap<>();
        aliases.put("gzip", "gz");
        aliases.put("x-gzip", "gz");
        aliases.put("compress", "z");
        COMPRESSION_ALIASES = Collections.unmodifiableMap(aliases);
    }

    /**
     * Returns a set of available input stream compression providers.
     *
     * @return a set of available input stream compression providers in lowercase.
     */
    public Set<String> getAvailableInputProviders() {
        return getAvailableProviders(inputProvidersCache, false);
    }

    /**
     * Returns a set of available output stream compression providers.
     *
     * @return a set of available output stream compression providers in lowercase.
     */
    public Set<String> getAvailableOutputProviders() {
        return getAvailableProviders(outputProvidersCache, true);
    }

    /**
     * Returns the formatted name of the provided compression format.
     * <p>
     * If the provided name matches an alias (e.g., "gzip" or "x-gzip"), the method will return the standard name.
     * </p>
     *
     * @param name the compression format name.
     * @return the formatted name, or the original name if no alias is found.
     * @throws IllegalArgumentException if the name is null or empty.
     */
    public String getFormattedName(final String name) {
        if (name == null || name.isEmpty()) {
            LOG.warn("Compression name is null or empty");
            return null;
        }
        final String lowerCaseName = name.toLowerCase(Locale.ROOT);
        return formattedNameCache.computeIfAbsent(lowerCaseName, key -> COMPRESSION_ALIASES.getOrDefault(key, key));
    }


    /**
     * Creates an input stream for the specified compression format and decompresses the provided input stream.
     * <p>
     * This method uses the specified compression name to decompress the input stream and supports the "nowrap" option
     * for deflate streams.
     * </p>
     *
     * @param name        the compression format.
     * @param inputStream the input stream to decompress.
     * @param nowrap      if true, disables the zlib header and trailer for deflate streams.
     * @return the decompressed input stream, or the original input stream if the format is not supported.
     */
    public InputStream getCompressorInputStream(final String name, final InputStream inputStream, final boolean nowrap) {
        Args.notNull(inputStream, "InputStream");
        Args.notNull(name, "name");

        final String formattedName = getFormattedName(name);
        return isSupported(formattedName, false)
                ? createCompressorInputStream(formattedName, inputStream, nowrap)
                : inputStream;
    }

    /**
     * Creates an output stream for the specified compression format and compresses the provided output stream.
     *
     * @param name         the compression format.
     * @param outputStream the output stream to compress.
     * @return the compressed output stream, or the original output stream if the format is not supported.
     */
    public OutputStream getCompressorOutputStream(final String name, final OutputStream outputStream) {
        final String formattedName = getFormattedName(name);
        return isSupported(formattedName, true)
                ? createCompressorOutputStream(formattedName, outputStream)
                : outputStream;
    }

    /**
     * Compresses the provided HTTP entity using the specified compression format.
     *
     * @param entity          the HTTP entity to compress.
     * @param contentEncoding the compression format.
     * @return a compressed {@link HttpEntity}, or {@code null} if the compression format is unsupported.
     */
    public HttpEntity compressEntity(final HttpEntity entity, final String contentEncoding) {
        Args.notNull(entity, "Entity");
        Args.notNull(contentEncoding, "Content Encoding");
        if (!isSupported(contentEncoding, true)) {
            LOG.warn("Unsupported compression type: {}", contentEncoding);
            return null;
        }
        return new CompressingEntity(entity, contentEncoding);
    }

    /**
     * Decompresses the provided HTTP entity using the specified compression format.
     *
     * @param entity          the HTTP entity to decompress.
     * @param contentEncoding the compression format.
     * @return a decompressed {@link HttpEntity}, or {@code null} if the compression format is unsupported.
     */
    public HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding) {
        return decompressEntity(entity, contentEncoding, false);
    }

    /**
     * Decompresses the provided HTTP entity using the specified compression format with the option for deflate streams.
     *
     * @param entity          the HTTP entity to decompress.
     * @param contentEncoding the compression format.
     * @param nowrap          if true, disables the zlib header and trailer for deflate streams.
     * @return a decompressed {@link HttpEntity}, or {@code null} if the compression format is unsupported.
     */
    public HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding, final boolean nowrap) {
        Args.notNull(entity, "Entity");
        Args.notNull(contentEncoding, "Content Encoding");
        if (!isSupported(contentEncoding, false)) {
            LOG.warn("Unsupported decompression type: {}", contentEncoding);
            return null;
        }
        return new DecompressEntity(entity, contentEncoding, nowrap);
    }

    /**
     * Creates a compressor input stream for the given compression format and input stream.
     * <p>
     * This method handles the special case for deflate compression where the zlib header can be skipped.
     * </p>
     *
     * @param name        the compression format.
     * @param inputStream the input stream to decompress.
     * @param nowrap      if true, disables the zlib header and trailer for deflate streams.
     * @return a decompressed input stream, or null if an error occurs.
     */
    private InputStream createCompressorInputStream(final String name, final InputStream inputStream, final boolean nowrap) {
        try {
            if ("deflate".equalsIgnoreCase(name)) {
                final DeflateParameters parameters = new DeflateParameters();
                parameters.setWithZlibHeader(nowrap);
                return new DeflateCompressorInputStream(inputStream, parameters);
            }
            return compressorStreamFactory.createCompressorInputStream(name, inputStream, true);
        } catch (final Exception ex) {
            LOG.warn("Could not create compressor {} input stream", name, ex);
            return null;
        }
    }

    /**
     * Determines if the specified compression format is supported for either input or output streams.
     *
     * @param name     the compression format.
     * @param isOutput if true, checks if the format is supported for output; otherwise, checks for input support.
     * @return true if the format is supported, false otherwise.
     */
    private boolean isSupported(final String name, final boolean isOutput) {
        final String formattedName = getFormattedName(name);
        return isOutput
                ? getAvailableOutputProviders().contains(formattedName)
                : getAvailableInputProviders().contains(formattedName);
    }

    /**
     * Creates a compressor output stream for the given compression format and output stream.
     *
     * @param name         the compression format.
     * @param outputStream the output stream to compress.
     * @return a compressed output stream, or null if an error occurs.
     */
    private OutputStream createCompressorOutputStream(final String name, final OutputStream outputStream) {
        try {
            return compressorStreamFactory.createCompressorOutputStream(name, outputStream);
        } catch (final Exception ex) {
            LOG.warn("Could not create compressor {} output stream", name, ex);

            return null;
        }
    }

    /**
     * Retrieves the available compression providers for input or output streams.
     * <p>
     * This method uses a cache to avoid redundant lookups and ensures the providers are formatted in lowercase.
     * </p>
     *
     * @param cache    the cache that stores the available providers.
     * @param isOutput if true, retrieves available providers for output streams; otherwise, for input streams.
     * @return a set of available compression providers in lowercase.
     */
    private Set<String> getAvailableProviders(final AtomicReference<Set<String>> cache, final boolean isOutput) {
        return cache.updateAndGet(existing -> existing != null ? existing : fetchAvailableProviders(isOutput));
    }

    /**
     * Fetches the available compression providers by querying the {@link CompressorStreamFactory}.
     *
     * @param isOutput if true, fetches available providers for output streams; otherwise, for input streams.
     * @return a set of available compression providers in lowercase.
     */
    private Set<String> fetchAvailableProviders(final boolean isOutput) {
        return (isOutput
                ? CompressorStreamFactory.findAvailableCompressorOutputStreamProviders()
                : CompressorStreamFactory.findAvailableCompressorInputStreamProviders())
                .keySet().stream()
                .map(String::toLowerCase)
                .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));
    }
}


