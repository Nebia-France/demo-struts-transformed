package fr.demo.souscription.gateway.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.quarkus.arc.Unremovable;
import io.quarkus.opentelemetry.runtime.exporter.otlp.tracing.LateBoundSpanProcessor;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Error-always rule of the gateway sampling policy (W6-T04).
 *
 * <p>A head sampler decides before the response exists, so with a uniform 10 % ratio nine failing
 * requests out of ten would leave no trace. {@link ErrorAwareSampler} records the spans it does not
 * sample instead of dropping them; this processor keeps those recorded spans per trace, in memory,
 * until the gateway's local root span (the HTTP server span) ends, then:
 * <ul>
 *   <li>exports the whole local trace (server span, journey span, client spans) when the request
 *       failed: the local root ended with status {@code ERROR} or answered {@code 5xx};</li>
 *   <li>otherwise discards it.</li>
 * </ul>
 * Sampled spans are left to the regular export path and never touched here.
 *
 * <p>The buffer is bounded ({@link #MAX_PENDING_TRACES} traces in flight, {@link #MAX_SPANS_PER_TRACE}
 * spans each): beyond it, new unsampled traces are not retained, so the rule can never grow the
 * heap without limit under load. Retained spans are handed to the same exporters as sampled ones:
 * the OTLP processor of {@code quarkus-opentelemetry} and any CDI {@link SpanExporter} bean.
 */
@Singleton
@Unremovable
public class ErrorTraceRetainer implements SpanProcessor {

    static final int MAX_PENDING_TRACES = 4096;

    static final int MAX_SPANS_PER_TRACE = 128;

    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");

    private final ConcurrentMap<String, PendingTrace> pending = new ConcurrentHashMap<>();

    private final Instance<SpanExporter> exporters;

    private final Instance<SpanProcessor> processors;

    private volatile List<SpanProcessor> otlp;

    @Inject
    public ErrorTraceRetainer(@Any Instance<SpanExporter> exporters, @Any Instance<SpanProcessor> processors) {
        this.exporters = exporters;
        this.processors = processors;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        SpanContext context = span.getSpanContext();
        if (context.isSampled()) {
            return;
        }
        if (isLocalRoot(span)) {
            if (pending.size() >= MAX_PENDING_TRACES && !pending.containsKey(context.getTraceId())) {
                return;
            }
            pending.computeIfAbsent(context.getTraceId(), id -> new PendingTrace()).openRoot();
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanContext context = span.getSpanContext();
        if (context.isSampled()) {
            return;
        }
        PendingTrace trace = pending.get(context.getTraceId());
        if (trace == null) {
            return;
        }
        boolean root = isLocalRoot(span);
        List<ReadableSpan> completed = trace.add(span, root, root && isErrorResponse(span));
        if (completed != null) {
            pending.remove(context.getTraceId(), trace);
            if (!completed.isEmpty()) {
                export(completed);
            }
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        pending.clear();
        return CompletableResultCode.ofSuccess();
    }

    private static boolean isLocalRoot(ReadableSpan span) {
        SpanContext parent = span.getParentSpanContext();
        return !parent.isValid() || parent.isRemote();
    }

    /**
     * Whether a local root reports a failed request: status {@code ERROR}, or a {@code 5xx} server
     * response. Client errors ({@code 4xx}, including the edge rejections) follow the uniform ratio,
     * so a flood of rejected requests cannot force every trace out.
     */
    private static boolean isErrorResponse(ReadableSpan span) {
        if (span.toSpanData().getStatus().getStatusCode() == StatusCode.ERROR) {
            return true;
        }
        Long status = span.getAttribute(HTTP_STATUS);
        return span.getKind() == SpanKind.SERVER && status != null && status >= 500;
    }

    private void export(List<ReadableSpan> spans) {
        List<ReadableSpan> views = spans.stream().map(SampledView::of).toList();
        List<SpanData> data = views.stream().map(ReadableSpan::toSpanData).toList();
        for (SpanExporter exporter : exporters) {
            exporter.export(data);
        }
        for (SpanProcessor processor : otlpProcessors()) {
            views.forEach(processor::onEnd);
        }
    }

    /**
     * The OTLP export path of {@code quarkus-opentelemetry}: its batch processor only accepts spans
     * whose context is sampled, hence the {@link SampledView}. Absent (or without delegate) when a
     * CDI {@link SpanExporter} replaces OTLP, as in the tests.
     */
    private List<SpanProcessor> otlpProcessors() {
        List<SpanProcessor> resolved = otlp;
        if (resolved == null) {
            List<SpanProcessor> found = new ArrayList<>();
            for (SpanProcessor processor : processors) {
                if (processor instanceof LateBoundSpanProcessor lateBound && !lateBound.isDelegateNull()) {
                    found.add(lateBound);
                }
            }
            resolved = List.copyOf(found);
            otlp = resolved;
        }
        return resolved;
    }

    /**
     * Spans of one unsampled trace, held until its last local root ends. Lock-free: the spans of
     * one request end on its event loop, and no monitor may be held there (docs/execution-model.md).
     */
    private static final class PendingTrace {

        private final ConcurrentLinkedQueue<ReadableSpan> spans = new ConcurrentLinkedQueue<>();

        private final AtomicInteger size = new AtomicInteger();

        private final AtomicInteger openRoots = new AtomicInteger();

        private final AtomicBoolean error = new AtomicBoolean();

        void openRoot() {
            openRoots.incrementAndGet();
        }

        /**
         * Adds an ended span; returns {@code null} while a local root is still open, otherwise the
         * spans to export (empty when the trace carries no error).
         */
        List<ReadableSpan> add(ReadableSpan span, boolean root, boolean failed) {
            if (size.incrementAndGet() <= MAX_SPANS_PER_TRACE) {
                spans.add(span);
            }
            if (failed) {
                error.set(true);
            }
            if (root && openRoots.decrementAndGet() <= 0) {
                return error.get() ? List.copyOf(spans) : List.of();
            }
            return null;
        }
    }

    /** Read-only view of a retained span whose context carries the sampled flag. */
    private record SampledView(ReadableSpan span, SpanContext context) implements ReadableSpan {

        static ReadableSpan of(ReadableSpan span) {
            SpanContext original = span.getSpanContext();
            return new SampledView(span, SpanContext.create(original.getTraceId(),
                    original.getSpanId(), TraceFlags.getSampled(), original.getTraceState()));
        }

        @Override
        public SpanContext getSpanContext() {
            return context;
        }

        @Override
        public SpanContext getParentSpanContext() {
            return span.getParentSpanContext();
        }

        @Override
        public String getName() {
            return span.getName();
        }

        @Override
        public SpanData toSpanData() {
            SpanContext sampled = context;
            return new DelegatingSpanData(span.toSpanData()) {
                @Override
                public SpanContext getSpanContext() {
                    return sampled;
                }
            };
        }

        @Override
        @SuppressWarnings("deprecation")
        public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
            return span.getInstrumentationLibraryInfo();
        }

        @Override
        public InstrumentationScopeInfo getInstrumentationScopeInfo() {
            return span.getInstrumentationScopeInfo();
        }

        @Override
        public boolean hasEnded() {
            return span.hasEnded();
        }

        @Override
        public long getLatencyNanos() {
            return span.getLatencyNanos();
        }

        @Override
        public SpanKind getKind() {
            return span.getKind();
        }

        @Override
        public <T> T getAttribute(AttributeKey<T> key) {
            return span.getAttribute(key);
        }

        @Override
        public Attributes getAttributes() {
            return span.getAttributes();
        }
    }
}
