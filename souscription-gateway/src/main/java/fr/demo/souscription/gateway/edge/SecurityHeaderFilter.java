package fr.demo.souscription.gateway.edge;

import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Adds the five security response headers of {@code fr.demo.souscription.web.SecurityFilter} to
 * <em>every</em> response (W2-T01).
 *
 * <p>The headers are written from {@link RoutingContext#addHeadersEndHandler} so that responses
 * produced outside JAX-RS — router 404s, management endpoints under {@code /q/*}, responses emitted
 * by short-circuiting edge filters — are covered too. A {@code ContainerResponseFilter} would miss
 * them; see {@code docs/edge-filters.md} and {@code docs/security-headers.md}.
 *
 * <p>The Content-Security-Policy value comes exclusively from {@code gateway.security.csp}: no
 * policy string is hardcoded here.
 */
@ApplicationScoped
public class SecurityHeaderFilter {

    @Inject
    GatewayConfig config;

    @RouteFilter(EdgeFilterOrder.SECURITY_HEADERS)
    void addHeaders(RoutingContext rc) {
        installHeaders(rc);
        rc.next();
    }

    /**
     * Registers the headers-end handler on a request.
     *
     * <p>Exposed so that an edge filter which short-circuits <em>before</em> this one on the request
     * path — the method allow-list of plan 13, ordered higher and therefore earlier in Vert.x — can
     * install the same handler itself instead of duplicating the header values.
     */
    public void installHeaders(RoutingContext rc) {
        rc.addHeadersEndHandler(v -> {
            var headers = rc.response().headers();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", config.security().csp());
            headers.set("Cache-Control", "no-store");
        });
    }
}
