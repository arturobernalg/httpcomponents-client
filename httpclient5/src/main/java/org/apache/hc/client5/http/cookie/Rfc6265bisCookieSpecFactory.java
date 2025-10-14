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
package org.apache.hc.client5.http.cookie;

import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.apache.hc.core5.annotation.Experimental;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * Experimental factory for the RFC 6265bis cookie policy.
 * <p>Enables parsing and validation of {@code SameSite} and prefix rules
 * (__Host-/__Secure-) with case-insensitive checks, and the empty-name rule.
 * <p>Register with {@code CookieSpecSupport.createDefaultBuilder().register(
 * StandardCookieSpec.RFC6265_BIS, new Rfc6265bisCookieSpecFactory())}, then
 * select via {@code RequestConfig#setCookieSpec(StandardCookieSpec.RFC6265_BIS)}.
 * <p><strong>Experimental:</strong> API and behavior may change in minor releases.
 *
 * @since 5.6
 */
@Experimental
public final class Rfc6265bisCookieSpecFactory implements CookieSpecFactory {
    private final PublicSuffixMatcher psl;

    public Rfc6265bisCookieSpecFactory() {
        this(PublicSuffixMatcherLoader.getDefault());
    }

    public Rfc6265bisCookieSpecFactory(final PublicSuffixMatcher psl) {
        this.psl = psl;
    }

    @Override
    public CookieSpec create(final HttpContext context) {
        return new Rfc6265bisCookieSpec(psl);
    }
}
