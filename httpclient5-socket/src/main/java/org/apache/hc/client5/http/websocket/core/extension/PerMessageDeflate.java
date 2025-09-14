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
package org.apache.hc.client5.http.websocket.core.extension;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.apache.hc.client5.http.websocket.core.frame.FrameHeaderBits;

public final class PerMessageDeflate implements Extension {

    private final boolean enabled;
    private final boolean serverNoContextTakeover;
    private final boolean clientNoContextTakeover;
    private final Integer clientMaxWindowBits; // negotiated or null
    private final Integer serverMaxWindowBits; // negotiated or null

    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // raw DEFLATE
    private final Inflater inflater = new Inflater(true);

    private static final byte[] TAIL = new byte[]{0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

    public PerMessageDeflate(final boolean enabled,
                             final boolean serverNoContextTakeover,
                             final boolean clientNoContextTakeover,
                             final Integer clientMaxWindowBits,
                             final Integer serverMaxWindowBits) {
        this.enabled = enabled;
        this.serverNoContextTakeover = serverNoContextTakeover;
        this.clientNoContextTakeover = clientNoContextTakeover;
        this.clientMaxWindowBits = clientMaxWindowBits;
        this.serverMaxWindowBits = serverMaxWindowBits;
    }

    // ---- Extension API ----

    @Override
    public int rsvMask() {
        // PMCE uses RSV1 per RFC 7692
        return FrameHeaderBits.RSV1; // RSV1 bit in the WebSocket frame header
    }

    @Override
    public Encoded encode(final byte[] data, final boolean first, final boolean fin) {
        if (!enabled) {
            return new Encoded(data, false);
        }
        final byte[] out = first && fin
                ? compressMessage(data)          // single-frame message
                : compressFragment(data, fin);   // streaming fragment
        // signal that RSV must be set on the FIRST compressed data frame only
        return new Encoded(out, first);
    }

    @Override
    public byte[] decode(final byte[] compressedMessage) {
        if (!enabled) {
            return compressedMessage;
        }
        return decompressMessage(compressedMessage);
    }

    // ---- helpers ----

    public byte[] compressMessage(final byte[] data) {
        return doDeflate(data, /*fin=*/true, /*stripTail=*/true, /*maybeReset=*/clientNoContextTakeover);
    }

    public byte[] compressFragment(final byte[] data, final boolean fin) {
        return doDeflate(data, fin, /*stripTail=*/true, /*maybeReset=*/fin && clientNoContextTakeover);
    }

    public byte[] decompressMessage(final byte[] compressed) {
        return doInflate(compressed, /*fin=*/true, /*maybeReset=*/serverNoContextTakeover);
    }

    private byte[] doDeflate(final byte[] data,
                             final boolean fin,
                             final boolean stripTail,
                             final boolean maybeReset) {
        if (data == null || data.length == 0) {
            if (maybeReset) {
                deflater.reset();
            }
            return new byte[0];
        }

        deflater.setInput(data);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, data.length / 2));
        final byte[] buf = new byte[8192];

        while (!deflater.needsInput()) {
            final int n = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            if (n > 0) {
                out.write(buf, 0, n);
            } else {
                break;
            }
        }

        byte[] all = out.toByteArray();
        if (stripTail && all.length >= 4) {
            final int newLen = all.length - 4; // strip 00 00 FF FF
            if (newLen <= 0) {
                all = new byte[0];
            } else {
                final byte[] trimmed = new byte[newLen];
                System.arraycopy(all, 0, trimmed, 0, newLen);
                all = trimmed;
            }
        }

        if (fin && maybeReset) {
            deflater.reset();
        }
        return all;
    }

    private byte[] doInflate(final byte[] compressed,
                             final boolean fin,
                             final boolean maybeReset) {
        final byte[] withTail;
        if (compressed == null || compressed.length == 0) {
            withTail = TAIL.clone();
        } else {
            withTail = new byte[compressed.length + 4];
            System.arraycopy(compressed, 0, withTail, 0, compressed.length);
            System.arraycopy(TAIL, 0, withTail, compressed.length, 4);
        }

        inflater.setInput(withTail);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, withTail.length * 2));
        final byte[] buf = new byte[8192];
        try {
            while (!inflater.needsInput()) {
                final int n = inflater.inflate(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                } else {
                    break;
                }
            }
        } catch (final Exception e) {
            throw new RuntimeException("permessage-deflate inflate failed", e);
        }

        if (fin && maybeReset) {
            inflater.reset();
        }
        return out.toByteArray();
    }

    // (optional) getters for logging/tests
    public boolean isEnabled() {
        return enabled;
    }

    public boolean isServerNoContextTakeover() {
        return serverNoContextTakeover;
    }

    public boolean isClientNoContextTakeover() {
        return clientNoContextTakeover;
    }

    public Integer getClientMaxWindowBits() {
        return clientMaxWindowBits;
    }

    public Integer getServerMaxWindowBits() {
        return serverMaxWindowBits;
    }
}
