package fr.demo.souscription.gateway.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.gateway.error.Problem;
import fr.demo.souscription.gateway.error.ProblemFactory;
import fr.demo.souscription.gateway.error.ProblemResponses;
import fr.demo.souscription.gateway.observability.RejectionMetrics;
import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.quarkus.vertx.web.RouteFilter;
import fr.demo.souscription.gateway.observability.RequestCorrelation;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Ports the HTTP method allow-list of {@code fr.demo.souscription.web.SecurityFilter} (W2-T02).
 *
 * <p>The source bundled the check inside {@code doFilter} with a hardcoded method list, an
 * {@code Allow} header and a servlet error dispatch, which rendered the HTML document
 * {@code erreur.jsp}. Here the allow-list is a Vert.x {@code @RouteFilter} ordered at
 * {@link EdgeFilterOrder#METHOD_ALLOW_LIST} (200), so a forbidden method is answered 405 and is
 * never CSRF-evaluated (plan 16, {@link EdgeFilterOrder#CSRF}). The body is {@code problem+json},
 * built from {@link ProblemFactory} — the single status decision point of plan 12 — because a
 * short-circuiting route filter never reaches the JAX-RS {@code ExceptionMapper} chain.
 *
 * <p>This filter runs <em>before</em> {@link SecurityHeaderFilter} on the request path (a higher
 * {@code @RouteFilter} value runs earlier in Vert.x), so it installs the headers-end handler itself
 * through {@link SecurityHeaderFilter#installHeaders(RoutingContext)}: the 405 carries the five
 * security headers, exactly as the source filter did on its error document.
 *
 * <p>The accepted method set comes exclusively from {@code gateway.security.allowed-methods}: no
 * method literal appears in this class. The set contains {@code PUT}, which the source did not
 * know, because the resource-oriented API exposes {@code PUT /api/v1/drafts/{id}/subscriber} and
 * {@code PUT /api/v1/drafts/{id}/simulation}. See {@code docs/edge-filters.md}.
 */
@ApplicationScoped
public class MethodAllowListFilter {

    @Inject
    GatewayConfig config;

    @Inject
    SecurityHeaderFilter securityHeaders;

    @Inject
    ProblemResponses problems;

    @Inject
    ObjectMapper json;

    @Inject
    RejectionMetrics rejections;

    @RouteFilter(EdgeFilterOrder.METHOD_ALLOW_LIST)
    void allowList(RoutingContext rc) {
        List<String> allowed = config.security().allowedMethods();
        if (!allowed.contains(rc.request().method().name())) {
            securityHeaders.installHeaders(rc);
            rejections.rejected(405, RejectionMetrics.METHOD, rc.normalizedPath());
            reject(rc, allowed);
            return;
        }
        rc.next();
    }

    private void reject(RoutingContext rc, List<String> allowed) {
        Problem problem = ProblemFactory.ofStatus(405, requestId(rc));
        rc.response().setStatusCode(405)
                .putHeader("Allow", String.join(", ", allowed))
                .putHeader("Content-Type", ProblemResponses.PROBLEM_JSON);
        try {
            rc.response().end(json.writeValueAsString(problem));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            rc.response().end();
        }
    }

    private String requestId(RoutingContext rc) {
        String assigned = RequestCorrelation.requestId(rc);
        return assigned == null ? problems.requestId() : assigned;
    }
}
