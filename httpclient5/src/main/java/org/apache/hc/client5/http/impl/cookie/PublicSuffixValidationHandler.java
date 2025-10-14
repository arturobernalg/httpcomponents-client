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
package org.apache.hc.client5.http.impl.cookie;

import org.apache.hc.client5.http.cookie.CommonCookieAttributeHandler;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieOrigin;
import org.apache.hc.client5.http.cookie.MalformedCookieException;
import org.apache.hc.client5.http.cookie.SetCookie;
import org.apache.hc.client5.http.psl.PublicSuffixMatcher;

public final class PublicSuffixValidationHandler implements CommonCookieAttributeHandler {

    private final PublicSuffixMatcher psl;

    public PublicSuffixValidationHandler(final PublicSuffixMatcher psl) {
        this.psl = psl;
    }

    @Override
    public void parse(final SetCookie cookie, final String value) {
        // no-op
    }

    @Override
    public void validate(final Cookie cookie, final CookieOrigin origin) throws MalformedCookieException {
        if (psl == null || !(cookie instanceof BasicClientCookie)) {
            return;
        }

        final BasicClientCookie bcc = (BasicClientCookie) cookie;

        if (!bcc.containsAttribute(Cookie.DOMAIN_ATTR)) {
            return;
        }

        final String domain = cookie.getDomain();
        if (domain == null || domain.isEmpty()) {
            return;
        }

        final String host = origin != null ? origin.getHost() : null;

        final String d = domain.toLowerCase(java.util.Locale.ROOT);
        final String h = host != null ? host.toLowerCase(java.util.Locale.ROOT) : null;

        if (h != null && h.equals(d)) {
            return;
        }

        // Otherwise, reject cookies whose Domain is a public suffix
        if (psl.matches(d)) {
            throw new MalformedCookieException("Domain is a public suffix: " + domain);
        }
    }

    @Override
    public boolean match(final Cookie cookie, final CookieOrigin origin) {
        return true;
    }

    @Override
    public String getAttributeName() {
        return "__psl__";
    }
}
