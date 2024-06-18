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

public enum CompressionAlgorithm {
    BROTLI("br"),
    BZIP2("bzip2"),
    GZIP("gzip", new String[]{"gz", "x-gzip"}), // Include "gz" and "x-gzip" as alternatives
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
    private final String[] alternativeIdentifiers; // Array to hold alternative identifiers

    CompressionAlgorithm(final String identifier) {
        this(identifier, new String[]{});
    }

    CompressionAlgorithm(final String identifier, String[] alternativeIdentifiers) {
        this.identifier = identifier;
        this.alternativeIdentifiers = alternativeIdentifiers;
    }

    public String getIdentifier() {
        return identifier;
    }

    public static CompressionAlgorithm fromHttpName(final  String httpName) {
        for (final CompressionAlgorithm algorithm : values()) {
            if (algorithm.identifier.equalsIgnoreCase(httpName)) {
                return algorithm;
            }
            for (String alt : algorithm.alternativeIdentifiers) {
                if (alt.equalsIgnoreCase(httpName)) {
                    return algorithm;
                }
            }
        }
        return null;
    }
}
