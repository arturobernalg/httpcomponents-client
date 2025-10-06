package org.apache.hc.client5.http.websocket.httpcore;


import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.core.extension.ExtensionChain;
import org.apache.hc.client5.http.websocket.core.frame.FrameWriter;
import org.apache.hc.client5.http.websocket.support.SimpleBufferPool;
import org.apache.hc.core5.reactor.ProtocolIOSession;

/**
 * Shared state & resources.
 */
final class WsState {

    // External
    final ProtocolIOSession session;
    final WebSocketListener listener;
    final WebSocketClientConfig cfg;

    // Extensions
    final ExtensionChain.EncodeChain encChain; // (not used yet for outbound compression)
    final ExtensionChain.DecodeChain decChain;

    // Buffers & codec
    final SimpleBufferPool bufferPool;
    final FrameWriter writer = new FrameWriter();
    final WsDecoder decoder;

    // Read side
    ByteBuffer readBuf;
    ByteBuffer inbuf = ByteBuffer.allocate(4096);

    // Outbound queues
    final ConcurrentLinkedQueue<WsOutbound.OutFrame> ctrlOutbound = new ConcurrentLinkedQueue<WsOutbound.OutFrame>();
    final ConcurrentLinkedQueue<WsOutbound.OutFrame> dataOutbound = new ConcurrentLinkedQueue<WsOutbound.OutFrame>();
    WsOutbound.OutFrame activeWrite = null;

    // Flags / locks
    final AtomicBoolean open = new AtomicBoolean(true);
    final Object writeLock = new Object();
    volatile boolean closingSent = false;

    // Message assembly
    int assemblingOpcode = -1;
    boolean assemblingCompressed = false;
    java.io.ByteArrayOutputStream assemblingBytes = null;
    long assemblingSize = 0L;

    // Outbound fragmentation
    int outOpcode = -1;
    final int outChunk;
    final int maxFramesPerTick;

    WsState(final ProtocolIOSession session,
            final WebSocketListener listener,
            final WebSocketClientConfig cfg,
            final ExtensionChain chain) {
        this.session = session;
        this.listener = listener;
        this.cfg = cfg;

        this.decoder = new WsDecoder(cfg.getMaxFrameSize(), false);

        this.outChunk = Math.max(256, cfg.getOutgoingChunkSize());
        this.maxFramesPerTick = Math.max(1, cfg.getMaxFramesPerTick());

        if (chain != null && !chain.isEmpty()) {
            this.encChain = chain.newEncodeChain();
            this.decChain = chain.newDecodeChain();
        } else {
            this.encChain = null;
            this.decChain = null;
        }

        final int poolBufSize = Math.max(8192, this.outChunk);
        final int poolCapacity = Math.max(16, cfg.getIoPoolCapacity());
        this.bufferPool = new SimpleBufferPool(poolBufSize, poolCapacity, cfg.isDirectBuffers());

        // Borrow one read buffer upfront
        this.readBuf = bufferPool.acquire();
    }
}