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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressorFactory implements CompressorProvider {

    private static final Logger LOG = LoggerFactory.getLogger(CompressorFactory.class);
    public static final CompressorFactory INSTANCE = new CompressorFactory();
    private final ConcurrentMap<String, Function<InputStream, InputStream>> compressorInputStream = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Function<OutputStream, OutputStream>> compressorOutputStream = new ConcurrentHashMap<>();
    private final CompressorStreamFactory compressorStreamFactory;

    public CompressorFactory() {
        this(true, -1);
    }

    public CompressorFactory(final boolean decompressConcatenated, final int memoryLimitInKb) {
        compressorStreamFactory = new CompressorStreamFactory(decompressConcatenated, memoryLimitInKb);
        initializeCompressorInputStream();
        initializeCompressorOutputStream();
    }

    private void initializeCompressorInputStream() {
        compressorStreamFactory.getInputStreamCompressorNames().forEach(name ->
                compressorInputStream.put(name, inputStream -> {
                    try {
                        return compressorStreamFactory.createCompressorInputStream(name, inputStream, true);
                    } catch (final Exception e) {
                        LOG.error("Could not create decompressor input stream for {}", name, e);
                        return null;
                    }
                })
        );
    }

    private void initializeCompressorOutputStream() {
        compressorStreamFactory.getOutputStreamCompressorNames().forEach(name ->
                compressorOutputStream.put(name, outputStream -> {
                    try {
                        return compressorStreamFactory.createCompressorOutputStream(name, outputStream);
                    } catch (final Exception e) {
                        LOG.error("Could not create compressor output stream for {}", name, e);
                        return null;
                    }
                })
        );
    }

    @Override
    public boolean isSupportedInput(final String compressionType) {
        return compressorInputStream.containsKey(compressionType);
    }

    @Override
    public boolean isSupportedOutput(final String compressionType) {
        return compressorOutputStream.containsKey(compressionType);
    }

    @Override
    public Function<InputStream, InputStream> getCompressorInput(final String compressionType) {
        return compressorInputStream.get(compressionType);
    }

    @Override
    public Map<String, Function<InputStream, InputStream>> getAllCompressorInput() {
        return Collections.unmodifiableMap(new HashMap<>(compressorInputStream));
    }

    @Override
    public Function<OutputStream, OutputStream> getCompressorOutputStream(final String compressionType) {
        return compressorOutputStream.get(compressionType);
    }

    public Set<String> getInputStreamCompressorNames() {
        return compressorStreamFactory.getInputStreamCompressorNames();
    }

    public Set<String> getOutputStreamCompressorNames() {
        return compressorStreamFactory.getOutputStreamCompressorNames();
    }
}
