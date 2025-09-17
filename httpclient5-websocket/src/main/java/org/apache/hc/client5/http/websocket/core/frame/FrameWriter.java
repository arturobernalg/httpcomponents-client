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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public final class FrameWriter {
    public static final int FIN = 0x80;
    public static final int RSV1 = 0x40;
    public static final int RSV2 = 0x20;
    public static final int RSV3 = 0x10;

    private static final int MASK_BIT = 0x80;

    public ByteBuffer text(final CharSequence data, final boolean fin) {
        return frame(Opcode.TEXT, StandardCharsets.UTF_8.encode(data.toString()), fin, true);
    }

    public ByteBuffer binary(final ByteBuffer data, final boolean fin) {
        return frame(Opcode.BINARY, data, fin, true);
    }

    public ByteBuffer close(final int code, final String reason) {
        final ByteBuffer reasonBuf = reason != null && !reason.isEmpty()
                ? StandardCharsets.UTF_8.encode(reason)
                : ByteBuffer.allocate(0);
        final ByteBuffer p = ByteBuffer.allocate(2 + reasonBuf.remaining());
        p.put((byte) (code >> 8 & 0xFF)).put((byte) (code & 0xFF));
        if (reasonBuf.hasRemaining()) {
            p.put(reasonBuf);
        }
        p.flip();
        return frame(Opcode.CLOSE, p, true, true);
    }

    public ByteBuffer closeEcho(final ByteBuffer payload) {
        return frame(Opcode.CLOSE, payload.asReadOnlyBuffer(), true, true);
    }

    /**
     * Standard frame builder (no RSV bits beyond those implied by opcode).
     */
    public ByteBuffer frame(final int opcode, final ByteBuffer payload, final boolean fin, final boolean mask) {
        return frameWithRSV(opcode, payload, fin, mask, 0);
    }

    /**
     * Frame builder with explicit RSV bits (e.g., RSV1 for permessage-deflate).
     */
    public ByteBuffer frameWithRSV(final int opcode, final ByteBuffer payload, final boolean fin, final boolean mask, final int rsvBits) {
        final int len = payload == null ? 0 : payload.remaining();
        final int hdrExtra = len <= 125 ? 0 : len <= 0xFFFF ? 2 : 8;
        final int maskLen = mask ? 4 : 0;
        final ByteBuffer out = ByteBuffer.allocate(2 + hdrExtra + maskLen + len);

        final int finBit = fin ? FIN : 0;
        out.put((byte) (finBit | rsvBits & (RSV1 | RSV2 | RSV3) | opcode & 0x0F));

        if (len <= 125) {
            out.put((byte) ((mask ? MASK_BIT : 0) | len));
        } else if (len <= 0xFFFF) {
            out.put((byte) ((mask ? MASK_BIT : 0) | 126));
            out.putShort((short) len);
        } else {
            out.put((byte) ((mask ? MASK_BIT : 0) | 127));
            out.putLong(len);
        }

        int[] mkey = null;
        if (mask) {
            mkey = new int[]{rnd(), rnd(), rnd(), rnd()};
            out.put((byte) mkey[0]).put((byte) mkey[1]).put((byte) mkey[2]).put((byte) mkey[3]);
        }

        if (len > 0) {
            final int pos = payload.position(), lim = payload.limit();
            for (int i = pos; i < lim; i++) {
                int b = payload.get(i) & 0xFF;
                if (mask) {
                    b ^= mkey[i - pos & 3];
                }
                out.put((byte) b);
            }
            payload.position(lim);
        }

        out.flip();
        return out;
    }

    private static int rnd() {
        return ThreadLocalRandom.current().nextInt(256);
    }
}
