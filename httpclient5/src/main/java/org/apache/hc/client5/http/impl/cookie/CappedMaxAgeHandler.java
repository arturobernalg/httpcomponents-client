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

import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.SetCookie;

public final class CappedMaxAgeHandler extends org.apache.hc.client5.http.impl.cookie.LaxMaxAgeHandler {
    private static final long MAX_AGE_SECONDS = 34560000L; // 400 days

    @Override
    public void parse(final SetCookie cookie, final String value) {
        if (value == null) {
            return;
        }
        try {
            long delta = Long.parseLong(value.trim());
            if (delta <= 0) {
                cookie.setExpiryDate(Instant.EPOCH); // expire immediately
                return;
            }
            if (delta > MAX_AGE_SECONDS) {
                delta = MAX_AGE_SECONDS;
            }
            cookie.setExpiryDate(Instant.now().plusSeconds(delta));
        } catch (final NumberFormatException ignore) {
        }
    }

    @Override
    public String getAttributeName() { return Cookie.MAX_AGE_ATTR; }
}
