/*
 * Licensed to the Apache Software Foundation (ASF) ...
 */
package org.apache.hc.client5.http.websocket.client;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;

public final class WebSocketClients {

    private WebSocketClients() {}

    public static WebSocketClientBuilder custom() {
        return WebSocketClientBuilder.create();
    }

    public static CloseableWebSocketClient createDefault() {
        return custom().build();
    }

    public static CloseableWebSocketClient createWith(final WebSocketClientConfig defaultConfig) {
        return custom().defaultConfig(defaultConfig).build();
    }
}
