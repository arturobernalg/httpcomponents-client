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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.hc.client5.http.compress.entity.CompressingEntity;
import org.apache.hc.client5.http.compress.entity.DecompressingEntity;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressorFactory implements CompressorProvider {

    private static final Logger LOG = LoggerFactory.getLogger(CompressorFactory.class);

    private static final Map<String, String> httpToApacheCompressMapping;


    private final AtomicReference<Set<String>> supportedInputCompressors = new AtomicReference<>();
    private final AtomicReference<Set<String>> supportedOutputCompressors = new AtomicReference<>();

    public static final CompressorFactory INSTANCE = new CompressorFactory();

    static {
        httpToApacheCompressMapping = new HashMap<>();
        httpToApacheCompressMapping.put("gzip", "gz"); // HTTP 'gzip' maps to Apache 'gz'
        httpToApacheCompressMapping.put("x-gzip", "gz"); // Treat 'x-gzip' the same as 'gzip'
    }

    private final CompressorStreamFactory compressorStreamFactory = new CompressorStreamFactory();

    private boolean isOutputSupported(final String name) {
        final String translated = translateHttpToApacheCompress(name).toLowerCase(Locale.ROOT);
        return populateSupportedOutputCompressors().contains(translated);
    }

    private boolean isInputSupported(final String name) {
        final String translated = translateHttpToApacheCompress(name).toLowerCase(Locale.ROOT);
        return populateSupportedInputCompressors().contains(translated);
    }

    private Set<String> populateSupportedCompressors(final Supplier<Set<String>> compressorNamesSupplier, final AtomicReference<Set<String>> compressorSetRef) {
        return compressorSetRef.updateAndGet(existingSet -> {
            if (existingSet == null) {
                return compressorNamesSupplier.get().stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());
            }
            return existingSet;
        });
    }

    private Set<String> populateSupportedInputCompressors() {
        return populateSupportedCompressors(compressorStreamFactory::getInputStreamCompressorNames,
                supportedInputCompressors
        );
    }

    private Set<String> populateSupportedOutputCompressors() {
        return populateSupportedCompressors(compressorStreamFactory::getOutputStreamCompressorNames,
                supportedOutputCompressors
        );
    }

    @Override
    public Function<InputStream, InputStream> getCompressorInput(final String name, final boolean decompressConcatenated) {
        final String normalizeName = translateHttpToApacheCompress(name.toLowerCase(Locale.ROOT));
        if (!isInputSupported(normalizeName)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Compressor {} is not supported.", normalizeName);
            }
            return null;
        }
        return inputStream -> createCompressorInputStream((normalizeName), inputStream, decompressConcatenated);
    }

    @Override
    public Function<OutputStream, OutputStream> getCompressorOutputStream(final String name) {
        final String normalizeName = translateHttpToApacheCompress(name.toLowerCase(Locale.ROOT));
        if (!isOutputSupported(normalizeName)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Compressor {} is not supported.", normalizeName);
            }
            return null;
        }
        return outputStream -> createCompressorOutputStream((translateHttpToApacheCompress(name)), outputStream);
    }


    @Override
    public Set<String> getInputStreamCompressorNames() {
        return compressorStreamFactory.getInputStreamCompressorNames();
    }

    @Override
    public Set<String> getOutputStreamCompressorNames() {
        return compressorStreamFactory.getOutputStreamCompressorNames();
    }

    private String translateHttpToApacheCompress(final String httpContentEncoding) {
        return httpToApacheCompressMapping.getOrDefault(httpContentEncoding, httpContentEncoding);
    }

    @Override
    public HttpEntity decompressEntity(final HttpEntity entity, final String contentEncoding, final boolean decompressConcatenated) {
        Args.notNull(entity, "Entity");
        Args.notNull(contentEncoding, "Content Encoding");

        if (!isInputSupported(contentEncoding)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unsupported decompression type: {}", contentEncoding);
            }
            return null;
        }

        final Function<InputStream, InputStream> decompressorFunction = getCompressorInput(contentEncoding, decompressConcatenated);
        return new DecompressingEntity(entity, decompressorFunction);
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
        return new CompressingEntity(entity, compressorFunction, contentEncoding);
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
                LOG.warn("Could not create decompressor input stream for {}", name, ex);
            }
            return null;
        }
    }

    private OutputStream createCompressorOutputStream(final String name, final OutputStream outputStream) {
        try {
            return compressorStreamFactory.createCompressorOutputStream(translateHttpToApacheCompress(name), outputStream);
        } catch (final Exception ex) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Could not create compressor output stream for {}", name, ex);
            }
            return null;
        }
    }
}