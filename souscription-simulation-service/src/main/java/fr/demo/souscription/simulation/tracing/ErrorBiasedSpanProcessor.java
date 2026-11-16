package fr.demo.souscription.simulation.tracing;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Exports the spans the ratio sampler declined, when they ended in error (W5-T03).
 *
 * <p>{@link ErrorBiasedSampler} downgrades a dropped decision to record-only, so this processor is
 * still called at {@code onEnd}. Spans the SDK already sampled are left alone — the regular pipeline
 * exports them, and re-exporting would duplicate them. A record-only span whose status is
 * {@code ERROR} is pushed to every configured exporter, which is what makes failed requests traced
 * at 100% whatever the success ratio.
 */
@Singleton
public class ErrorBiasedSpanProcessor implements SpanProcessor {

    private final Instance<SpanExporter> exporters;

    @Inject
    public ErrorBiasedSpanProcessor(@Any Instance<SpanExporter> exporters) {
        this.exporters = exporters;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (span.getSpanContext().isSampled()) {
            return;
        }
        SpanData data = span.toSpanData();
        if (data.getStatus().getStatusCode() != StatusCode.ERROR) {
            return;
        }
        List<SpanData> batch = List.of(data);
        for (SpanExporter exporter : exporters) {
            exporter.export(batch);
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }
}
