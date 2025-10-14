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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hc.client5.http.cookie.CommonCookieAttributeHandler;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieAttributeHandler;
import org.apache.hc.client5.http.cookie.CookieOrigin;
import org.apache.hc.client5.http.cookie.CookiePriorityComparator;
import org.apache.hc.client5.http.cookie.CookieSpec;
import org.apache.hc.client5.http.cookie.MalformedCookieException;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.FormattedHeader;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.message.BufferedHeader;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.hc.core5.util.Tokenizer;

/**
 * Cookie management functions shared by RFC 6265 compliant specification.
 *
 * @since 4.5
 */
@Contract(threading = ThreadingBehavior.SAFE)
    public class RFC6265CookieSpec implements CookieSpec {

    private final static char PARAM_DELIMITER = ';';
    private final static char COMMA_CHAR = ',';
    private final static char EQUAL_CHAR = '=';
    private final static char DQUOTE_CHAR = '"';
    private final static char ESCAPE_CHAR = '\\';

    private static final Tokenizer.Delimiter TOKEN_DELIMS = Tokenizer.delimiters(EQUAL_CHAR, PARAM_DELIMITER);
    private static final Tokenizer.Delimiter VALUE_DELIMS = Tokenizer.delimiters(PARAM_DELIMITER);
    private static final Tokenizer.Delimiter SPECIAL_CHARS = Tokenizer.delimiters(' ',
            DQUOTE_CHAR, COMMA_CHAR, PARAM_DELIMITER, ESCAPE_CHAR);

    private final CookieAttributeHandler[] attribHandlers;
    private final Map<String, CookieAttributeHandler> attribHandlerMap;
    private final Tokenizer tokenParser;

    private static final int MAX_NAME_VALUE_LEN = 4096;
    private static final int MAX_ATTR_VALUE_LEN = 1024;

    private static boolean isCtlExcludingHtab(final char ch) {
        return ch >= 0x00 && ch <= 0x08 || ch >= 0x0A && ch <= 0x1F || ch == 0x7F;
    }

    private static boolean headerContainsCtlExcludingHtab(final CharArrayBuffer buf, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (isCtlExcludingHtab(buf.charAt(i))) {
                return true;
            }
        }
        return false;
    }


    protected RFC6265CookieSpec(final CommonCookieAttributeHandler... handlers) {
        super();
        this.attribHandlers = handlers.clone();
        this.attribHandlerMap = new ConcurrentHashMap<>(handlers.length);
        for (final CommonCookieAttributeHandler handler : handlers) {
            this.attribHandlerMap.put(handler.getAttributeName().toLowerCase(Locale.ROOT), handler);
        }
        this.tokenParser = Tokenizer.INSTANCE;
    }

    static String getDefaultPath(final CookieOrigin origin) {
        return CookieSpecBase.getDefaultPath(origin);
    }

    static String getDefaultDomain(final CookieOrigin origin) {
        return origin.getHost();
    }

    @Override
    public final List<Cookie> parse(final Header header, final CookieOrigin origin)
            throws MalformedCookieException {
        Args.notNull(header, "Header");
        Args.notNull(origin, "Cookie origin");
        if (!header.getName().equalsIgnoreCase("Set-Cookie")) {
            throw new MalformedCookieException("Unrecognized cookie header: '" + header + "'");
        }

        final CharArrayBuffer buffer;
        final Tokenizer.Cursor cursor;
        final int valuePos;
        if (header instanceof FormattedHeader) {
            buffer = ((FormattedHeader) header).getBuffer();
            valuePos = ((FormattedHeader) header).getValuePos();
            cursor = new Tokenizer.Cursor(valuePos, buffer.length());
        } else {
            final String s = header.getValue();
            if (s == null) {
                throw new MalformedCookieException("Header value is null");
            }
            buffer = new CharArrayBuffer(s.length());
            buffer.append(s);
            valuePos = 0;
            cursor = new Tokenizer.Cursor(0, buffer.length());
        }

        // 6265bis §5.6 (first step): reject CTLs (excluding HTAB)
        if (headerContainsCtlExcludingHtab(buffer, valuePos, buffer.length())) {
            return Collections.emptyList(); // ignore the set-cookie entirely
        }

        // name-value-pair (with nameless support)
        cursor.getPos();
        final String firstToken = tokenParser.parseToken(buffer, cursor, TOKEN_DELIMS);
        if (firstToken.isEmpty() && cursor.atEnd()) {
            return Collections.emptyList();
        }
        final String cookieName;
        final String cookieValue;

        if (cursor.atEnd()) {
            // "Set-Cookie: token" -> ("", "token")
            cookieName = "";
            cookieValue = firstToken;
        } else {
            final char delim = buffer.charAt(cursor.getPos());
            if (delim == EQUAL_CHAR) {
                cursor.updatePos(cursor.getPos() + 1);
                final String val = tokenParser.parseValue(buffer, cursor, VALUE_DELIMS);
                cookieName = firstToken;
                cookieValue = val;
                if (!cursor.atEnd() && buffer.charAt(cursor.getPos()) == PARAM_DELIMITER) {
                    cursor.updatePos(cursor.getPos() + 1);
                }
            } else {
                // No '=', treat name-value as value, and continue into attributes if present
                cookieName = "";
                cookieValue = firstToken;
                if (delim == PARAM_DELIMITER) {
                    cursor.updatePos(cursor.getPos() + 1);
                }
            }
        }

        // 6265bis §5.6 / §5.7: length guard on name+value
        if (cookieName.length() + cookieValue.length() > MAX_NAME_VALUE_LEN) {
            return Collections.emptyList();
        }

        final BasicClientCookie cookie = new BasicClientCookie(cookieName, cookieValue);
        cookie.setPath(getDefaultPath(origin));
        cookie.setDomain(getDefaultDomain(origin));
        cookie.setCreationDate(Instant.now());

        final Map<String, String> attribMap = new LinkedHashMap<>();
        while (!cursor.atEnd()) {
            cursor.getPos();
            final String rawName = tokenParser.parseToken(buffer, cursor, TOKEN_DELIMS);
            if (rawName.isEmpty()) break;
            final String paramName = rawName.toLowerCase(Locale.ROOT);

            boolean hasEq = false;
            if (!cursor.atEnd()) {
                final char delim = buffer.charAt(cursor.getPos());
                if (delim == EQUAL_CHAR) { hasEq = true; cursor.updatePos(cursor.getPos() + 1); }
                else if (delim == PARAM_DELIMITER) { cursor.updatePos(cursor.getPos() + 1); }
            }

            if (hasEq) {
                final String v = tokenParser.parseValue(buffer, cursor, VALUE_DELIMS);
                if (!cursor.atEnd() && buffer.charAt(cursor.getPos()) == PARAM_DELIMITER) {
                    cursor.updatePos(cursor.getPos() + 1);
                }
                if (v != null && v.length() <= MAX_ATTR_VALUE_LEN) {
                    cookie.setAttribute(paramName, v);
                    attribMap.put(paramName, v);
                } // else: ignore this cookie-av
            } else {
                // flag attribute
                cookie.setAttribute(paramName, null);
                attribMap.put(paramName, null);
            }
        }

        // Ignore 'Expires' if 'Max-Age' is present
        if (attribMap.containsKey(Cookie.MAX_AGE_ATTR)) {
            attribMap.remove(Cookie.EXPIRES_ATTR);
        }

        for (final Map.Entry<String, String> entry : attribMap.entrySet()) {
            final String paramName = entry.getKey();
            final String paramValue = entry.getValue();
            final CookieAttributeHandler handler = this.attribHandlerMap.get(paramName);
            if (handler != null) {
                handler.parse(cookie, paramValue);
            }
        }

        return Collections.singletonList(cookie);
    }

    @Override
    public final void validate(final Cookie cookie, final CookieOrigin origin)
            throws MalformedCookieException {
        Args.notNull(cookie, "Cookie");
        Args.notNull(origin, "Cookie origin");
        for (final CookieAttributeHandler handler : this.attribHandlers) {
            handler.validate(cookie, origin);
        }
    }

    @Override
    public final boolean match(final Cookie cookie, final CookieOrigin origin) {
        Args.notNull(cookie, "Cookie");
        Args.notNull(origin, "Cookie origin");
        for (final CookieAttributeHandler handler : this.attribHandlers) {
            if (!handler.match(cookie, origin)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Header> formatCookies(final List<Cookie> cookies) {
        Args.notEmpty(cookies, "List of cookies");
        final List<? extends Cookie> sortedCookies;
        if (cookies.size() > 1) {
            // Create a mutable copy and sort the copy.
            sortedCookies = new ArrayList<>(cookies);
            sortedCookies.sort(CookiePriorityComparator.INSTANCE);
        } else {
            sortedCookies = cookies;
        }
        final CharArrayBuffer buffer = new CharArrayBuffer(20 * sortedCookies.size());
        buffer.append("Cookie");
        buffer.append(": ");
        for (int n = 0; n < sortedCookies.size(); n++) {
            final Cookie cookie = sortedCookies.get(n);
            if (n > 0) {
                buffer.append(PARAM_DELIMITER);
                buffer.append(' ');
            }
            buffer.append(cookie.getName());
            final String s = cookie.getValue();
            if (s != null) {
                buffer.append(EQUAL_CHAR);
                if (containsSpecialChar(s)) {
                    buffer.append(DQUOTE_CHAR);
                    for (int i = 0; i < s.length(); i++) {
                        final char ch = s.charAt(i);
                        if (ch == DQUOTE_CHAR || ch == ESCAPE_CHAR) {
                            buffer.append(ESCAPE_CHAR);
                        }
                        buffer.append(ch);
                    }
                    buffer.append(DQUOTE_CHAR);
                } else {
                    buffer.append(s);
                }
            }
        }
        final List<Header> headers = new ArrayList<>(1);
        try {
            headers.add(new BufferedHeader(buffer));
        } catch (final ParseException ignore) {
            // should never happen
        }
        return headers;
    }

    boolean containsSpecialChar(final CharSequence s) {
        return containsChars(s, SPECIAL_CHARS);
    }

    boolean containsChars(final CharSequence s, final Tokenizer.Delimiter chars) {
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (chars.test(ch)) {
                return true;
            }
        }
        return false;
    }

}
