package fr.demo.souscription.gateway.error;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Builds the {@code application/problem+json} responses shared by every mapper of this package.
 *
 * <p>Centralises three invariants: the media type, the {@code instance} correlation value (the
 * request id, exploited by tracing in plan 28) and the {@code Retry-After} header advertised with a
 * 503. No internal diagnostic is ever written to the body.
 */
@ApplicationScoped
public class ProblemResponses {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(ProblemResponses.class);

    /** Media type of every response with a status greater than or equal to 400 (NFG-018). */
    public static final String PROBLEM_JSON = "application/problem+json";

    /** Request header carrying the correlation id injected by the ingress, when present. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Inject
    CurrentVertxRequest currentRequest;

    /** Problem response for an exception, status resolved by {@link ProblemFactory}. */
    public Response toResponse(Throwable t) {
        Problem problem = ProblemFactory.of(t, requestId());
        journalise(problem, t);
        return build(problem);
    }

    /**
     * The failure cause stays server-side: it is journalised here and correlated with the response
     * through {@code instance}, as {@code docs/error-mapping.md} requires. Nothing of what is
     * logged reaches the body.
     *
     * <p>One record per mapped failure (W6-T03). Only the public status and the class chain of the
     * cause are written: an exception message may quote a request or downstream payload (a
     * validation path naming a subscriber field, a deserialisation error citing a value), so
     * neither the message nor the stack trace is ever logged.
     */
    private void journalise(Problem problem, Throwable t) {
        if (problem.status() >= 500) {
            LOG.errorf("request failure status=%d cause=%s", problem.status(), causeChain(t));
        } else {
            LOG.debugf("request refused status=%d cause=%s", problem.status(), causeChain(t));
        }
    }

    /** Simple class names of the cause chain, outermost first, e.g. {@code A>B}. */
    static String causeChain(Throwable t) {
        StringBuilder chain = new StringBuilder();
        int depth = 0;
        for (Throwable current = t; current != null && depth < 8; current = current.getCause()) {
            if (chain.length() > 0) {
                chain.append('>');
            }
            chain.append(current.getClass().getSimpleName());
            depth++;
            if (current.getCause() == current) {
                break;
            }
        }
        return chain.toString();
    }

    /** Problem response for a status produced outside JAX-RS. */
    public Response toResponse(int status) {
        return build(ProblemFactory.ofStatus(status, requestId()));
    }

    /** Serialises a problem, adding {@code Retry-After} when the service is unavailable. */
    public Response build(Problem problem) {
        Response.ResponseBuilder builder = Response.status(problem.status())
                .type(PROBLEM_JSON)
                .entity(problem);
        if (problem.status() == 503) {
            builder.header("Retry-After", ProblemFactory.RETRY_AFTER_SECONDS);
        }
        return builder.build();
    }

    /**
     * The request id: the one {@code RequestCorrelation} assigned to the current request (also the
     * {@code requestId} of its log records), otherwise the ingress header, otherwise generated.
     */
    public String requestId() {
        RoutingContext context = currentRequest == null ? null : currentRequest.getCurrent();
        if (context != null) {
            String assigned = fr.demo.souscription.gateway.observability.RequestCorrelation
                    .requestId(context);
            if (assigned != null) {
                return assigned;
            }
            String fromHeader = context.request().getHeader(REQUEST_ID_HEADER);
            if (fromHeader != null && !fromHeader.isBlank()) {
                return fromHeader;
            }
        }
        return UUID.randomUUID().toString();
    }
}
