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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hc.core5.util.Timeout;

/**
 * Immutable configuration for {@link org.apache.hc.client5.http.websocket.client.WebSocketClient}.
 *
 * <p>The builder exposes common protocol and runtime knobs:
 * maximum frame/message sizes, timeouts, automatic PONG behavior,
 * outbound chunking (for timely control frames), optional subprotocols, and
 * RFC&nbsp;7692 <em>permessage-deflate</em> offer parameters.</p>
 *
 * <h3>Defaults</h3>
 * <ul>
 *   <li>{@code maxFrameSize = 64 KiB}</li>
 *   <li>{@code maxMessageSize = 8 MiB}</li>
 *   <li>{@code connectTimeout = 10s}, {@code exchangeTimeout = 10s},
 *       {@code closeWaitTimeout = 5s}</li>
 *   <li>{@code autoPong = true}</li>
 *   <li>{@code outgoingChunkSize = 4096} (bytes)</li>
 *   <li>permessage-deflate disabled by default</li>
 * </ul>
 *
 * <h3>Fragmentation and control latency</h3>
 * <p>RFC&nbsp;6455 does not mandate fragment sizes. The implementation uses
 * {@link #outgoingChunkSize} to auto-fragment large payloads so that control frames
 * (PING/PONG/CLOSE) can be scheduled promptly between fragments.</p>
 *
 * <h3>permessage-deflate (RFC 7692)</h3>
 * <p>Set {@link Builder#enablePerMessageDeflate(boolean)} and optional offer parameters
 * to include an extension offer in the handshake. The client verifies the server's
 * selection strictly. Incoming compressed messages are inflated transparently.
 * Outbound single-frame messages may be compressed; fragmented outbound messages
 * are sent uncompressed in the current version (allowed by the RFC).</p>
 *
 * @since 5.6
 */
public final class WebSocketClientConfig {

    /**
     * Maximum allowed frame payload size (bytes). Frames exceeding are rejected.
     */
    public final int maxFrameSize;

    /**
     * Maximum assembled message size (bytes). Messages exceeding result in 1009 (Too Big).
     */
    public final long maxMessageSize;

    /**
     * Connect timeout for the underlying HTTP connection.
     */
    public final Timeout connectTimeout;

    /**
     * Generic exchange timeout for the initial HTTP upgrade.
     */
    public final Timeout exchangeTimeout;

    /**
     * Time to wait for peer CLOSE after we send CLOSE.
     */
    public final Timeout closeWaitTimeout;

    /**
     * If {@code true}, reply to incoming PING with PONG automatically.
     */
    public final boolean autoPong;

    /**
     * Outbound chunk size (bytes) used for auto-fragmentation.
     * A smaller value improves PING/PONG responsiveness under load.
     */
    public final int outgoingChunkSize;

    // --- RFC 7692 offer flags ---
    /**
     * Include "permessage-deflate" in Sec-WebSocket-Extensions offer.
     */
    public final boolean perMessageDeflateEnabled;
    /**
     * Offer "server_no_context_takeover".
     */
    public final boolean offerServerNoContextTakeover;
    /**
     * Offer "client_no_context_takeover".
     */
    public final boolean offerClientNoContextTakeover;
    /**
     * Offer "client_max_window_bits" with this value (nullable).
     */
    public final Integer offerClientMaxWindowBits;
    /**
     * Offer "server_max_window_bits" with this value (nullable).
     */
    public final Integer offerServerMaxWindowBits;

    /**
     * Optional subprotocols offered (Sec-WebSocket-Protocol).
     */
    public final List<String> subprotocols;

    /**
     * Max frames drained per output tick (backpressure on writer loop).
     */
    public final int maxFramesPerTick;

    public final int ioPoolCapacity;

    public boolean directBuffers;

    private WebSocketClientConfig(
            final int maxFrameSize,
            final long maxMessageSize,
            final Timeout connectTimeout,
            final Timeout exchangeTimeout,
            final Timeout closeWaitTimeout,
            final boolean autoPong,
            final int outgoingChunkSize,
            final boolean perMessageDeflateEnabled,
            final boolean offerServerNoContextTakeover,
            final boolean offerClientNoContextTakeover,
            final Integer offerClientMaxWindowBits,
            final Integer offerServerMaxWindowBits,
            final List<String> subprotocols,
            final int maxFramesPerTick,
            final int ioPoolCapacity,
            final boolean directBuffers) {
        this.maxFrameSize = maxFrameSize;
        this.maxMessageSize = maxMessageSize;
        this.connectTimeout = connectTimeout;
        this.exchangeTimeout = exchangeTimeout;
        this.closeWaitTimeout = closeWaitTimeout;
        this.autoPong = autoPong;
        this.outgoingChunkSize = outgoingChunkSize;
        this.perMessageDeflateEnabled = perMessageDeflateEnabled;
        this.offerServerNoContextTakeover = offerServerNoContextTakeover;
        this.offerClientNoContextTakeover = offerClientNoContextTakeover;
        this.offerClientMaxWindowBits = offerClientMaxWindowBits;
        this.offerServerMaxWindowBits = offerServerMaxWindowBits;
        this.subprotocols = subprotocols;
        this.maxFramesPerTick = maxFramesPerTick;
        this.ioPoolCapacity = ioPoolCapacity;
        this.directBuffers = directBuffers;
    }

    /**
     * @return a new builder with sensible defaults.
     */
    public static Builder custom() {
        return new Builder();
    }

    /**
     * Builder for {@link WebSocketClientConfig}.
     *
     * <p>All setters validate inputs minimally and return {@code this} for chaining.</p>
     *
     * @since 5.6
     */
    public static final class Builder {
        private int maxFrameSize = 64 * 1024;                // 64 KiB
        private long maxMessageSize = 8L * 1024 * 1024;      // 8 MiB
        private Timeout connectTimeout = Timeout.ofSeconds(10);
        private Timeout exchangeTimeout = Timeout.ofSeconds(10);
        private Timeout closeWaitTimeout = Timeout.ofSeconds(5);
        private boolean autoPong = true;
        private int outgoingChunkSize = 4096;                // 4 KiB
        private int maxFramesPerTick = 64;
        private int ioPoolCapacity = 64;
        private boolean directBuffers = false;

        private boolean perMessageDeflateEnabled = false;
        private boolean offerServerNoContextTakeover = false;
        private boolean offerClientNoContextTakeover = false;
        private Integer offerClientMaxWindowBits = null;
        private Integer offerServerMaxWindowBits = null;

        private final List<String> subprotocols = new ArrayList<>();




        /**
         * Maximum allowed frame payload size in bytes (default 64 KiB).
         */
        public Builder setMaxFrameSize(final int bytes) {
            this.maxFrameSize = Math.max(1, bytes);
            return this;
        }

        /**
         * Maximum size of a reassembled message in bytes (default 8 MiB).
         */
        public Builder setMaxMessageSize(final long bytes) {
            this.maxMessageSize = Math.max(1L, bytes);
            return this;
        }

        /**
         * Connect timeout for the underlying HTTP transport (default 10s).
         */
        public Builder setConnectTimeout(final Timeout t) {
            this.connectTimeout = t;
            return this;
        }

        /**
         * Exchange timeout for the initial HTTP/1.1 upgrade (default 10s).
         */
        public Builder setExchangeTimeout(final Timeout t) {
            this.exchangeTimeout = t;
            return this;
        }

        /**
         * Time allowed to receive peer CLOSE after sending ours (default 5s).
         */
        public Builder setCloseWaitTimeout(final Timeout t) {
            this.closeWaitTimeout = t;
            return this;
        }

        /**
         * Whether to answer PING automatically with PONG (default {@code true}).
         */
        public Builder setAutoPong(final boolean v) {
            this.autoPong = v;
            return this;
        }

        /**
         * Outbound chunk size in bytes (default 4096).
         * <p>Smaller values increase control-frame responsiveness under large sends.</p>
         */
        public Builder setOutgoingChunkSize(final int bytes) {
            this.outgoingChunkSize = Math.max(256, bytes);
            return this;
        }

        // --- RFC 7692 offer helpers ---

        /**
         * Enable the "permessage-deflate" extension offer (default disabled).
         */
        public Builder enablePerMessageDeflate(final boolean v) {
            this.perMessageDeflateEnabled = v;
            return this;
        }

        /**
         * Offer "server_no_context_takeover".
         */
        public Builder offerServerNoContextTakeover(final boolean v) {
            this.offerServerNoContextTakeover = v;
            return this;
        }

        /**
         * Offer "client_no_context_takeover".
         */
        public Builder offerClientNoContextTakeover(final boolean v) {
            this.offerClientNoContextTakeover = v;
            return this;
        }

        /**
         * Offer "client_max_window_bits" with the given value (nullable to omit).
         */
        public Builder offerClientMaxWindowBits(final Integer v) {
            this.offerClientMaxWindowBits = v;
            return this;
        }

        /**
         * Offer "server_max_window_bits" with the given value (nullable to omit).
         */
        public Builder offerServerMaxWindowBits(final Integer v) {
            this.offerServerMaxWindowBits = v;
            return this;
        }

        /**
         * Add a subprotocol to offer (Sec-WebSocket-Protocol).
         */
        public Builder addSubprotocol(final String proto) {
            if (proto != null && !proto.isEmpty()) {
                this.subprotocols.add(proto);
            }
            return this;
        }

        /**
         * Replace the list of subprotocols offered.
         */
        public Builder setSubprotocols(final List<String> protos) {
            this.subprotocols.clear();
            if (protos != null) {
                this.subprotocols.addAll(protos);
            }
            return this;
        }

        /**
         * Max frames to write per output spin (default 64).
         */
        public Builder setMaxFramesPerTick(final int n) {
            this.maxFramesPerTick = Math.max(1, n);
            return this;
        }

        public Builder setIoPoolCapacity(final int ioPoolCapacity) {
            this.ioPoolCapacity = ioPoolCapacity;
            return this;
        }
        public Builder setDirectBuffers(final boolean directBuffers) {
            this.directBuffers = directBuffers;
            return this;
        }

        /**
         * Builds an immutable {@link WebSocketClientConfig}.
         */
        public WebSocketClientConfig build() {
            final int chunk = outgoingChunkSize <= 0 ? 4096 : outgoingChunkSize;
            return new WebSocketClientConfig(
                    maxFrameSize,
                    maxMessageSize,
                    connectTimeout,
                    exchangeTimeout,
                    closeWaitTimeout,
                    autoPong,
                    chunk,
                    perMessageDeflateEnabled,
                    offerServerNoContextTakeover,
                    offerClientNoContextTakeover,
                    offerClientMaxWindowBits,
                    offerServerMaxWindowBits,
                    Collections.unmodifiableList(new ArrayList<>(subprotocols)),
                    maxFramesPerTick,
                    ioPoolCapacity,
                    directBuffers
            );
        }
    }
}
