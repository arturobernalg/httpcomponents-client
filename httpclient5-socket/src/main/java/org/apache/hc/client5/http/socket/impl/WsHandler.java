package org.apache.hc.client5.http.socket.impl;

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

import org.apache.hc.client5.http.socket.WebSocket;
import org.apache.hc.client5.http.socket.WebSocketClientConfig;
import org.apache.hc.client5.http.socket.WebSocketListener;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.EventMask;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WsHandler implements IOEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WsHandler.class);

    private final ProtocolIOSession session;
    private final WebSocketListener listener;
    private final WebSocketClientConfig cfg;
    private final PerMessageDeflate pmce; // may be null if not negotiated

    private final WsEncoder encoder = new WsEncoder();
    private final WsDecoder decoder;
    private final ConcurrentLinkedQueue<ByteBuffer> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final WsFacade facade = new WsFacade();

    private ByteBuffer inbuf = ByteBuffer.allocate(4096);
    private final ByteBuffer readBuf = ByteBuffer.allocate(8192);

    // Incoming assembly
    private int assemblingOpcode = -1;
    private boolean assemblingCompressed = false;
    private ByteArrayOutputStream assemblingBytes = null;
    private long assemblingSize = 0L;

    // Outgoing streaming state (for fragmentation + PMCE)
    private int outOpcode = -1;           // TEXT or BINARY while a fragmented message is in progress
    private boolean outCompressed = false;
    private boolean outFirst = false;     // whether next fragment is the first

    private volatile boolean closingSent = false;

    public WsHandler(final ProtocolIOSession session,
                     final WebSocketListener listener,
                     final WebSocketClientConfig cfg) {
        this(session, listener, cfg, null);
    }

    public WsHandler(final ProtocolIOSession session,
                     final WebSocketListener listener,
                     final WebSocketClientConfig cfg,
                     final PerMessageDeflate pmce) {
        this.session = session;
        this.listener = listener;
        this.cfg = cfg;
        this.pmce = pmce;
        this.decoder = new WsDecoder(cfg.maxFrameSize);
    }

    public WebSocket exposeWebSocket() {
        return facade;
    }

    @Override
    public void connected(final IOSession ioSession) {
        if (LOG.isDebugEnabled()) LOG.debug("WS handler connected; arming READ|WRITE");
        ioSession.setEventMask(EventMask.READ | EventMask.WRITE);
    }

    @Override
    public void inputReady(final IOSession ioSession, final ByteBuffer src) {
        try {
            int totalRead = 0;
            if (src != null && src.hasRemaining()) {
                appendToInbuf(src);
                totalRead += src.remaining();
            }
            int n;
            do {
                readBuf.clear();
                n = ioSession.read(readBuf);
                if (n > 0) {
                    totalRead += n;
                    readBuf.flip();
                    appendToInbuf(readBuf);
                }
            } while (n > 0);
            if (n < 0) {
                disconnected(ioSession);
                return;
            }
            if (LOG.isDebugEnabled()) LOG.debug("WS inputReady drained {} bytes", totalRead);

            inbuf.flip();
            while (true) {
                final boolean has;
                try {
                    has = decoder.decode(inbuf);
                } catch (WsProtocolException wpe) {
                    sendCloseAndStartCloseWait(ioSession, wpe.closeCode, wpe.getMessage());
                    inbuf.clear();
                    return;
                } catch (RuntimeException rte) {
                    sendCloseAndStartCloseWait(ioSession, 1002, rte.getMessage());
                    inbuf.clear();
                    return;
                }
                if (!has) break;

                final int op = decoder.opcode();
                final boolean fin = decoder.fin();
                final boolean rsv1 = decoder.rsv1();
                final ByteBuffer payload = decoder.payload();

                if (closingSent && op != WsOpcode.CLOSE) {
                    if (LOG.isDebugEnabled()) LOG.debug("Ignoring frame opcode={} after close sent", op);
                    continue;
                }

                if (op == WsOpcode.PING) {
                    try {
                        listener.onPing(payload.asReadOnlyBuffer());
                    } catch (Throwable ignore) {
                    }
                    if (cfg.autoPong) enqueue(encoder.frame(WsOpcode.PONG, payload.asReadOnlyBuffer(), true, true));
                    continue;
                }
                if (op == WsOpcode.PONG) {
                    try {
                        listener.onPong(payload.asReadOnlyBuffer());
                    } catch (Throwable ignore) {
                    }
                    continue;
                }

                if (op == WsOpcode.CLOSE) {
                    final ByteBuffer payRO = payload.asReadOnlyBuffer();
                    final int len = payRO.remaining();
                    int code = 1005;
                    String reason = "";
                    if (len == 0) { /* valid */ } else {
                        if (len == 1) {
                            sendCloseAndStartCloseWait(ioSession, 1002, "Close frame length of 1 is invalid");
                            inbuf.clear();
                            return;
                        }
                        final int b1 = (payRO.get() & 0xFF), b2 = (payRO.get() & 0xFF);
                        code = (b1 << 8) | b2;
                        final ByteBuffer reasonBytes = payRO.slice();
                        final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT);
                        try {
                            dec.decode(reasonBytes.duplicate());
                            reason = StandardCharsets.UTF_8.decode(reasonBytes).toString();
                        } catch (CharacterCodingException e) {
                            sendCloseAndStartCloseWait(ioSession, 1007, "Invalid UTF-8 in close reason");
                            inbuf.clear();
                            return;
                        }
                        if (!WsCodec.isValidCloseCodeReceived(code)) {
                            sendCloseAndStartCloseWait(ioSession, 1002, "Invalid close code: " + code);
                            inbuf.clear();
                            return;
                        }
                    }
                    open.set(false);
                    try {
                        listener.onClose(code, reason);
                    } catch (Throwable ignore) {
                    }
                    if (!closingSent) {
                        enqueue(encoder.closeEcho(payload.asReadOnlyBuffer()));
                        closingSent = true;
                        session.setSocketTimeout(cfg.closeWaitTimeout);
                    }
                    ioSession.close(CloseMode.GRACEFUL);
                    inbuf.clear();
                    return;
                }

                // Data frames
                if (op == WsOpcode.CONT) {
                    if (assemblingOpcode == -1) {
                        sendCloseAndStartCloseWait(ioSession, 1002, "Unexpected CONT frame");
                        inbuf.clear();
                        return;
                    }
                    if (rsv1) {
                        sendCloseAndStartCloseWait(ioSession, 1002, "RSV1 on continuation");
                        inbuf.clear();
                        return;
                    }
                    appendToMessage(payload, ioSession);
                    if (fin) deliverAssembledMessage();
                    continue;
                }

                if (assemblingOpcode != -1) {
                    sendCloseAndStartCloseWait(ioSession, 1002, "New data frame while fragmented message in progress");
                    inbuf.clear();
                    return;
                }

                if (rsv1 && (pmce == null || !pmce.active)) {
                    sendCloseAndStartCloseWait(ioSession, 1002, "RSV1 without negotiated permessage-deflate");
                    inbuf.clear();
                    return;
                }

                if (!fin) {
                    startMessage(op, payload, rsv1, ioSession);
                    continue;
                }

                // Single-frame
                if (pmce != null && pmce.active && rsv1) {
                    final byte[] comp = toBytes(payload);
                    final byte[] plain;
                    try {
                        plain = pmce.decompressMessage(comp);
                    } catch (Exception e) {
                        sendCloseAndStartCloseWait(ioSession, 1007, "Decompress failed");
                        inbuf.clear();
                        return;
                    }
                    deliverSingle(op, ByteBuffer.wrap(plain));
                } else {
                    deliverSingle(op, payload.asReadOnlyBuffer());
                }
            }
            inbuf.compact();
        } catch (Exception ex) {
            exception(ioSession, ex);
        }
    }

    private void deliverSingle(final int op, final ByteBuffer payloadRO) {
        if (op == WsOpcode.TEXT) {
            final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                final CharBuffer cb = dec.decode(payloadRO);
                try {
                    listener.onText(cb, true);
                } catch (Throwable ignore) {
                }
            } catch (CharacterCodingException cce) {
                try {
                    listener.onError(cce);
                } catch (Throwable ignore) {
                }
            }
        } else if (op == WsOpcode.BINARY) {
            try {
                listener.onBinary(payloadRO, true);
            } catch (Throwable ignore) {
            }
        }
    }

    private void startMessage(final int opcode, final ByteBuffer payload, final boolean rsv1, final IOSession ioSession) {
        this.assemblingOpcode = opcode;
        this.assemblingCompressed = (pmce != null && pmce.active && rsv1);
        this.assemblingBytes = new ByteArrayOutputStream(Math.max(1024, payload.remaining()));
        this.assemblingSize = 0L;
        appendToMessage(payload, ioSession);
    }

    private void appendToMessage(final ByteBuffer payload, final IOSession ioSession) {
        final ByteBuffer dup = payload.asReadOnlyBuffer();
        final int n = dup.remaining();
        assemblingSize += n;
        if (assemblingSize > cfg.maxMessageSize) {
            sendCloseAndStartCloseWait(ioSession, 1009, "Message too big");
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
        if (compressed && pmce != null && pmce.active) {
            try {
                data = pmce.decompressMessage(body);
            } catch (Exception e) {
                try {
                    listener.onError(e);
                } catch (Throwable ignore) {
                }
                return;
            }
        }

        if (op == WsOpcode.TEXT) {
            final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                final CharBuffer cb = dec.decode(ByteBuffer.wrap(data));
                try {
                    listener.onText(cb, true);
                } catch (Throwable ignore) {
                }
            } catch (CharacterCodingException cce) {
                try {
                    listener.onError(cce);
                } catch (Throwable ignore) {
                }
            }
        } else if (op == WsOpcode.BINARY) {
            try {
                listener.onBinary(ByteBuffer.wrap(data).asReadOnlyBuffer(), true);
            } catch (Throwable ignore) {
            }
        }
    }

    private static byte[] toBytes(final ByteBuffer buf) {
        final ByteBuffer b = buf.asReadOnlyBuffer();
        final byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private void appendToInbuf(final ByteBuffer src) {
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

    @Override
    public void outputReady(final IOSession ioSession) {
        try {
            for (; ; ) {
                final ByteBuffer next = outbound.peek();
                if (next == null) {
                    ioSession.clearEvent(EventMask.WRITE);
                    break;
                }
                final int before = next.remaining();
                ioSession.write(next);
                final int after = next.remaining();
                if (LOG.isDebugEnabled()) LOG.debug("WS output wrote={} remaining={}", (before - after), after);
                if (!next.hasRemaining()) outbound.poll();
                else {
                    ioSession.setEvent(EventMask.WRITE);
                    break;
                }
            }
        } catch (Exception ex) {
            exception(ioSession, ex);
        }
    }

    @Override
    public void timeout(final IOSession ioSession, final Timeout timeout) {
        try {
            listener.onError(new java.util.concurrent.TimeoutException());
        } catch (Throwable ignore) {
        }
        open.set(false);
        ioSession.close(CloseMode.GRACEFUL);
    }

    @Override
    public void exception(final IOSession ioSession, final Exception cause) {
        if (LOG.isDebugEnabled()) LOG.debug("WS handler exception", cause);
        try {
            listener.onError(cause);
        } catch (Throwable ignore) {
        }
        open.set(false);
        ioSession.close(CloseMode.GRACEFUL);
    }

    @Override
    public void disconnected(final IOSession ioSession) {
        if (open.getAndSet(false)) {
            try {
                listener.onClose(1006, "abnormal closure");
            } catch (Throwable ignore) {
            }
        }
    }

    private void enqueue(final ByteBuffer frame) {
        outbound.add(frame);
        session.setEvent(EventMask.WRITE);
    }

    private void sendCloseAndStartCloseWait(final IOSession ioSession, final int code, final String reason) {
        if (!closingSent) {
            try {
                enqueue(encoder.close(code, reason == null ? "" : reason));
            } catch (Throwable ignore) {
            }
            closingSent = true;
            session.setSocketTimeout(cfg.closeWaitTimeout);
        }
        ioSession.close(CloseMode.GRACEFUL);
    }

    // ------------------------------------------------------------
    // Outgoing facade (now supports threshold + true streaming PMCE)
    // ------------------------------------------------------------
    private final class WsFacade implements WebSocket {
        @Override
        public boolean sendText(final CharSequence data, final boolean finalFragment) {
            if (!open.get() || closingSent) return false;
            final ByteBuffer plain = StandardCharsets.UTF_8.encode(data.toString());
            return sendData(WsOpcode.TEXT, plain, finalFragment);
        }

        @Override
        public boolean sendBinary(final ByteBuffer data, final boolean finalFragment) {
            if (!open.get() || closingSent) return false;
            return sendData(WsOpcode.BINARY, data.asReadOnlyBuffer(), finalFragment);
        }

        private boolean sendData(final int opcode, final ByteBuffer data, final boolean finalFragment) {
            final boolean pmceOn = (pmce != null && pmce.active);
            // If no message is in progress, we’re at the first fragment
            final boolean first = (outOpcode == -1);

            if (pmceOn && first && finalFragment) {
                // Single-frame: apply threshold
                if (data.remaining() < cfg.minCompressBytes) {
                    enqueue(encoder.frame(opcode, data, true, true));
                    return true;
                }
                final byte[] comp = pmce.compressMessage(toBytes(data));
                enqueue(encoder.frameCompressedFirst(opcode, ByteBuffer.wrap(comp), true, true)); // RSV1=1
                return true;
            }

            if (pmceOn && cfg.streamCompressedFragments) {
                if (first) {
                    // Start a compressed fragmented message
                    outOpcode = opcode;
                    outCompressed = true;
                    outFirst = true;
                } else if (outOpcode != opcode && opcode != WsOpcode.CONT) {
                    // Caller tried to change opcode mid-message
                    return false;
                }

                final byte[] comp = pmce.compressFragment(toBytes(data), finalFragment);
                final ByteBuffer payload = ByteBuffer.wrap(comp);

                if (outFirst) {
                    // First fragment -> opcode=TEXT/BINARY, RSV1=1
                    enqueue(encoder.frameCompressedFirst(outOpcode, payload, finalFragment, true));
                    outFirst = false;
                } else {
                    // Continuations use opcode=CONT, RSV1=0
                    enqueue(encoder.frame(WsOpcode.CONT, payload, finalFragment, true));
                }

                if (finalFragment) {
                    // Message done
                    outOpcode = -1;
                    outCompressed = false;
                    outFirst = false;
                }
                return true;
            }

            // No PMCE (or streaming disabled): pass through user fragments as-is
            if (first) {
                if (finalFragment) {
                    enqueue(encoder.frame(opcode, data, true, true));
                } else {
                    outOpcode = opcode;
                    enqueue(encoder.frame(opcode, data, false, true));
                }
            } else {
                // Continuations
                enqueue(encoder.frame(WsOpcode.CONT, data, finalFragment, true));
                if (finalFragment) outOpcode = -1;
            }
            return true;
        }

        @Override
        public boolean ping(final ByteBuffer data) {
            if (!open.get() || closingSent) return false;
            if (data != null && data.remaining() > 125) return false;
            enqueue(encoder.frame(WsOpcode.PING, data == null ? ByteBuffer.allocate(0) : data, true, true));
            return true;
        }

        @Override
        public CompletableFuture<Void> close(final int statusCode, final String reason) {
            final CompletableFuture<Void> f = new CompletableFuture<>();
            if (!open.getAndSet(false)) {
                f.complete(null);
                return f;
            }
            if (!closingSent) {
                try {
                    enqueue(encoder.close(statusCode, reason));
                } catch (Throwable ignore) {
                }
                closingSent = true;
                session.setSocketTimeout(cfg.closeWaitTimeout);
            }
            f.complete(null);
            return f;
        }

        @Override
        public boolean isOpen() {
            return open.get() && !closingSent;
        }
    }
}
