package fr.demo.souscription.gateway.edge;

import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.NewCookie;

/**
 * Issues the double-submit CSRF cookie on safe requests (W3-T02), replacing the session attribute
 * the source filter created on every {@code *.do} access.
 *
 * <p>The cookie is set only on {@code GET} and {@code HEAD} — a state-changing request is verified,
 * never granted a fresh token — and only when the request carries no live token. Issuance is
 * idempotent and lock-free: the source guarded the session with a monitor lock, which has no
 * equivalent here because nothing shared is mutated (FG-003, AG-015).
 *
 * <p>Attributes: {@code Secure}, {@code SameSite=Strict}, {@code Path=/}, {@code Max-Age} aligned
 * with {@code gateway.csrf.ttl}, and deliberately <em>no</em> {@code HttpOnly}: the SPA must read
 * the value to echo it in the {@code gateway.csrf.header-name} header.
 */
@ApplicationScoped
public class CsrfCookieIssuer {

    /** Path of the explicit token endpoint, which issues the cookie itself. */
    static final String TOKEN_PATH = "/api/v1/csrf-token";

    private final CsrfTokenService tokens;
    private final GatewayConfig config;

    @Inject
    public CsrfCookieIssuer(CsrfTokenService tokens, GatewayConfig config) {
        this.tokens = tokens;
        this.config = config;
    }

    @RouteFilter(EdgeFilterOrder.CSRF_COOKIE_ISSUE)
    void issueOnSafeMethods(RoutingContext rc) {
        String method = rc.request().method().name();
        if (("GET".equals(method) || "HEAD".equals(method))
                && !TOKEN_PATH.equals(rc.normalizedPath())) {
            String present = readCookie(rc);
            if (present == null || tokens.isExpired(present)) {
                String header = cookieHeader(tokens.issue());
                rc.addHeadersEndHandler(v -> rc.response().headers().add("Set-Cookie", header));
            }
        }
        rc.next();
    }

    /** Current token of the request, or {@code null} when absent or past its lifetime. */
    public String liveToken(String cookieValue) {
        return cookieValue != null && !tokens.isExpired(cookieValue) ? cookieValue : null;
    }

    /** {@code Set-Cookie} value for a token, used by the filter and asserted by the tests. */
    public String cookieHeader(String token) {
        return newCookie(token).toString();
    }

    /** Typed cookie for the JAX-RS token endpoint. */
    public NewCookie newCookie(String token) {
        return new NewCookie.Builder(config.csrf().cookieName())
                .value(token)
                .path("/")
                .secure(true)
                .httpOnly(false)
                .sameSite(NewCookie.SameSite.STRICT)
                .maxAge((int) tokens.ttl().toSeconds())
                .build();
    }

    private String readCookie(RoutingContext rc) {
        var cookie = rc.request().getCookie(config.csrf().cookieName());
        return cookie == null ? null : cookie.getValue();
    }
}
