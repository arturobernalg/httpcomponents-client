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
package org.apache.hc.client5.http.example;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.observation.HttpClientObservationSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.message.StatusLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ClientObservationOtelDemo {

    private static final URI URL = URI.create("https://httpbin.org/get");

    @Test
    void interceptorExportsOneSpan() throws Exception {

        /* -------- OTEL SDK with in-memory exporter -------- */
        final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        final SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .setResource(Resource.empty())
                .build();

        final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.noop())
                .build();

        /* -------- Build Micrometer OtelTracer -------- */
        final OtelCurrentTraceContext currentCtx = new OtelCurrentTraceContext();
        final OtelBaggageManager baggage = new OtelBaggageManager(currentCtx, new ArrayList<>(), new ArrayList<>());
        final OtelTracer micrometerTracer = new OtelTracer(
                otel.getTracer("demo"),
                currentCtx,
                event -> { /* NO-OP event publisher */ },
                baggage);

        /* -------- Observation registry wired to tracer -------- */
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(micrometerTracer));

        /* -------- Classic client with observation enabled ------ */
        final HttpClientBuilder builder = HttpClients.custom();
        HttpClientObservationSupport.enable(builder, registry);

        try (final CloseableHttpClient client = builder.build()) {
            final ClassicHttpResponse res =
                    client.executeOpen(null, new HttpGet(URL), null);
            System.out.println("[classic] " + new StatusLine(res));
            res.close();
        }

        /* -------- Assert one exported span --------------------- */
        final List<?> finished = spanExporter.getFinishedSpanItems();
        Assertions.assertEquals(1, finished.size(), "exactly one span expected");
        System.out.println("Exported span = " + finished.get(0).toString());
    }
}
