package fr.demo.souscription.gateway.observability;

import fr.demo.souscription.gateway.edge.EdgeFilterOrder;
import fr.demo.souscription.gateway.error.ProblemResponses;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.MDC;

/**
 * Assigns the request id of every request before any edge filter runs (W6-T03).
 *
 * <p>The id is the ingress {@code X-Request-Id} when present, otherwise a generated UUID. It is
 * stored once on the routing context and in the MDC under {@link #REQUEST_ID}, so the
 * {@code instance} member of a problem document and the {@code requestId} field of every log
 * record written while serving the request carry the same value. On Quarkus the MDC is bound to
 * the request's duplicated Vert.x context, so the value never leaks into another request.
 */
@ApplicationScoped
public class RequestCorrelation {

    /** MDC key and routing-context key of the request id. */
    public static final String REQUEST_ID = "requestId";

    /** Runs before every edge filter: strictly above {@code CSRF}, the highest edge filter order. */
    static final int ORDER = EdgeFilterOrder.CSRF + 100;

    /**
     * Shape accepted from the ingress header. Anything else (oversized value, free text with spaces,
     * quotes or separators) is replaced by a generated id, so a client cannot write arbitrary content into
     * every log record of its request.
     */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @RouteFilter(ORDER)
    void assign(RoutingContext rc) {
        String fromHeader = rc.request().getHeader(ProblemResponses.REQUEST_ID_HEADER);
        String id = fromHeader != null && ACCEPTED.matcher(fromHeader).matches()
                ? fromHeader : UUID.randomUUID().toString();
        rc.put(REQUEST_ID, id);
        MDC.put(REQUEST_ID, id);
        rc.next();
    }

    /** The request id assigned to this request, or {@code null} outside a correlated request. */
    public static String requestId(RoutingContext rc) {
        return rc == null ? null : rc.get(REQUEST_ID);
    }
}
