package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Parity check for 20-souscription-gateway-downstream-error-mapping.
 *
 * <p>Downstream failures are a NEW failure class: an in-process call could not fail partially. They
 * are therefore verified against the table frozen by plan 12 in {@code docs/error-mapping.md}:
 * relayable 4xx -> same status, 5xx -> 502, timeout -> 504, announced unavailability -> 503 +
 * {@code Retry-After}.
 *
 * <p>The one PRESERVED behaviour is the field-level feedback the source rendered through
 * {@code errors.jspf}: the {@code FieldErrors} map must be relayed verbatim. It is also the only
 * downstream payload allowed through — no other provider body text may appear
 * ({@code SubscriptionIT} line 115).
 *
 * <p>Each fixture is a WireMock answer installed on the simulation edge; the WireMock servers are
 * started for the whole run by {@link DownstreamWireMockResource}.
 */
@QuarkusTest
class DownstreamErrorParityTest {

    private static final String SIMULATE = "/api/v1/simulations";

    private static final String DOWNSTREAM_PATH = "/v1/simulations";

    /** Text written by every stub body: it must never reach the public surface. */
    private static final String STUB_MARKER =
            "wiremock java.lang.IllegalStateException trace localhost:65535";

    private static final String REQUEST_BODY = "{\"productCode\":\"EQUILIBRE\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}";

    private static final String FIXTURE_400 = "downstream-400";
    private static final String FIXTURE_404 = "downstream-404";
    private static final String FIXTURE_409 = "downstream-409";
    private static final String FIXTURE_500 = "downstream-500";
    private static final String FIXTURE_503 = "downstream-503";
    private static final String FIXTURE_TIMEOUT = "downstream-timeout";

    @AfterEach
    void restoreTheContractStubs() {
        DownstreamWireMocks.server("simulation").resetToDefaultMappings();
    }

    @Test
    void relayableClientErrorsKeepTheirStatus() {
        assertProblem(call(FIXTURE_400), 400);
        assertProblem(call(FIXTURE_404), 404);
        assertProblem(call(FIXTURE_409), 409);
    }

    @Test
    void downstreamServerErrorBecomes502() {
        assertProblem(call(FIXTURE_500), 502);
    }

    @Test
    void downstreamUnavailableStays503WithRetryAfter() {
        Response response = call(FIXTURE_503);
        assertProblem(response, 503);
        assertNotNull(response.getHeader("Retry-After"),
                "without Retry-After the SPA retries immediately and keeps the breaker open");
    }

    @Test
    void readTimeoutBecomes504() {
        assertProblem(call(FIXTURE_TIMEOUT), 504);
    }

    @Test
    void fieldErrorsAreRelayedVerbatim() {
        Response response = call(FIXTURE_400);
        String body = response.asString();

        assertTrue(body.contains("\"errors\""),
                "the errors array is the machine-readable successor of errors.jspf");
        // The validating service owns the wording; the gateway is a conduit: same fields, same
        // messages, same codes, same order (FieldErrors.json shape of the reference contract).
        assertEquals(List.of(
                        Map.of("field", "initialAmount",
                                "message", "Le versement initial est hors bornes",
                                "code", "out-of-range"),
                        Map.of("field", "initialAmount",
                                "message", "Le versement initial doit etre un multiple de 50",
                                "code", "step"),
                        Map.of("field", "durationYears", "message", "La duree est hors bornes",
                                "code", "out-of-range")),
                response.jsonPath().getList("errors"), body);
    }

    @Test
    void noOtherDownstreamBodyTextLeaks() {
        for (String fixture : new String[] {FIXTURE_500, FIXTURE_503, FIXTURE_TIMEOUT}) {
            String body = call(fixture).asString();
            assertFalse(body.contains("wiremock"), "no stub text may leak: " + body);
            assertFalse(body.contains("Exception"), "no provider exception may leak: " + body);
            assertFalse(body.contains("localhost:"), "no internal host may leak: " + body);
        }
    }

    @Test
    void newStatusesAreDeclaredInTheContract() {
        // /q/openapi is published on the management interface, not on the application port.
        String openApi = given().port(managementPort()).accept("application/json")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().body().asString();
        assertTrue(openApi.contains("\"502\""), "502 must be declared on downstream-backed operations");
        assertTrue(openApi.contains("\"503\""), "503 must be declared");
        assertTrue(openApi.contains("\"504\""), "504 must be declared");
    }

    /** Installs the fixture on the simulation edge and calls the public surface. */
    private static Response call(String fixture) {
        installFixture(fixture);
        return CsrfTestPair.withCsrf().contentType("application/json").body(REQUEST_BODY)
                .when().post(SIMULATE);
    }

    private static void installFixture(String fixture) {
        switch (fixture) {
            case FIXTURE_400 -> downstreamAnswers(400, fieldErrorsBody(), 0);
            case FIXTURE_404 -> downstreamAnswers(404, opaqueBody(404), 0);
            case FIXTURE_409 -> downstreamAnswers(409, opaqueBody(409), 0);
            case FIXTURE_500 -> downstreamAnswers(500, opaqueBody(500), 0);
            case FIXTURE_503 -> downstreamAnswers(503, opaqueBody(503), 0);
            case FIXTURE_TIMEOUT -> downstreamAnswers(200, "{\"initialAmount\":\"1000.00\"}",
                    readTimeoutMillis() + 1500);
            default -> throw new IllegalArgumentException("fixture inconnue : " + fixture);
        }
    }

    /** A downstream 400 produced by the validating service, carrying its {@code FieldErrors}. */
    private static String fieldErrorsBody() {
        return "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"" + STUB_MARKER + "\",\"errors\":["
                + "{\"field\":\"initialAmount\",\"message\":\"Le versement initial est hors bornes\","
                + "\"code\":\"out-of-range\"},"
                + "{\"field\":\"initialAmount\","
                + "\"message\":\"Le versement initial doit etre un multiple de 50\","
                + "\"code\":\"step\"},"
                + "{\"field\":\"durationYears\",\"message\":\"La duree est hors bornes\","
                + "\"code\":\"out-of-range\"}]}";
    }

    private static String opaqueBody(int status) {
        return "{\"title\":\"" + STUB_MARKER + "\",\"status\":" + status
                + ",\"detail\":\"" + STUB_MARKER + "\"}";
    }

    private static void downstreamAnswers(int status, String body, int delayMillis) {
        WireMockServer simulation = DownstreamWireMocks.server("simulation");
        var response = aResponse().withStatus(status)
                .withHeader("Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json")
                .withBody(body);
        if (delayMillis > 0) {
            response = response.withFixedDelay(delayMillis);
        }
        simulation.stubFor(post(urlPathEqualTo(DOWNSTREAM_PATH)).atPriority(1)
                .willReturn(response));
    }

    private static void assertProblem(Response response, int expected) {
        assertEquals(expected, response.getStatusCode(),
                "see the frozen table in souscription-gateway/docs/error-mapping.md: "
                        + response.asString());
        assertTrue(String.valueOf(response.getContentType()).startsWith("application/problem+json"),
                "media type attendu problem+json : " + response.getContentType());
    }

    private static int readTimeoutMillis() {
        return ConfigProvider.getConfig()
                .getValue("quarkus.rest-client.simulation.read-timeout", Integer.class);
    }

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }
}
