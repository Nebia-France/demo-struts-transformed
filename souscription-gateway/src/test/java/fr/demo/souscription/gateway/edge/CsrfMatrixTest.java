package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.demo.souscription.gateway.observability.RejectionMetrics;
import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Rejection matrix of the CSRF double-submit check (W3-T03, NFG-002).
 *
 * <p>Six cases must each be answered 403 {@code problem+json} — cookie and header both absent,
 * cookie absent, header absent, differing values, header longer than
 * {@code gateway.csrf.max-token-length}, token whose signed lifetime cannot be trusted — and a
 * valid matched pair must pass the filter. The bodies of the six rejections must be identical once
 * the correlation id is removed: no case may disclose which half of the pair failed.
 *
 * <p>Not a functional test of the proxied routes: "passes" means the request is not answered 403
 * by the edge. What the downstream then answers belongs to plans 20 and 21.
 */
@QuarkusTest
class CsrfMatrixTest {

    private static final String START_DRAFT = "/api/v1/drafts";
    private static final String BODY = "{\"productCode\":\"AUTO-TIERS\"}";

    @Inject
    GatewayConfig config;

    @Inject
    MeterRegistry registry;

    private final ObjectMapper json = new ObjectMapper();

    private String freshToken() {
        String token = given().when().get("/api/v1/csrf-token")
                .then().statusCode(200)
                .extract().jsonPath().getString("token");
        assertNotNull(token, "no token issued by GET /api/v1/csrf-token");
        return token;
    }

    private RequestSpecification submission() {
        return given().contentType("application/json").body(BODY);
    }

    private Response post(String cookie, String header) {
        RequestSpecification spec = submission();
        if (cookie != null) {
            spec = spec.cookie(config.csrf().cookieName(), cookie);
        }
        if (header != null) {
            spec = spec.header(config.csrf().headerName(), header);
        }
        return spec.when().post(START_DRAFT);
    }

    /** Problem body with the per-request correlation id removed, for cross-case comparison. */
    private String normalisedBody(Response response) {
        try {
            ObjectNode body = (ObjectNode) json.readTree(response.getBody().asString());
            body.remove("instance");
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new AssertionError("403 body is not parseable problem+json: "
                    + response.getBody().asString(), e);
        }
    }

    /**
     * Sends one case in its own observation window: 403 problem+json, the {@code reason} series
     * moved by exactly one and exactly one correlated rejection entry written.
     */
    private String assertRejected(String label, String reason, Supplier<Response> request) {
        RejectionObservation observation = RejectionObservation.start(registry);
        try {
            double before = observation.count(403, reason);
            Response response = request.get();
            assertEquals(403, response.getStatusCode(), label + " must be rejected: "
                    + response.getBody().asString());
            assertTrue(String.valueOf(response.getContentType()).contains("problem+json"),
                    label + " must be problem+json: " + response.getContentType());
            observation.assertSingleRejection(403, reason, before,
                    response.jsonPath().getString("instance"));
            return normalisedBody(response);
        } finally {
            observation.stop();
        }
    }

    @Test
    void theSixRejectionCasesAllAnswer403WithAnIndistinguishableBody() {
        String token = freshToken();
        Set<String> bodies = new LinkedHashSet<>();

        String other = freshToken();
        String stale = staleToken();
        bodies.add(assertRejected("cookie absent + header absent", RejectionMetrics.CSRF_MISSING,
                () -> post(null, null)));
        bodies.add(assertRejected("cookie absent + header present", RejectionMetrics.CSRF_MISSING,
                () -> post(null, token)));
        bodies.add(assertRejected("cookie present + header absent", RejectionMetrics.CSRF_MISSING,
                () -> post(token, null)));
        bodies.add(assertRejected("differing values", RejectionMetrics.CSRF_MISMATCH,
                () -> post(token, other)));
        bodies.add(assertRejected("header longer than the bound", RejectionMetrics.CSRF_MISMATCH,
                () -> post(token, "x".repeat(config.csrf().maxTokenLength() + 1))));
        bodies.add(assertRejected("untrusted lifetime", RejectionMetrics.CSRF_MISMATCH,
                () -> post(stale, stale)));

        assertEquals(1, bodies.size(),
                "the six rejections must share one body, none disclosing the cause: " + bodies);
        String body = bodies.iterator().next();
        assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("cookie"),
                "the body must not mention the cookie: " + body);
        assertFalse(body.contains(config.csrf().headerName()),
                "the body must not mention the header: " + body);
        assertTrue(body.contains("\"status\":403"), body);
    }

    @Test
    void aValidMatchedPairPassesTheFilter() {
        String token = freshToken();
        Response response = post(token, token);
        assertNotEquals(403, response.getStatusCode(),
                "a valid double-submit pair must not be rejected: "
                        + response.getBody().asString());
    }

    /** A token whose signed timestamp cannot be verified is treated as past its lifetime. */
    private String staleToken() {
        String token = freshToken();
        int lastDot = token.lastIndexOf('.');
        String signature = token.substring(lastDot + 1);
        String random = token.substring(0, token.substring(0, lastDot).lastIndexOf('.'));
        return random + ".1000000000." + signature;
    }

    /** Safe methods are never rejected, whatever the pair: parity with the source filter. */
    @Test
    void safeMethodsAreNeverRejected() {
        assertNotEquals(403, given().when().get("/api/v1/products").getStatusCode());
        assertNotEquals(403, given().when().head("/api/v1/products").getStatusCode());
    }

    /** Out-of-scope paths carry no CSRF requirement, as the source left its static assets alone. */
    @Test
    void pathsOutsideTheApiAreNotCsrfChecked() {
        assertNotEquals(403, given().when().get("/assets/css/application.css").getStatusCode());
    }

    /** A state-changing method outside the allow-list stays a 405, never a 403. */
    @Test
    void aForbiddenMethodIsStillAnsweredByTheAllowList() {
        RejectionObservation observation = RejectionObservation.start(registry);
        try {
            double before = observation.count(405, RejectionMetrics.METHOD);
            Response response = given().when().delete("/api/v1/drafts/" + UUID.randomUUID());
            assertEquals(405, response.getStatusCode());
            observation.assertSingleRejection(405, RejectionMetrics.METHOD, before,
                    response.jsonPath().getString("instance"));
        } finally {
            observation.stop();
        }
    }
}
