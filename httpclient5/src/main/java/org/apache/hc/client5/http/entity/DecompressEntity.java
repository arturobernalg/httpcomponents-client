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
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.util.Args;


/**
 * An {@link HttpEntity} wrapper that decompresses the content of the wrapped entity.
 * This class supports different compression types and can handle both standard
 * compression (e.g., gzip, deflate) and variations that require a custom handling (e.g., nowrap).
 *
 * <p>Decompression is performed using a {@link LazyDecompressInputStream} that
 * applies decompression lazily when content is requested.</p>
 *
 * @since 5.4
 */
public class DecompressEntity extends HttpEntityWrapper {

    /**
     * Default buffer size used during decompression.
     */
    private static final int BUFFER_SIZE = 1024 * 2;

    /**
     * The content input stream, initialized lazily during the first read.
     */
    private InputStream content;

    /**
     * The compression type used for decompression (e.g., gzip, deflate).
     */
    private final String compressionType;

    /**
     * The flag indicating if decompression should skip certain headers (nowrap).
     */
    private final boolean nowrap;

    /**
     * Constructs a new {@link DecompressEntity} with the specified compression type and nowrap setting.
     *
     * @param wrapped         the non-null {@link HttpEntity} to be wrapped.
     * @param compressionType the compression type (e.g., "gzip", "deflate").
     * @param nowrap          whether to decompress without headers for certain compression formats.
     */
    public DecompressEntity(final HttpEntity wrapped, final String compressionType, final boolean nowrap) {
        super(wrapped);
        this.compressionType = compressionType;
        this.nowrap = nowrap;
    }

    /**
     * Constructs a new {@link DecompressEntity} with the specified compression type, defaulting to no nowrap handling.
     *
     * @param wrapped         the non-null {@link HttpEntity} to be wrapped.
     * @param compressionType the compression type (e.g., "gzip", "deflate").
     */
    public DecompressEntity(final HttpEntity wrapped, final String compressionType) {
        this(wrapped, compressionType, false);
    }

    /**
     * Initializes and returns a stream for decompression.
     * The decompression is applied lazily on the wrapped entity's content.
     *
     * @return a lazily initialized {@link InputStream} that decompresses the content.
     * @throws IOException if an error occurs during decompression.
     */
    private InputStream getDecompressingStream() throws IOException {
        return new LazyDecompressInputStream(super.getContent(), compressionType, nowrap);
    }

    /**
     * Returns the decompressed content stream. If the entity is streaming,
     * the same {@link InputStream} is returned on subsequent calls.
     *
     * @return the decompressed {@link InputStream}.
     * @throws IOException if an error occurs during decompression.
     */
    @Override
    public InputStream getContent() throws IOException {
        if (super.isStreaming()) {
            if (content == null) {
                content = getDecompressingStream();
            }
            return content;
        }
        return getDecompressingStream();
    }

    /**
     * Writes the decompressed content to the specified {@link OutputStream}.
     *
     * @param outStream the {@link OutputStream} to which the decompressed content is written; must not be {@code null}.
     * @throws IOException if an I/O error occurs during writing or decompression.
     */
    @Override
    public void writeTo(final OutputStream outStream) throws IOException {
        Args.notNull(outStream, "Output stream");
        try (InputStream inStream = getContent()) {
            final byte[] buffer = new byte[BUFFER_SIZE];
            int l;
            while ((l = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, l);
            }
        }
    }

    /**
     * Returns the compression type (e.g., "gzip", "deflate").
     *
     * @return the content encoding (compression type).
     */
    @Override
    public String getContentEncoding() {
        return compressionType;
    }

    /**
     * Returns the length of the decompressed content. Since the content is being decompressed,
     * the exact length is unknown and this method returns the length of the wrapped entity.
     *
     * @return the content length, or {@code -1} if unknown.
     */
    @Override
    public long getContentLength() {
        return super.getContentLength();
    }
}
