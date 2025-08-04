package org.apache.hc.client5.http.observation.impl;

import java.io.IOException;
import java.net.URISyntaxException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;

final class ObservationClassicExecInterceptor implements ExecChainHandler {

    private final ObservationRegistry registry;

    ObservationClassicExecInterceptor(final ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ClassicHttpResponse execute(final ClassicHttpRequest request,
                                       final ExecChain.Scope scope,
                                       final ExecChain chain)
            throws IOException, HttpException {

        final Observation obs;
        try {
            obs = Observation
                    .createNotStarted("http.client.request", registry)
                    .contextualName(request.getMethod() + " " + request.getUri())
                    .lowCardinalityKeyValue("http.method", request.getMethod())
                    .lowCardinalityKeyValue("net.peer.name", request.getAuthority().getHostName())
                    .start();
        } catch (final URISyntaxException e) {
            throw new RuntimeException(e);
        }

        ClassicHttpResponse response = null;
        Throwable error = null;
        try {
            response = chain.proceed(request, scope);
            return response;
        } catch (final Throwable t) {
            error = t;
            throw t;
        } finally {
            if (response != null) {
                obs.lowCardinalityKeyValue("http.status_code",
                        Integer.toString(response.getCode()));
            }
            if (error != null) {
                obs.error(error);
            }
            obs.stop();
        }
    }
}