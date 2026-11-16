package fr.demo.souscription.gateway.edge;

/**
 * Single source of truth for the ordering of the edge interception components that replace the
 * single {@code fr.demo.souscription.web.SecurityFilter} servlet filter mapped on {@code /*} by
 * {@code web.xml}.
 *
 * <p>The source filter bundled three concerns in one {@code doFilter} body, ordered implicitly by
 * statement order: security response headers, HTTP method allow-list, CSRF token check. They are
 * split here into three independently ordered components ({@code securityHeaderFilter},
 * {@code methodAllowListFilter}, {@code csrfDoubleSubmitFilter}), implemented by plans 11, 13
 * and 16.
 *
 * <p>The effective request order is method allow-list, then CSRF, then the response headers, which
 * are written from a headers-end handler. The rationale and the Vert.x ordering semantics (a higher
 * {@code @RouteFilter} value runs earlier) are documented in {@code docs/edge-filters.md}.
 */
public final class EdgeFilterOrder {

    /**
     * Body-size rejection normalised to problem+json; runs after the headers-end handler is
     * installed, so a short-circuited 413 still carries the five security headers.
     */
    public static final int PAYLOAD_LIMIT = 50;

    /** Security response headers; {@code @RouteFilter}, applied via {@code addHeadersEndHandler}. */
    public static final int SECURITY_HEADERS = 100;

    /** HTTP method allow-list; runs before the CSRF check. */
    public static final int METHOD_ALLOW_LIST = 200;

    /**
     * CSRF double-submit cookie issuance. Disjoint from the check by construction: issuance only
     * applies to safe methods, the check only to state-changing ones, so their relative order is
     * immaterial.
     */
    public static final int CSRF_COOKIE_ISSUE = 250;

    /** CSRF double-submit cookie check. */
    public static final int CSRF = 300;

    private EdgeFilterOrder() {
    }
}
