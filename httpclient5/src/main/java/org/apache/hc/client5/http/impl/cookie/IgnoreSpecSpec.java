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

import java.util.Collections;
import java.util.List;

import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieOrigin;
import org.apache.hc.client5.http.cookie.MalformedCookieException;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.Header;

/**
 * CookieSpec that ignores all cookies
 *
 * @since 4.1
 */
@Contract(threading = ThreadingBehavior.STATELESS)
public class IgnoreSpecSpec extends CookieSpecBase {

    /**
     * Default instance of {@link IgnoreSpecSpec}.
     *
     * @since 5.2
     */
    public static final IgnoreSpecSpec INSTANCE = new IgnoreSpecSpec();

    @Override
    public List<Cookie> parse(final Header header, final CookieOrigin origin)
            throws MalformedCookieException {
        return Collections.emptyList();
    }

    @Override
    public boolean match(final Cookie cookie, final CookieOrigin origin) {
        return false;
    }

    @Override
    public List<Header> formatCookies(final List<Cookie> cookies) {
        return Collections.emptyList();
    }

}
