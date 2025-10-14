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

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieStore;

public final class BisCookieStore implements CookieStore {

    private final List<Cookie> cookies = new CopyOnWriteArrayList<>();

    private static boolean isHostOnly(final Cookie c) {
        return c instanceof BasicClientCookie && !c.containsAttribute(Cookie.DOMAIN_ATTR);
    }

    private static boolean sameKey(final Cookie a, final Cookie b) {
        return Objects.equals(a.getName(), b.getName())
                && Objects.equals(a.getDomain(), b.getDomain())
                && Objects.equals(a.getPath(), b.getPath())
                && isHostOnly(a) == isHostOnly(b);
    }

    @Override
    public void addCookie(final Cookie cookie) {
        if (cookie == null) return;
        final Instant now = Instant.now();
        final Instant exp = cookie.getExpiryInstant();
        if (exp != null && !exp.isAfter(now)) return;

        for (int i = 0; i < cookies.size(); i++) {
            if (sameKey(cookies.get(i), cookie)) {
                cookies.set(i, cookie); // COW-safe
                return;
            }
        }
        cookies.add(cookie);
    }

    @Override
    public List<Cookie> getCookies() {
        return Collections.unmodifiableList(cookies);
    }

    @Override
    public boolean clearExpired(final Date date) {
        final Instant t = date.toInstant();
        return cookies.removeIf(c -> {
            final Instant exp = c.getExpiryInstant();
            return exp != null && !exp.isAfter(t);
        });
    }

    @Override
    public void clear() {
        cookies.clear();
    }
}