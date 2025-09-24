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
package org.apache.hc.client5.http.websocket.httpcore;

import java.nio.ByteBuffer;

import org.apache.hc.client5.http.websocket.core.close.WsProtocolException;
import org.apache.hc.client5.http.websocket.core.frame.Opcode;

public final class WsDecoder {
    private final int maxFrameSize;
    private final boolean strictNoExtensions;

    private int opcode;
    private boolean fin;
    private boolean rsv1, rsv2, rsv3;
    private ByteBuffer payload = ByteBuffer.allocate(0);

    // Keep old 1-arg ctor strict by default so existing tests keep working.
    public WsDecoder(final int maxFrameSize) {
        this(maxFrameSize, true);
    }

    /**
     * @param strictNoExtensions when true, any RSV bit triggers 1002 unless an extension negotiated at this layer.
     *                           When false, decoder only parses and exposes RSV flags without enforcing policy.
     */
    public WsDecoder(final int maxFrameSize, final boolean strictNoExtensions) {
        this.maxFrameSize = maxFrameSize;
        this.strictNoExtensions = strictNoExtensions;
    }

    public boolean decode(final ByteBuffer in) {
        in.mark();
        if (in.remaining() < 2) {
            in.reset();
            return false;
        }

        final int b0 = in.get() & 0xFF;
        final int b1 = in.get() & 0xFF;

        fin = (b0 & 0x80) != 0;
        rsv1 = (b0 & 0x40) != 0;
        rsv2 = (b0 & 0x20) != 0;
        rsv3 = (b0 & 0x10) != 0;

        // Enforce when strict
        if (strictNoExtensions && (rsv1 || rsv2 || rsv3)) {
            throw new WsProtocolException(1002, "RSV bits set without extension");
        }

        opcode = b0 & 0x0F;

        final boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        // Server->client frames MUST NOT be masked
        if (masked) {
            throw new WsProtocolException(1002, "Server frame is masked");
        }

        if (len == 126) {
            if (in.remaining() < 2) {
                in.reset();
                return false;
            }
            len = in.getShort() & 0xFFFF;
        } else if (len == 127) {
            if (in.remaining() < 8) {
                in.reset();
                return false;
            }
            final long l = in.getLong();
            if (l < 0) {
                throw new WsProtocolException(1002, "Negative length");
            }
            len = l;
        }

        if (Opcode.isControl(opcode)) {
            if (!fin) {
                throw new WsProtocolException(1002, "fragmented control frame");
            }
            if (len > 125) {
                throw new WsProtocolException(1002, "control frame too large");
            }
        }

        if (len > Integer.MAX_VALUE || maxFrameSize > 0 && len > maxFrameSize) {
            throw new WsProtocolException(1009, "Frame too large: " + len);
        }

        if (in.remaining() < len) {
            in.reset();
            return false;
        }

        final ByteBuffer data = ByteBuffer.allocate((int) len);
        for (int i = 0; i < len; i++) {
            data.put(in.get());
        }
        data.flip();
        payload = data.asReadOnlyBuffer();
        return true;
    }

    public int opcode() {
        return opcode;
    }

    public boolean fin() {
        return fin;
    }

    public boolean rsv1() {
        return rsv1;
    }

    public boolean rsv2() {
        return rsv2;
    }

    public boolean rsv3() {
        return rsv3;
    }

    public ByteBuffer payload() {
        return payload.asReadOnlyBuffer();
    }
}
