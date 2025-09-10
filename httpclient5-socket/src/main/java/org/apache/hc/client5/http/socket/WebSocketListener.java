package org.apache.hc.client5.http.socket;

import java.nio.ByteBuffer;

public interface WebSocketListener {
    default void onOpen(WebSocket ws) {
    }

    default void onText(CharSequence text, boolean last) {
    }

    default void onBinary(ByteBuffer payload, boolean last) {
    }

    default void onPing(ByteBuffer payload) {
    }

    default void onPong(ByteBuffer payload) {
    }

    default void onClose(int statusCode, String reason) {
    }

    default void onError(Throwable ex) {
    }
}
