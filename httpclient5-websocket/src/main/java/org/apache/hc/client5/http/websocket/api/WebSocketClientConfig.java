package org.apache.hc.client5.http.websocket.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.hc.core5.util.Timeout;

/**
 * Immutable WebSocket client configuration + RFC 8441 flags.
 */
public final class WebSocketClientConfig {

    private final Timeout connectTimeout;
    private final List<String> subprotocols;

    // PMCE offer
    private final boolean perMessageDeflateEnabled;
    private final boolean offerServerNoContextTakeover;
    private final boolean offerClientNoContextTakeover;
    private final Integer offerClientMaxWindowBits;
    private final Integer offerServerMaxWindowBits;

    // Framing / flow
    private final int maxFrameSize;
    private final int outgoingChunkSize;
    private final int maxFramesPerTick;

    // Buffers / pool
    private final int ioPoolCapacity;
    private final boolean directBuffers;

    // Behavior
    private final boolean autoPong;
    private final Timeout closeWaitTimeout;
    private final long maxMessageSize;

    // RFC 8441 / HTTP/2 flags
    private final boolean preferH2;
    private final boolean allowH2ExtendedConnect;
    private final boolean requireH2;
    private final boolean disableH1Fallback;

    private WebSocketClientConfig(
            final Timeout connectTimeout,
            final List<String> subprotocols,
            final boolean perMessageDeflateEnabled,
            final boolean offerServerNoContextTakeover,
            final boolean offerClientNoContextTakeover,
            final Integer offerClientMaxWindowBits,
            final Integer offerServerMaxWindowBits,
            final int maxFrameSize,
            final int outgoingChunkSize,
            final int maxFramesPerTick,
            final int ioPoolCapacity,
            final boolean directBuffers,
            final boolean autoPong,
            final Timeout closeWaitTimeout,
            final long maxMessageSize,
            final boolean preferH2,
            final boolean allowH2ExtendedConnect,
            final boolean requireH2,
            final boolean disableH1Fallback) {

        this.connectTimeout = connectTimeout;
        this.subprotocols = subprotocols != null
                ? Collections.unmodifiableList(new ArrayList<>(subprotocols))
                : Collections.emptyList();
        this.perMessageDeflateEnabled = perMessageDeflateEnabled;
        this.offerServerNoContextTakeover = offerServerNoContextTakeover;
        this.offerClientNoContextTakeover = offerClientNoContextTakeover;
        this.offerClientMaxWindowBits = offerClientMaxWindowBits;
        this.offerServerMaxWindowBits = offerServerMaxWindowBits;
        this.maxFrameSize = maxFrameSize;
        this.outgoingChunkSize = outgoingChunkSize;
        this.maxFramesPerTick = maxFramesPerTick;
        this.ioPoolCapacity = ioPoolCapacity;
        this.directBuffers = directBuffers;
        this.autoPong = autoPong;
        this.closeWaitTimeout = Objects.requireNonNull(closeWaitTimeout, "closeWaitTimeout");
        this.maxMessageSize = maxMessageSize;
        this.preferH2 = preferH2;
        this.allowH2ExtendedConnect = allowH2ExtendedConnect;
        this.requireH2 = requireH2;
        this.disableH1Fallback = disableH1Fallback;
    }

    // ---- getters used across your code ----
    public Timeout getConnectTimeout() {
        return connectTimeout;
    }

    public List<String> getSubprotocols() {
        return subprotocols;
    }

    public boolean isPerMessageDeflateEnabled() {
        return perMessageDeflateEnabled;
    }

    public boolean isOfferServerNoContextTakeover() {
        return offerServerNoContextTakeover;
    }

    public boolean isOfferClientNoContextTakeover() {
        return offerClientNoContextTakeover;
    }

    public Integer getOfferClientMaxWindowBits() {
        return offerClientMaxWindowBits;
    }

    public Integer getOfferServerMaxWindowBits() {
        return offerServerMaxWindowBits;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public int getOutgoingChunkSize() {
        return outgoingChunkSize;
    }

    public int getMaxFramesPerTick() {
        return maxFramesPerTick;
    }

    public int getIoPoolCapacity() {
        return ioPoolCapacity;
    }

    public boolean isDirectBuffers() {
        return directBuffers;
    }

    public boolean isAutoPong() {
        return autoPong;
    }

    public Timeout getCloseWaitTimeout() {
        return closeWaitTimeout;
    }

    public long getMaxMessageSize() {
        return maxMessageSize;
    }

    public boolean isPreferH2() {
        return preferH2;
    }

    public boolean isAllowH2ExtendedConnect() {
        return allowH2ExtendedConnect;
    }

    public boolean isRequireH2() {
        return requireH2;
    }

    public boolean isDisableH1Fallback() {
        return disableH1Fallback;
    }

    // ---- builder ----
    public static Builder custom() {
        return new Builder();
    }

    public static final class Builder {
        private Timeout connectTimeout = Timeout.ofSeconds(10);
        private List<String> subprotocols = new ArrayList<>();

        private boolean perMessageDeflateEnabled = true;
        private boolean offerServerNoContextTakeover = true;
        private boolean offerClientNoContextTakeover = true;
        private Integer offerClientMaxWindowBits = 15;
        private Integer offerServerMaxWindowBits = null;

        private int maxFrameSize = 64 * 1024;
        private int outgoingChunkSize = 8 * 1024;
        private int maxFramesPerTick = 1024;

        private int ioPoolCapacity = 64;
        private boolean directBuffers = true;

        private boolean autoPong = true;
        private Timeout closeWaitTimeout = Timeout.ofSeconds(5);
        private long maxMessageSize = 0L;

        private boolean preferH2 = false;
        private boolean allowH2ExtendedConnect = false;
        private boolean requireH2 = false;
        private boolean disableH1Fallback = false;

        public Builder setConnectTimeout(final Timeout v) {
            this.connectTimeout = v;
            return this;
        }

        public Builder setSubprotocols(final List<String> v) {
            this.subprotocols = (v != null) ? new ArrayList<>(v) : new ArrayList<>();
            return this;
        }

        public Builder enablePerMessageDeflate(final boolean v) {
            this.perMessageDeflateEnabled = v;
            return this;
        }

        public Builder offerServerNoContextTakeover(final boolean v) {
            this.offerServerNoContextTakeover = v;
            return this;
        }

        public Builder offerClientNoContextTakeover(final boolean v) {
            this.offerClientNoContextTakeover = v;
            return this;
        }

        public Builder offerClientMaxWindowBits(final Integer v) {
            this.offerClientMaxWindowBits = v;
            return this;
        }

        public Builder offerServerMaxWindowBits(final Integer v) {
            this.offerServerMaxWindowBits = v;
            return this;
        }

        public Builder setMaxFrameSize(final int v) {
            this.maxFrameSize = v;
            return this;
        }

        public Builder setOutgoingChunkSize(final int v) {
            this.outgoingChunkSize = v;
            return this;
        }

        public Builder setMaxFramesPerTick(final int v) {
            this.maxFramesPerTick = v;
            return this;
        }

        public Builder setIoPoolCapacity(final int v) {
            this.ioPoolCapacity = v;
            return this;
        }

        public Builder setDirectBuffers(final boolean v) {
            this.directBuffers = v;
            return this;
        }

        public Builder setAutoPong(final boolean v) {
            this.autoPong = v;
            return this;
        }

        public Builder setCloseWaitTimeout(final Timeout v) {
            this.closeWaitTimeout = v;
            return this;
        }

        public Builder setMaxMessageSize(final long v) {
            this.maxMessageSize = v;
            return this;
        }

        public Builder preferH2(final boolean v) {
            this.preferH2 = v;
            return this;
        }

        public Builder allowH2ExtendedConnect(final boolean v) {
            this.allowH2ExtendedConnect = v;
            return this;
        }

        public Builder requireH2(final boolean v) {
            this.requireH2 = v;
            return this;
        }

        public Builder disableH1Fallback(final boolean v) {
            this.disableH1Fallback = v;
            return this;
        }

        public WebSocketClientConfig build() {
            if (maxFrameSize <= 0) {
                throw new IllegalArgumentException("maxFrameSize > 0");
            }
            if (outgoingChunkSize <= 0) {
                throw new IllegalArgumentException("outgoingChunkSize > 0");
            }
            if (maxFramesPerTick <= 0) {
                throw new IllegalArgumentException("maxFramesPerTick > 0");
            }
            if (ioPoolCapacity <= 0) {
                throw new IllegalArgumentException("ioPoolCapacity > 0");
            }
            if (closeWaitTimeout == null) {
                throw new IllegalArgumentException("closeWaitTimeout != null");
            }
            return new WebSocketClientConfig(
                    connectTimeout, subprotocols,
                    perMessageDeflateEnabled, offerServerNoContextTakeover, offerClientNoContextTakeover,
                    offerClientMaxWindowBits, offerServerMaxWindowBits,
                    maxFrameSize, outgoingChunkSize, maxFramesPerTick,
                    ioPoolCapacity, directBuffers,
                    autoPong, closeWaitTimeout, maxMessageSize,
                    preferH2, allowH2ExtendedConnect, requireH2, disableH1Fallback
            );
        }
    }
}
