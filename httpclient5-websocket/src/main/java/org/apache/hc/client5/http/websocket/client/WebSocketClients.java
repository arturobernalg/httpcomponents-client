package org.apache.hc.client5.http.websocket.client;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;

/**
 * Static factory methods for {@link CloseableWebSocketClient}.
 *
 * @since 5.6
 */
public final class WebSocketClients {

    private WebSocketClients() {}

    /** Creates a builder for custom {@link CloseableWebSocketClient} instances. */
    public static WebSocketClientBuilder custom() {
        return WebSocketClientBuilder.create();
    }

    /** Creates a {@link CloseableWebSocketClient} with sensible defaults. */
    public static CloseableWebSocketClient createDefault() {
        return custom().build();
    }

    /**
     * Creates a {@link CloseableWebSocketClient} with the supplied default
     * {@link WebSocketClientConfig} applied to connections, using default transport settings.
     */
    public static CloseableWebSocketClient createWith(final WebSocketClientConfig defaultConfig) {
        return custom()
                .setDefaultConfig(defaultConfig)   // requires the builder method below
                .build();
    }
}
