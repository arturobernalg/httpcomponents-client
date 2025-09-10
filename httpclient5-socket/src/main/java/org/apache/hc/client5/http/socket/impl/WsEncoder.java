package org.apache.hc.client5.http.socket.impl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

final class WsEncoder {
    private static final int MASK_BIT = 0x80;

    ByteBuffer text(final CharSequence data, final boolean fin) {
        return frame(WsOpcode.TEXT, StandardCharsets.UTF_8.encode(data.toString()), fin, true);
    }

    ByteBuffer binary(final ByteBuffer data, final boolean fin) {
        return frame(WsOpcode.BINARY, data, fin, true);
    }

    ByteBuffer close(final int code, final String reason) {
        if (!WsCodec.isValidCloseCodeToSend(code)) {
            throw new IllegalArgumentException("Invalid close code: " + code);
        }
        final ByteBuffer reasonBuf = (reason != null && !reason.isEmpty())
                ? StandardCharsets.UTF_8.encode(reason) : ByteBuffer.allocate(0);
        if (reasonBuf.remaining() > 123) {
            throw new IllegalArgumentException("Close reason too long");
        }
        final ByteBuffer p = ByteBuffer.allocate(2 + reasonBuf.remaining());
        p.put((byte) ((code >> 8) & 0xFF)).put((byte) (code & 0xFF));
        if (reasonBuf.hasRemaining()) {
            p.put(reasonBuf);
        }
        p.flip();
        return frameRaw(WsOpcode.CLOSE, p, true, true, false);
    }

    ByteBuffer closeEcho(final ByteBuffer payload) {
        return frameRaw(WsOpcode.CLOSE, payload, true, true, false);
    }

    ByteBuffer frame(final int opcode, final ByteBuffer payload, final boolean fin, final boolean mask) {
        return frameRaw(opcode, payload, fin, mask, false);
    }

    ByteBuffer frameCompressedFirst(final int opcode, final ByteBuffer payload, final boolean fin, final boolean mask) {
        // RSV1 must be set only on first frame of compressed message
        return frameRaw(opcode, payload, fin, mask, true);
    }

    private ByteBuffer frameRaw(final int opcode, final ByteBuffer payload, final boolean fin, final boolean mask, final boolean rsv1) {
        final int len = payload.remaining();
        if (isControl(opcode)) {
            if (!fin) {
                throw new IllegalArgumentException("Control frames MUST NOT be fragmented");
            }
            if (len > 125) {
                throw new IllegalArgumentException("Control frame payload too large");
            }
            if (rsv1) {
                throw new IllegalArgumentException("RSV1 on control frame");
            }
        }
        final int hdrExtra = len <= 125 ? 0 : (len <= 0xFFFF ? 2 : 8);
        final int maskLen = mask ? 4 : 0;
        final ByteBuffer out = ByteBuffer.allocate(2 + hdrExtra + maskLen + len);

        int b0 = (fin ? 0x80 : 0x00) | (opcode & 0x0F);
        if (rsv1) {
            b0 |= 0x40;
        }
        out.put((byte) b0);
        if (len <= 125) {
            out.put((byte) ((mask ? MASK_BIT : 0) | len));
        } else if (len <= 0xFFFF) {
            out.put((byte) ((mask ? MASK_BIT : 0) | 126));
            out.putShort((short) (len & 0xFFFF));
        } else {
            out.put((byte) ((mask ? MASK_BIT : 0) | 127));
            out.putLong(len);
        }

        int[] mkey = null;
        if (mask) {
            mkey = new int[]{rnd(), rnd(), rnd(), rnd()};
            out.put((byte) mkey[0]).put((byte) mkey[1]).put((byte) mkey[2]).put((byte) mkey[3]);
        }

        final int pos = payload.position(), lim = payload.limit();
        for (int i = pos; i < lim; i++) {
            int b = payload.get(i) & 0xFF;
            if (mask) {
                b ^= mkey[(i - pos) & 3];
            }
            out.put((byte) b);
        }
        payload.position(lim);
        out.flip();
        return out;
    }

    private static int rnd() {
        return ThreadLocalRandom.current().nextInt(256);
    }

    private static boolean isControl(final int op) {
        return (op & 0x08) != 0;
    }
}
