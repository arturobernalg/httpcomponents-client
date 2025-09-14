package org.apache.hc.client5.http.websocket.perf;

import java.util.Arrays;

/**
 * Tiny latency recorder (nanoseconds in, µs out), Java 8 compatible.
 */
public final class LatencyHistogram {
    private final long[] buf;
    private int count;

    public LatencyHistogram(final int capacity) {
        this.buf = new long[Math.max(1, capacity)];
        this.count = 0;
    }

    public synchronized void record(final long nanos) {
        if (count < buf.length) {
            buf[count++] = nanos;
        }
    }

    public synchronized int count() {
        return count;
    }

    public synchronized double avgMicros() {
        if (count == 0) return 0.0;
        long sum = 0L;
        for (int i = 0; i < count; i++) sum += buf[i];
        return (sum / 1000.0) / count;
    }

    public synchronized double percentileMicros(final int p) {
        if (count == 0) return 0.0;
        final long[] copy = Arrays.copyOf(buf, count);
        Arrays.sort(copy);
        final int idx = Math.min(copy.length - 1, Math.max(0, (int) Math.round((p / 100.0) * (copy.length - 1))));
        return copy[idx] / 1000.0;
    }
}
