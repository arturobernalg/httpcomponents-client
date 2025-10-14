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

import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;


/**
 * Validates RFC 6265bis name prefixes:
 */
@Contract(threading = ThreadingBehavior.STATELESS)
@Internal
final class PrefixValidationHandler implements CommonCookieAttributeHandler {

    private static final String SYNTH_ATTR = "__prefix__";

    private static boolean startsWithIgnoreCase(final String s, final String pfx) {
        return s != null && s.regionMatches(true, 0, pfx, 0, pfx.length());
    }

    @Override
    public void parse(final SetCookie cookie, final String value) {
        // name-based rule; nothing to parse
    }

    @Override
    public void validate(final Cookie cookie, final CookieOrigin origin) throws MalformedCookieException {
        String name = cookie.getName();
        if (name == null) {
            name = "";
        }

        // 6265bis §5 creation algorithm: empty cookie-name + value beginning with __Secure-/__Host- (case-insensitive) must be ignored.
        // We mirror that by rejecting such cookies here.
        if (name.isEmpty()) {
            final String value = cookie.getValue();
            if (startsWithIgnoreCase(value, "__Secure-") || startsWithIgnoreCase(value, "__Host-")) {
                throw new MalformedCookieException("Empty-name cookie with prefixed value must be ignored");
            }
            return; // No further prefix checks for empty names
        }

        final boolean isHost = startsWithIgnoreCase(name, "__Host-");
        final boolean isSecure = startsWithIgnoreCase(name, "__Secure-");

        if (isHost) {
            if (!cookie.isSecure()) {
                throw new MalformedCookieException("__Host- cookie must be Secure");
            }
            // Host-only: reject if Domain ATTRIBUTE is present (not the effective domain value).
            if (cookie instanceof BasicClientCookie) {
                final String domainAttr = cookie.getAttribute(Cookie.DOMAIN_ATTR);
                if (domainAttr != null) {
                    throw new MalformedCookieException("__Host- cookie must be host-only (no Domain)");
                }
            }
            // MUST have Path=/; require explicit Path attribute set to "/" if present, otherwise fall back to effective path.
            if (!"/".equals(cookie.getPath())) {
                throw new MalformedCookieException("__Host- cookie must have Path=/");
            }
            if (cookie instanceof BasicClientCookie) {
                final String pathAttr = cookie.getAttribute(Cookie.PATH_ATTR);
                if (pathAttr == null || !"/".equals(pathAttr)) {
                    throw new MalformedCookieException("__Host- cookie must explicitly set Path=/");
                }
            }
        } else if (isSecure) {
            if (!cookie.isSecure()) {
                throw new MalformedCookieException("__Secure- cookie must be Secure");
            }
        }
    }

    @Override
    public boolean match(final Cookie cookie, final CookieOrigin origin) {
        return true;
    }

    @Override
    public String getAttributeName() {
        return SYNTH_ATTR;
    }
}
