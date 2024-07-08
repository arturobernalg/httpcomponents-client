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
import java.util.function.Function;

import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.util.Args;

public class DecompressEntity extends HttpEntityWrapper {

    private static final int BUFFER_SIZE = 1024 * 2;

    private final Function<InputStream, InputStream> decompressor;
    private InputStream content;

    private final String contentEncoding;


    /**
     * Creates a new instance of {@code DecompressEntity}.
     *
     * @param wrapped          the wrapped entity
     * @param decompressor     the decompressor function
     * @param contentEncoding  the content encoding of the entity
     */
    public DecompressEntity(final HttpEntity wrapped,
                            final Function<InputStream, InputStream> decompressor,
                            final String contentEncoding) {
        super(wrapped);
        this.decompressor = decompressor;
        this.contentEncoding = contentEncoding;
    }

    private InputStream getDecompressingStream() throws IOException {
        return decompressor.apply(super.getContent());
    }

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

    @Override
    public String getContentEncoding() {
        return contentEncoding;
    }

    @Override
    public long getContentLength() {
        return -1;
    }
}
