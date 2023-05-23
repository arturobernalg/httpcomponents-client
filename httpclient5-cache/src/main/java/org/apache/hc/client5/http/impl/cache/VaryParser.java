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
package org.apache.hc.client5.http.impl.cache;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.FormattedHeader;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.hc.core5.util.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The VaryParser class is responsible for parsing HTTP 'Vary' response headers.
 *
 * <p>
 * The class provides functionality to iterate over 'Vary' headers and construct
 * a VaryHeader object that encapsulates the set of header fields indicated in the 'Vary' header
 * and whether it includes a wildcard (*).
 * </p>
 *
 * <p>
 * This class is thread-safe and is designed to be used as a singleton instance.
 * </p>
 *
 * @see VaryHeader
 * @since 5.3
 */
@Internal
@Contract(threading = ThreadingBehavior.SAFE)
class VaryParser {

    /**
     * The singleton instance of this parser.
     */
    public static final VaryParser INSTANCE = new VaryParser();

    /**
     * The logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(VaryParser.class);

    /**
     * The set of characters that can delimit a token in the header.
     */
    private static final BitSet TOKEN_DELIMS = Tokenizer.INIT_BITSET(',');


    /**
     * The token parser used to extract values from the header.
     */
    private final Tokenizer tokenParser;

    /**
     * Constructs a new instance of this parser.
     */
    protected VaryParser() {
        super();
        this.tokenParser = Tokenizer.INSTANCE;
    }

    /**
     * Parses an iterator of 'Vary' headers and constructs a VaryHeader object.
     *
     * <p>
     * This method iterates over 'Vary' headers, tokenizing each one and adding
     * each token to a set. If a wildcard (*) is found, it's flagged in the resulting VaryHeader object.
     * </p>
     *
     * @param headerIterator an iterator of 'Vary' headers to parse
     * @return a VaryHeader object that encapsulates the set of header fields indicated in the 'Vary' headers
     * and whether a wildcard (*) was found
     * @throws NullPointerException if headerIterator is null
     */
    public final VaryHeader parse(final Iterator<Header> headerIterator) {
        Args.notNull(headerIterator, "headerIterator");
        final Set<String> result = new HashSet<>();
        boolean hasWildcard = false;
        while (headerIterator.hasNext()) {
            final Header header = headerIterator.next();
            if (header == null) {
                continue; // ignore null headers
            }
            final CharArrayBuffer buffer;
            final Tokenizer.Cursor cursor;
            if (header instanceof FormattedHeader) {
                buffer = ((FormattedHeader) header).getBuffer();
                cursor = new Tokenizer.Cursor(((FormattedHeader) header).getValuePos(), buffer.length());
            } else {
                final String s = header.getValue();
                if (s == null) {
                    return new VaryHeader();

                }
                buffer = new CharArrayBuffer(s.length());
                buffer.append(s);
                cursor = new Tokenizer.Cursor(0, buffer.length());
            }

            while (!cursor.atEnd()) {
                final String name = tokenParser.parseToken(buffer, cursor, TOKEN_DELIMS);
                if ("*" .equals(name)) {
                    hasWildcard = true;
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Vary header field has  wildcard  {}: ", header);
                    }
                }
                result.add(name);
                if (!cursor.atEnd()) {
                    cursor.updatePos(cursor.getPos() + 1);
                }
            }
        }
        return new VaryHeader(result, hasWildcard);
    }
}
