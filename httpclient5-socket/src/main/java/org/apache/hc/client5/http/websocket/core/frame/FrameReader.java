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
package org.apache.hc.client5.http.websocket.core.frame;

import java.nio.ByteBuffer;

/**
 * Minimal RFC6455 frame reader (no extensions negotiated here).
 */
public final class FrameReader {

    private final int maxFrameSize;

    private int opcode;
    private boolean fin;
    private ByteBuffer payload = ByteBuffer.allocate(0);

    public FrameReader(final int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    public boolean decode(final ByteBuffer in) {
        in.mark();

        // Peek first octet so we can fail fast on RSV even if only 1 byte is available.
        if (in.remaining() < 1) {
            in.reset();
            return false;
        }
        final int first = in.get(in.position()) & 0xFF;
        if ((first & 0x70) != 0) {
            throw new IllegalStateException("RSV bits set without extension");
        }

        // Need 2 bytes for the base header
        if (in.remaining() < 2) {
            in.reset();
            return false;
        }

        final int b0 = in.get() & 0xFF;
        final int b1 = in.get() & 0xFF;

        fin = (b0 & 0x80) != 0;
        opcode = b0 & 0x0F;

        final boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        // RFC6455 §5.1: server-to-client frames MUST NOT be masked
        if (masked) {
            throw new IllegalStateException("Server frame is masked");
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
                throw new IllegalStateException("Negative length");
            }
            len = l;
        }

        if (Opcode.isControl(opcode)) {
            if (!fin) {
                throw new IllegalStateException("fragmented control frame");
            }
            if (len > 125) {
                throw new IllegalStateException("control frame too large");
            }
        }

        if (len > Integer.MAX_VALUE || maxFrameSize > 0 && len > maxFrameSize) {
            throw new IllegalStateException("Frame too large: " + len);
        }

        // No masking key section (masked == false)
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

    public ByteBuffer payload() {
        return payload.asReadOnlyBuffer();
    }
}
