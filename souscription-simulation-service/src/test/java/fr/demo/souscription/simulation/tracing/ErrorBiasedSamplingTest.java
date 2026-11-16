package fr.demo.souscription.simulation.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W5-T03: with the success ratio at 0, a successful span must not be exported while an errored span
 * must be, so that 100% of failures stay traceable whatever the sampling ratio.
 *
 * <p>Driven directly on the SDK: the two collaborators are wired exactly as the container wires them
 * (sampler + extra span processor), without depending on a 500 being reachable over HTTP.
 */
class ErrorBiasedSamplingTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    private SdkTracerProvider providerWithRatio(double ratio) {
        return SdkTracerProvider.builder()
                .setSampler(new ErrorBiasedSampler(ratio))
                // The regular pipeline, which only exports sampled spans.
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                // The error bias, which exports the record-only spans that ended in error.
                .addSpanProcessor(new ErrorBiasedSpanProcessor(new SingleInstance<>(exporter)))
                .build();
    }

    @Test
    void successfulSpanIsDroppedAtRatioZero() {
        try (SdkTracerProvider provider = providerWithRatio(0d)) {
            Tracer tracer = provider.get("test");
            tracer.spanBuilder("ok").startSpan().end();
            assertEquals(List.of(), exporter.getFinishedSpanItems(),
                    "at ratio 0 a successful span must not be exported");
        }
    }

    @Test
    void erroredSpanIsAlwaysExported() {
        try (SdkTracerProvider provider = providerWithRatio(0d)) {
            Tracer tracer = provider.get("test");
            Span span = tracer.spanBuilder("boom").startSpan();
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            List<SpanData> exported = exporter.getFinishedSpanItems();
            assertEquals(1, exported.size(), "100% of error spans must be exported, got: " + exported);
            assertEquals(StatusCode.ERROR, exported.get(0).getStatus().getStatusCode());
        }
    }

    @Test
    void sampledSpanIsExportedExactlyOnce() {
        try (SdkTracerProvider provider = providerWithRatio(1d)) {
            Tracer tracer = provider.get("test");
            Span span = tracer.spanBuilder("boom").startSpan();
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();
            assertEquals(1, exporter.getFinishedSpanItems().size(),
                    "a sampled error span must not be exported twice");
        }
    }

    @Test
    void samplerNeverDropsSoTheOutcomeCanStillBeSeen() {
        SamplingResult result = new ErrorBiasedSampler(0d).shouldSample(
                io.opentelemetry.context.Context.root(),
                "4bf92f3577b34da6a3ce929d0e0e4736", "span",
                io.opentelemetry.api.trace.SpanKind.SERVER,
                io.opentelemetry.api.common.Attributes.empty(), List.of());
        assertNotEquals(SamplingDecision.DROP, result.getDecision(),
                "a dropped span never reaches onEnd, so errors could not be recovered");
        assertTrue(new ErrorBiasedSampler(0.25d).getDescription().contains("0.25"));
    }

    /** Minimal {@code Instance} stand-in: only iteration is used by the processor. */
    private static final class SingleInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final T value;

        private SingleInstance(T value) {
            this.value = value;
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return List.of(value).iterator();
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public jakarta.enterprise.inject.Instance<T> select(java.lang.annotation.Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(
                Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(
                jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
        }

        @Override
        public jakarta.enterprise.inject.Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }
    }
}
