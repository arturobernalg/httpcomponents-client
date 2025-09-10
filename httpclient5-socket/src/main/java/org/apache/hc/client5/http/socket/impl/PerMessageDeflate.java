package org.apache.hc.client5.http.socket.impl;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PerMessageDeflate {
    private static final Logger LOG = LoggerFactory.getLogger(PerMessageDeflate.class);
    private static final byte[] TAIL = new byte[]{0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

    final boolean active;
    final boolean serverNoContextTakeover;
    final boolean clientNoContextTakeover;
    final Integer clientMaxWindowBits;
    final Integer serverMaxWindowBits;

    private final Inflater inflater; // raw DEFLATE (nowrap=true)
    private final Deflater deflater; // raw DEFLATE (nowrap=true)

    public PerMessageDeflate(boolean active,
                      boolean serverNoContextTakeover,
                      boolean clientNoContextTakeover,
                      Integer clientMaxWindowBits,
                      Integer serverMaxWindowBits) {
        this.active = active;
        this.serverNoContextTakeover = serverNoContextTakeover;
        this.clientNoContextTakeover = clientNoContextTakeover;
        this.clientMaxWindowBits = clientMaxWindowBits;
        this.serverMaxWindowBits = serverMaxWindowBits;
        this.inflater = new Inflater(true);
        this.deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    }

    byte[] decompressMessage(byte[] compressed) throws Exception {
        if (!active) return compressed;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, compressed.length * 2));
        inflater.setInput(concat(compressed, TAIL));
        final byte[] buf = new byte[8192];
        while (true) {
            int n = inflater.inflate(buf);
            if (n > 0) out.write(buf, 0, n);
            else if (inflater.needsInput() || inflater.finished()) break;
            else if (n == 0) break;
        }
        if (serverNoContextTakeover) inflater.reset();
        return out.toByteArray();
    }

    /**
     * Compress a whole message in one shot.
     */
    byte[] compressMessage(byte[] plain) {
        if (!active) return plain;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, plain.length / 2));
        deflater.setInput(plain);
        final byte[] buf = new byte[8192];
        while (!deflater.needsInput()) {
            int n = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            if (n > 0) out.write(buf, 0, n);
            else break;
        }
        if (clientNoContextTakeover) deflater.reset();
        final byte[] full = out.toByteArray();
        return stripTail(full);
    }

    /**
     * Compress a message fragment (keeps context; strips SYNC_FLUSH tail).
     */
    byte[] compressFragment(byte[] chunk, boolean finalFragment) {
        if (!active) return chunk;
        deflater.setInput(chunk);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, chunk.length));
        final byte[] buf = new byte[8192];
        // SYNC_FLUSH after this fragment
        while (!deflater.needsInput()) {
            int n = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            if (n > 0) out.write(buf, 0, n);
            else break;
        }
        if (finalFragment && clientNoContextTakeover) deflater.reset();
        final byte[] full = out.toByteArray();
        return stripTail(full);
    }

    private static byte[] stripTail(byte[] full) {
        if (full.length >= 4 &&
                full[full.length - 4] == 0x00 &&
                full[full.length - 3] == 0x00 &&
                (full[full.length - 2] & 0xFF) == 0xFF &&
                (full[full.length - 1] & 0xFF) == 0xFF) {
            final byte[] trimmed = new byte[full.length - 4];
            System.arraycopy(full, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return full;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
