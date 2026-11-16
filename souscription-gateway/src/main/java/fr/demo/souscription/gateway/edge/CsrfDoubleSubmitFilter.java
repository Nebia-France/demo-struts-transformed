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
import java.util.Set;

/**
 * Enforces the double-submit CSRF check on every state-changing request of the public API (W3-T03),
 * replacing the session-token comparison of {@code fr.demo.souscription.web.SecurityFilter}.
 *
 * <p>Three deliberate differences from the source, documented in {@code docs/csrf-trust-model.md}:
 * <ul>
 *   <li>the enforced set is derived from the <em>safe</em> methods ({@code GET}, {@code HEAD}), so
 *       every state-changing method is covered — {@code POST} as in the source, plus the
 *       {@code PUT}, {@code PATCH} and {@code DELETE} the resource-oriented API of plan 07
 *       introduced and that the source never saw;</li>
 *   <li>the scope is {@code /api/v1/**} instead of {@code path.endsWith(".do")}; static assets and
 *       the management endpoints under {@code /q/*} are untouched, as the source left its static
 *       resources untouched;</li>
 *   <li>the expected value is read from the {@code gateway.csrf.header-name} header and compared to
 *       the {@code gateway.csrf.cookie-name} cookie, instead of a hidden form field compared to a
 *       session attribute.</li>
 * </ul>
 *
 * <p>Rejection is 403 {@code problem+json} with a body that does not disclose which half of the
 * pair failed (see {@link CsrfValidationException}). The response is written through
 * {@code RoutingContext} — a short-circuiting route filter never reaches the JAX-RS mapper chain —
 * and the five security headers are installed first, as {@code MethodAllowListFilter} does.
 *
 * <p>A method outside {@code gateway.security.allowed-methods} is passed through untouched so that
 * {@link MethodAllowListFilter} answers it 405: the method verdict stays the single decision point
 * for method rejection, and a forbidden method never surfaces as 403.
 */
@ApplicationScoped
public class CsrfDoubleSubmitFilter {

    /** Methods exempt from the check; every other method is state-changing and is verified. */
    private static final Set<String> SAFE = Set.of("GET", "HEAD");

    /** Scope of the enforcement: the public API only. */
    static final String PROTECTED_PREFIX = "/api/v1";

    @Inject
    CsrfTokenService tokens;

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

    @RouteFilter(EdgeFilterOrder.CSRF)
    void enforce(RoutingContext rc) {
        String method = rc.request().method().name();
        if (SAFE.contains(method)
                || !inScope(rc)
                || !config.security().allowedMethods().contains(method)) {
            rc.next();
            return;
        }
        String cookie = cookieValue(rc);
        String header = rc.request().getHeader(config.csrf().headerName());
        if (!tokens.matches(cookie, header, config.csrf().maxTokenLength())
                || tokens.isExpired(cookie)) {
            securityHeaders.installHeaders(rc);
            rejections.rejected(403, isBlank(cookie) || isBlank(header)
                    ? RejectionMetrics.CSRF_MISSING : RejectionMetrics.CSRF_MISMATCH,
                    rc.normalizedPath());
            reject(rc);
            return;
        }
        rc.next();
    }

    private boolean inScope(RoutingContext rc) {
        String path = rc.normalizedPath();
        return path != null && path.startsWith(PROTECTED_PREFIX);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String cookieValue(RoutingContext rc) {
        var cookie = rc.request().getCookie(config.csrf().cookieName());
        return cookie == null ? null : cookie.getValue();
    }

    private void reject(RoutingContext rc) {
        Problem problem = ProblemFactory.of(new CsrfValidationException(), requestId(rc));
        rc.response().setStatusCode(problem.status())
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
