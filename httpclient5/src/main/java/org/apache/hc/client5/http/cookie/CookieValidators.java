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
import org.apache.hc.core5.annotation.Internal;

/**
 * Helper validators mirroring RFC 6265bis creation-time checks for programmatic cookies.
 */
@Internal
public final class CookieValidators {
    private CookieValidators() {
    }

    private static boolean startsWithIgnoreCase(final String s, final String pfx) {
        return s != null && s.regionMatches(true, 0, pfx, 0, pfx.length());
    }

    /**
     * Returns null if OK, or a human-readable reason if invalid.
     */
    public static String validatePrefixConstraints(final Cookie cookie) {
        String name = cookie.getName();
        if (name == null) name = "";
        if (name.isEmpty()) {
            final String v = cookie.getValue();
            if (startsWithIgnoreCase(v, "__Secure-") || startsWithIgnoreCase(v, "__Host-")) {
                return "Empty-name cookie with prefixed value must be ignored";
            }
            return null;
        }

        final boolean isHost = startsWithIgnoreCase(name, "__Host-");
        final boolean isSecure = startsWithIgnoreCase(name, "__Secure-");

        if (isHost) {
            if (!cookie.isSecure()) {
                return "__Host- requires Secure";
            }
            if (cookie instanceof BasicClientCookie) {
                final String domainAttr = cookie.getAttribute(Cookie.DOMAIN_ATTR);
                if (domainAttr != null) {
                    return "__Host- must be host-only (no Domain)";
                }
                final String pathAttr = cookie.getAttribute(Cookie.PATH_ATTR);
                if (!"/".equals(pathAttr)) {
                    // <-- key change: require explicit Path=/
                    return "__Host- requires explicit Path=/";
                }
            } else {
                // No attribute access ⇒ cannot prove Path attribute is present; align with spec
                return "__Host- requires explicit Path=/";
            }
            return null;
        }

        if (isSecure && !cookie.isSecure()) {
            return "__Secure- requires Secure";
        }
        return null;
    }


    /**
     * SameSite=None must also be Secure.
     */
    public static String validateSameSite(final Cookie cookie) {
        if (cookie instanceof BasicClientCookie) {
            final String v = cookie.getAttribute("samesite");
            if ("none".equalsIgnoreCase(v) && !cookie.isSecure()) {
                return "SameSite=None requires Secure";
            }
        }
        return null;
    }
}