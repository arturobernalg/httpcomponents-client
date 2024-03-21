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

package org.apache.hc.client5.http.compress.util;

public enum CompressionAlgorithm {
    BROTLI("br"),
    BZIP2("bzip2"),
    GZIP("gz"),
    PACK200("pack200"),
    XZ("xz"),
    LZMA("lzma"),
    SNAPPY_FRAMED("snappy-framed"),
    SNAPPY_RAW("snappy-raw"),
    Z("z"),
    DEFLATE("deflate"),
    DEFLATE64("deflate64"),
    LZ4_BLOCK("lz4-block"),
    LZ4_FRAMED("lz4-framed"),
    ZSTANDARD("zstd"),
    IDENTITY("identity");

    private final String identifier;

    CompressionAlgorithm(final String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }

    public boolean isSame(final String value) {
        if (value == null) {
            return false;
        }
        return getIdentifier().equalsIgnoreCase(value);
    }
}