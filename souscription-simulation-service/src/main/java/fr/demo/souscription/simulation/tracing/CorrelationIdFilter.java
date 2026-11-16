package fr.demo.souscription.simulation.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

/**
 * Propagates the caller's correlation identifier (W5-T04).
 *
 * <p>The W3C {@code traceparent} is consumed by the OpenTelemetry propagator before this filter runs,
 * so the server span already inherits the incoming trace id; nothing is re-created here.
 * {@code X-Correlation-Id} has no OpenTelemetry semantics: it is copied, as received, into the span
 * attributes and the MDC so the JSON log lines of this request carry it next to {@code traceId} and
 * {@code spanId}. When the header is absent the trace id is used as the correlation value — a new
 * identifier is never minted while the header is present.
 *
 * <p>The value is bounded and stripped of control characters: it reaches the logs, and an unbounded
 * or newline-carrying header would allow log injection.
 */
@Provider
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";
    static final String SPAN_ATTRIBUTE = "correlation.id";
    private static final int MAX_LENGTH = 64;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        Span span = Span.current();
        String received = sanitize(requestContext.getHeaderString(CORRELATION_ID_HEADER));
        String correlationId = received != null
                ? received
                : span.getSpanContext().getTraceId();
        span.setAttribute(SPAN_ATTRIBUTE, correlationId);
        MDC.put(MDC_KEY, correlationId);
        if (received != null) {
            requestContext.setProperty(CORRELATION_ID_HEADER, received);
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object echoed = requestContext.getProperty(CORRELATION_ID_HEADER);
        if (echoed != null) {
            responseContext.getHeaders().putSingle(CORRELATION_ID_HEADER, echoed);
        }
        MDC.remove(MDC_KEY);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._:-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
