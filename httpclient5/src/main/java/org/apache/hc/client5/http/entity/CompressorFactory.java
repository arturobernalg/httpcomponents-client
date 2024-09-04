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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides compression and decompression capabilities for HTTP entities using various
 * compression formats supported by Apache Commons Compress.
 * This class serves as a factory for creating input and output streams that handle
 * the compression and decompression of data streams.
 * <p>
 * It utilizes a cache for formatted compressor names and caches for input and output
 * compressor providers to optimize performance. The available compressors are determined
 * dynamically at runtime by querying the {@link CompressorStreamFactory}.
 * </p>
 */
public class CompressorFactory implements CompressorProvider {

    /**
     * Logger for logging information and errors.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CompressorFactory.class);

    /**
     * Singleton instance of the CompressorFactory.
     */
    public static final CompressorFactory INSTANCE = new CompressorFactory();

    /**
     * Instance of CompressorStreamFactory for creating compressor streams.
     */
    private final CompressorStreamFactory compressorStreamFactory = new CompressorStreamFactory();

    /**
     * Cache for input stream compressor providers to optimize performance.
     */
    private final AtomicReference<Set<String>> inputProvidersCache = new AtomicReference<>();

    /**
     * Cache for output stream compressor providers to optimize performance.
     */
    private final AtomicReference<Set<String>> outputProvidersCache = new AtomicReference<>();

    /**
     * Cache for formatted compressor names to avoid redundant formatting operations.
     */
    private final Map<String, String> formattedNameCache = new HashMap<>();


    @Override
    public Function<InputStream, InputStream> getCompressorInput(final String name) {
        final String formattedName = getFormattedName(name);

        if (!isInputSupported(formattedName)) {
            return null;
        }
        return inputStream -> createCompressorInputStream(formattedName, inputStream);
    }

    @Override
    public Function<OutputStream, OutputStream> getCompressorOutputStream(final String name) {
        final String formattedName = getFormattedName(name);

        if (!isOutputSupported(formattedName)) {
            return null;
        }

        return outputStream -> createCompressorOutputStream(formattedName, outputStream);
    }

    @Override
    public Set<String> getInputStreamCompressorNames() {
        return getAvailableInputProviders().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getOutputStreamCompressorNames() {
        return getAvailableOutputProviders().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    @Override
    public HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding) {
        Args.notNull(entity, "Entity");
        Args.notNull(contentEncoding, "Content Encoding");

        if (!isInputSupported(contentEncoding)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unsupported decompression type: {}", contentEncoding);
            }
            return null;
        }
        final Function<InputStream, InputStream> decompressorFunction = getCompressorInput(contentEncoding);
        return new DecompressEntity(entity, decompressorFunction, contentEncoding);
    }

    @Override
    public HttpEntity compressEntity(final HttpEntity entity, final String contentEncoding) {
        Args.notNull(entity, "Entity");
        Args.notNull(contentEncoding, "Content Encoding");
        if (!isOutputSupported(contentEncoding)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unsupported compression type: {}", contentEncoding);
            }
            return null;
        }
        final Function<OutputStream, OutputStream> compressorFunction = getCompressorOutputStream(contentEncoding);
        return new CompressEntity(entity, compressorFunction, contentEncoding);
    }

    /**
     * Creates a compressor input stream for the specified compressor name, input stream, and
     * decompression concatenation flag.
     * <p>
     * For the "DEFLATE" compression, we use the {@link DeflateInputStream} from Apache HTTP Components
     * instead of the one from Apache Commons Compress. This is because the latter requires an additional
     * parameter {@code zlibHeader} to handle both RFC1951 (raw DEFLATE) and RFC1950 (zlib wrapped DEFLATE)
     * streams. By using {@link DeflateInputStream}, we simplify the handling of DEFLATE streams without
     * the need to manually specify the {@code zlibHeader} parameter.
     * </p>
     *
     * @param name                   the name of the compressor
     * @param inputStream            the input stream to be decompressed
     * @return the decompressed input stream, or {@code null} if an error occurs
     */
    private InputStream createCompressorInputStream(final String name, final InputStream inputStream) {
        try {
            if ("DEFLATE".equalsIgnoreCase(name)) {
                return new DeflateInputStream(inputStream);
            }
            return compressorStreamFactory.createCompressorInputStream(name, inputStream);
        } catch (final Exception ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Could not create compressor input stream for {}", name, ex);
            }
            return null;
        }
    }

    /**
     * Creates a compressor output stream for the specified compressor name and output stream.
     *
     * @param name         the name of the compressor
     * @param outputStream the output stream to be compressed
     * @return the compressed output stream, or {@code null} if an error occurs
     */
    private OutputStream createCompressorOutputStream(final String name, final OutputStream outputStream) {
        try {
            return compressorStreamFactory.createCompressorOutputStream(name, outputStream);
        } catch (final Exception ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Could not create compressor output stream for {}", name, ex);
            }
            return null;
        }
    }

    /**
     * Retrieves the available input stream compressor providers, using a cache for efficiency.
     *
     * @return a sorted map of available input stream compressor providers
     */
    private Set<String> getAvailableInputProviders() {
        return inputProvidersCache.updateAndGet(existing -> {
            if (existing == null) {
                return CompressorStreamFactory.findAvailableCompressorInputStreamProviders().keySet();
            }
            return existing;
        });
    }

    /**
     * Retrieves the available output stream compressor providers, using a cache for efficiency.
     *
     * @return a sorted map of available output stream compressor providers
     */
    private Set<String> getAvailableOutputProviders() {
        return outputProvidersCache.updateAndGet(existing -> {
            if (existing == null) {
                return CompressorStreamFactory.findAvailableCompressorOutputStreamProviders().keySet();
            }
            return existing;
        });
    }

    /**
     * Formats the given compressor name by converting it to lowercase.
     *
     * @param name the compressor name to format
     * @return the formatted compressor name
     */
    private String formatName(final String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Checks if the specified compressor is supported, either for input or output.
     *
     * @param name     the name of the compressor
     * @param isOutput whether to check for output support (true) or input support (false)
     * @return {@code true} if the compressor is supported, {@code false} otherwise
     */
    private boolean isSupported(final String name, final boolean isOutput) {
        final String formattedName = getFormattedName(name);
        if (formattedName == null) {
            return false;
        }
        if (isOutput) {
            return getAvailableOutputProviders().contains(formattedName);
        } else {
            return getAvailableInputProviders().contains(formattedName);
        }
    }

    /**
     * Retrieves the formatted name for the specified compressor, using a cache for efficiency.
     *
     * @param name the compressor name to format
     * @return the formatted compressor name, or the original name in uppercase if not found
     */
    private String getFormattedName(final String name) {
        return formattedNameCache.computeIfAbsent(formatName(name), key -> {
            switch (key) {
                case "gzip":
                case "x-gzip":
                    return "GZ";
                case "compress":
                    return "Z";
                default:
                    return key.toUpperCase(Locale.ROOT);
            }
        });
    }

    /**
     * Checks if the specified compressor is supported for output.
     *
     * @param name the name of the compressor
     * @return {@code true} if the compressor is supported for output, {@code false} otherwise
     */
    private boolean isOutputSupported(final String name) {
        return isSupported(name, true);
    }

    /**
     * Checks if the specified compressor is supported for input.
     *
     * @param name the name of the compressor
     * @return {@code true} if the compressor is supported for input, {@code false} otherwise
     */
    private boolean isInputSupported(final String name) {
        return isSupported(name, false);
    }
}
