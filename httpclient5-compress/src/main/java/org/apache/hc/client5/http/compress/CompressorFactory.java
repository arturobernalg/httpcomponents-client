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
import java.util.function.Function;

import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressorFactory implements CompressorProvider {

    private static final Map<String, String> httpToApacheCompressMapping;

    static {
        httpToApacheCompressMapping = new HashMap<>();
        httpToApacheCompressMapping.put("gzip", "gz"); // HTTP 'gzip' maps to Apache 'gz'
        httpToApacheCompressMapping.put("x-gzip", "gz"); // Treat 'x-gzip' the same as 'gzip'
    }

    private static final Logger LOG = LoggerFactory.getLogger(CompressorFactory.class);
    public static final CompressorFactory INSTANCE = new CompressorFactory();
    private final CompressorStreamFactory compressorStreamFactory;

    public CompressorFactory() {
        this(true, -1);
    }

    public CompressorFactory(final int memoryLimitInKb) {
        this(true, memoryLimitInKb);
    }

    public CompressorFactory(final boolean decompressConcatenated, final int memoryLimitInKb) {
        compressorStreamFactory = new CompressorStreamFactory(decompressConcatenated, memoryLimitInKb);
    }

    @Override
    public Function<InputStream, InputStream> getCompressorInput(final String name, final boolean decompressConcatenated) {
        final String normalizeName = translateHttpToApacheCompress(name.toLowerCase(Locale.ROOT));
        if (!isSupported(normalizeName)) {
            LOG.debug("Compressor {} is not supported.", normalizeName);
            return null;
        }
        return inputStream -> createCompressorInputStream((normalizeName), inputStream, decompressConcatenated);
    }

    public Function<OutputStream, OutputStream> getCompressorOutputStream(final String name) {
        final String normalizeName = translateHttpToApacheCompress(name.toLowerCase(Locale.ROOT));
        if (!isSupported(normalizeName)) {
            LOG.debug("Compressor {} is not supported.", normalizeName);
            return null;
        }
        return outputStream -> createCompressorOutputStream((translateHttpToApacheCompress(name)), outputStream);
    }

    private InputStream createCompressorInputStream(final String name, final InputStream inputStream, final boolean decompressConcatenated) {
        try {
            return compressorStreamFactory.createCompressorInputStream(name, inputStream, decompressConcatenated);
        } catch (final Exception ex) {
            LOG.debug("Could not create decompressor input stream for {}", name, ex);
            return null;
        }
    }

    private OutputStream createCompressorOutputStream(final String name, final OutputStream outputStream) {
        try {
            return compressorStreamFactory.createCompressorOutputStream(translateHttpToApacheCompress(name), outputStream);
        } catch (final Exception ex) {
            LOG.debug("Could not create compressor output stream for {}", name, ex);
            return null;
        }
    }

    private boolean isSupported(final String name) {
        if (name == null) {
            return false;
        }

        final boolean isInputSupported = compressorStreamFactory.getInputStreamCompressorNames()
                .stream()
                .map(String::toLowerCase)
                .anyMatch(name::equals);

        final boolean isOutputSupported = compressorStreamFactory.getOutputStreamCompressorNames()
                .stream()
                .map(String::toLowerCase)
                .anyMatch(name::equals);

        final boolean isProviderSupported = compressorStreamFactory.getCompressorOutputStreamProviders()
                .keySet()
                .stream()
                .map(String::toLowerCase)
                .anyMatch(name::equals);

        return isInputSupported || isOutputSupported || isProviderSupported;
    }


    @Override
    public boolean isSupportedInput(final String compressionType) {
        final String translated = translateHttpToApacheCompress(compressionType);
        return compressorStreamFactory.getInputStreamCompressorNames().contains(translated);
    }

    @Override
    public boolean isSupportedOutput(final String compressionType) {
        final String translated = translateHttpToApacheCompress(compressionType);
        return compressorStreamFactory.getOutputStreamCompressorNames().contains(translated);
    }

    @Override
    public Set<String> getInputStreamCompressorNames() {
        return compressorStreamFactory.getInputStreamCompressorNames();
    }

    @Override
    public Set<String> getOutputStreamCompressorNames() {
        return compressorStreamFactory.getOutputStreamCompressorNames();
    }

    private static String translateHttpToApacheCompress(final String httpContentEncoding) {
        return httpToApacheCompressMapping.getOrDefault(httpContentEncoding, httpContentEncoding);
    }


}