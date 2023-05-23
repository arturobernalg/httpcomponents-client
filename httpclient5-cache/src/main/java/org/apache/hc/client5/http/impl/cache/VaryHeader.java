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
package org.apache.hc.client5.http.impl.cache;

import java.util.Collections;
import java.util.Set;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;

/**
 * The VaryHeader class represents an HTTP 'Vary' response header.
 *
 * <p>
 * In HTTP, the 'Vary' response header is used to express the parameters
 * that might lead the server to provide a different representation of the requested resource.
 * It signals the set of request header fields that the server used to select among multiple
 * representations.
 * </p>
 *
 * <p>
 * This class encapsulates the information carried by the 'Vary' header.
 * It maintains a set of header field names indicating what request headers
 * were used in the selection process. It also tracks if the 'Vary' header contains
 * a wildcard character (*) suggesting that the response may vary for any change in
 * request headers. This data is crucial for a caching system to correctly manage
 * the cache validation process.
 * </p>
 *
 */
@Internal
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class VaryHeader {

    /**
     * The set of header field names used in the 'Vary' response.
     */
    private final Set<String> headers;

    /**
     * Flag indicating whether the 'Vary' header includes a wildcard (*).
     * If true, it means the response may vary for any change in request headers.
     */
    private final boolean hasWildcard;

    /**
     * Creates a new {@code VaryHeader} with no headers and no wildcard.
     */
    public VaryHeader() {
        this(Collections.emptySet(), false);
    }

    /**
     * Creates a new {@code VaryHeader} with the specified headers and wildcard flag.
     *
     * @param headers     the names of the request header fields that were used to select
     *                    among multiple representations
     * @param hasWildcard true if the 'Vary' header field contains a wildcard (*), false otherwise
     */
    public VaryHeader(final Set<String> headers, final boolean hasWildcard) {
        this.headers = headers != null ? Collections.unmodifiableSet(headers) : Collections.emptySet();
        this.hasWildcard = hasWildcard;
    }

    /**
     * Returns the names of the request header fields that were used to select
     * among multiple representations.
     *
     * @return a set of header field names
     */
    public Set<String> getHeaders() {
        return headers;
    }

    /**
     * Checks if the 'Vary' header field contains a wildcard (*).
     *
     * @return true if the 'Vary' header field contains a wildcard, false otherwise
     */
    public boolean hasWildcard() {
        return hasWildcard;
    }
}
