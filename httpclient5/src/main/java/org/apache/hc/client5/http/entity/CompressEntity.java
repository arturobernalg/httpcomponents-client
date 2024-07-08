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

public class CompressEntity extends HttpEntityWrapper {
    private final Function<OutputStream, OutputStream> compressorFunction;
    private final String compressionType;

    public CompressEntity(final HttpEntity wrappedEntity,
                             final Function<OutputStream, OutputStream> compressorFunction,
                             final String compressionType) {
        super(wrappedEntity);
        this.compressorFunction = compressorFunction;
        this.compressionType = compressionType;
    }

    @Override
    public void writeTo(final OutputStream outstream) throws IOException {
        try (final OutputStream compressorStream = compressorFunction.apply(outstream)) {
            super.writeTo(compressorStream);
        } catch (final Exception e) {
            throw new IOException("Failed to compress data using " + compressionType, e);
        }
    }

    @Override
    public String getContentEncoding() {
        return compressionType;
    }

    @Override
    public long getContentLength() {
        return -1;
    }

    @Override
    public boolean isChunked() {
        return true;
    }
}