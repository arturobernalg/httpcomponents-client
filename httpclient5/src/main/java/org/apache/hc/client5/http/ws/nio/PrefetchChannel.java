/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.nio;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.hc.core5.ws.io.DuplexChannel;

/**
 * Serves a pre-read prefix before delegating to the real channel.
 * Useful for delivering bytes already read past HTTP headers to {@link org.apache.hc.core5.ws.engine.WsEngine}.
 */
public final class PrefetchChannel implements DuplexChannel {
    private final ByteBuffer pre;
    private final DuplexChannel delegate;

    public PrefetchChannel(final ByteBuffer pre, final DuplexChannel delegate) {
        this.pre = pre;
        this.delegate = delegate;
    }

    @Override
    public int read(final ByteBuffer dst) throws IOException {
        if (pre.hasRemaining()) {
            final int n = Math.min(dst.remaining(), pre.remaining());
            final int lim = pre.limit();
            pre.limit(pre.position() + n);
            dst.put(pre);
            pre.limit(lim);
            return n;
        }
        return delegate.read(dst);
    }

    @Override
    public int write(final ByteBuffer src) throws IOException {
        return delegate.write(src);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }
}
