package org.apache.hc.client5.http.socket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.hc.core5.util.Timeout;

public final class WebSocketClientConfig {
    public final int maxFrameSize;
    public final long maxMessageSize;
    public final Timeout connectTimeout;
    public final Timeout exchangeTimeout;
    public final Timeout closeWaitTimeout;
    public final List<String> subprotocols;
    public final boolean autoPong;

    // RFC 7692 (permessage-deflate) offer
    public final boolean perMessageDeflateEnabled;
    public final boolean offerServerNoContextTakeover;
    public final boolean offerClientNoContextTakeover;
    public final Integer offerClientMaxWindowBits;
    public final Integer offerServerMaxWindowBits;

    // New: compression behavior
    public final int minCompressBytes;            // below this, send uncompressed (single-frame case)
    public final boolean streamCompressedFragments; // enable true streaming fragmentation when caller fragments

    private WebSocketClientConfig(
            int maxFrameSize,
            long maxMessageSize,
            Timeout connectTimeout,
            Timeout exchangeTimeout,
            Timeout closeWaitTimeout,
            List<String> subprotocols,
            boolean autoPong,
            boolean perMessageDeflateEnabled,
            boolean offerServerNoContextTakeover,
            boolean offerClientNoContextTakeover,
            Integer offerClientMaxWindowBits,
            Integer offerServerMaxWindowBits,
            int minCompressBytes,
            boolean streamCompressedFragments) {
        this.maxFrameSize = maxFrameSize;
        this.maxMessageSize = maxMessageSize;
        this.connectTimeout = connectTimeout;
        this.exchangeTimeout = exchangeTimeout;
        this.closeWaitTimeout = closeWaitTimeout;
        this.subprotocols = subprotocols;
        this.autoPong = autoPong;

        this.perMessageDeflateEnabled = perMessageDeflateEnabled;
        this.offerServerNoContextTakeover = offerServerNoContextTakeover;
        this.offerClientNoContextTakeover = offerClientNoContextTakeover;
        this.offerClientMaxWindowBits = offerClientMaxWindowBits;
        this.offerServerMaxWindowBits = offerServerMaxWindowBits;

        this.minCompressBytes = minCompressBytes;
        this.streamCompressedFragments = streamCompressedFragments;
    }

    public static Builder custom() {
        return new Builder();
    }

    public static final class Builder {
        private int maxFrameSize = 64 * 1024;
        private long maxMessageSize = 8L * 1024 * 1024;
        private Timeout connectTimeout = Timeout.ofSeconds(10);
        private Timeout exchangeTimeout = Timeout.ofSeconds(10);
        private Timeout closeWaitTimeout = Timeout.ofSeconds(5);
        private final List<String> subprotocols = new ArrayList<>();
        private boolean autoPong = true;

        private boolean perMessageDeflateEnabled = true;
        private boolean offerServerNoContextTakeover = true;
        private boolean offerClientNoContextTakeover = true;
        private Integer offerClientMaxWindowBits = 15;
        private Integer offerServerMaxWindowBits = null;

        private int minCompressBytes = 256;             // new default
        private boolean streamCompressedFragments = true;

        public Builder setMaxFrameSize(int bytes) {
            this.maxFrameSize = bytes;
            return this;
        }

        public Builder setMaxMessageSize(long bytes) {
            this.maxMessageSize = bytes;
            return this;
        }

        public Builder setConnectTimeout(Timeout t) {
            this.connectTimeout = t;
            return this;
        }

        public Builder setExchangeTimeout(Timeout t) {
            this.exchangeTimeout = t;
            return this;
        }

        public Builder setCloseWaitTimeout(Timeout t) {
            this.closeWaitTimeout = t;
            return this;
        }

        public Builder setSubprotocols(String... protos) {
            this.subprotocols.clear();
            if (protos != null) this.subprotocols.addAll(Arrays.asList(protos));
            return this;
        }

        public Builder addSubprotocol(String proto) {
            if (proto != null && !proto.isEmpty()) this.subprotocols.add(proto);
            return this;
        }

        public Builder setAutoPong(boolean v) {
            this.autoPong = v;
            return this;
        }

        public Builder enablePerMessageDeflate(boolean v) {
            this.perMessageDeflateEnabled = v;
            return this;
        }

        public Builder offerServerNoContextTakeover(boolean v) {
            this.offerServerNoContextTakeover = v;
            return this;
        }

        public Builder offerClientNoContextTakeover(boolean v) {
            this.offerClientNoContextTakeover = v;
            return this;
        }

        public Builder offerClientMaxWindowBits(Integer bits) {
            this.offerClientMaxWindowBits = bits;
            return this;
        }

        public Builder offerServerMaxWindowBits(Integer bits) {
            this.offerServerMaxWindowBits = bits;
            return this;
        }

        public Builder setMinCompressBytes(int bytes) {
            this.minCompressBytes = Math.max(0, bytes);
            return this;
        }

        public Builder setStreamCompressedFragments(boolean v) {
            this.streamCompressedFragments = v;
            return this;
        }

        public WebSocketClientConfig build() {
            return new WebSocketClientConfig(
                    maxFrameSize, maxMessageSize,
                    connectTimeout, exchangeTimeout, closeWaitTimeout,
                    Collections.unmodifiableList(new ArrayList<>(subprotocols)),
                    autoPong,
                    perMessageDeflateEnabled, offerServerNoContextTakeover, offerClientNoContextTakeover,
                    offerClientMaxWindowBits, offerServerMaxWindowBits,
                    minCompressBytes, streamCompressedFragments
            );
        }
    }
}
