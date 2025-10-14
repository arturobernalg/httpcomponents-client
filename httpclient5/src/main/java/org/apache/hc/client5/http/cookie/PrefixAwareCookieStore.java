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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.hc.client5.http.impl.cookie.BisCookieStore;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Optional store wrapper that enforces RFC 6265bis prefix/SameSite rules at storage time.
 * Useful for programmatic cookies added via {@link CookieStore#addCookie}.
 */
@Internal
public final class PrefixAwareCookieStore implements CookieStore {
    private static final Logger LOG = LoggerFactory.getLogger(PrefixAwareCookieStore.class);

    public enum Mode { WARN, STRICT }

    private final CookieStore delegate;
    private final Mode mode;

    public PrefixAwareCookieStore() {
        this(new BisCookieStore(), Mode.WARN);
    }

    public PrefixAwareCookieStore(final CookieStore delegate, final Mode mode) {
        this.delegate = Args.notNull(delegate, "delegate");
        this.mode = Args.notNull(mode, "mode");
    }

    @Override
    public void addCookie(final Cookie cookie) {
        // Prefix + SameSite checks
        final String p = CookieValidators.validatePrefixConstraints(cookie);
        final String s = CookieValidators.validateSameSite(cookie);

        // Overlay protection (6265bis §5.7): block non-secure that would overlay a secure one
        final boolean overlayBlocked = blockNonSecureOverlay(cookie);

        final List<String> reasons = new ArrayList<>();
        if (p != null) reasons.add(p);
        if (s != null) reasons.add(s);
        if (overlayBlocked) reasons.add("non-secure cookie would overlay existing secure cookie");

        if (!reasons.isEmpty()) {
            final String reason = String.join("; ", reasons);
            if (mode == Mode.STRICT) {
                LOG.warn("Dropping cookie {}: {}", cookie.getName(), reason);
                return;
            }
            LOG.warn("Accepting cookie {} with warnings: {}", cookie.getName(), reason);
        }
        delegate.addCookie(cookie);
    }

    private boolean blockNonSecureOverlay(final Cookie incoming) {
        if (incoming == null || incoming.isSecure()) return false;

        final String n = incoming.getName();
        final String d = lower(incoming.getDomain());
        final String p = incoming.getPath() != null ? incoming.getPath() : "/";

        for (final Cookie c : delegate.getCookies()) {
            if (!c.isSecure()) continue;
            if (!n.equals(c.getName())) continue;

            final String ed = lower(c.getDomain());
            final String ep = c.getPath() != null ? c.getPath() : "/";

            if (domainMatchEitherWay(d, ed) && pathMatches(p, ep)) {
                return true; // block overlay
            }
        }
        return false;
    }

    private static String lower(final String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static boolean domainMatchEitherWay(final String a, final String b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        return a.endsWith("." + b) || b.endsWith("." + a);
    }

    // 6265bis path-match, used as: newPath path-matches existingPath
    private static boolean pathMatches(final String newPath, final String existingPath) {
        if (Objects.equals(newPath, existingPath)) return true;
        if (existingPath.equals("/")) return true;
        if (newPath.startsWith(existingPath)) {
            if (existingPath.endsWith("/")) return true;
            return newPath.length() > existingPath.length() && newPath.charAt(existingPath.length()) == '/';
        }
        return false;
    }

    @Override
    public List<Cookie> getCookies() {
        return delegate.getCookies();
    }

    @Override
    public boolean clearExpired(final Date date) {
        return delegate.clearExpired(date);
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}