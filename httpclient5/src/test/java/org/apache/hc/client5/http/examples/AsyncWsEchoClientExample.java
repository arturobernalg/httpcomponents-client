package org.apache.hc.client5.http.examples;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.ws.async.AsyncWebSocketClient;
import org.apache.hc.core5.net.Host;
import org.apache.hc.core5.reactor.DefaultConnectingIOReactor;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOEventHandlerFactory;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;

public final class AsyncWsEchoClientExample {

    public static void main(String[] args) throws Exception {
        final URI uri = args.length > 0 ? URI.create(args[0]) : URI.create("ws://echo.websocket.events/");
        if (!"ws".equalsIgnoreCase(uri.getScheme())) {
            System.out.println("Please pass a ws:// URL for this demo.");
            return;
        }

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

        final IOEventHandlerFactory handlers = new IOEventHandlerFactory() {
            @Override
            public IOEventHandler createHandler(ProtocolIOSession protocolSession, Object attachment) {
                return new IOEventHandler() {
                    @Override
                    public void connected(IOSession session) {
                    }

                    @Override
                    public void inputReady(IOSession session, java.nio.ByteBuffer src) {
                    }

                    @Override
                    public void outputReady(IOSession session) {
                    }

                    @Override
                    public void timeout(IOSession session, Timeout timeout) {
                    }

                    @Override
                    public void exception(IOSession session, Exception cause) {
                        cause.printStackTrace(System.out);
                    }

                    @Override
                    public void disconnected(IOSession session) {
                    }
                };
            }
        };

        final IOReactorConfig ioCfg = IOReactorConfig.custom().setIoThreadCount(1).build();
        final DefaultConnectingIOReactor reactor = new DefaultConnectingIOReactor(handlers, ioCfg, null);
        reactor.start();

        try {
            final Host host = new Host(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 80);
            final Future<IOSession> fut = reactor.connect(
                    host, new InetSocketAddress(host.getHostName(), host.getPort()),
                    null, Timeout.ofSeconds(5), null, null);

            final IOSession ioSession = fut.get(5, TimeUnit.SECONDS);

            final AsyncWebSocketClient wsClient = new AsyncWebSocketClient();
            final CompletableFuture<WebSocketSession> upgraded = wsClient.upgradeOn(ioSession, uri, listener, cfg);
            final WebSocketSession ws = upgraded.get(5, TimeUnit.SECONDS);

            open.await(2, TimeUnit.SECONDS);

            ws.sendText("hola from async reactor client", true);

            echoed.await(5, TimeUnit.SECONDS);

            ws.close(1000, "done");
            closed.await(2, TimeUnit.SECONDS);

        } finally {
            reactor.initiateShutdown();
            reactor.awaitShutdown(TimeValue.ofSeconds(5));
        }
    }
}
