package fr.demo.souscription.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.gateway.edge.EdgeFilterOrder;
import fr.demo.souscription.gateway.observability.RejectionMetrics;
import io.quarkus.vertx.web.RouteFilter;
import fr.demo.souscription.gateway.observability.RequestCorrelation;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Normalises the body-size rejection, the one failure the HTTP layer answers before the Vert.x
 * router can fail the context, so {@code RouterProblemHandler} never sees it.
 *
 * <p>An oversized body would otherwise answer a bare 413 with no media type, breaking the
 * problem+json contract for every response with a status greater than or equal to 400 (NFG-018).
 * The declared {@code Content-Length} is compared to {@code quarkus.http.limits.max-body-size};
 * the status itself still comes from {@link ProblemFactory}, the single decision point.
 *
 * <p>Ordered after {@code SECURITY_HEADERS} on the request path (a lower {@code @RouteFilter} value
 * runs later in Vert.x) so the headers-end handler of {@code SecurityHeaderFilter} is already
 * installed when this filter short-circuits.
 */
@ApplicationScoped
public class BodySizeProblemFilter {

    @Inject
    ProblemResponses problems;

    @Inject
    ObjectMapper json;

    @Inject
    RejectionMetrics rejections;

    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    String maxBodySize;

    @RouteFilter(EdgeFilterOrder.PAYLOAD_LIMIT)
    void rejectOversizedBody(RoutingContext rc) {
        String contentLength = rc.request().getHeader("Content-Length");
        long declared = parse(contentLength);
        if (declared > limitInBytes()) {
            rejections.rejected(413, RejectionMetrics.SIZE, rc.normalizedPath());
            Problem problem = ProblemFactory.ofStatus(413, requestId(rc));
            rc.response().setStatusCode(413)
                    .putHeader("Content-Type", ProblemResponses.PROBLEM_JSON);
            try {
                rc.response().end(json.writeValueAsString(problem));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                rc.response().end();
            }
            return;
        }
        rc.next();
    }

    private String requestId(RoutingContext rc) {
        String assigned = RequestCorrelation.requestId(rc);
        return assigned == null ? problems.requestId() : assigned;
    }

    private long parse(String contentLength) {
        if (contentLength == null || contentLength.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(contentLength.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses the configured limit, which accepts the {@code K}/{@code M} suffixes of Quarkus. */
    private long limitInBytes() {
        String value = maxBodySize.trim().toUpperCase(java.util.Locale.ROOT);
        long multiplier = 1L;
        if (value.endsWith("K")) {
            multiplier = 1024L;
        } else if (value.endsWith("M")) {
            multiplier = 1024L * 1024L;
        }
        String digits = multiplier == 1L ? value : value.substring(0, value.length() - 1);
        return Long.parseLong(digits.trim()) * multiplier;
    }
}
