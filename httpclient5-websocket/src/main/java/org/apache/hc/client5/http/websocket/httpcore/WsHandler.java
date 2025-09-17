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
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.EventMask;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RFC6455/7692 WebSocket handler on HttpCore.
 * - Control frames + close handshake.
 * - Inbound fragmentation assembly + maxMessageSize.
 * - PMCE thread-safety: per-thread encoder (app) / decoder (I/O) via EncodeChain/DecodeChain.
 * - Outbound: control-first scheduling BETWEEN frames, never mid-frame.
 * - Outbound: bounded drain loop + cfg.outgoingChunkSize chunks so PING is timely.
 */
public final class WsHandler implements IOEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WsHandler.class);

    /**
     * Prevent monopolizing the reactor: max frames we’ll push per output tick.
     */
//    private static final int MAX_FRAMES_PER_TICK = 64;

    private final ProtocolIOSession session;
    private final WebSocketListener listener;
    private final WebSocketClientConfig cfg;

    // Per-thread PMCE chains
    private final ExtensionChain.EncodeChain encChain; // app thread
    private final ExtensionChain.DecodeChain decChain; // I/O thread

    private final FrameWriter writer = new FrameWriter();
    private final WsDecoder decoder;

    // Outbound queues (never interleave mid-frame): control first, then data
    private final ConcurrentLinkedQueue<ByteBuffer> ctrlOutbound = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ByteBuffer> dataOutbound = new ConcurrentLinkedQueue<>();
    private ByteBuffer activeWrite = null;

    private final AtomicBoolean open = new AtomicBoolean(true);
    private final WsFacade facade = new WsFacade();

    // Input accumulation
    private ByteBuffer inbuf = ByteBuffer.allocate(4096);
    private final ByteBuffer readBuf = ByteBuffer.allocate(8192);

    private volatile boolean closingSent = false;

    // Inbound fragmented message assembly
    private int assemblingOpcode = -1;
    private boolean assemblingCompressed = false;
    private ByteArrayOutputStream assemblingBytes = null;
    private long assemblingSize = 0L;

    // Outbound application fragmentation state
    private int outOpcode = -1;

    // Configured outbound chunk size
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
        this.decoder = new WsDecoder(cfg.maxFrameSize);
        this.outChunk = Math.max(256, cfg.outgoingChunkSize);
        this.maxFramesPerTick = Math.max(1, cfg.maxFramesPerTick);
        if (chain != null && !chain.isEmpty()) {
            this.encChain = chain.newEncodeChain(); // app thread
            this.decChain = chain.newDecodeChain(); // I/O thread
        } else {
            this.encChain = null;
            this.decChain = null;
        }
    }

    public WebSocket exposeWebSocket() {
        return facade;
    }

    // ------------------------------------------------------------------------
    // IOEventHandler
    // ------------------------------------------------------------------------

    @Override
    public void connected(final IOSession ioSession) {
        ioSession.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    @Override
    public void inputReady(final IOSession ioSession, final ByteBuffer src) {
        try {
            if (src != null && src.hasRemaining()) {
                appendToInbuf(src);
            }
            int n;
            do {
                readBuf.clear();
                n = ioSession.read(readBuf);
                if (n > 0) {
                    readBuf.flip();
                    appendToInbuf(readBuf);
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
                    final int code = (rte instanceof WsProtocolException)
                            ? ((WsProtocolException) rte).closeCode
                            : 1002; // protocol error
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

                // RSV validation
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

                // After we sent CLOSE, ignore everything except CLOSE
                if (closingSent && op != Opcode.CLOSE) {
                    continue;
                }

                // Control frame checks (already enforced by decoder, but keep)
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
                        if (cfg.autoPong) {
                            enqueueCtrl(writer.frame(Opcode.PONG, payload.asReadOnlyBuffer(), true, true));
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
                            enqueueCtrl(writer.closeEcho(payload.asReadOnlyBuffer()));
                            closingSent = true;
                            session.setSocketTimeout(cfg.closeWaitTimeout);
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
                        if (cfg.maxMessageSize > 0 && payload.remaining() > cfg.maxMessageSize) {
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

            // Drain bounded number of frames per tick
            while (framesThisTick < maxFramesPerTick) {
                // Finish current frame first
                if (activeWrite != null && activeWrite.hasRemaining()) {
                    final int written = ioSession.write(activeWrite);
                    if (written == 0) {
                        ioSession.setEvent(EventMask.WRITE);
                        return;
                    }
                    if (activeWrite.hasRemaining()) {
                        ioSession.setEvent(EventMask.WRITE);
                        return;
                    }
                    activeWrite = null; // finished one frame
                    framesThisTick++;
                    continue;
                }

                // No active frame: control frames have priority
                ByteBuffer next = ctrlOutbound.poll();
                if (next == null) {
                    next = dataOutbound.poll();
                }
                if (next == null) {
                    // nothing to send
                    ioSession.clearEvent(EventMask.WRITE);
                    return;
                }

                activeWrite = next;
                final int written = ioSession.write(activeWrite);
                if (written == 0) {
                    ioSession.setEvent(EventMask.WRITE);
                    return;
                }
                if (activeWrite.hasRemaining()) {
                    ioSession.setEvent(EventMask.WRITE);
                    return;
                }
                activeWrite = null; // finished this frame
                framesThisTick++;
            }

            // More work pending? ask for another spin
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
            listener.onError(new java.util.concurrent.TimeoutException());
        } catch (final Throwable ignore) {
        }
        open.set(false);
        ioSession.close(CloseMode.GRACEFUL);
    }

    @Override
    public void exception(final IOSession ioSession, final Exception cause) {
        try {
            listener.onError(cause);
        } catch (final Throwable ignore) {
        }
        open.set(false);
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
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

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
            final ByteBuffer bigger = ByteBuffer.allocate(newCap);
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
        if (cfg.maxMessageSize > 0 && assemblingSize > cfg.maxMessageSize) {
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

    private void enqueueCtrl(final ByteBuffer frame) {
        ctrlOutbound.add(frame);
        session.setEvent(EventMask.WRITE);
        session.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    private void enqueueData(final ByteBuffer frame) {
        dataOutbound.add(frame);
        session.setEvent(EventMask.WRITE);
        session.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    private void initiateCloseAndWait(final IOSession ioSession, final int code, final String reason) {
        if (!closingSent) {
            try {
                enqueueCtrl(writer.close(code, reason == null ? "" : reason));
            } catch (final Throwable ignore) {
            }
            closingSent = true;
            session.setSocketTimeout(cfg.closeWaitTimeout);
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
            int opcodeCopy = opcode;

            // Single-frame fast path with extension encode on app thread
            if (outOpcode == -1 && fin && encChain != null && data.remaining() <= outChunk) {
                final byte[] src = toBytes(data);
                final ExtensionChain.EncodeChain.Enc enc = encChain.encode(src, true, true);
                final ByteBuffer payload = ByteBuffer.wrap(enc.payload);
                final int rsv = enc.setRsv1 ? FrameWriter.RSV1 : 0;
                enqueueData(writer.frameWithRSV(opcodeCopy, payload, true, true, rsv));
                return true;
            }

            // Auto-fragment into small chunks
            if (outOpcode == -1) {
                outOpcode = opcodeCopy;
                while (data.hasRemaining()) {
                    final int n = Math.min(data.remaining(), outChunk);
                    final ByteBuffer slice = sliceN(data, n);
                    final boolean lastChunk = !data.hasRemaining() && fin;
                    enqueueData(writer.frame(opcodeCopy, slice, lastChunk, true));
                    opcodeCopy = Opcode.CONT; // subsequent chunks use CONT
                }
                if (fin) {
                    outOpcode = -1;
                }
                return true;
            }

            // Continuations while a fragmented message is in progress
            while (data.hasRemaining()) {
                final int n = Math.min(data.remaining(), outChunk);
                final ByteBuffer slice = sliceN(data, n);
                final boolean lastChunk = !data.hasRemaining() && fin;
                enqueueData(writer.frame(Opcode.CONT, slice, lastChunk, true));
            }
            if (fin) {
                outOpcode = -1;
            }
            return true;
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
            enqueueCtrl(writer.frame(Opcode.PING, data == null ? ByteBuffer.allocate(0) : data.asReadOnlyBuffer(), true, true));
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
                    enqueueCtrl(writer.close(statusCode, reason));
                } catch (final Throwable ignore) {
                }
                closingSent = true;
                session.setSocketTimeout(cfg.closeWaitTimeout);
            }
            notifyCloseOnce(statusCode, reason);
            f.complete(null);
            return f;
        }

        @Override
        public boolean isOpen() {
            return open.get() && !closingSent;
        }
    }
}
