package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check of the single downstream failure mapper (W4-T05): WireMock answers each outcome
 * the frozen table of {@code docs/error-mapping.md} names, and the public answer is asserted on the
 * real HTTP surface.
 *
 * <p>Outcomes covered, all on {@code POST /api/v1/simulations} (the simulation edge):
 * downstream 400 carrying a {@code FieldErrors} body, 404, 409, 500, 503 and a response delayed
 * past {@code quarkus.rest-client.simulation.read-timeout}.
 *
 * <p>Two invariants are asserted on every outcome: the answer is {@code application/problem+json},
 * and no text written by the stub appears in the body — the relayed {@code errors} of the 400 being
 * the only downstream payload allowed through.
 */
@QuarkusTest
class DownstreamErrorMappingTest {

    private static final String SIMULATE = "/api/v1/simulations";

    private static final String DOWNSTREAM_PATH = "/v1/simulations";

    /** Text written by every stub body: it must never reach the public surface. */
    private static final String INTERNAL_MARKER = "trace-interne-aval-ne-doit-pas-fuiter";

    private static final String REQUEST_BODY = "{\"productCode\":\"EQUILIBRE\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}";

    private static WireMockServer simulation() {
        return DownstreamWireMocks.server("simulation");
    }

    @AfterEach
    void restoreTheContractStubs() {
        // Restores the mappings loaded from src/test/resources/wiremock/simulation: the server is
        // shared by the whole test run.
        simulation().resetToDefaultMappings();
    }

    /** Overrides the contract stub with the given downstream answer. */
    private void downstreamAnswers(int status, String body, int delayMillis) {
        var response = aResponse().withStatus(status)
                .withHeader("Content-Type", status >= 400
                        ? "application/problem+json" : "application/json")
                .withBody(body);
        if (delayMillis > 0) {
            response = response.withFixedDelay(delayMillis);
        }
        simulation().stubFor(post(urlPathEqualTo(DOWNSTREAM_PATH)).atPriority(1)
                .willReturn(response));
    }

    private Response simulate() {
        return CsrfTestPair.withCsrf().contentType("application/json").body(REQUEST_BODY)
                .when().post(SIMULATE);
    }

    /** A downstream 400 relays its status and its field errors, and nothing else. */
    @Test
    void downstream400RelaysTheFieldErrorsVerbatim() {
        downstreamAnswers(400, "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"" + INTERNAL_MARKER + "\",\"errors\":["
                + "{\"field\":\"initialAmount\",\"message\":\"Le versement initial est hors bornes\","
                + "\"code\":\"out-of-range\"},"
                + "{\"field\":\"durationYears\",\"message\":\"La duree est hors bornes\","
                + "\"code\":\"out-of-range\"}]}", 0);

        Response response = simulate();

        assertEquals(400, response.statusCode(), response.asString());
        assertProblemJson(response);
        assertEquals(List.of(
                        java.util.Map.of("field", "initialAmount",
                                "message", "Le versement initial est hors bornes",
                                "code", "out-of-range"),
                        java.util.Map.of("field", "durationYears",
                                "message", "La duree est hors bornes", "code", "out-of-range")),
                response.jsonPath().getList("errors"));
        assertFalse(response.asString().contains(INTERNAL_MARKER),
                "seuls les errors sont relayes : " + response.asString());
    }

    @Test
    void downstream404IsRelayedWithoutAnyDownstreamBody() {
        assertMapped(404, 404);
    }

    @Test
    void downstream409IsRelayedWithoutAnyDownstreamBody() {
        assertMapped(409, 409);
    }

    @Test
    void downstream500BecomesAGatewayError() {
        assertMapped(500, 502);
    }

    /** The one relayed 5xx: the transient signal is kept, with {@code Retry-After}. */
    @Test
    void downstream503StaysUnavailableWithRetryAfter() {
        Response response = assertMapped(503, 503);
        assertEquals(String.valueOf(fr.demo.souscription.gateway.error.ProblemFactory
                        .RETRY_AFTER_SECONDS), response.header("Retry-After"),
                "un 503 doit annoncer Retry-After");
    }

    /** A read timeout is a 504, and the caller is told how to resume. */
    @Test
    void aReadTimeoutBecomesAGatewayTimeout() {
        downstreamAnswers(200, "{\"initialAmount\":\"1000.00\"}", readTimeoutMillis() + 1500);

        Response response = simulate();

        assertEquals(504, response.statusCode(), response.asString());
        assertProblemJson(response);
        assertNull(response.jsonPath().get("errors"), "aucun champ rejete sur une expiration");
    }

    private Response assertMapped(int downstreamStatus, int expectedPublicStatus) {
        downstreamAnswers(downstreamStatus, "{\"title\":\"" + INTERNAL_MARKER + "\",\"status\":"
                + downstreamStatus + ",\"detail\":\"" + INTERNAL_MARKER + "\"}", 0);

        Response response = simulate();

        assertEquals(expectedPublicStatus, response.statusCode(), response.asString());
        assertProblemJson(response);
        assertFalse(response.asString().contains(INTERNAL_MARKER),
                "aucun corps aval ne doit fuiter : " + response.asString());
        assertNull(response.jsonPath().get("errors"),
                "aucun champ rejete n'est produit par cet echec");
        return response;
    }

    private static void assertProblemJson(Response response) {
        assertTrue(String.valueOf(response.contentType()).contains("problem+json"),
                "media type attendu problem+json : " + response.contentType());
        assertEquals(response.statusCode(), response.jsonPath().getInt("status"));
        assertTrue(response.jsonPath().getString("instance") != null,
                "instance doit correler la requete");
    }

    private static int readTimeoutMillis() {
        return org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("quarkus.rest-client.simulation.read-timeout", Integer.class);
    }
}
