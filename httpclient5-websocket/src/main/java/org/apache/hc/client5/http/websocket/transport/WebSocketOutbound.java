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
package org.apache.hc.client5.http.websocket.transport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.core.extension.WebSocketExtensionChain;
import org.apache.hc.client5.http.websocket.core.frame.FrameHeaderBits;
import org.apache.hc.client5.http.websocket.core.frame.FrameOpcode;
import org.apache.hc.client5.http.websocket.core.message.CloseCodec;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.EventMask;
import org.apache.hc.core5.reactor.IOSession;

/**
 * Outbound path: frame building, queues, writing, and the app-facing WebSocket facade.
 */
@Internal
final class WebSocketOutbound {

    static final class OutFrame {
        final ByteBuffer buf;
        final boolean pooled;

        OutFrame(final ByteBuffer buf, final boolean pooled) {
            this.buf = buf;
            this.pooled = pooled;
        }
    }

    private final WebSocketSessionState s;
    private final WebSocket facade;

    WebSocketOutbound(final WebSocketSessionState state) {
        this.s = state;
        this.facade = new Facade();
    }

    WebSocket facade() {
        return facade;
    }

    // ---- IO writing ----
    void onOutputReady(final IOSession ioSession) {
        try {
            int framesThisTick = 0;

            while (framesThisTick < s.maxFramesPerTick) {
                if (s.activeWrite != null && s.activeWrite.buf.hasRemaining()) {
                    final int written = ioSession.write(s.activeWrite.buf);
                    if (written == 0) {
                        ioSession.setEvent(EventMask.WRITE);
                        return;
                    }
                    if (s.activeWrite.buf.hasRemaining()) {
                        ioSession.setEvent(EventMask.WRITE);
                        return;
                    }
                    releaseIfPooled(s.activeWrite);
                    s.activeWrite = null;
                    framesThisTick++;
                    continue;
                }

                OutFrame next = s.ctrlOutbound.poll();
                if (next == null) {
                    next = s.dataOutbound.poll();
                }
                if (next == null) {
                    ioSession.clearEvent(EventMask.WRITE);
                    return;
                }

                s.activeWrite = next;
                final int written = ioSession.write(s.activeWrite.buf);
                if (written == 0) {
                    ioSession.setEvent(EventMask.WRITE);
                    return;
                }
                if (s.activeWrite.buf.hasRemaining()) {
                    ioSession.setEvent(EventMask.WRITE);
                    return;
                }
                releaseIfPooled(s.activeWrite);
                s.activeWrite = null;
                framesThisTick++;
            }

            if (s.activeWrite != null || !s.ctrlOutbound.isEmpty() || !s.dataOutbound.isEmpty()) {
                ioSession.setEvent(EventMask.WRITE);
            } else {
                ioSession.clearEvent(EventMask.WRITE);
            }
        } catch (final Exception ex) {
            try {
                s.listener.onError(ex);
            } catch (final Throwable ignore) {
            }
            ioSession.close(CloseMode.GRACEFUL);
        }
    }

    // ---- Queue helpers ----
    void enqueueCtrl(final OutFrame frame) {
        s.ctrlOutbound.add(frame);
        s.session.setEvent(EventMask.WRITE);
        s.session.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    void enqueueData(final OutFrame frame) {
        s.dataOutbound.add(frame);
        s.session.setEvent(EventMask.WRITE);
        s.session.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    void drainAndRelease() {
        if (s.activeWrite != null) {
            releaseIfPooled(s.activeWrite);
            s.activeWrite = null;
        }
        OutFrame f;
        while ((f = s.ctrlOutbound.poll()) != null) {
            releaseIfPooled(f);
        }
        while ((f = s.dataOutbound.poll()) != null) {
            releaseIfPooled(f);
        }
    }

    void releaseIfPooled(final OutFrame f) {
        if (f != null && f.pooled) {
            s.bufferPool.release(f.buf);
        }
    }

    // ---- Frame building (pooled if possible) ----
    OutFrame pooledFrame(final int opcode, final ByteBuffer payload, final boolean fin) {
        final int payloadLen = payload != null ? payload.remaining() : 0;
        final int headerExtra = payloadLen <= 125 ? 0 : payloadLen <= 0xFFFF ? 2 : 8;
        final int need = 2 + headerExtra + 4 + payloadLen; // +4 for client mask
        final ByteBuffer target = need <= s.bufferPool.bufferSize() ? s.bufferPool.acquire() : null;

        if (target != null) {
            final ByteBuffer built = s.writer.frameInto(opcode, payload, fin, true, target);
            built.flip();
            return new OutFrame(built, true);
        } else {
            return new OutFrame(s.writer.frame(opcode, payload, fin, true), false);
        }
    }

    OutFrame pooledFrameWithRSV(final int opcode, final ByteBuffer payload, final boolean fin, final boolean setRsv1) {
        final int rsv = setRsv1 ? FrameHeaderBits.RSV1 : 0;
        final int payloadLen = payload != null ? payload.remaining() : 0;
        final int headerExtra = payloadLen <= 125 ? 0 : payloadLen <= 0xFFFF ? 2 : 8;
        final int need = 2 + headerExtra + 4 + payloadLen;
        final ByteBuffer target = need <= s.bufferPool.bufferSize() ? s.bufferPool.acquire() : null;

        if (target != null) {
            final ByteBuffer built = s.writer.frameIntoWithRSV(opcode, payload, fin, true, rsv, target);
            built.flip();
            return new OutFrame(built, true);
        } else {
            return new OutFrame(s.writer.frameWithRSV(opcode, payload, fin, true, rsv), false);
        }
    }

    OutFrame pooledCloseEcho(final ByteBuffer payload) {
        final int payloadLen = payload != null ? payload.remaining() : 0;
        final int need = 2 + (payloadLen <= 125 ? 0 : payloadLen <= 0xFFFF ? 2 : 8) + 4 + payloadLen;
        final ByteBuffer target = need <= s.bufferPool.bufferSize() ? s.bufferPool.acquire() : null;
        if (target != null) {
            final ByteBuffer built = s.writer.frameInto(FrameOpcode.CLOSE, payload, true, true, target);
            built.flip();
            return new OutFrame(built, true);
        } else {
            return new OutFrame(s.writer.frame(FrameOpcode.CLOSE, payload, true, true), false);
        }
    }

    // ---- Facade ----
    private final class Facade implements WebSocket {

        @Override
        public boolean sendText(final CharSequence data, final boolean finalFragment) {
            if (!s.open.get() || s.closingSent) return false;
            final ByteBuffer plain = StandardCharsets.UTF_8.encode(data.toString());
            return sendData(FrameOpcode.TEXT, plain, finalFragment);
        }

        @Override
        public boolean sendBinary(final ByteBuffer data, final boolean finalFragment) {
            if (!s.open.get() || s.closingSent) return false;
            return sendData(FrameOpcode.BINARY, data.asReadOnlyBuffer(), finalFragment);
        }

        private boolean sendData(final int opcode, final ByteBuffer data, final boolean fin) {
            final ReentrantLock lock = s.writeLock;
            lock.lock();
            try {
                final boolean compressionOn = s.encChain != null;
                int opcodeCopy = opcode;
                boolean first = s.outOpcode == -1; // first frame of this message?

                if (s.outOpcode == -1) {
                    s.outOpcode = opcodeCopy;
                }

                final ByteBuffer remaining = data;
                while (remaining.hasRemaining()) {
                    final int n = Math.min(remaining.remaining(), s.outChunk);
                    final ByteBuffer slice = sliceN(remaining, n);
                    final boolean lastChunk = !remaining.hasRemaining() && fin;

                    if (!compressionOn) {
                        enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                    } else {
                        final byte[] in = toBytes(slice);
                        final WebSocketExtensionChain.Encoded enc =
                                s.encChain.encode(in, first, lastChunk);
                        final ByteBuffer encBuf = ByteBuffer.wrap(enc.payload);
                        final boolean setRsv1 = first && enc.setRsvOnFirst;
                        enqueueData(pooledFrameWithRSV(opcodeCopy, encBuf, lastChunk, setRsv1));
                    }

                    opcodeCopy = FrameOpcode.CONT;
                    first = false;
                }

                if (fin) {
                    s.outOpcode = -1;
                }
                return true;
            } finally {
                lock.unlock();
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

        private byte[] toBytes(final ByteBuffer buf) {
            final ByteBuffer b = buf.asReadOnlyBuffer();
            final byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        }

        @Override
        public boolean ping(final ByteBuffer data) {
            if (!s.open.get() || s.closingSent) return false;
            if (data != null && data.remaining() > 125) return false;
            enqueueCtrl(pooledFrame(FrameOpcode.PING, data == null ? ByteBuffer.allocate(0) : data.asReadOnlyBuffer(), true));
            return true;
        }

        @Override
        public boolean pong(final ByteBuffer data) {
            if (!s.open.get() || s.closingSent) return false;
            if (data != null && data.remaining() > 125) return false;
            enqueueCtrl(pooledFrame(FrameOpcode.PONG, data == null ? ByteBuffer.allocate(0) : data.asReadOnlyBuffer(), true));
            return true;
        }

        @Override
        public CompletableFuture<Void> close(final int statusCode, final String reason) {
            final CompletableFuture<Void> f = new CompletableFuture<>();
            if (!s.open.get()) {
                f.complete(null);
                return f;
            }

            if (!CloseCodec.isValidToSend(statusCode)) {
                f.completeExceptionally(new IllegalArgumentException("Invalid close code to send: " + statusCode));
                return f;
            }

            if (!s.closingSent) {
                final String safe = CloseCodec.truncateReasonUtf8(reason);
                final ByteBuffer reasonBuf = safe.isEmpty() ? ByteBuffer.allocate(0) : StandardCharsets.UTF_8.encode(safe);
                if (reasonBuf.remaining() > 123) {
                    f.completeExceptionally(new IllegalArgumentException("Close reason too long"));
                    return f;
                }

                final ByteBuffer p = ByteBuffer.allocate(2 + reasonBuf.remaining());
                p.put((byte) (statusCode >> 8 & 0xFF)).put((byte) (statusCode & 0xFF));
                if (reasonBuf.hasRemaining()) {
                    p.put(reasonBuf);
                }
                p.flip();

                enqueueCtrl(pooledFrame(FrameOpcode.CLOSE, p.asReadOnlyBuffer(), true));
                s.closingSent = true;
                s.session.setSocketTimeout(s.cfg.getCloseWaitTimeout());
            }

            if (s.open.getAndSet(false)) {
                try {
                    s.listener.onClose(statusCode, reason == null ? "" : CloseCodec.truncateReasonUtf8(reason));
                } catch (final Throwable ignore) {
                }
            }
            f.complete(null);
            return f;
        }

        @Override
        public boolean isOpen() {
            return s.open.get() && !s.closingSent;
        }

        @Override
        public boolean sendTextBatch(final List<CharSequence> fragments, final boolean finalFragment) {
            if (!s.open.get() || s.closingSent || fragments == null || fragments.isEmpty()) return false;
            final ReentrantLock lock = s.writeLock;
            lock.lock();
            try {
                final boolean compressionOn = s.encChain != null;
                int opcodeCopy = s.outOpcode == -1 ? FrameOpcode.TEXT : FrameOpcode.CONT;
                boolean first = s.outOpcode == -1;
                if (s.outOpcode == -1) s.outOpcode = opcodeCopy;

                for (int i = 0; i < fragments.size(); i++) {
                    final CharSequence data = fragments.get(i);
                    final ByteBuffer plain = StandardCharsets.UTF_8.encode(data.toString());
                    final ByteBuffer remaining = plain;
                    while (remaining.hasRemaining()) {
                        final int n = Math.min(remaining.remaining(), s.outChunk);
                        final ByteBuffer slice = sliceN(remaining, n);
                        final boolean isLastFragment = i == fragments.size() - 1;
                        final boolean lastChunk = !remaining.hasRemaining() && isLastFragment && finalFragment;

                        if (!compressionOn) {
                            enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        } else {
                            final byte[] in = toBytes(slice);
                            final WebSocketExtensionChain.Encoded enc =
                                    s.encChain.encode(in, first, lastChunk);
                            final ByteBuffer encBuf = ByteBuffer.wrap(enc.payload);
                            final boolean setRsv1 = first && enc.setRsvOnFirst;
                            enqueueData(pooledFrameWithRSV(opcodeCopy, encBuf, lastChunk, setRsv1));
                        }

                        opcodeCopy = FrameOpcode.CONT;
                        first = false;
                    }
                }
                if (finalFragment) s.outOpcode = -1;
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean sendBinaryBatch(final List<ByteBuffer> fragments, final boolean finalFragment) {
            if (!s.open.get() || s.closingSent || fragments == null || fragments.isEmpty()) return false;
            final ReentrantLock lock = s.writeLock;
            lock.lock();
            try {
                final boolean compressionOn = s.encChain != null;
                int opcodeCopy = s.outOpcode == -1 ? FrameOpcode.BINARY : FrameOpcode.CONT;
                boolean first = s.outOpcode == -1;
                if (s.outOpcode == -1) s.outOpcode = opcodeCopy;

                for (int i = 0; i < fragments.size(); i++) {
                    final ByteBuffer ro = fragments.get(i).asReadOnlyBuffer();
                    final ByteBuffer remaining = ro;
                    while (remaining.hasRemaining()) {
                        final int n = Math.min(remaining.remaining(), s.outChunk);
                        final ByteBuffer slice = sliceN(remaining, n);
                        final boolean isLastFragment = i == fragments.size() - 1;
                        final boolean lastChunk = !remaining.hasRemaining() && isLastFragment && finalFragment;

                        if (!compressionOn) {
                            enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        } else {
                            final byte[] in = toBytes(slice);
                            final WebSocketExtensionChain.Encoded enc =
                                    s.encChain.encode(in, first, lastChunk);
                            final ByteBuffer encBuf = ByteBuffer.wrap(enc.payload);
                            final boolean setRsv1 = first && enc.setRsvOnFirst;
                            enqueueData(pooledFrameWithRSV(opcodeCopy, encBuf, lastChunk, setRsv1));
                        }

                        opcodeCopy = FrameOpcode.CONT;
                        first = false;
                    }
                }
                if (finalFragment) s.outOpcode = -1;
                return true;
            } finally {
                lock.unlock();
            }
        }
    }
}