package org.apache.hc.client5.http.observation.impl;

import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.cache.CachingHttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.cache.CachingHttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;

public final class HttpClientObservationSupport {

    private static final String NAME = "micrometer-observation";


    public static void enable(final HttpClientBuilder b, final ObservationRegistry reg) {
        if (reg == null) {
            return;
        }
        b.addExecInterceptorFirst(NAME, new ObservationClassicExecInterceptor(reg));
    }

    public static void enable(final CachingHttpClientBuilder b, final ObservationRegistry reg) {
        if (reg == null) {
            return;
        }
        // after caching, before PROTOCOL-level handlers
        b.addExecInterceptorAfter(ChainElement.CACHING.name(), NAME,
                new ObservationClassicExecInterceptor(reg));
    }

    /* ---------- async builders ---------- */

    public static void enable(final HttpAsyncClientBuilder b, final ObservationRegistry reg) {
        if (reg == null) {
            return;
        }
        b.addExecInterceptorFirst(NAME, new ObservationAsyncExecInterceptor(reg));
    }

    public static void enable(final CachingHttpAsyncClientBuilder b, final ObservationRegistry reg) {
        if (reg == null) {
            return;
        }
        b.addExecInterceptorAfter(ChainElement.CACHING.name(),
                NAME, new ObservationAsyncExecInterceptor(reg));
    }

    private HttpClientObservationSupport() {
    }
}
