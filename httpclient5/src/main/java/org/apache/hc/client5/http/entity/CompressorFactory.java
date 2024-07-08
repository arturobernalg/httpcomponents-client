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
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.CompressorStreamProvider;
import org.apache.hc.core5.http.HttpEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressorFactory implements CompressorProvider {

    private static final Logger LOG = LoggerFactory.getLogger(CompressorFactory.class);

    public static final CompressorFactory INSTANCE = new CompressorFactory();

    private final CompressorStreamFactory compressorStreamFactory = new CompressorStreamFactory();

    private static final Map<String, String> COMPRESSOR_NAME_MAP = new HashMap<>();

    static {
        COMPRESSOR_NAME_MAP.put("gzip", "gz");
        COMPRESSOR_NAME_MAP.put("x-gzip", "gz");
        COMPRESSOR_NAME_MAP.put("compress", "z");  // "z" is the identifier used by Commons Compress
        COMPRESSOR_NAME_MAP.put("deflate", "deflate");
        COMPRESSOR_NAME_MAP.put("br", "br");
        COMPRESSOR_NAME_MAP.put("zstd", "zstd");
    }

    private final AtomicReference<SortedMap<String, CompressorStreamProvider>> inputProvidersCache = new AtomicReference<>();
    private final AtomicReference<SortedMap<String, CompressorStreamProvider>> outputProvidersCache = new AtomicReference<>();

    private SortedMap<String, CompressorStreamProvider> getAvailableInputProviders() {
        return inputProvidersCache.updateAndGet(existing -> {
            if (existing == null) {
                return CompressorStreamFactory.findAvailableCompressorInputStreamProviders();
            }
            return existing;
        });
    }

    private SortedMap<String, CompressorStreamProvider> getAvailableOutputProviders() {
        return outputProvidersCache.updateAndGet(existing -> {
            if (existing == null) {
                return CompressorStreamFactory.findAvailableCompressorOutputStreamProviders();
            }
            return existing;
        });
    }

    private boolean isOutputSupported(final String name) {
        final String supportName = name.toLowerCase(Locale.ROOT);
        final String mappedName = getOrDefault(supportName);
        return (COMPRESSOR_NAME_MAP.containsKey(supportName) || COMPRESSOR_NAME_MAP.containsValue(supportName))
                && getAvailableOutputProviders().containsKey(mappedName.toUpperCase(Locale.ROOT));
    }

    private boolean isInputSupported(final String name) {
        final String supportName = name.toLowerCase(Locale.ROOT);
        final String mappedName = getOrDefault(supportName);
        return (COMPRESSOR_NAME_MAP.containsKey(supportName) || COMPRESSOR_NAME_MAP.containsValue(supportName))
                && getAvailableInputProviders().containsKey(mappedName.toUpperCase(Locale.ROOT));
    }

    @Override
    public Function<InputStream, InputStream> getCompressorInput(final String name, final boolean decompressConcatenated) {
        if (!isInputSupported(name)) {
            return null;
        }
        final String compressorName = getOrDefault(name);
        return inputStream -> createCompressorInputStream(compressorName, inputStream, decompressConcatenated);
    }

    @Override
    public Function<OutputStream, OutputStream> getCompressorOutputStream(final String name) {
        if (!isOutputSupported(name)) {
            return null;
        }
        final String compressorName = getOrDefault(name);
        return outputStream -> createCompressorOutputStream(compressorName, outputStream);
    }

    @Override
    public Set<String> getInputStreamCompressorNames() {
        return COMPRESSOR_NAME_MAP.keySet().stream()
                .filter(this::isInputSupported)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getOutputStreamCompressorNames() {
        return COMPRESSOR_NAME_MAP.keySet().stream()
                .filter(this::isOutputSupported)
                .collect(Collectors.toSet());
    }

    @Override
    public HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding, final boolean decompressConcatenated) {
        if (!isInputSupported(contentEncoding)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unsupported decompression type: {}", contentEncoding);
            }
            return null;
        }
        final Function<InputStream, InputStream> decompressorFunction = getCompressorInput(contentEncoding, decompressConcatenated);
        return new DecompressEntity(entity, decompressorFunction, contentEncoding);
    }

    @Override
    public HttpEntity compressEntity(final HttpEntity entity, final String contentEncoding) {
        if (!isOutputSupported(contentEncoding)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unsupported compression type: {}", contentEncoding);
            }
            return null;
        }
        final Function<OutputStream, OutputStream> compressorFunction = getCompressorOutputStream(contentEncoding);
        return new CompressEntity(entity, compressorFunction, contentEncoding);
    }

    @Override
    public HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding) {
        return decompressEntity(entity, contentEncoding, true);
    }

    private InputStream createCompressorInputStream(final String name, final InputStream inputStream, final boolean decompressConcatenated) {
        try {
            return compressorStreamFactory.createCompressorInputStream(name, inputStream, decompressConcatenated);
        } catch (final Exception ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Could not create compressor input stream for {}", name, ex);
            }
            return null;
        }
    }

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

    private String getOrDefault(final String compressorName) {
        return COMPRESSOR_NAME_MAP.getOrDefault(compressorName.toLowerCase(Locale.ROOT), compressorName);
    }
}