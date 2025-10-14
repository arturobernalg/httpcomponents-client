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
 * Parses {@code SameSite} and enforces {@code SameSite=None} ⇒ {@code Secure}.
 * Delivery filtering by site context is a user-agent concern and is not enforced here.
 */
@Contract(threading = ThreadingBehavior.STATELESS)
@Internal
public final class SameSiteAttributeHandler implements CommonCookieAttributeHandler {

    public static final String ATTR = "samesite";

    @Override
    public void parse(final SetCookie cookie, final String value) throws MalformedCookieException {
        // No-op: RFC6265CookieSpec already stores attributes into BasicClientCookie
        // under lower-cased keys (e.g. "samesite").
    }

    @Override
    public void validate(final Cookie cookie, final CookieOrigin origin) throws MalformedCookieException {
        if (cookie instanceof BasicClientCookie) {
            final String v = cookie.getAttribute(ATTR);
            if ("none".equalsIgnoreCase(v) && !cookie.isSecure()) {
                throw new MalformedCookieException("SameSite=None requires Secure");
            }
        }
    }

    @Override
    public boolean match(final Cookie cookie, final CookieOrigin origin) {
        // Site-context delivery is a browser concern; do not filter here.
        return true;
    }

    @Override
    public String getAttributeName() {
        return ATTR;
    }
}
