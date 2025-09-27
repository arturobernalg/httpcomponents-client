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
package org.apache.hc.client5.http.websocket.httpcore;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.core.close.WsProtocolException;
import org.apache.hc.client5.http.websocket.core.extension.ExtensionChain;
import org.apache.hc.client5.http.websocket.core.frame.FrameWriter;
import org.apache.hc.client5.http.websocket.core.frame.Opcode;
import org.apache.hc.client5.http.websocket.core.message.CloseCodec;
import org.apache.hc.client5.http.websocket.support.SimpleBufferPool;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.EventMask;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RFC6455/7692 WebSocket handler on HttpCore with pooled buffers.
 */
public final class WsHandler implements IOEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WsHandler.class);

    /**
     * Simple pool used for read buffers and small outbound frames.
     */
    private final SimpleBufferPool bufferPool;

    /**
     * Borrowed read buffer; returned on disconnect.
     */
    private ByteBuffer readBuf;

    /**
     * Marks buffers that must be returned to pool after write completes.
     */
    private static final class OutFrame {
        final ByteBuffer buf;
        final boolean pooled;

        OutFrame(final ByteBuffer buf, final boolean pooled) {
            this.buf = buf;
            this.pooled = pooled;
        }
    }

    private final ProtocolIOSession session;
    private final WebSocketListener listener;
    private final WebSocketClientConfig cfg;

    private final ExtensionChain.EncodeChain encChain; // app thread
    private final ExtensionChain.DecodeChain decChain; // I/O thread

    private final FrameWriter writer = new FrameWriter();
    private final WsDecoder decoder;

    // Outbound queues (control first, then data). We enqueue OutFrame so we can release pooled buffers.
    private final ConcurrentLinkedQueue<OutFrame> ctrlOutbound = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<OutFrame> dataOutbound = new ConcurrentLinkedQueue<>();
    private OutFrame activeWrite = null;

    private final AtomicBoolean open = new AtomicBoolean(true);
    private final WsFacade facade = new WsFacade();
    private final Object writeLock = new Object();

    // Input accumulation (message framing). Grows as needed (heap); hot scratch is pooled (readBuf).
    private ByteBuffer inbuf = ByteBuffer.allocate(4096);

    private volatile boolean closingSent = false;

    // Inbound fragmented message assembly
    private int assemblingOpcode = -1;
    private boolean assemblingCompressed = false;
    private ByteArrayOutputStream assemblingBytes = null;
    private long assemblingSize = 0L;

    // Outbound application fragmentation
    private int outOpcode = -1;

    private final int outChunk;
    private final int maxFramesPerTick;

    public WsHandler(final ProtocolIOSession session,
                     final WebSocketListener listener,
                     final WebSocketClientConfig cfg) {
        this(session, listener, cfg, null);
    }

    public WsHandler(final ProtocolIOSession session,
                     final WebSocketListener listener,
                     final WebSocketClientConfig cfg,
                     final ExtensionChain chain) {
        this.session = session;
        this.listener = listener;
        this.cfg = cfg;

        // Decoder: policy enforcement happens in handler; decoder is lenient on RSV.
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

        // Pooled buffers: size = max(8k, outChunk) so chunks fit in one buffer.
        final int poolBufSize = Math.max(8192, this.outChunk);
        final int poolCapacity = Math.max(16, cfg.getIoPoolCapacity());
        this.bufferPool = new SimpleBufferPool(poolBufSize, poolCapacity, cfg.isDirectBuffers());

        // Borrow one read buffer upfront to prevent NPEs in inputReady()
        this.readBuf = bufferPool.acquire();
    }

    public WebSocket exposeWebSocket() {
        return facade;
    }

    @Override
    public void connected(final IOSession ioSession) {
        ioSession.setSocketTimeout(Timeout.DISABLED);
        ioSession.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    @Override
    public void inputReady(final IOSession ioSession, final ByteBuffer src) {
        try {
            if (!open.get()) {
                return;
            }
            if (readBuf == null) {
                readBuf = bufferPool.acquire();
                if (readBuf == null) {
                    // Pool exhausted or session is tearing down; nothing to read safely.
                    return;
                }
            }
            if (src != null && src.hasRemaining()) {
                appendToInbuf(src);
            }
            int n;
            do {
                ByteBuffer rb = readBuf;
                if (rb == null) {
                    rb = bufferPool.acquire();
                    readBuf = rb;
                }
                rb.clear();
                n = ioSession.read(rb);
                if (n > 0) {
                    rb.flip();
                    appendToInbuf(rb);
                }
            } while (n > 0);

            if (n < 0) {
                disconnected(ioSession);
                return;
            }

            inbuf.flip();
            for (; ; ) {
                final boolean has;
                try {
                    has = decoder.decode(inbuf);
                } catch (final RuntimeException rte) {
                    final int code = rte instanceof WsProtocolException
                            ? ((WsProtocolException) rte).closeCode
                            : 1002;
                    initiateCloseAndWait(ioSession, code, rte.getMessage());
                    inbuf.clear();
                    return;
                }
                if (!has) {
                    break;
                }

                final int op = decoder.opcode();
                final boolean fin = decoder.fin();
                final boolean r1 = decoder.rsv1();
                final boolean r2 = decoder.rsv2();
                final boolean r3 = decoder.rsv3();
                final ByteBuffer payload = decoder.payload();

                // RSV validation policy at handler-level
                if (r2 || r3) {
                    initiateCloseAndWait(ioSession, 1002, "RSV2/RSV3 not supported");
                    inbuf.clear();
                    return;
                }
                if (r1 && decChain == null) {
                    initiateCloseAndWait(ioSession, 1002, "RSV1 without negotiated extension");
                    inbuf.clear();
                    return;
                }

                if (closingSent && op != Opcode.CLOSE) {
                    continue;
                }

                if (Opcode.isControl(op)) {
                    if (!fin) {
                        initiateCloseAndWait(ioSession, 1002, "fragmented control frame");
                        inbuf.clear();
                        return;
                    }
                    if (payload.remaining() > 125) {
                        initiateCloseAndWait(ioSession, 1002, "control frame too large");
                        inbuf.clear();
                        return;
                    }
                }

                switch (op) {
                    case Opcode.PING: {
                        try {
                            listener.onPing(payload.asReadOnlyBuffer());
                        } catch (final Throwable ignore) {
                        }
                        if (cfg.isAutoPong()) {
                            enqueueCtrl(pooledFrame(Opcode.PONG, payload.asReadOnlyBuffer(), true));
                        }
                        break;
                    }
                    case Opcode.PONG: {
                        try {
                            listener.onPong(payload.asReadOnlyBuffer());
                        } catch (final Throwable ignore) {
                        }
                        break;
                    }
                    case Opcode.CLOSE: {
                        final ByteBuffer ro = payload.asReadOnlyBuffer();
                        int code = 1005;
                        String reason = "";
                        final int len = ro.remaining();
                        if (len == 1) {
                            initiateCloseAndWait(ioSession, 1002, "Close frame length of 1 is invalid");
                            inbuf.clear();
                            return;
                        } else if (len >= 2) {
                            final ByteBuffer dup = ro.slice();
                            code = CloseCodec.readCloseCode(dup);
                            reason = CloseCodec.readCloseReason(dup);
                            if (!CloseCodec.isValidCloseCodeReceived(code)) {
                                initiateCloseAndWait(ioSession, 1002, "Invalid close code: " + code);
                                inbuf.clear();
                                return;
                            }
                        }
                        notifyCloseOnce(code, reason);
                        if (!closingSent) {
                            enqueueCtrl(pooledCloseEcho(ro));
                            closingSent = true;
                            session.setSocketTimeout(cfg.getCloseWaitTimeout());
                        }
                        ioSession.close(CloseMode.GRACEFUL);
                        inbuf.clear();
                        return;
                    }
                    case Opcode.CONT: {
                        if (assemblingOpcode == -1) {
                            initiateCloseAndWait(ioSession, 1002, "Unexpected continuation frame");
                            inbuf.clear();
                            return;
                        }
                        if (r1) {
                            initiateCloseAndWait(ioSession, 1002, "RSV1 set on continuation");
                            inbuf.clear();
                            return;
                        }
                        appendToMessage(payload, ioSession);
                        if (fin) {
                            deliverAssembledMessage();
                        }
                        break;
                    }
                    case Opcode.TEXT:
                    case Opcode.BINARY: {
                        if (assemblingOpcode != -1) {
                            initiateCloseAndWait(ioSession, 1002, "New data frame while fragmented message in progress");
                            inbuf.clear();
                            return;
                        }
                        if (!fin) {
                            startMessage(op, payload, r1, ioSession);
                            break;
                        }
                        if (cfg.getMaxMessageSize() > 0 && payload.remaining() > cfg.getMaxMessageSize()) {
                            initiateCloseAndWait(ioSession, 1009, "Message too big");
                            break;
                        }
                        if (r1 && decChain != null) {
                            final byte[] comp = toBytes(payload);
                            final byte[] plain;
                            try {
                                plain = decChain.decode(comp);
                            } catch (final Exception e) {
                                initiateCloseAndWait(ioSession, 1007, "Extension decode failed");
                                inbuf.clear();
                                return;
                            }
                            deliverSingle(op, ByteBuffer.wrap(plain));
                        } else {
                            deliverSingle(op, payload.asReadOnlyBuffer());
                        }
                        break;
                    }
                    default: {
                        initiateCloseAndWait(ioSession, 1002, "Unsupported opcode: " + op);
                        inbuf.clear();
                        return;
                    }
                }
            }
            inbuf.compact();
        } catch (final Exception ex) {
            exception(ioSession, ex);
        }
    }

    @Override
    public void outputReady(final IOSession ioSession) {
        try {
            int framesThisTick = 0;

            while (framesThisTick < maxFramesPerTick) {
                if (activeWrite != null && activeWrite.buf.hasRemaining()) {
                    final int written = ioSession.write(activeWrite.buf);
                    if (written == 0) {
                        ioSession.setEvent(EventMask.WRITE);
                        return;
                    }
                    if (activeWrite.buf.hasRemaining()) {
                        ioSession.setEvent(EventMask.WRITE);
                        return;
                    }
                    releaseIfPooled(activeWrite);
                    activeWrite = null;
                    framesThisTick++;
                    continue;
                }

                OutFrame next = ctrlOutbound.poll();
                if (next == null) {
                    next = dataOutbound.poll();
                }
                if (next == null) {
                    ioSession.clearEvent(EventMask.WRITE);
                    return;
                }

                activeWrite = next;
                final int written = ioSession.write(activeWrite.buf);
                if (written == 0) {
                    ioSession.setEvent(EventMask.WRITE);
                    return;
                }
                if (activeWrite.buf.hasRemaining()) {
                    ioSession.setEvent(EventMask.WRITE);
                    return;
                }
                releaseIfPooled(activeWrite);
                activeWrite = null;
                framesThisTick++;
            }

            if (activeWrite != null || !ctrlOutbound.isEmpty() || !dataOutbound.isEmpty()) {
                ioSession.setEvent(EventMask.WRITE);
            } else {
                ioSession.clearEvent(EventMask.WRITE);
            }
        } catch (final Exception ex) {
            exception(ioSession, ex);
        }
    }

    @Override
    public void timeout(final IOSession ioSession, final Timeout timeout) {
        try {
            final String msg = "I/O timeout: " + (timeout != null ? timeout : Timeout.ZERO_MILLISECONDS);
            listener.onError(new java.util.concurrent.TimeoutException(msg));
        } catch (final Throwable ignore) {
        }
        ioSession.close(CloseMode.GRACEFUL);
    }


    @Override
    public void exception(final IOSession ioSession, final Exception cause) {
        try {
            listener.onError(cause);
        } catch (final Throwable ignore) {
        }
        ioSession.close(CloseMode.GRACEFUL);
    }

    @Override
    public void disconnected(final IOSession ioSession) {
        if (open.getAndSet(false)) {
            try {
                listener.onClose(1006, "abnormal closure");
            } catch (final Throwable ignore) {
            }
        }
        session.clearEvent(EventMask.READ | EventMask.WRITE);
        // Return any pooled buffers
        if (readBuf != null) {
            bufferPool.release(readBuf);
            readBuf = null;
        }
        if (activeWrite != null) {
            releaseIfPooled(activeWrite);
            activeWrite = null;
        }
        OutFrame f;
        while ((f = ctrlOutbound.poll()) != null) {
            releaseIfPooled(f);
        }
        while ((f = dataOutbound.poll()) != null) {
            releaseIfPooled(f);
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private void releaseIfPooled(final OutFrame f) {
        if (f != null && f.pooled) {
            bufferPool.release(f.buf);
        }
    }

    /**
     * Grow-and-append into the session input accumulator.
     */
    private void appendToInbuf(final ByteBuffer src) {
        if (src == null || !src.hasRemaining()) {
            return;
        }
        if (inbuf.remaining() < src.remaining()) {
            final int need = inbuf.position() + src.remaining();
            final int newCap = Math.max(inbuf.capacity() * 2, need);
            final ByteBuffer bigger = ByteBuffer.allocate(newCap); // keep heap for large aggregates
            inbuf.flip();
            bigger.put(inbuf);
            inbuf = bigger;
        }
        inbuf.put(src);
    }

    private void startMessage(final int opcode, final ByteBuffer payload, final boolean rsv1, final IOSession ioSession) {
        this.assemblingOpcode = opcode;
        this.assemblingCompressed = rsv1 && decChain != null;
        this.assemblingBytes = new ByteArrayOutputStream(Math.max(1024, payload.remaining()));
        this.assemblingSize = 0L;
        appendToMessage(payload, ioSession);
    }

    private void appendToMessage(final ByteBuffer payload, final IOSession ioSession) {
        final ByteBuffer dup = payload.asReadOnlyBuffer();
        final int n = dup.remaining();
        assemblingSize += n;
        if (cfg.getMaxMessageSize() > 0 && assemblingSize > cfg.getMaxMessageSize()) {
            initiateCloseAndWait(ioSession, 1009, "Message too big");
            return;
        }
        final byte[] tmp = new byte[n];
        dup.get(tmp);
        assemblingBytes.write(tmp, 0, n);
    }

    private void deliverAssembledMessage() {
        final byte[] body = assemblingBytes.toByteArray();
        final int op = assemblingOpcode;
        final boolean compressed = assemblingCompressed;

        assemblingOpcode = -1;
        assemblingCompressed = false;
        assemblingBytes = null;
        assemblingSize = 0L;

        byte[] data = body;
        if (compressed && decChain != null) {
            try {
                data = decChain.decode(body);
            } catch (final Exception e) {
                try {
                    listener.onError(e);
                } catch (final Throwable ignore) {
                }
                return;
            }
        }

        if (op == Opcode.TEXT) {
            final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                final CharBuffer cb = dec.decode(ByteBuffer.wrap(data));
                try {
                    listener.onText(cb, true);
                } catch (final Throwable ignore) {
                }
            } catch (final CharacterCodingException cce) {
                try {
                    listener.onError(cce);
                } catch (final Throwable ignore) {
                }
            }
        } else if (op == Opcode.BINARY) {
            try {
                listener.onBinary(ByteBuffer.wrap(data).asReadOnlyBuffer(), true);
            } catch (final Throwable ignore) {
            }
        }
    }

    private void deliverSingle(final int op, final ByteBuffer payloadRO) {
        if (op == Opcode.TEXT) {
            final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                final CharBuffer cb = dec.decode(payloadRO);
                try {
                    listener.onText(cb, true);
                } catch (final Throwable ignore) {
                }
            } catch (final CharacterCodingException cce) {
                try {
                    listener.onError(cce);
                } catch (final Throwable ignore) {
                }
            }
        } else if (op == Opcode.BINARY) {
            try {
                listener.onBinary(payloadRO, true);
            } catch (final Throwable ignore) {
            }
        }
    }

    private static byte[] toBytes(final ByteBuffer buf) {
        final ByteBuffer b = buf.asReadOnlyBuffer();
        final byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private void enqueueCtrl(final OutFrame frame) {
        ctrlOutbound.add(frame);
        session.setEvent(EventMask.WRITE);
        session.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    private void enqueueData(final OutFrame frame) {
        dataOutbound.add(frame);
        session.setEvent(EventMask.WRITE);
        session.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    /**
     * Build a pooled control/data frame if it fits in pool buffer, else fall back to normal allocation.
     */
    private OutFrame pooledFrame(final int opcode, final ByteBuffer payload, final boolean fin) {
        final int payloadLen = payload != null ? payload.remaining() : 0;
        final int headerExtra = payloadLen <= 125 ? 0 : payloadLen <= 0xFFFF ? 2 : 8;
        final int need = 2 + headerExtra + 4 + payloadLen; // +4 for client mask
        final ByteBuffer target = need <= bufferPool.bufferSize() ? bufferPool.acquire() : null;

        if (target != null) {
            final ByteBuffer built = writer.frameInto(opcode, payload, fin, true, target);
            built.flip();                    // position=0, limit=frame length
            return new OutFrame(built, true); // built is the same pooled buffer
        } else {
            return new OutFrame(writer.frame(opcode, payload, fin, true), false);
        }
    }

    private OutFrame pooledCloseEcho(final ByteBuffer payload) {
        final int payloadLen = payload != null ? payload.remaining() : 0;
        final int need = 2 + (payloadLen <= 125 ? 0 : payloadLen <= 0xFFFF ? 2 : 8) + 4 + payloadLen;
        final ByteBuffer target = need <= bufferPool.bufferSize() ? bufferPool.acquire() : null;
        if (target != null) {
            final ByteBuffer built = writer.frameInto(Opcode.CLOSE, payload, true, true, target);
            built.flip();
            return new OutFrame(built, true);
        } else {
            return new OutFrame(writer.frame(Opcode.CLOSE, payload, true, true), false);
        }
    }

    private void initiateCloseAndWait(final IOSession ioSession, final int code, final String reason) {
        if (!closingSent) {
            try {
                final ByteBuffer reasonBuf = reason != null && !reason.isEmpty()
                        ? StandardCharsets.UTF_8.encode(reason)
                        : ByteBuffer.allocate(0);
                if (reasonBuf.remaining() > 123) {
                    throw new IllegalArgumentException("Close reason too long");
                }
                final ByteBuffer p = ByteBuffer.allocate(2 + reasonBuf.remaining());
                p.put((byte) (code >> 8 & 0xFF)).put((byte) (code & 0xFF));
                if (reasonBuf.hasRemaining()) {
                    p.put(reasonBuf);
                }
                p.flip();
                enqueueCtrl(pooledFrame(Opcode.CLOSE, p.asReadOnlyBuffer(), true));
            } catch (final Throwable ignore) {
            }
            closingSent = true;
            session.setSocketTimeout(cfg.getCloseWaitTimeout());
        }
        notifyCloseOnce(code, reason);
    }

    private void notifyCloseOnce(final int code, final String reason) {
        if (open.getAndSet(false)) {
            try {
                listener.onClose(code, reason == null ? "" : reason);
            } catch (final Throwable ignore) {
            }
        }
    }


    private final class WsFacade implements WebSocket {
        @Override
        public boolean sendText(final CharSequence data, final boolean finalFragment) {
            if (!open.get() || closingSent) {
                return false;
            }
            final ByteBuffer plain = StandardCharsets.UTF_8.encode(data.toString());
            return sendData(Opcode.TEXT, plain, finalFragment);
        }

        @Override
        public boolean sendBinary(final ByteBuffer data, final boolean finalFragment) {
            if (!open.get() || closingSent) {
                return false;
            }
            return sendData(Opcode.BINARY, data.asReadOnlyBuffer(), finalFragment);
        }

        private boolean sendData(final int opcode, final ByteBuffer data, final boolean fin) {
            synchronized (writeLock) {
                int opcodeCopy = opcode;
                if (outOpcode == -1) {
                    outOpcode = opcodeCopy;
                    while (data.hasRemaining()) {
                        final int n = Math.min(data.remaining(), outChunk);
                        final ByteBuffer slice = sliceN(data, n);
                        final boolean lastChunk = !data.hasRemaining() && fin;
                        enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        opcodeCopy = Opcode.CONT;
                    }
                    if (fin) {
                        outOpcode = -1;
                    }
                    return true;
                }

                // Continuations
                while (data.hasRemaining()) {
                    final int n = Math.min(data.remaining(), outChunk);
                    final ByteBuffer slice = sliceN(data, n);
                    final boolean lastChunk = !data.hasRemaining() && fin;
                    enqueueData(pooledFrame(Opcode.CONT, slice, lastChunk));
                }
                if (fin) {
                    outOpcode = -1;
                }
                return true;
            }
        }

        private ByteBuffer sliceN(final ByteBuffer src, final int n) {
            final int oldLimit = src.limit();
            final int newLimit = src.position() + n;
            src.limit(newLimit);
            final ByteBuffer slice = src.slice();
            src.limit(oldLimit);
            src.position(newLimit);
            return slice;
        }

        @Override
        public boolean ping(final ByteBuffer data) {
            if (!open.get() || closingSent) {
                return false;
            }
            if (data != null && data.remaining() > 125) {
                return false;
            }
            enqueueCtrl(pooledFrame(Opcode.PING, data == null ? ByteBuffer.allocate(0) : data.asReadOnlyBuffer(), true));
            return true;
        }

        @Override
        public boolean pong(final ByteBuffer data) {
            if (!open.get() || closingSent) {
                return false;
            }
            if (data != null && data.remaining() > 125) {
                return false;
            }
            enqueueCtrl(pooledFrame(Opcode.PONG, data == null ? ByteBuffer.allocate(0) : data.asReadOnlyBuffer(), true));
            return true;
        }

        @Override
        public CompletableFuture<Void> close(final int statusCode, final String reason) {
            final CompletableFuture<Void> f = new CompletableFuture<>();
            if (!open.get()) {
                f.complete(null);
                return f;
            }
            if (!closingSent) {
                try {
                    final ByteBuffer reasonBuf = reason != null && !reason.isEmpty()
                            ? StandardCharsets.UTF_8.encode(reason)
                            : ByteBuffer.allocate(0);
                    if (reasonBuf.remaining() > 123) {
                        throw new IllegalArgumentException("Close reason too long");
                    }
                    final ByteBuffer p = ByteBuffer.allocate(2 + reasonBuf.remaining());
                    p.put((byte) (statusCode >> 8 & 0xFF)).put((byte) (statusCode & 0xFF));
                    if (reasonBuf.hasRemaining()) {
                        p.put(reasonBuf);
                    }
                    p.flip();
                    enqueueCtrl(pooledFrame(Opcode.CLOSE, p.asReadOnlyBuffer(), true));
                } catch (final Throwable ignore) {
                }
                closingSent = true;
                session.setSocketTimeout(cfg.getCloseWaitTimeout());
            }
            notifyCloseOnce(statusCode, reason);
            f.complete(null);
            return f;
        }

        @Override
        public boolean isOpen() {
            return open.get() && !closingSent;
        }

        @Override
        public boolean sendTextBatch(final List<CharSequence> fragments, final boolean finalFragment) {
            if (!open.get() || closingSent || fragments == null || fragments.isEmpty()) {
                return false;
            }
            synchronized (writeLock) {
                int opcodeCopy = outOpcode == -1 ? Opcode.TEXT : Opcode.CONT;
                int i = 0;
                for (CharSequence data : fragments) {
                    final ByteBuffer plain = StandardCharsets.UTF_8.encode(data.toString());
                    final boolean lastChunk = (i == fragments.size() - 1) && finalFragment;
                    while (plain.hasRemaining()) {
                        final int n = Math.min(plain.remaining(), outChunk);
                        final ByteBuffer slice = sliceN(plain, n);
                        enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        opcodeCopy = Opcode.CONT;
                    }
                    i++;
                }
                if (finalFragment) {
                    outOpcode = -1;
                }
                return true;
            }
        }

        public boolean sendBinaryBatch(final List<ByteBuffer> fragments, final boolean finalFragment) {
            if (!open.get() || closingSent || fragments == null || fragments.isEmpty()) {
                return false;
            }
            synchronized (writeLock) {
                int opcodeCopy = outOpcode == -1 ? Opcode.BINARY : Opcode.CONT;
                int i = 0;
                for (ByteBuffer data : fragments) {
                    final ByteBuffer roData = data.asReadOnlyBuffer();
                    final boolean lastChunk = (i == fragments.size() - 1) && finalFragment;
                    while (roData.hasRemaining()) {
                        final int n = Math.min(roData.remaining(), outChunk);
                        final ByteBuffer slice = sliceN(roData, n);
                        enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        opcodeCopy = Opcode.CONT;
                    }
                    i++;
                }
                if (finalFragment) {
                    outOpcode = -1;
                }
                return true;
            }
        }
    }


}
