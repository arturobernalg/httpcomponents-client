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
package org.apache.hc.client5.http.websocket.core.message;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Helpers for RFC6455 CLOSE parsing & validation.
 */
public final class CloseCodec {

    private CloseCodec() {
    }

    public static int readCloseCode(final ByteBuffer p) {
        if (p.remaining() >= 2) {
            final int b1 = p.get() & 0xFF;
            final int b2 = p.get() & 0xFF;
            return b1 << 8 | b2;
        }
        return 1005; // no status
    }

    public static String readCloseReason(final ByteBuffer p) {
        if (p.remaining() == 0) {
            return "";
        }
        final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            dec.decode(p.asReadOnlyBuffer());
            return StandardCharsets.UTF_8.decode(p.slice()).toString();
        } catch (final CharacterCodingException e) {
            return ""; // handler will usually react with 1007 when validating
        }
    }

    /**
     * RFC6455 valid received status codes.
     */
    public static boolean isValidCloseCodeReceived(final int code) {
        if (code < 1000) {
            return false;
        }
        switch (code) {
            case 1000: // normal
            case 1001: // going away
            case 1002: // protocol error
            case 1003: // unsupported data
            case 1007: // invalid payload
            case 1008: // policy violation
            case 1009: // message too big
            case 1010: // mandatory extension
            case 1011: // internal error
                return true;
            // disallowed on wire: 1004, 1005, 1006, 1015
            default:
                return code >= 3000 && code <= 4999;
        }
    }
}
