package fr.demo.souscription.simulation.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import jakarta.inject.Singleton;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Error-biased sampler (W5-T03). A CDI {@code Sampler} bean replaces the sampler built from
 * {@code quarkus.otel.traces.sampler}, so the parent-based ratio semantics are rebuilt here.
 *
 * <p>Successful traces follow the configurable ratio (OTEL_TRACES_SAMPLE_RATIO). What the delegate
 * would drop is downgraded to {@link SamplingResult#recordOnly()} instead of being dropped: the span
 * is still recorded, so {@link ErrorBiasedSpanProcessor} sees it at {@code onEnd} and exports it when
 * it ended in error. The sampling decision is taken before the outcome is known, hence the two-stage
 * design: ratio at start, error bias at end.
 *
 * <p>An incoming sampled {@code traceparent} is honoured by the parent-based delegate: the caller's
 * decision wins and no new trace is started.
 */
@Singleton
public class ErrorBiasedSampler implements Sampler {

    private final Sampler delegate;
    private final double ratio;

    public ErrorBiasedSampler(
            @ConfigProperty(name = "quarkus.otel.traces.sampler.arg", defaultValue = "0.1") double ratio) {
        this.ratio = ratio;
        this.delegate = Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                       SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
        SamplingResult result = delegate.shouldSample(parentContext, traceId, name, spanKind,
                attributes, parentLinks);
        if (result.getDecision() == SamplingDecision.DROP) {
            // Recorded but not sampled: kept in memory only, and exported by
            // ErrorBiasedSpanProcessor if, and only if, the span ends in error.
            return SamplingResult.recordOnly();
        }
        return result;
    }

    @Override
    public String getDescription() {
        return "ErrorBiasedSampler{parentbased_traceidratio:" + ratio + ",errors=always}";
    }
}
