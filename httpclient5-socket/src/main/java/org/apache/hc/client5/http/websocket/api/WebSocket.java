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
package org.apache.hc.client5.http.websocket.api;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side WebSocket endpoint.
 *
 * <p>This interface represents an established WebSocket connection as seen by
 * application code. Instances are created by {@link org.apache.hc.client5.http.websocket.client.WebSocketClient}
 * after a successful HTTP Upgrade handshake (RFC&nbsp;6455).</p>
 *
 * <h3>Thread-safety</h3>
 * <p>All methods are safe to invoke from multiple threads. Calls return immediately;
 * outgoing data is enqueued and written asynchronously by the I/O reactor.</p>
 *
 * <h3>Masking and fragmentation</h3>
 * <ul>
 *   <li>Client frames are masked automatically (RFC&nbsp;6455 §5.3).</li>
 *   <li>When {@code finalFragment} is {@code false}, the implementation sends a
 *       data fragment (TEXT/BINARY) and continues the message with CONT frames
 *       until a fragment with {@code finalFragment == true} is sent.</li>
 * </ul>
 *
 * <h3>Control frames</h3>
 * <ul>
 *   <li>{@link #ping(ByteBuffer)} sends a PING control frame with an optional payload
 *       up to 125 bytes (RFC&nbsp;6455 §5.5.2). A corresponding PONG is delivered
 *       to {@code WebSocketListener#onPong}.</li>
 *   <li>When {@code WebSocketClientConfig.autoPong} is enabled, incoming PINGs are
 *       answered automatically by the implementation.</li>
 * </ul>
 *
 * <h3>Close handshake</h3>
 * <p>{@link #close(int, String)} initiates the close handshake (RFC&nbsp;6455 §1.4, §5.5.1).
 * After a CLOSE is sent, the connection will ignore further data frames and
 * complete gracefully once the peer's CLOSE is received or a close-wait timeout
 * elapses.</p>
 *
 * <h3>Backpressure</h3>
 * <p>Sending methods return {@code boolean}: {@code true} if the frame was accepted
 * for asynchronous delivery; {@code false} if the connection is already closing/closed.</p>
 *
 * @since 5.6
 */
public interface WebSocket {

    /**
     * Sends a UTF-8 text fragment or complete message.
     *
     * @param data          textual data (will be encoded as UTF-8)
     * @param finalFragment whether this fragment finishes the message
     * @return {@code true} if accepted for send; {@code false} if not open/closing
     */
    boolean sendText(CharSequence data, boolean finalFragment);

    /**
     * Sends a binary fragment or complete message.
     *
     * @param data          binary payload (read-only view is taken)
     * @param finalFragment whether this fragment finishes the message
     * @return {@code true} if accepted for send; {@code false} if not open/closing
     */
    boolean sendBinary(ByteBuffer data, boolean finalFragment);

    /**
     * Sends a PING control frame.
     *
     * <p>Payload, if provided, must be ≤ 125 bytes per RFC&nbsp;6455. The method
     * returns immediately; a PONG (from the peer) will arrive on the listener.</p>
     *
     * @param data optional payload (may be {@code null})
     * @return {@code true} if accepted for send; {@code false} if not open/closing
     */
    boolean ping(ByteBuffer data);

    /**
     * Initiates the close handshake.
     *
     * <p>The future completes once the close request has been enqueued. The connection
     * will finish gracefully after the peer's CLOSE is received or the configured
     * close-wait timeout elapses.</p>
     *
     * @param statusCode RFC&nbsp;6455 close code (e.g. 1000)
     * @param reason     human-readable reason (UTF-8), may be {@code null} or empty
     * @return a future completed when the request is queued
     */
    CompletableFuture<Void> close(int statusCode, String reason);

    /**
     * @return {@code true} while the connection is open and not closing.
     */
    boolean isOpen();
}
