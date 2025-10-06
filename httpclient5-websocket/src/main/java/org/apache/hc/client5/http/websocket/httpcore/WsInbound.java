package org.apache.hc.client5.http.websocket.httpcore;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.apache.hc.client5.http.websocket.core.close.WsProtocolException;
import org.apache.hc.client5.http.websocket.core.frame.Opcode;
import org.apache.hc.client5.http.websocket.core.message.CloseCodec;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.EventMask;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbound path: decoding, validation, fragment assembly, close handshake.
 */
final class WsInbound {

    private static final Logger LOG = LoggerFactory.getLogger(WsInbound.class);

    private final WsState s;
    private final WsOutbound out;

    WsInbound(final WsState state, final WsOutbound outbound) {
        this.s = state;
        this.out = outbound;
    }

    // ---- lifecycle ----
    void onConnected(final IOSession ioSession) {
        ioSession.setSocketTimeout(Timeout.DISABLED);
        ioSession.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    void onTimeout(final IOSession ioSession, final Timeout timeout) {
        try {
            final String msg = "I/O timeout: " + (timeout != null ? timeout : Timeout.ZERO_MILLISECONDS);
            s.listener.onError(new java.util.concurrent.TimeoutException(msg));
        } catch (final Throwable ignore) {
        }
    }

    void onException(final IOSession ioSession, final Exception cause) {
        try {
            s.listener.onError(cause);
        } catch (final Throwable ignore) {
        }
    }

    void onDisconnected(final IOSession ioSession) {
        if (s.open.getAndSet(false)) {
            try {
                s.listener.onClose(1006, "abnormal closure");
            } catch (final Throwable ignore) {
            }
        }
        // Return any pooled buffers
        if (s.readBuf != null) {
            s.bufferPool.release(s.readBuf);
            s.readBuf = null;
        }
        out.drainAndRelease();
        s.session.clearEvent(EventMask.READ | EventMask.WRITE);
    }

    // ---- input ----
    void onInputReady(final IOSession ioSession, final ByteBuffer src) {
        try {
            if (!s.open.get()) {
                return;
            }
            if (s.readBuf == null) {
                s.readBuf = s.bufferPool.acquire();
                if (s.readBuf == null) {
                    // Pool exhausted or tearing down
                    return;
                }
            }
            if (src != null && src.hasRemaining()) {
                appendToInbuf(src);
            }
            int n;
            do {
                ByteBuffer rb = s.readBuf;
                if (rb == null) {
                    rb = s.bufferPool.acquire();
                    s.readBuf = rb;
                }
                rb.clear();
                n = ioSession.read(rb);
                if (n > 0) {
                    rb.flip();
                    appendToInbuf(rb);
                }
            } while (n > 0);

            if (n < 0) {
                onDisconnected(ioSession);
                return;
            }

            s.inbuf.flip();
            for (; ; ) {
                final boolean has;
                try {
                    has = s.decoder.decode(s.inbuf);
                } catch (final RuntimeException rte) {
                    final int code = (rte instanceof WsProtocolException)
                            ? ((WsProtocolException) rte).closeCode
                            : 1002;
                    initiateCloseAndWait(ioSession, code, rte.getMessage());
                    s.inbuf.clear();
                    return;
                }
                if (!has) {
                    break;
                }

                final int op = s.decoder.opcode();
                final boolean fin = s.decoder.fin();
                final boolean r1 = s.decoder.rsv1();
                final boolean r2 = s.decoder.rsv2();
                final boolean r3 = s.decoder.rsv3();
                final ByteBuffer payload = s.decoder.payload();

                if (r2 || r3) {
                    initiateCloseAndWait(ioSession, 1002, "RSV2/RSV3 not supported");
                    s.inbuf.clear();
                    return;
                }
                if (r1 && s.decChain == null) {
                    initiateCloseAndWait(ioSession, 1002, "RSV1 without negotiated extension");
                    s.inbuf.clear();
                    return;
                }

                if (s.closingSent && op != Opcode.CLOSE) {
                    continue;
                }

                if (Opcode.isControl(op)) {
                    if (!fin) {
                        initiateCloseAndWait(ioSession, 1002, "fragmented control frame");
                        s.inbuf.clear();
                        return;
                    }
                    if (payload.remaining() > 125) {
                        initiateCloseAndWait(ioSession, 1002, "control frame too large");
                        s.inbuf.clear();
                        return;
                    }
                }

                switch (op) {
                    case Opcode.PING: {
                        try {
                            s.listener.onPing(payload.asReadOnlyBuffer());
                        } catch (final Throwable ignore) {
                        }
                        if (s.cfg.isAutoPong()) {
                            out.enqueueCtrl(out.pooledFrame(Opcode.PONG, payload.asReadOnlyBuffer(), true));
                        }
                        break;
                    }
                    case Opcode.PONG: {
                        try {
                            s.listener.onPong(payload.asReadOnlyBuffer());
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
                            s.inbuf.clear();
                            return;
                        } else if (len >= 2) {
                            final ByteBuffer dup = ro.slice();
                            code = CloseCodec.readCloseCode(dup);
                            reason = CloseCodec.readCloseReason(dup);
                            if (!CloseCodec.isValidCloseCodeReceived(code)) {
                                initiateCloseAndWait(ioSession, 1002, "Invalid close code: " + code);
                                s.inbuf.clear();
                                return;
                            }
                        }
                        notifyCloseOnce(code, reason);
                        if (!s.closingSent) {
                            out.enqueueCtrl(out.pooledCloseEcho(ro));
                            s.closingSent = true;
                            s.session.setSocketTimeout(s.cfg.getCloseWaitTimeout());
                        }
                        ioSession.close(CloseMode.GRACEFUL);
                        s.inbuf.clear();
                        return;
                    }
                    case Opcode.CONT: {
                        if (s.assemblingOpcode == -1) {
                            initiateCloseAndWait(ioSession, 1002, "Unexpected continuation frame");
                            s.inbuf.clear();
                            return;
                        }
                        if (r1) {
                            initiateCloseAndWait(ioSession, 1002, "RSV1 set on continuation");
                            s.inbuf.clear();
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
                        if (s.assemblingOpcode != -1) {
                            initiateCloseAndWait(ioSession, 1002, "New data frame while fragmented message in progress");
                            s.inbuf.clear();
                            return;
                        }
                        if (!fin) {
                            startMessage(op, payload, r1, ioSession);
                            break;
                        }
                        if (s.cfg.getMaxMessageSize() > 0 && payload.remaining() > s.cfg.getMaxMessageSize()) {
                            initiateCloseAndWait(ioSession, 1009, "Message too big");
                            break;
                        }
                        if (r1 && s.decChain != null) {
                            final byte[] comp = toBytes(payload);
                            final byte[] plain;
                            try {
                                plain = s.decChain.decode(comp);
                            } catch (final Exception e) {
                                initiateCloseAndWait(ioSession, 1007, "Extension decode failed");
                                s.inbuf.clear();
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
                        s.inbuf.clear();
                        return;
                    }
                }
            }
            s.inbuf.compact();
        } catch (final Exception ex) {
            onException(ioSession, ex);
            ioSession.close(CloseMode.GRACEFUL);
        }
    }

    // ---- helpers ----
    private void appendToInbuf(final ByteBuffer src) {
        if (src == null || !src.hasRemaining()) {
            return;
        }
        if (s.inbuf.remaining() < src.remaining()) {
            final int need = s.inbuf.position() + src.remaining();
            final int newCap = Math.max(s.inbuf.capacity() * 2, need);
            final ByteBuffer bigger = ByteBuffer.allocate(newCap);
            s.inbuf.flip();
            bigger.put(s.inbuf);
            s.inbuf = bigger;
        }
        s.inbuf.put(src);
    }

    private void startMessage(final int opcode, final ByteBuffer payload, final boolean rsv1, final IOSession ioSession) {
        s.assemblingOpcode = opcode;
        s.assemblingCompressed = rsv1 && s.decChain != null;
        s.assemblingBytes = new java.io.ByteArrayOutputStream(Math.max(1024, payload.remaining()));
        s.assemblingSize = 0L;
        appendToMessage(payload, ioSession);
    }

    private void appendToMessage(final ByteBuffer payload, final IOSession ioSession) {
        final ByteBuffer dup = payload.asReadOnlyBuffer();
        final int n = dup.remaining();
        s.assemblingSize += n;
        if (s.cfg.getMaxMessageSize() > 0 && s.assemblingSize > s.cfg.getMaxMessageSize()) {
            initiateCloseAndWait(ioSession, 1009, "Message too big");
            return;
        }
        final byte[] tmp = new byte[n];
        dup.get(tmp);
        s.assemblingBytes.write(tmp, 0, n);
    }

    private void deliverAssembledMessage() {
        final byte[] body = s.assemblingBytes.toByteArray();
        final int op = s.assemblingOpcode;
        final boolean compressed = s.assemblingCompressed;

        s.assemblingOpcode = -1;
        s.assemblingCompressed = false;
        s.assemblingBytes = null;
        s.assemblingSize = 0L;

        byte[] data = body;
        if (compressed && s.decChain != null) {
            try {
                data = s.decChain.decode(body);
            } catch (final Exception e) {
                try {
                    s.listener.onError(e);
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
                    s.listener.onText(cb, true);
                } catch (final Throwable ignore) {
                }
            } catch (final CharacterCodingException cce) {
                try {
                    s.listener.onError(cce);
                } catch (final Throwable ignore) {
                }
            }
        } else if (op == Opcode.BINARY) {
            try {
                s.listener.onBinary(ByteBuffer.wrap(data).asReadOnlyBuffer(), true);
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
                    s.listener.onText(cb, true);
                } catch (final Throwable ignore) {
                }
            } catch (final CharacterCodingException cce) {
                try {
                    s.listener.onError(cce);
                } catch (final Throwable ignore) {
                }
            }
        } else if (op == Opcode.BINARY) {
            try {
                s.listener.onBinary(payloadRO, true);
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

    private void initiateCloseAndWait(final IOSession ioSession, final int code, final String reason) {
        if (!s.closingSent) {
            try {
                final ByteBuffer reasonBuf = (reason != null && !reason.isEmpty())
                        ? StandardCharsets.UTF_8.encode(reason)
                        : ByteBuffer.allocate(0);
                if (reasonBuf.remaining() > 123) {
                    throw new IllegalArgumentException("Close reason too long");
                }
                final ByteBuffer p = ByteBuffer.allocate(2 + reasonBuf.remaining());
                p.put((byte) ((code >> 8) & 0xFF)).put((byte) (code & 0xFF));
                if (reasonBuf.hasRemaining()) {
                    p.put(reasonBuf);
                }
                p.flip();
                out.enqueueCtrl(out.pooledFrame(Opcode.CLOSE, p.asReadOnlyBuffer(), true));
            } catch (final Throwable ignore) {
            }
            s.closingSent = true;
            s.session.setSocketTimeout(s.cfg.getCloseWaitTimeout());
        }
        notifyCloseOnce(code, reason);
    }

    private void notifyCloseOnce(final int code, final String reason) {
        if (s.open.getAndSet(false)) {
            try {
                s.listener.onClose(code, reason == null ? "" : reason);
            } catch (final Throwable ignore) {
            }
        }
    }
}