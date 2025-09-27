package org.apache.hc.client5.http.websocket.example;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.CloseableWebSocketClient;
import org.apache.hc.client5.http.websocket.client.WebSocketClients;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

public final class WebSocketEchoClient {

    public static void main(final String[] args) throws Exception {
        final URI uri = URI.create(args.length > 0 ? args[0] : "ws://localhost:8080/echo");
        final CountDownLatch done = new CountDownLatch(1);

        final WebSocketClientConfig cfg = WebSocketClientConfig.custom()
                .enablePerMessageDeflate(true)
                .offerServerNoContextTakeover(true)
                .offerClientNoContextTakeover(true)
                .offerClientMaxWindowBits(15)
                .setCloseWaitTimeout(Timeout.ofMilliseconds(200))
                .allowH2ExtendedConnect(false)
                .build();

        try (final CloseableWebSocketClient client = WebSocketClients.createDefault()) {
            System.out.println("[TEST] connecting: " + uri);
            client.start();
            client.connect(uri, new WebSocketListener() {
                private WebSocket ws;

                @Override
                public void onOpen(final WebSocket ws) {
                    this.ws = ws;
                    System.out.println("[TEST] open: " + uri);

                    final String prefix = "hello from hc5 WS @ " + Instant.now() + " — ";
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 256; i++) {
                        sb.append(prefix);
                    }
                    final String msg = sb.toString();

                    ws.sendText(msg, true);
                    System.out.println("[TEST] sent (chars=" + msg.length() + ")");
                }

                @Override
                public void onText(final CharSequence text, final boolean last) {
                    final int len = text.length();
                    final CharSequence preview = len > 120 ? text.subSequence(0, 120) + "…" : text;
                    System.out.println("[TEST] text (chars=" + len + "): " + preview);
                    ws.close(1000, "done");
                }

                @Override
                public void onPong(final ByteBuffer payload) {
                    System.out.println("[TEST] pong: " + StandardCharsets.UTF_8.decode(payload).toString());
                }

                @Override
                public void onClose(final int code, final String reason) {
                    System.out.println("[TEST] close: " + code + " " + reason);
                    done.countDown();
                }

                @Override
                public void onError(final Throwable ex) {
                    ex.printStackTrace(System.err);
                    done.countDown();
                }
            }, cfg).exceptionally(ex -> {
                ex.printStackTrace();
                done.countDown();
                return null;
            });

            if (!done.await(12, TimeUnit.SECONDS)) {
                System.err.println("[TEST] Timed out waiting for echo/close");
                System.exit(1);
            }

            // Optional: explicit mode (try-with-resources already calls GRACEFUL)
            client.close(CloseMode.IMMEDIATE);
        }
    }
}
