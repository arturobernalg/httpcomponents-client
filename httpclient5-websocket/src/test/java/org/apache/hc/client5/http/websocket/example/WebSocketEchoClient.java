/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
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
import org.apache.hc.client5.http.websocket.client.WebSocketClient;

/**
 * <h1>WebSocketEchoClient</h1>
 *
 * <p>Minimal example client using {@link WebSocketClient} to connect to an echo endpoint,
 * send a (compressible) text message, receive the echo, and close cleanly.</p>
 *
 * <h2>What it demonstrates</h2>
 * <ul>
 *   <li>WebSocket HTTP upgrade via Apache HttpCore 5</li>
 *   <li>Optional permessage-deflate (RFC&nbsp;7692) negotiation</li>
 *   <li>Sending text, receiving echo, and performing a graceful close</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   # With default URI ws://localhost:8080/echo
 *   java -cp ... org.apache.hc.client5.http.websocket.example.WebSocketEchoClient
 *
 *   # With custom URI
 *   java -cp ... org.apache.hc.client5.http.websocket.example.WebSocketEchoClient ws://host:port/path
 * </pre>
 *
 * <h2>Notes</h2>
 * <ul>
 *   <li>The example builds a compressible payload to exercise permessage-deflate if negotiated.</li>
 *   <li>Adjust the {@link WebSocketClientConfig} builder to change limits and behavior.</li>
 * </ul>
 */
public final class WebSocketEchoClient {

    public static void main(final String[] args) throws Exception {
        final URI uri = URI.create(args.length > 0 ? args[0] : "ws://localhost:8080/echo");
        final CountDownLatch done = new CountDownLatch(1);

        // Build a config that offers permessage-deflate; tune other knobs as needed.
        final WebSocketClientConfig cfg = WebSocketClientConfig.custom()
                .enablePerMessageDeflate(true)
                .offerServerNoContextTakeover(true)
                .offerClientNoContextTakeover(true)
                .offerClientMaxWindowBits(15)
                .build();

        try (final WebSocketClient client = new WebSocketClient()) {
            System.out.println("[TEST] connecting: " + uri);

            client.connect(uri, new WebSocketListener() {
                private WebSocket ws;

                @Override
                public void onOpen(final WebSocket ws) {
                    this.ws = ws;
                    System.out.println("[TEST] open: " + uri);

                    // Build a compressible message without String.repeat() (Java 8 compatible).
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
                    final CharSequence preview = (len > 120) ? text.subSequence(0, 120) + "…" : text;
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
        }
    }
}
