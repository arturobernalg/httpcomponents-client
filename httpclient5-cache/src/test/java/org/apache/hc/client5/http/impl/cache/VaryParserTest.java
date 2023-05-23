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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;


import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

public class VaryParserTest {

    private final VaryParser parser = VaryParser.INSTANCE;

    @Test
    public void testParseNullVaryHeader() {
        final Header header = null;
        final VaryHeader varyHeader = parser.parse(Collections.singletonList(header).iterator());

        assertTrue(varyHeader.getHeaders().isEmpty());
        assertFalse(varyHeader.hasWildcard());
    }

    @Test
    public void testParseSingleVaryHeader() {
        final Header header = new BasicHeader("Vary", "Accept-Encoding");
        final VaryHeader varyHeader = parser.parse(Collections.singletonList(header).iterator());

        assertEquals(1, varyHeader.getHeaders().size());
        assertTrue(varyHeader.getHeaders().contains("Accept-Encoding"));
        assertFalse(varyHeader.hasWildcard());
    }

    @Test
    public void testParseMultipleVaryHeaders() {
        final Header header1 = new BasicHeader("Vary", "Accept-Encoding");
        final Header header2 = new BasicHeader("Vary", "Accept-Language");
        final VaryHeader varyHeader = parser.parse(Arrays.asList(header1, header2).iterator());

        assertEquals(2, varyHeader.getHeaders().size());
        assertTrue(varyHeader.getHeaders().contains("Accept-Encoding"));
        assertTrue(varyHeader.getHeaders().contains("Accept-Language"));
        assertFalse(varyHeader.hasWildcard());
    }

    @Test
    public void testParseWildcardVaryHeader() {
        final Header header = new BasicHeader("Vary", "*");
        final VaryHeader varyHeader = parser.parse(Collections.singletonList(header).iterator());
        assertTrue(varyHeader.hasWildcard());
    }

    @Test
    public void testParseVaryHeaderWithMultipleValues() {
        final Header header = new BasicHeader("Vary", "Accept-Encoding, Accept-Language");
        final VaryHeader varyHeader = parser.parse(Collections.singletonList(header).iterator());

        assertEquals(2, varyHeader.getHeaders().size());
        assertTrue(varyHeader.getHeaders().contains("Accept-Encoding"));
        assertTrue(varyHeader.getHeaders().contains("Accept-Language"));
        assertFalse(varyHeader.hasWildcard());
    }

    @Test
    public void testParseEmptyVaryHeader() {
        final Header header = new BasicHeader("Vary", "");
        final VaryHeader varyHeader = parser.parse(Collections.singletonList(header).iterator());

        assertTrue(varyHeader.getHeaders().isEmpty());
        assertFalse(varyHeader.hasWildcard());
    }

}
