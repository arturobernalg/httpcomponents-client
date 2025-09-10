package org.apache.hc.client5.http.socket;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

public interface WebSocket {
    boolean sendText(CharSequence data, boolean finalFragment);

    boolean sendBinary(ByteBuffer data, boolean finalFragment);

    boolean ping(ByteBuffer data);

    CompletableFuture<Void> close(int statusCode, String reason);

    boolean isOpen();
}
