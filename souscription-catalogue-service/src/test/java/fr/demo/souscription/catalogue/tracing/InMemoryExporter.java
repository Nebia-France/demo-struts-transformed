package fr.demo.souscription.catalogue.tracing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only span sink (W5-T03). Quarkus registers every CDI {@link SpanExporter} bean on the
 * tracer provider, so the spans produced by a request can be asserted without a collector.
 */
@ApplicationScoped
@Unremovable
public class InMemoryExporter implements SpanExporter {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> finishedSpans() {
        return List.copyOf(spans);
    }

    public void reset() {
        spans.clear();
    }
}
