package org.apache.hc.client5.http.examples;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.ws.classic.ClassicWebSocketClient;
import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;

public final class ClassicWsEchoClientExample {

    public static void main(String[] args) throws Exception {
        final URI uri = URI.create("wss://echo.websocket.events/");

        final WsConfig cfg = WsConfig.custom()
                .enablePerMessageDeflate(true)
                .setClientNoContextTakeover(true)
                .setServerNoContextTakeover(true)
                .setStrictTextUtf8(true)
                .setAutoPong(true)
                .build();

        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch echoed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        final WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocketSession s) {
                System.out.println("[open]");
                open.countDown();
            }

            @Override
            public void onText(WebSocketSession s, CharSequence data, boolean last) {
                System.out.println("[text] " + data + " last=" + last);
                echoed.countDown();
            }

            @Override
            public void onBinary(WebSocketSession s, ByteBuffer d, boolean last) {
            }

            @Override
            public void onPing(WebSocketSession s, ByteBuffer d) {
            }

            @Override
            public void onPong(WebSocketSession s, ByteBuffer d) {
            }

            @Override
            public void onClose(WebSocketSession s, int code, String reason) {
                System.out.println("[close] " + code + " " + reason);
                closed.countDown();
            }

            @Override
            public void onError(WebSocketSession s, Exception ex) {
                ex.printStackTrace(System.out);
            }
        };

        final ClassicWebSocketClient client = new ClassicWebSocketClient();
        final WebSocketSession session = client.connect(uri, listener, cfg);

        open.await(2, TimeUnit.SECONDS);

        session.sendText("hola from classic client", true);
        session.sendPing(ByteBuffer.wrap(new byte[]{1, 2, 3}));

        echoed.await(3, TimeUnit.SECONDS);

        session.close(1000, "done");
        closed.await(2, TimeUnit.SECONDS);

        client.close();
    }
}
