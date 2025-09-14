package org.apache.hc.client5.http.websocket.perf;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.WebSocketClient;

/**
 * Simple WebSocket throughput tool for manual performance checks.
 * <p>
 * Usage (defaults shown):
 * --mode=throughput
 * --uri=ws://localhost:8080/echo
 * --clients=16
 * --durationSec=15
 * --messageBytes=512
 * --inflight=32
 * --pmce=true
 * --compressible=true
 * --spawnServer=false
 */
public final class WebSocketPerfTool {

    // ----------------------------- CLI -----------------------------

    private static final class Cli {
        String mode = "throughput";
        String uri = "ws://localhost:8080/echo";
        int clients = 16;
        int durationSec = 15;
        int messageBytes = 512;
        int inflight = 32;
        boolean pmce = true;
        boolean compressible = true;
        boolean spawnServer = false; // flip to true if you want it on by default

        static Cli parse(final String[] args) {
            final Cli c = new Cli();
            for (String a : args) {
                if (a == null) continue;
                final int eq = a.indexOf('=');
                final String k = (eq > 0 ? a.substring(0, eq) : a).replaceFirst("^-+", "").toLowerCase(Locale.ROOT);
                final String v = (eq > 0 ? a.substring(eq + 1) : "true");
                switch (k) {
                    case "mode":
                        c.mode = v;
                        break;
                    case "uri":
                        c.uri = v;
                        break;
                    case "clients":
                        c.clients = Integer.parseInt(v);
                        break;
                    case "durationsec":
                        c.durationSec = Integer.parseInt(v);
                        break;
                    case "messagebytes":
                    case "bytes":
                        c.messageBytes = Integer.parseInt(v);
                        break;
                    case "inflight":
                        c.inflight = Integer.parseInt(v);
                        break;
                    case "pmce":
                        c.pmce = Boolean.parseBoolean(v);
                        break;
                    case "compressible":
                        c.compressible = Boolean.parseBoolean(v);
                        break;
                    case "spawnserver":
                        c.spawnServer = Boolean.parseBoolean(v);
                        break;
                }
            }
            return c;
        }
    }

    public static void main(final String[] args) throws Exception {
        final Cli cfg = Cli.parse(args);
        System.out.println(String.format("PERF] mode=%s uri=%s clients=%d durationSec=%d bytes=%d inflight=%d pmce=%s compressible=%s",
                cfg.mode, cfg.uri, cfg.clients, cfg.durationSec, cfg.messageBytes, cfg.inflight, cfg.pmce, cfg.compressible));

        EmbeddedEchoServer server = null;
        if (cfg.spawnServer) {
            server = new EmbeddedEchoServer(8080, "/echo", cfg.pmce);
            server.start();
        }

        try {
            if (!"throughput".equalsIgnoreCase(cfg.mode)) {
                System.out.println("Only --mode=throughput supported in this tool.");
                return;
            }
            runThroughput(cfg);
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    private static void runThroughput(final Cli cfg) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(cfg.clients);
        final CountDownLatch started = new CountDownLatch(cfg.clients);

        final AtomicLong sent = new AtomicLong();
        final AtomicLong echoes = new AtomicLong();

        final long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(cfg.durationSec);
        final String payload = buildPayload(cfg.messageBytes, cfg.compressible);

        for (int i = 0; i < cfg.clients; i++) {
            pool.submit(new ClientWorker(
                    URI.create(cfg.uri),
                    payload,
                    cfg.inflight,
                    cfg.pmce,
                    sent,
                    echoes,
                    started,
                    endAt
            ));
        }

        started.await(10, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(cfg.durationSec + 20, TimeUnit.SECONDS);

        final long msgs = echoes.get();
        final long bytes = msgs * payload.getBytes(StandardCharsets.UTF_8).length;
        final double seconds = cfg.durationSec;
        final double msgsPerSec = msgs / seconds;
        final double mibPerSec = (bytes / (1024.0 * 1024.0)) / seconds;

        System.out.printf(Locale.ROOT,
                "[THROUGHPUT] msgs=%d, bytes=%d, msgs/s=%.1f, MiB/s=%.2f%n",
                msgs, bytes, msgsPerSec, mibPerSec);
        System.out.printf(Locale.ROOT,
                "[THROUGHPUT] sent=%d (in-flight cap %d)%n",
                sent.get(), cfg.inflight);
    }

    private static final class ClientWorker implements Runnable {
        private final URI uri;
        private final String payload;
        private final int inflightCap;
        private final boolean pmce;
        private final AtomicLong sent;
        private final AtomicLong echoes;
        private final CountDownLatch started;
        private final long endAt;

        ClientWorker(final URI uri,
                     final String payload,
                     final int inflightCap,
                     final boolean pmce,
                     final AtomicLong sent,
                     final AtomicLong echoes,
                     final CountDownLatch started,
                     final long endAt) {
            this.uri = uri;
            this.payload = payload;
            this.inflightCap = inflightCap;
            this.pmce = pmce;
            this.sent = sent;
            this.echoes = echoes;
            this.started = started;
            this.endAt = endAt;
        }

        @Override
        public void run() {
            final AtomicBoolean open = new AtomicBoolean(false);
            final Semaphore inflight = new Semaphore(inflightCap);
            final AtomicReference<WebSocket> wsRef = new AtomicReference<>();

            // Build client config per connection (match server PMCE parameters)
            final WebSocketClientConfig cfg = WebSocketClientConfig.custom()
                    .enablePerMessageDeflate(pmce)
                    .offerClientNoContextTakeover(true)
                    .offerServerNoContextTakeover(true)
                    .offerClientMaxWindowBits(15)
                    .build();

            try (final WebSocketClient client = new WebSocketClient()) {
                // Connect and install listener
                client.connect(uri, new WebSocketListener() {
                    @Override
                    public void onOpen(final WebSocket ws) {
                        wsRef.set(ws);
                        open.set(true);
                        started.countDown();
                    }

                    @Override
                    public void onText(final CharSequence text, final boolean last) {
                        echoes.incrementAndGet();
                        inflight.release();
                    }

                    @Override
                    public void onBinary(final ByteBuffer data, final boolean last) {
                        echoes.incrementAndGet();
                        inflight.release();
                    }

                    @Override
                    public void onPong(final ByteBuffer payload) { /* ignore */ }

                    @Override
                    public void onClose(final int code, final String reason) {
                        System.out.println("[PERF] close code=" + code + " reason=" + reason);
                        open.set(false);
                    }

                    @Override
                    public void onError(final Throwable ex) {
                        System.out.println("[PERF] error: " + ex);
                        open.set(false);
                        started.countDown();
                    }
                }, cfg).get(8, TimeUnit.SECONDS);

                // Non-blocking send loop
                while (System.nanoTime() < endAt && open.get()) {
                    if (inflight.tryAcquire(2, TimeUnit.MILLISECONDS)) {
                        final WebSocket ws = wsRef.get();
                        if (ws == null) {
                            inflight.release();
                            break;
                        }
                        sent.incrementAndGet();
                        ws.sendText(payload, true);
                    }
                    Thread.yield();
                }

                // Try orderly close
                final WebSocket ws = wsRef.get();
                if (ws != null) {
                    try {
                        ws.close(1000, "done");
                    } catch (Throwable ignore) {
                    }
                }

            } catch (Exception ex) {
                System.out.println("[PERF] worker exception: " + ex);
                started.countDown();
            }
        }
    }

    private static String buildPayload(final int bytes, final boolean compressible) {
        if (bytes <= 0) return "";
        if (compressible) {
            final String token = "hc5-" + Instant.now().getEpochSecond() + "-";
            final StringBuilder sb = new StringBuilder(bytes);
            while (sb.length() < bytes) sb.append(token);
            if (sb.length() > bytes) sb.setLength(bytes);
            return sb.toString();
        } else {
            final byte[] arr = new byte[bytes];
            ThreadLocalRandom.current().nextBytes(arr);
            return StandardCharsets.ISO_8859_1.decode(ByteBuffer.wrap(arr)).toString();
        }
    }

    // ----------------------- Embedded echo server -----------------------
    // Lightweight Jetty echo endpoint so you can run the tool standalone:
    // pass --spawnServer=true (listens on http://localhost:8080/echo).
    // PMCE is enabled if you also pass --pmce=true.
    // The server only echoes PMCE parameters actually offered by the client.
    // -------------------------------------------------------------------
    private static final class EmbeddedEchoServer {
        private final int port;
        private final String path;
        private final boolean pmce;

        private org.eclipse.jetty.server.Server server;

        EmbeddedEchoServer(final int port, final String path, final boolean pmce) {
            this.port = port;
            this.path = path;
            this.pmce = pmce;
        }

        void start() throws Exception {
            server = new org.eclipse.jetty.server.Server(port);

            final org.eclipse.jetty.servlet.ServletContextHandler context =
                    new org.eclipse.jetty.servlet.ServletContextHandler(org.eclipse.jetty.servlet.ServletContextHandler.SESSIONS);
            context.setContextPath("/");
            server.setHandler(context);

            // Use the context-wide upgrade filter so the same factory negotiates AND builds connections.
            final org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter filter =
                    org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter.configureContext(context);

            final org.eclipse.jetty.websocket.servlet.WebSocketServletFactory factory = filter.getFactory();

            // Make PMCE wiring explicit.
            if (pmce) {
                // Ensure PMCE is registered (use Jetty's PerMessageDeflate implementation).
                factory.getExtensionFactory().unregister("permessage-deflate");
                factory.getExtensionFactory().register(
                        "permessage-deflate",
                        org.eclipse.jetty.websocket.common.extensions.compress.PerMessageDeflateExtension.class
                );
            } else {
                // Disable PMCE entirely.
                factory.getExtensionFactory().unregister("permessage-deflate");
            }

            // Map /echo to a creator that mirrors only offered PMCE params (to avoid negotiation mismatches).
            filter.addMapping(path, (org.eclipse.jetty.websocket.servlet.WebSocketCreator) (req, resp) -> {
                if (pmce) {
                    // Build chosen extensions based ONLY on what the client offered.
                    final List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> offered = req.getExtensions();
                    final List<org.eclipse.jetty.websocket.api.extensions.ExtensionConfig> chosen = new ArrayList<>();

                    for (org.eclipse.jetty.websocket.api.extensions.ExtensionConfig ext : offered) {
                        if ("permessage-deflate".equalsIgnoreCase(ext.getName())) {
                            final org.eclipse.jetty.websocket.api.extensions.ExtensionConfig cfg =
                                    new org.eclipse.jetty.websocket.api.extensions.ExtensionConfig("permessage-deflate");

                            if (ext.getParameters().containsKey("client_no_context_takeover")) {
                                cfg.setParameter("client_no_context_takeover", "");
                            }
                            if (ext.getParameters().containsKey("server_no_context_takeover")) {
                                cfg.setParameter("server_no_context_takeover", "");
                            }
                            if (ext.getParameters().containsKey("client_max_window_bits")) {
                                cfg.setParameter("client_max_window_bits",
                                        ext.getParameter("client_max_window_bits"));
                            }
                            if (ext.getParameters().containsKey("server_max_window_bits")) {
                                cfg.setParameter("server_max_window_bits",
                                        ext.getParameter("server_max_window_bits"));
                            }
                            chosen.add(cfg);
                            break;
                        }
                    }

                    // Apply what we chose (or nothing if client didn't offer PMCE).
                    resp.setExtensions(chosen);
                } else {
                    // With PMCE disabled, ensure we don't negotiate any extension.
                    resp.setExtensions(Collections.emptyList());
                }

                return new org.eclipse.jetty.websocket.api.WebSocketAdapter() {
                    @Override
                    public void onWebSocketText(final String message) {
                        if (isConnected()) {
                            getRemote().sendStringByFuture(message);
                        }
                    }

                    @Override
                    public void onWebSocketBinary(final byte[] payload, final int offset, final int len) {
                        if (isConnected()) {
                            getRemote().sendBytesByFuture(java.nio.ByteBuffer.wrap(payload, offset, len));
                        }
                    }

                    @Override
                    public void onWebSocketClose(int statusCode, String reason) {
                        System.out.println("[SERVER] close " + statusCode + " " + (reason == null ? "" : reason));
                        super.onWebSocketClose(statusCode, reason);
                    }
                };
            });

            server.start();
        }

        void stop() {
            try {
                if (server != null) server.stop();
            } catch (Exception ignore) {
            }
        }
    }
}
