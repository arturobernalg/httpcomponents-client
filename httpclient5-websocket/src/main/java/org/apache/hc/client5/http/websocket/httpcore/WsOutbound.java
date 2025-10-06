package org.apache.hc.client5.http.websocket.httpcore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.core.frame.Opcode;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.EventMask;
import org.apache.hc.core5.reactor.IOSession;

/**
 * Outbound path: frame building, queues, writing, and the app-facing WebSocket facade.
 */
final class WsOutbound {

    static final class OutFrame {
        final ByteBuffer buf;
        final boolean pooled;

        OutFrame(final ByteBuffer buf, final boolean pooled) {
            this.buf = buf;
            this.pooled = pooled;
        }
    }

    private final WsState s;
    private final WebSocket facade;

    WsOutbound(final WsState state) {
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

    OutFrame pooledCloseEcho(final ByteBuffer payload) {
        final int payloadLen = payload != null ? payload.remaining() : 0;
        final int need = 2 + (payloadLen <= 125 ? 0 : payloadLen <= 0xFFFF ? 2 : 8) + 4 + payloadLen;
        final ByteBuffer target = need <= s.bufferPool.bufferSize() ? s.bufferPool.acquire() : null;
        if (target != null) {
            final ByteBuffer built = s.writer.frameInto(Opcode.CLOSE, payload, true, true, target);
            built.flip();
            return new OutFrame(built, true);
        } else {
            return new OutFrame(s.writer.frame(Opcode.CLOSE, payload, true, true), false);
        }
    }

    // ---- Facade ----
    private final class Facade implements WebSocket {

        @Override
        public boolean sendText(final CharSequence data, final boolean finalFragment) {
            if (!s.open.get() || s.closingSent) {
                return false;
            }
            final ByteBuffer plain = StandardCharsets.UTF_8.encode(data.toString());
            return sendData(Opcode.TEXT, plain, finalFragment);
        }

        @Override
        public boolean sendBinary(final ByteBuffer data, final boolean finalFragment) {
            if (!s.open.get() || s.closingSent) {
                return false;
            }
            return sendData(Opcode.BINARY, data.asReadOnlyBuffer(), finalFragment);
        }

        private boolean sendData(final int opcode, final ByteBuffer data, final boolean fin) {
            synchronized (s.writeLock) {
                int opcodeCopy = opcode;
                if (s.outOpcode == -1) {
                    s.outOpcode = opcodeCopy;
                    while (data.hasRemaining()) {
                        final int n = Math.min(data.remaining(), s.outChunk);
                        final ByteBuffer slice = sliceN(data, n);
                        final boolean lastChunk = !data.hasRemaining() && fin;
                        enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        opcodeCopy = Opcode.CONT;
                    }
                    if (fin) {
                        s.outOpcode = -1;
                    }
                    return true;
                }
                // Continuations
                while (data.hasRemaining()) {
                    final int n = Math.min(data.remaining(), s.outChunk);
                    final ByteBuffer slice = sliceN(data, n);
                    final boolean lastChunk = !data.hasRemaining() && fin;
                    enqueueData(pooledFrame(Opcode.CONT, slice, lastChunk));
                }
                if (fin) {
                    s.outOpcode = -1;
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
            if (!s.open.get() || s.closingSent) {
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
            if (!s.open.get() || s.closingSent) {
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
            final CompletableFuture<Void> f = new CompletableFuture<Void>();
            if (!s.open.get()) {
                f.complete(null);
                return f;
            }
            if (!s.closingSent) {
                try {
                    final ByteBuffer reasonBuf = reason != null && !reason.isEmpty()
                            ? StandardCharsets.UTF_8.encode(reason)
                            : ByteBuffer.allocate(0);
                    if (reasonBuf.remaining() > 123) {
                        throw new IllegalArgumentException("Close reason too long");
                    }
                    final ByteBuffer p = ByteBuffer.allocate(2 + reasonBuf.remaining());
                    p.put((byte) ((statusCode >> 8) & 0xFF)).put((byte) (statusCode & 0xFF));
                    if (reasonBuf.hasRemaining()) {
                        p.put(reasonBuf);
                    }
                    p.flip();
                    enqueueCtrl(pooledFrame(Opcode.CLOSE, p.asReadOnlyBuffer(), true));
                } catch (final Throwable ignore) {
                }
                s.closingSent = true;
                s.session.setSocketTimeout(s.cfg.getCloseWaitTimeout());
            }
            // Notify app now; the peer reply (if any) will be handled by inbound
            if (s.open.getAndSet(false)) {
                try {
                    s.listener.onClose(statusCode, reason == null ? "" : reason);
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
            if (!s.open.get() || s.closingSent || fragments == null || fragments.isEmpty()) {
                return false;
            }
            synchronized (s.writeLock) {
                int opcodeCopy = s.outOpcode == -1 ? Opcode.TEXT : Opcode.CONT;
                int i = 0;
                for (final CharSequence data : fragments) {
                    final ByteBuffer plain = StandardCharsets.UTF_8.encode(data.toString());
                    final boolean lastChunk = i == fragments.size() - 1 && finalFragment;
                    while (plain.hasRemaining()) {
                        final int n = Math.min(plain.remaining(), s.outChunk);
                        final ByteBuffer slice = sliceN(plain, n);
                        enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        opcodeCopy = Opcode.CONT;
                    }
                    i++;
                }
                if (finalFragment) {
                    s.outOpcode = -1;
                }
                return true;
            }
        }

        @Override
        public boolean sendBinaryBatch(final List<ByteBuffer> fragments, final boolean finalFragment) {
            if (!s.open.get() || s.closingSent || fragments == null || fragments.isEmpty()) {
                return false;
            }
            synchronized (s.writeLock) {
                int opcodeCopy = s.outOpcode == -1 ? Opcode.BINARY : Opcode.CONT;
                int i = 0;
                for (final ByteBuffer data : fragments) {
                    final ByteBuffer roData = data.asReadOnlyBuffer();
                    final boolean lastChunk = i == fragments.size() - 1 && finalFragment;
                    while (roData.hasRemaining()) {
                        final int n = Math.min(roData.remaining(), s.outChunk);
                        final ByteBuffer slice = sliceN(roData, n);
                        enqueueData(pooledFrame(opcodeCopy, slice, lastChunk));
                        opcodeCopy = Opcode.CONT;
                    }
                    i++;
                }
                if (finalFragment) {
                    s.outOpcode = -1;
                }
                return true;
            }
        }
    }
}