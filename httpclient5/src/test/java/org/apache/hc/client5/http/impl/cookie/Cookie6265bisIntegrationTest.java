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

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieOrigin;
import org.apache.hc.client5.http.cookie.CookieSpec;
import org.apache.hc.client5.http.cookie.CookieSpecFactory;
import org.apache.hc.client5.http.cookie.MalformedCookieException;
import org.apache.hc.client5.http.cookie.PrefixAwareCookieStore;
import org.apache.hc.client5.http.cookie.Rfc6265bisCookieSpecFactory;
import org.apache.hc.client5.http.impl.CookieSpecSupport;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

final class Cookie6265bisIntegrationTest {

    private static Cookie parseOne(final CookieSpec spec,
                                   final String setCookie,
                                   final CookieOrigin origin) throws Exception {
        final Header h = new BasicHeader("Set-Cookie", setCookie);
        final List<Cookie> cookies = spec.parse(h, origin);
        assertEquals(1, cookies.size(), "expected exactly one cookie");
        return cookies.get(0);
    }

    @Test
    void handler_validates_prefix_and_samesite() throws Exception {
        // Build registry with our spec (includes PrefixValidationHandler)
        final RegistryBuilder<CookieSpecFactory> builder = CookieSpecSupport.createDefaultBuilder();
        builder.register("rfc6265bis", new Rfc6265bisCookieSpecFactory());
        final Lookup<CookieSpecFactory> registry = builder.build();

        final CookieSpec spec = registry.lookup("rfc6265bis").create(null);
        final CookieOrigin httpsOrigin = new CookieOrigin("www.example.com", 443, "/", true);
        final CookieOrigin httpOrigin = new CookieOrigin("www.example.com", 80, "/", false);

        // OK: __Host- + Path=/ + Secure, no Domain attribute
        final Cookie okHost = parseOne(spec, "__Host-sid=1; Path=/; Secure", httpsOrigin);
        assertInstanceOf(BasicClientCookie.class, okHost);
        assertNull(okHost.getAttribute("domain")); // host-only (no Domain attribute)
        assertDoesNotThrow(new ExecutableWithSpec(spec, okHost, httpsOrigin));

        // FAIL: __Host- with Domain attribute present
        final Cookie badHost = parseOne(spec, "__Host-sid=1; Domain=example.com; Path=/; Secure", httpsOrigin);
        assertThrows(MalformedCookieException.class, new ExecutableWithSpec(spec, badHost, httpsOrigin));

        // FAIL: SameSite=None without Secure
        final Cookie badSS = parseOne(spec, "sid=1; Path=/; SameSite=None", httpOrigin);
        assertThrows(MalformedCookieException.class, new ExecutableWithSpec(spec, badSS, httpOrigin));
    }

    @Test
    void store_strict_drops_non_compliant_programmatic_cookies() {
        final PrefixAwareCookieStore store = new PrefixAwareCookieStore(
                new org.apache.hc.client5.http.cookie.BasicCookieStore(), PrefixAwareCookieStore.Mode.STRICT);

        // __Secure- without Secure -> drop
        final BasicClientCookie badSecure = new BasicClientCookie("__Secure-id", "1");
        badSecure.setPath("/");
        badSecure.setSecure(false);
        store.addCookie(badSecure);
        assertTrue(store.getCookies().isEmpty(), "non-compliant __Secure- cookie must be dropped");

        final BasicClientCookie badHost = new BasicClientCookie("__Host-sid", "1");
        badHost.setPath("/");
        badHost.setSecure(true);
        badHost.setDomain("example.com");

        badHost.setAttribute(Cookie.DOMAIN_ATTR, "example.com");

        store.addCookie(badHost);
        assertTrue(store.getCookies().isEmpty(), "non-compliant __Host- cookie (Domain present) must be dropped");


        // __Host- valid -> accept
        final BasicClientCookie okHost = new BasicClientCookie("__Host-sid", "1");
        okHost.setPath("/");
        okHost.setSecure(true);
        okHost.setAttribute(Cookie.PATH_ATTR, "/"); // <-- required: explicit Path=/
        store.addCookie(okHost);
        assertEquals(1, store.getCookies().size(), "valid __Host- cookie should be stored");
    }

    @Test
    void parse_then_store_end_to_end() throws Exception {
        // Registry/spec (with PrefixValidationHandler)
        final RegistryBuilder<CookieSpecFactory> builder = CookieSpecSupport.createDefaultBuilder();
        builder.register("rfc6265bis", new Rfc6265bisCookieSpecFactory());
        final Lookup<CookieSpecFactory> registry = builder.build();
        final CookieSpec spec = registry.lookup("rfc6265bis").create(null);

        // Strict store (PrefixAwareCookieStore)
        final PrefixAwareCookieStore store = new PrefixAwareCookieStore(
                new org.apache.hc.client5.http.cookie.BasicCookieStore(), PrefixAwareCookieStore.Mode.STRICT);

        final CookieOrigin httpsOrigin = new CookieOrigin("www.example.com", 443, "/", true);

        // Valid cookie passes spec.validate and is accepted by the store
        final Cookie good = parseOne(spec, "__Host-token=abc; Path=/; Secure", httpsOrigin);
        assertDoesNotThrow(new ExecutableWithSpec(spec, good, httpsOrigin));
        store.addCookie(good);
        assertEquals(1, store.getCookies().size());

        // Invalid cookie is rejected by spec.validate even before store sees it
        final Cookie bad = parseOne(spec, "__Host-token=abc; Domain=example.com; Path=/; Secure", httpsOrigin);
        assertThrows(MalformedCookieException.class, new ExecutableWithSpec(spec, bad, httpsOrigin));
        // (not added to store)
        assertEquals(1, store.getCookies().size(), "store should still contain only the valid cookie");
    }

    private static final class ExecutableWithSpec implements org.junit.jupiter.api.function.Executable {
        private final CookieSpec spec;
        private final Cookie cookie;
        private final CookieOrigin origin;

        ExecutableWithSpec(final CookieSpec spec, final Cookie cookie, final CookieOrigin origin) {
            this.spec = spec;
            this.cookie = cookie;
            this.origin = origin;
        }

        @Override
        public void execute() throws Throwable {
            spec.validate(cookie, origin);
        }
    }

    @Test
    void empty_name_with_prefixed_value_is_ignored_by_store() {
        final PrefixAwareCookieStore store = new PrefixAwareCookieStore(new BasicCookieStore(), PrefixAwareCookieStore.Mode.STRICT);

        final BasicClientCookie bad = new BasicClientCookie("", "__Host-anything");
        bad.setPath("/");
        bad.setSecure(true);
        store.addCookie(bad);

        assertTrue(store.getCookies().isEmpty(), "Empty-name + prefixed value must be dropped");
    }
}
