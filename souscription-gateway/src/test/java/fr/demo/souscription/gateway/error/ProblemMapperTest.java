package fr.demo.souscription.gateway.error;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * W2-T03: every response with a status greater than or equal to 400 is
 * {@code application/problem+json} conforming to the published {@code Problem} schema (NFG-018).
 *
 * <p>The parameterised case covers the seven statuses formerly declared as container error pages
 * (400, 403, 404, 405, 409, 500 and the {@code Throwable} catch-all), plus the downstream failure
 * classes frozen by this plan.
 */
@QuarkusTest
class ProblemMapperTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        "GET, /test-only/problems/bad-request, 400",
        "GET, /test-only/problems/validation, 400",
        "GET, /test-only/problems/forbidden, 403",
        "GET, /test-only/problems/not-found, 404",
        "POST, /test-only/problems/method, 405",
        "GET, /test-only/problems/conflict, 409",
        "GET, /test-only/problems/boom, 500",
        "GET, /nonexistent, 404",
        "GET, /test-only/problems/downstream-4xx, 409",
        "GET, /test-only/problems/downstream-5xx, 502",
        "GET, /test-only/problems/downstream-503, 503",
        "GET, /test-only/problems/timeout, 504",
        "GET, /test-only/problems/circuit-open, 503"
    })
    void everyFailureIsASchemaValidProblem(String method, String path, int expectedStatus) {
        Response response = request(method, path);
        response.then().statusCode(expectedStatus).contentType(PROBLEM_JSON);

        assertEquals(expectedStatus, response.jsonPath().getInt("status"), path);
        String type = response.jsonPath().getString("type");
        assertNotNull(type, "type is mandatory: " + path);
        assertTrue(type.startsWith(ProblemFactory.TYPE_BASE), "unstable type on " + path + ": " + type);
        assertNotNull(response.jsonPath().getString("title"), "title is mandatory: " + path);
        assertNotNull(response.jsonPath().getString("instance"),
                "instance must correlate the request: " + path);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "GET, /test-only/problems/boom",
        "GET, /test-only/problems/bad-request",
        "GET, /nonexistent"
    })
    void noErrorBodyLeaksAStackTraceOrAnInternalDetail(String method, String path) {
        String body = request(method, path).body().asString();
        assertFalse(body.contains("at fr.demo"), "stack trace leaked by " + path + ": " + body);
        assertFalse(body.contains("Exception:"), "exception detail leaked by " + path + ": " + body);
    }

    @Test
    void validationFailureCarriesTheRejectedFields() {
        Response response = request("GET", "/test-only/problems/validation");
        java.util.List<java.util.Map<String, String>> errors =
                response.jsonPath().getList("errors");
        assertNotNull(errors, "the rejected fields must be reported in errors: "
                + response.body().asString());
        assertEquals(1, errors.size(), "exactly one field is rejected: " + errors);
        assertEquals("code", errors.get(0).get("field"),
                "the rejected field must be named by its field member: " + errors);
    }

    @Test
    void anOpenCircuitAdvertisesRetryAfter() {
        request("GET", "/test-only/problems/circuit-open")
                .then().statusCode(503)
                .header("Retry-After", String.valueOf(ProblemFactory.RETRY_AFTER_SECONDS));
    }

    @Test
    void anUncaughtExceptionKeepsTheFiveSecurityHeaders() {
        request("GET", "/test-only/problems/boom")
                .then().statusCode(500).contentType(PROBLEM_JSON)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        ConfigProvider.getConfig().getValue("gateway.security.csp", String.class))
                .header("Cache-Control", "no-store");
    }

    @Test
    void aRouterLevelNotFoundKeepsTheFiveSecurityHeaders() {
        request("GET", "/outside-api-v1")
                .then().statusCode(404).contentType(PROBLEM_JSON)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        ConfigProvider.getConfig().getValue("gateway.security.csp", String.class))
                .header("Cache-Control", "no-store");
    }

    @Test
    void theRequestIdOfTheCallerIsUsedAsInstance() {
        given().header("X-Request-Id", "req-42")
                .when().get("/test-only/problems/not-found")
                .then().statusCode(404)
                .body("instance", org.hamcrest.Matchers.equalTo("req-42"));
    }

    private Response request(String method, String path) {
        return "POST".equals(method)
                ? given().when().post(path)
                : given().when().get(path);
    }
}
