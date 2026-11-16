package fr.demo.souscription.gateway.edge;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 11-souscription-gateway-security-headers.
 *
 * SecurityFilter.doFilter set five headers on EVERY response, including the error documents of
 * the 7 web.xml error-page entries. Four values must be byte-identical; the CSP is deliberately
 * narrowed (the gateway serves no HTML) and only its presence is asserted here, the narrowing
 * itself being recorded in docs/security-headers.md.
 *
 * The router-404 case is the important one: a ContainerResponseFilter never sees a response that
 * does not reach JAX-RS, which would silently reduce coverage below the source's.
 */
@QuarkusTest
class SecurityHeaderParityTest {

    @Test
    void headersPresentOnSuccess() {
        assertFiveHeaders(given().when().get("/api/v1/products"));
    }

    @Test
    void headersPresentOnRouterLevel404() {
        // Path outside /api/v1: never reaches JAX-RS.
        assertFiveHeaders(given().when().get("/nonexistent"));
    }

    @Test
    void headersPresentOnJaxRs404() {
        assertFiveHeaders(given().when().get("/api/v1/products/UNKNOWN-CODE"));
    }

    @Test
    void headersPresentOnServerError() {
        // Any route reachable with a provoked failure; keep the stub/WireMock 500 fixture here.
        assertFiveHeaders(given().when().get("/api/v1/products/@@forced-error@@"));
    }

    private static void assertFiveHeaders(Response response) {
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"),
                "preserved verbatim from SecurityFilter");
        assertEquals("DENY", response.getHeader("X-Frame-Options"),
                "SAMEORIGIN would be a weakening, not a modernisation");
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"),
                "preserved verbatim from SecurityFilter");
        assertEquals("no-store", response.getHeader("Cache-Control"),
                "the public edge serves journey data and keeps no-store");

        String csp = response.getHeader("Content-Security-Policy");
        assertNotNull(csp, "a CSP must always be present, as in the source");
        assertTrue(csp.contains("default-src 'none'"),
                "the gateway serves no HTML: the narrowed policy is the documented target"
                        + " (docs/security-headers.md), the document CSP belongs to souscription-web");
    }
}
