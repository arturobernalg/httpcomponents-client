package org.apache.hc.client5.http.socket.impl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class WsCodec {
    static int readCloseCode(final ByteBuffer p) {
        if (p.remaining() >= 2) {
            final int b1 = (p.get() & 0xFF);
            final int b2 = (p.get() & 0xFF);
            return (b1 << 8) | b2;
        }
        return 1005; // no status
    }

    static String readCloseReason(final ByteBuffer p) {
        if (p.remaining() > 0) {
            return StandardCharsets.UTF_8.decode(p.slice()).toString();
        }
        return "";
    }

    static boolean isValidCloseCodeReceived(final int code) {
        if (code == 1005 || code == 1006 || code == 1015 || code == 1004) {
            return false;
        }
        if (code >= 1000 && code <= 1014) {
            return (code == 1000 || code == 1001 || code == 1002 || code == 1003 ||
                    code == 1007 || code == 1008 || code == 1009 || code == 1010 ||
                    code == 1011 || code == 1012 || code == 1013 || code == 1014);
        }
        return code >= 3000 && code <= 4999;
    }

    static boolean isValidCloseCodeToSend(final int code) {
        return isValidCloseCodeReceived(code);
    }

    private WsCodec() {
    }
}
