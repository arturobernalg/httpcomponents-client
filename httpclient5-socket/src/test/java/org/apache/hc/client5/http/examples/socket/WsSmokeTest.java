package org.apache.hc.client5.http.examples.socket;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.socket.WebSocket;
import org.apache.hc.client5.http.socket.WebSocketClient;
import org.apache.hc.client5.http.socket.WebSocketClientConfig;
import org.apache.hc.client5.http.socket.WebSocketListener;

public final class WsSmokeTest {
    public static void main(String[] args) throws Exception {
        final URI uri = URI.create(args.length > 0 ? args[0] : "ws://localhost:8080/echo");
        final CountDownLatch done = new CountDownLatch(1);

        try (WebSocketClient client = new WebSocketClient()) {
            // Enable permessage-deflate (RFC 7692) in the client offer.
            // Defaults elsewhere: maxFrameSize=64 KiB, maxMessageSize=8 MiB, autoPong=true.
            final WebSocketClientConfig cfg = WebSocketClientConfig.custom()
                    .enablePerMessageDeflate(true)
                    .offerServerNoContextTakeover(true)  // server can reset its inflater per message
                    .offerClientNoContextTakeover(true)  // we reset our deflater per message
                    .offerClientMaxWindowBits(15)        // JDK raw DEFLATE prefers 15
                    // .offerServerMaxWindowBits(15)      // optional hint; safe to omit
                    .build();

            System.out.println("[TEST] connecting: " + uri);
            client.connect(uri, new WebSocketListener() {
                private WebSocket ws;

                @Override
                public void onOpen(WebSocket ws) {
                    this.ws = ws;
                    System.out.println("[TEST] open: " + uri);

                    // Build a compressible payload to exercise PMCE.
                    final String prefix = "hello from hc5 WS @ " + Instant.now() + " — ";
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 256; i++) {  // ~ a few KB when uncompressed
                        sb.append(prefix);
                    }
                    final String msg = sb.toString();

                    ws.sendText(msg, true); // our client will compress this if PMCE was negotiated
                    System.out.println("[TEST] sent (chars=" + msg.length() + ")");
                }

                @Override
                public void onText(CharSequence text, boolean last) {
                    System.out.println("[TEST] text (chars=" + text.length() + "): "
                            + (text.length() > 120 ? text.subSequence(0, 120) + "…" : text));
                    ws.close(1000, "done");
                }

                @Override
                public void onPong(ByteBuffer payload) {
                    System.out.println("[TEST] pong: " + StandardCharsets.UTF_8.decode(payload).toString());
                }

                @Override
                public void onClose(int code, String reason) {
                    System.out.println("[TEST] close: " + code + " " + reason);
                    done.countDown();
                }

                @Override
                public void onError(Throwable ex) {
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
        }
    }
}
