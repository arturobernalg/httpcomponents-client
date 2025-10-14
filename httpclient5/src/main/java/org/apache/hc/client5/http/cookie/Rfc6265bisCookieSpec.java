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

import org.apache.hc.client5.http.impl.cookie.BasicDomainHandler;
import org.apache.hc.client5.http.impl.cookie.BasicHttpOnlyHandler;
import org.apache.hc.client5.http.impl.cookie.BasicPathHandler;
import org.apache.hc.client5.http.impl.cookie.BasicSecureHandler;
import org.apache.hc.client5.http.impl.cookie.CappedExpiresHandler;
import org.apache.hc.client5.http.impl.cookie.CappedMaxAgeHandler;
import org.apache.hc.client5.http.impl.cookie.PublicSuffixValidationHandler;
import org.apache.hc.client5.http.impl.cookie.RFC6265CookieSpec;
import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;

/**
 * RFC 6265bis CookieSpec implementation.
 * <p>Internal implementation detail used by {@link Rfc6265bisCookieSpecFactory}.
 * Not intended for direct use; binary incompatible changes may occur without notice.
 */
@Contract(threading = ThreadingBehavior.STATELESS)
@Internal
public final class Rfc6265bisCookieSpec extends RFC6265CookieSpec {

    public Rfc6265bisCookieSpec(final PublicSuffixMatcher psl) {
        this(
                new BasicPathHandler(),
                new BasicDomainHandler(),
                new CappedMaxAgeHandler(),
                new CappedExpiresHandler(),
                new BasicSecureHandler(),
                new BasicHttpOnlyHandler(),
                new SameSiteAttributeHandler(),
                new PrefixValidationHandler(),
                new PublicSuffixValidationHandler(psl)
        );
    }

    public Rfc6265bisCookieSpec() {
        this((PublicSuffixMatcher) null);
    }

    private Rfc6265bisCookieSpec(final CommonCookieAttributeHandler... handlers) {
        super(handlers);
    }
}
