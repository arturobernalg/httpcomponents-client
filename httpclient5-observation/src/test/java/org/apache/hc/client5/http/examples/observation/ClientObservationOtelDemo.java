package org.apache.hc.client5.http.examples.observation;

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
import org.apache.hc.client5.http.observation.impl.HttpClientObservationSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.message.StatusLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ClientObservationOtelSmokeTest {

    private static final URI URL = URI.create("https://httpbin.org/get");

    @Test
    void interceptorExportsOneSpan() throws Exception {

        /* -------- OTEL SDK with in-memory exporter -------- */
        InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .setResource(Resource.empty())
                .build();

        OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.noop())
                .build();

        /* -------- Build Micrometer OtelTracer -------- */
        OtelCurrentTraceContext currentCtx = new OtelCurrentTraceContext();
        OtelBaggageManager baggage = new OtelBaggageManager(currentCtx, new ArrayList<>(), new ArrayList<>());
        OtelTracer micrometerTracer = new OtelTracer(
                otel.getTracer("demo"),
                currentCtx,
                event -> { /* NO-OP event publisher */ },
                baggage);

        /* -------- Observation registry wired to tracer -------- */
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(micrometerTracer));

        /* -------- Classic client with observation enabled ------ */
        HttpClientBuilder builder = HttpClients.custom();
        HttpClientObservationSupport.enable(builder, registry);

        try (CloseableHttpClient client = builder.build()) {
            ClassicHttpResponse res =
                    client.executeOpen(null, new HttpGet(URL), null);
            System.out.println("[classic] " + new StatusLine(res));
            res.close();
        }

        /* -------- Assert one exported span --------------------- */
        List<?> finished = spanExporter.getFinishedSpanItems();
        Assertions.assertEquals(1, finished.size(), "exactly one span expected");
        System.out.println("Exported span = " + finished.get(0).toString());
    }
}
