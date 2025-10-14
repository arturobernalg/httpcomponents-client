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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieOrigin;
import org.apache.hc.client5.http.cookie.CookieSpec;
import org.apache.hc.client5.http.cookie.MalformedCookieException;
import org.apache.hc.client5.http.cookie.Rfc6265bisCookieSpec;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

final class Rfc6265bisCookieSpecTest {

    private static Cookie parseOne(final
                                   CookieSpec spec,
                                   final String setCookie,
                                   final CookieOrigin origin) throws Exception {
        final Header h = new BasicHeader("Set-Cookie", setCookie);
        final List<Cookie> cookies = spec.parse(h, origin);
        assertEquals(1, cookies.size(), "expected exactly one cookie");
        return cookies.get(0);
    }

    @Test
    void samesiteNone_requires_secure() throws Exception {
        final Rfc6265bisCookieSpec spec = new Rfc6265bisCookieSpec();
        final CookieOrigin origin = new CookieOrigin("example.com", 80, "/", false);

        final Cookie c = parseOne(spec, "sid=1; Path=/; SameSite=None", origin);
        assertInstanceOf(BasicClientCookie.class, c);
        assertTrue("None".equalsIgnoreCase(c.getAttribute("samesite")));

        assertThrows(MalformedCookieException.class, () -> spec.validate(c, origin));
    }

    @Test
    void samesiteNone_with_secure_is_valid() throws Exception {
        final Rfc6265bisCookieSpec spec = new Rfc6265bisCookieSpec();
        final CookieOrigin origin = new CookieOrigin("example.com", 443, "/", true);

        final Cookie c = parseOne(spec, "sid=1; Path=/; SameSite=None; Secure", origin);
        assertDoesNotThrow(() -> spec.validate(c, origin));
    }

    @Test
    void host_prefix_rules_ok() throws Exception {
        final Rfc6265bisCookieSpec spec = new Rfc6265bisCookieSpec();
        final CookieOrigin origin = new CookieOrigin("www.example.com", 443, "/", true);

        final Cookie c = parseOne(spec, "__Host-sid=1; Path=/; Secure", origin);

        // host-only => no Domain attribute present (domain field may be set to host, but attribute is absent)
        assertInstanceOf(BasicClientCookie.class, c);
        assertNull(c.getAttribute("domain"));

        assertEquals("/", c.getPath());
        assertTrue(c.isSecure());

        assertDoesNotThrow(() -> spec.validate(c, origin));
    }

    @Test
    void host_prefix_must_be_host_only_and_path_slash_and_secure() throws Exception {
        final Rfc6265bisCookieSpec spec = new Rfc6265bisCookieSpec();
        final CookieOrigin origin = new CookieOrigin("www.example.com", 443, "/", true);

        final Cookie c1 = parseOne(spec, "__Host-sid=1; Domain=example.com; Path=/; Secure", origin);
        assertThrows(MalformedCookieException.class, () -> spec.validate(c1, origin));

        final Cookie c2 = parseOne(spec, "__Host-sid=1; Path=/", origin); // missing Secure
        assertThrows(MalformedCookieException.class, () -> spec.validate(c2, origin));

        final Cookie c3 = parseOne(spec, "__Host-sid=1; Path=/app; Secure", origin); // wrong Path
        assertThrows(MalformedCookieException.class, () -> spec.validate(c3, origin));
    }

    @Test
    void secure_prefix_requires_secure() throws Exception {
        final Rfc6265bisCookieSpec spec = new Rfc6265bisCookieSpec();

        final CookieOrigin insecure = new CookieOrigin("example.com", 80, "/", false);
        final Cookie bad = parseOne(spec, "__Secure-id=1", insecure);
        assertThrows(MalformedCookieException.class, () -> spec.validate(bad, insecure));

        final CookieOrigin secure = new CookieOrigin("example.com", 443, "/", true);
        final Cookie ok = parseOne(spec, "__Secure-id=1; Secure", secure);
        assertDoesNotThrow(() -> spec.validate(ok, secure));
    }

    @Test
    void mixed_case_prefixes_are_enforced_case_insensitively() throws Exception {
        final CookieSpec spec = new Rfc6265bisCookieSpec();
        final CookieOrigin https = new CookieOrigin("example.com", 443, "/", true);

        // __hOsT- should be treated as __Host-
        final Cookie ok = parseOne(spec, "__hOsT-id=1; Path=/; Secure", https);
        assertDoesNotThrow(() -> spec.validate(ok, https));

        // __seCUre- must still be Secure
        final Cookie bad = parseOne(spec, "__seCUre-id=1", new CookieOrigin("example.com", 80, "/", false));
        assertThrows(MalformedCookieException.class, () -> spec.validate(bad, new CookieOrigin("example.com", 80, "/", false)));
    }
}
