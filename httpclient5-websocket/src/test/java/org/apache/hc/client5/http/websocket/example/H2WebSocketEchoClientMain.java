package org.apache.hc.client5.http.websocket.example;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.CloseableWebSocketClient;
import org.apache.hc.client5.http.websocket.client.WebSocketClientBuilder;
import org.apache.hc.core5.http.nio.ssl.BasicClientTlsStrategy;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;

public final class H2WebSocketEchoClientMain {
    public static void main(final String[] args) throws Exception {
        final String url = "ws://localhost:8081/echo";  // <-- h2c, no TLS

        final WebSocketClientConfig cfg = WebSocketClientConfig.custom()
                .allowH2ExtendedConnect(true)
                .requireH2(true)
                .enablePerMessageDeflate(false)
                .setCloseWaitTimeout(Timeout.ofSeconds(1))
                .build();

      //  final BasicClientTlsStrategy tls = new BasicClientTlsStrategy(
        //        SSLContexts.custom().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build());

        final TlsStrategy tls =  new BasicClientTlsStrategy();

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>();

        try (final CloseableWebSocketClient client = WebSocketClientBuilder.create()
                .defaultConfig(cfg)
                .setTlsStrategy(tls)
                .build()) {

            client.start();
            client.connect(URI.create(url), new WebSocketListener() {
                private WebSocket ws;

                @Override public void onOpen(final WebSocket ws) {
                    this.ws = ws;
                    System.out.println("[client] open");
                    ws.sendText("h2-echo", true);
                }
                @Override public void onText(final CharSequence data, final boolean last) {
                    System.out.println("[client] got: " + data);
                    ws.close(1000, "ok");
                }
                @Override public void onClose(final int code, final String reason) {
                    System.out.println("[client] close: " + code + " " + reason);
                    done.countDown();
                }
                @Override public void onError(final Throwable ex) {
                    ex.printStackTrace();
                    err.set(ex);
                    done.countDown();
                }
            }, cfg).join();

            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("timeout");
            }
            if (err.get() != null) {
                throw new RuntimeException("ws error", err.get());
            }
        }
    }
}
