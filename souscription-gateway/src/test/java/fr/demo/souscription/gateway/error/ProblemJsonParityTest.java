package fr.demo.souscription.gateway.error;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Parity skeleton for 12-souscription-gateway-problem-json-mappers.
 *
 * <p>The source {@code web.xml} declared 7 {@code <error-page>} entries (400, 403, 404, 405, 409,
 * 500 and {@code java.lang.Throwable}) all forwarding to one HTML view. The statuses are the
 * preserved contract; the media type deliberately becomes {@code application/problem+json}. Two
 * source properties must survive exactly: the status set, and the absence of internal detail in the
 * error body (asserted by {@code SubscriptionIT} line 115).
 */
@QuarkusTest
class ProblemJsonParityTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Step 1: each of the 7 source statuses answers problem+json with the mandatory members. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        "GET, /test-only/problems/bad-request, 400",
        "GET, /test-only/problems/forbidden, 403",
        "GET, /test-only/problems/not-found, 404",
        "POST, /test-only/problems/method, 405",
        "GET, /test-only/problems/conflict, 409",
        "GET, /test-only/problems/boom, 500"
    })
    void eachSourceStatusIsAProblemDocument(String method, String path, int expected) {
        Response response = request(method, path);
        response.then().statusCode(expected).contentType(PROBLEM_JSON);
        assertEquals(expected, response.jsonPath().getInt("status"), path);
        String type = response.jsonPath().getString("type");
        assertNotNull(type, "type is mandatory: " + path);
        assertTrue(type.startsWith(ProblemFactory.TYPE_BASE), "unstable type: " + type);
        assertNotNull(response.jsonPath().getString("title"), "title is mandatory: " + path);
        assertNotNull(response.jsonPath().getString("instance"), "instance is mandatory: " + path);
    }

    /** Step 2: no internal detail leaks into any error body. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "GET, /test-only/problems/boom",
        "GET, /test-only/problems/bad-request",
        "GET, /test-only/problems/conflict",
        "GET, /test-only/problems/downstream-5xx",
        "GET, /nonexistent"
    })
    void noErrorBodyLeaksInternalDetail(String method, String path) {
        String body = request(method, path).body().asString();
        assertFalse(body.contains("Exception"), "exception detail leaked by " + path + ": " + body);
        assertFalse(body.contains("at fr.demo"), "stack frame leaked by " + path + ": " + body);
        assertFalse(body.contains("fr.demo.souscription"), "class name leaked by " + path + ": " + body);
        assertFalse(body.matches("(?s).*\\bSQLState\\b.*"), "SQL state leaked by " + path);
    }

    /**
     * Step 3: a validation failure carries the field-level entries in {@code errors}, in the
     * {@code FieldErrors.json} array shape of the reference contract (W7-T03).
     */
    @Test
    void validationFailureCarriesTheFieldErrorsMap() {
        Response response = request("GET", "/test-only/problems/validation");
        response.then().statusCode(400).contentType(PROBLEM_JSON);
        List<Map<String, String>> errors = response.jsonPath().getList("errors");
        assertNotNull(errors, "field errors must be reported: " + response.body().asString());
        assertTrue(errors.stream().anyMatch(e -> "code".equals(e.get("field"))),
                "errors must name the rejected field: " + errors);
        assertFalse(errors.stream().filter(e -> "code".equals(e.get("field")))
                        .map(e -> e.get("message")).anyMatch(m -> m == null || m.isEmpty()),
                "a rejected field carries its message: " + errors);
    }

    /** Step 4: router-level failures are normalised and keep the five security headers. */
    @Test
    void routerLevelNotFoundIsAProblemWithTheSecurityHeaders() {
        assertProblemWithHeaders(given().when().get("/nonexistent"), 404);
    }

    @Test
    void oversizedBodyIsAProblemWithTheSecurityHeaders() {
        String oversized = "{\"productCode\":\"" + "A".repeat(65 * 1024) + "\"}";
        assertProblemWithHeaders(fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(oversized)
                .when().post("/api/v1/drafts"), 413);
    }

    @Test
    void unsupportedMediaTypeIsAProblemWithTheSecurityHeaders() {
        assertProblemWithHeaders(fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.TEXT).body("productCode=PRUDENT")
                .when().post("/api/v1/drafts"), 415);
    }

    /** Step 5: the frozen downstream table, consumed by plans 20 and 21. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "/test-only/problems/downstream-4xx, 409",
        "/test-only/problems/downstream-5xx, 502",
        "/test-only/problems/downstream-503, 503",
        "/test-only/problems/timeout, 504",
        "/test-only/problems/circuit-open, 503"
    })
    void downstreamFailuresFollowTheFrozenTable(String path, int expected) {
        given().when().get(path).then().statusCode(expected).contentType(PROBLEM_JSON);
    }

    @Test
    void openCircuitAdvertisesRetryAfter() {
        given().when().get("/test-only/problems/circuit-open")
                .then().statusCode(503)
                .header("Retry-After", String.valueOf(ProblemFactory.RETRY_AFTER_SECONDS));
    }

    /** Step 8: the Problem schema is published and every error response is declared. */
    @Test
    void theProblemSchemaIsPublished() {
        String openapi = given().port(managementPort()).accept("application/json")
                .when().get("/q/openapi").then().statusCode(200)
                .extract().body().asString();
        assertTrue(openapi.contains("Problem"), "the Problem schema must be published");
        assertTrue(openapi.contains("problem+json"), "errors must be declared as problem+json");
    }

    private void assertProblemWithHeaders(Response response, int expectedStatus) {
        response.then().statusCode(expectedStatus).contentType(PROBLEM_JSON)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        ConfigProvider.getConfig().getValue("gateway.security.csp", String.class))
                .header("Cache-Control", "no-store");
    }

    private Response request(String method, String path) {
        return "POST".equals(method) ? given().when().post(path) : given().when().get(path);
    }

    /** {@code /q/openapi} is served on the management interface, not on the application port. */
    private int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }
}
