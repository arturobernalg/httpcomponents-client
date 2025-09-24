package org.apache.hc.client5.http.websocket.client;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;

public final class WebSocketClients {

    private WebSocketClients() {}

    /** Creates a builder for custom {@link WebSocketClient} instances. */
    public static WebSocketClientBuilder custom() {
        return WebSocketClientBuilder.create();
    }

    /** Creates a {@link WebSocketClient} with sensible defaults. */
    public static WebSocketClient createDefault() {
        return custom().build();
    }

    /**
     * Convenience: create a client with the given default WebSocket config.
     * Transport defaults are used.
     */
    public static WebSocketClient createWith(final WebSocketClientConfig defaultConfig) {
        return custom().setDefaultConfig(defaultConfig).build();
    }
}
