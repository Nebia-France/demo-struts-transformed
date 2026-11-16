package fr.demo.souscription.gateway.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Head sampler of the gateway (W6-T04): {@code parentbased_traceidratio} that never discards a
 * request outright.
 *
 * <p>Quarkus uses the first CDI {@link Sampler} bean instead of the one named by
 * {@code quarkus.otel.traces.sampler}, so this bean keeps the configured semantics and reads the
 * configured ratio:
 * <ul>
 *   <li>a valid parent (the SPA's {@code traceparent} or a local parent span) is followed: sampled
 *       parent, sampled span; the trace id is always the parent's, never a new one;</li>
 *   <li>without a parent the gateway creates the root, sampled at the uniform ratio.</li>
 * </ul>
 * Where the plain parent-based sampler would answer {@code DROP}, this one answers
 * {@code RECORD_ONLY}: the spans are recorded (not exported, and the propagated
 * {@code traceparent} carries the unsampled flag) so that {@link ErrorTraceRetainer} can still
 * export the trace if the request fails. A low ratio therefore never loses a failing request.
 */
@Singleton
@Unremovable
public class ErrorAwareSampler implements Sampler {

    private final double ratio;

    private final Sampler uniform;

    @Inject
    public ErrorAwareSampler(
            @ConfigProperty(name = "quarkus.otel.traces.sampler.arg", defaultValue = "0.1") double ratio) {
        this.ratio = ratio;
        this.uniform = Sampler.traceIdRatioBased(ratio);
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                       SpanKind spanKind, Attributes attributes,
                                       List<LinkData> parentLinks) {
        SpanContext parent = Span.fromContext(parentContext).getSpanContext();
        if (parent.isValid()) {
            return parent.isSampled() ? SamplingResult.recordAndSample() : SamplingResult.recordOnly();
        }
        SamplingResult decision = uniform.shouldSample(parentContext, traceId, name, spanKind,
                attributes, parentLinks);
        return decision.getDecision() == SamplingDecision.RECORD_AND_SAMPLE
                ? decision : SamplingResult.recordOnly();
    }

    @Override
    public String getDescription() {
        return "ErrorAwareSampler{parentbased_traceidratio=" + ratio + ", unsampled=record-only}";
    }
}
