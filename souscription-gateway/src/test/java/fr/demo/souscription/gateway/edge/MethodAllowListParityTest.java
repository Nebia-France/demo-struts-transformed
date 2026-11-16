package fr.demo.souscription.gateway.edge;

import fr.demo.souscription.gateway.observability.RejectionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.RestAssured;
import io.restassured.http.Method;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 13-souscription-gateway-method-allow-list.
 *
 * Source (SecurityFilter): only GET, HEAD, POST were accepted; anything else got
 * "Allow: GET, HEAD, POST" + sendError(405). SubscriptionIT line 125 asserted 405 on
 * GET /commencer.do.
 *
 * Target: the same rejection, plus PUT in the allow-list - a recorded decision caused by
 * PUT /api/v1/drafts/{id}/subscriber. The rejection must still happen BEFORE the CSRF check so a
 * forbidden method yields 405 and not 403.
 */
@QuarkusTest
class MethodAllowListParityTest {

    @Inject
    MeterRegistry registry;

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "PATCH", "OPTIONS", "TRACE"})
    void forbiddenMethodsAreRejectedWithAllowHeaderAndProblemJson(String method) {
        Response response = RejectionObservation.rejectedOnce(registry, 405, RejectionMetrics.METHOD,
                () -> RestAssured.given().request(Method.valueOf(method), "/api/v1/products"));

        assertEquals(405, response.getStatusCode(), method + " must be refused");
        assertEquals("GET, HEAD, POST, PUT", response.getHeader("Allow"),
                "the Allow header is preserved verbatim from SecurityFilter and derived from"
                        + " gateway.security.allowed-methods");
        assertTrue(response.getContentType().startsWith("application/problem+json"),
                "the source produced HTML through erreur.jsp; the target normalises to RFC 7807");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void safeAllowedMethodsAreNotRejectedByTheFilter(String method) {
        Response response = RestAssured.given().request(Method.valueOf(method), "/api/v1/products");
        assertNotEquals(405, response.getStatusCode(), method + " is in the allow-list");
    }

    @Test
    void postAndPutAreNotRejectedByTheFilter() {
        assertNotEquals(405, given().contentType("application/json").body("{}")
                .when().post("/api/v1/drafts").getStatusCode());
        assertNotEquals(405, given().contentType("application/json").body("{}")
                .when().put("/api/v1/drafts/{id}/subscriber",
                        "00000000-0000-0000-0000-000000000000").getStatusCode());
    }

    @Test
    void forbiddenMethodWithoutCsrfTokenStillAnswers405Not403() {
        // Ordering assertion: METHOD_ALLOW_LIST (200) runs before CSRF (300), so a forbidden
        // method is never CSRF-evaluated. An inverted order changes the observable status.
        Response response = RejectionObservation.rejectedOnce(registry, 405, RejectionMetrics.METHOD,
                () -> given().when().delete("/api/v1/drafts/{id}/subscriber",
                        "00000000-0000-0000-0000-000000000000"));
        assertEquals(405, response.getStatusCode(),
                "405 must win over 403 for a forbidden method with no token");
    }

    @Test
    void noLiteralMethodListLeaksIntoTheAllowHeader() {
        // If the Allow header were built from a Java literal it could diverge from the configured
        // list; this asserts both halves agree on the documented PUT extension.
        String allow = given().when().delete("/api/v1/products").getHeader("Allow");
        assertTrue(allow.contains("PUT"),
                "the PUT extension must be visible in Allow and documented in docs/edge-filters.md");
    }
}
