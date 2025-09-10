package org.apache.hc.client5.http.socket.impl;

import java.nio.ByteBuffer;

final class WsDecoder {
    private final int maxFrameSize;
    private int opcode;
    private boolean fin;
    private boolean rsv1;
    private ByteBuffer payload = ByteBuffer.allocate(0);

    WsDecoder(final int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    boolean decode(final ByteBuffer in) {
        in.mark();
        if (in.remaining() < 2) {
            in.reset();
            return false;
        }

        final int b0 = in.get() & 0xFF, b1 = in.get() & 0xFF;
        fin = (b0 & 0x80) != 0;
        rsv1 = (b0 & 0x40) != 0;
        final boolean rsv2 = (b0 & 0x20) != 0;
        final boolean rsv3 = (b0 & 0x10) != 0;
        opcode = b0 & 0x0F;

        // RSV2/3 are never used by permessage-deflate
        if (rsv2 || rsv3) {
            throw new WsProtocolException(1002, "RSV2/RSV3 must be zero");
        }

        if (!isKnownOpcode(opcode)) {
            throw new WsProtocolException(1002, "Reserved/unknown opcode: 0x" + Integer.toHexString(opcode));
        }

        final boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        // server->client MUST be unmasked
        if (masked) {
            throw new WsProtocolException(1002, "Server frame is masked");
        }

        if (len == 126) {
            if (in.remaining() < 2) {
                in.reset();
                return false;
            }
            len = (in.getShort() & 0xFFFF);
        } else if (len == 127) {
            if (in.remaining() < 8) {
                in.reset();
                return false;
            }
            final long l = in.getLong();
            if (l < 0) {
                throw new WsProtocolException(1002, "Invalid 64-bit length");
            }
            len = l;
        }

        if (isControl(opcode)) {
            if (!fin) {
                throw new WsProtocolException(1002, "Fragmented control frame");
            }
            if (len > 125) {
                throw new WsProtocolException(1002, "Control frame too large: " + len);
            }
            if (opcode == WsOpcode.CLOSE && len == 1) {
                throw new WsProtocolException(1002, "Close len=1 invalid");
            }
            if (rsv1) {
                throw new WsProtocolException(1002, "RSV1 on control frame");
            }
        }

        if (len > Integer.MAX_VALUE || len > maxFrameSize) {
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
        payload = data;
        return true;
    }

    int opcode() {
        return opcode;
    }

    boolean fin() {
        return fin;
    }

    boolean rsv1() {
        return rsv1;
    }

    ByteBuffer payload() {
        return payload.asReadOnlyBuffer();
    }

    private static boolean isControl(final int op) {
        return (op & 0x08) != 0;
    }

    private static boolean isKnownOpcode(final int op) {
        return op == WsOpcode.CONT || op == WsOpcode.TEXT || op == WsOpcode.BINARY ||
                op == WsOpcode.CLOSE || op == WsOpcode.PING || op == WsOpcode.PONG;
    }
}
