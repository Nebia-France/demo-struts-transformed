package fr.demo.souscription.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.ext.web.Router;
import fr.demo.souscription.gateway.observability.RequestCorrelation;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Normalises the failures that never reach JAX-RS, so no response with a status greater than or
 * equal to 400 escapes the problem+json contract (NFG-018).
 *
 * <p>A request to a path outside {@code /api/v1} is rejected by the Vert.x router before any
 * resource is selected, exactly as the servlet container used to render its error page without
 * entering the Struts front controller. The same holds for the body-size (413) and media-type
 * (415) rejections of the HTTP layer.
 *
 * <p>The response is written through {@code RoutingContext}, so the headers-end handler installed
 * by {@code SecurityHeaderFilter} still applies the five security headers.
 */
@ApplicationScoped
public class RouterProblemHandler {

    /** Router-level statuses observed on this gateway: unmatched route, method, body, media type. */
    private static final List<Integer> HANDLED_STATUSES = List.of(403, 404, 405, 413, 415, 500);

    @Inject
    ProblemResponses problems;

    @Inject
    ObjectMapper json;

    void registerFailureHandlers(@Observes Router router) {
        HANDLED_STATUSES.forEach(status ->
                router.errorHandler(status, context -> write(context, status)));
    }

    private void write(RoutingContext context, int status) {
        if (context.response().ended()) {
            return;
        }
        Problem problem = ProblemFactory.ofStatus(status, requestId(context));
        context.response().setStatusCode(status)
                .putHeader("Content-Type", ProblemResponses.PROBLEM_JSON);
        if (status == 503) {
            context.response().putHeader("Retry-After",
                    String.valueOf(ProblemFactory.RETRY_AFTER_SECONDS));
        }
        try {
            context.response().end(json.writeValueAsString(problem));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            context.response().end();
        }
    }

    private String requestId(RoutingContext context) {
        String assigned = RequestCorrelation.requestId(context);
        return assigned == null ? problems.requestId() : assigned;
    }
}
