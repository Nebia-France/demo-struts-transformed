package fr.demo.souscription.gateway.contract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.contract.ContractDocuments.Located;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import fr.demo.souscription.gateway.error.ProblemFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Downstream -> public error mappings decided in wave 2 (W2-T03 / W4-T05), checked across the
 * contract boundary (W7-T03): 404, 422, 502, 503 and 504.
 *
 * <p>Where the consumed contract declares the failure, the downstream answer comes from the
 * contract-derived stub itself (catalogue 404 and 500, subscription 404); the other answers
 * override one stub for the duration of a test. Every public answer must be
 * {@code application/problem+json}, conform to {@code shared-schemas/FieldErrors.json}, carry the
 * HTTP status in {@code status}, and leak no downstream text.
 *
 * <p>The 422 case is error mapping only: the gateway relays the simulation service's verdict and
 * its field errors verbatim, it does not duplicate the computation rules.
 */
@QuarkusTest
class ContractErrorMappingTest {

    private static final String PREFIX = "/api/v1";

    private static final String INTERNAL_MARKER = "texte-interne-aval-ne-doit-pas-fuiter";

    private static final String SIMULATE_BODY = "{\"productCode\":\"PRUDENT\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":10}";

    private static final String DRAFT = "11111111-1111-1111-1111-111111111111";

    @AfterEach
    void restoreTheContractStubs() {
        for (String key : DownstreamWireMocks.CONFIG_KEYS) {
            DownstreamWireMocks.server(key).resetToDefaultMappings();
            DownstreamWireMocks.server(key).resetScenarios();
        }
    }

    @Test
    void anUnknownProductOrDraftIsA404() {
        Response product = given().when().get(PREFIX + "/products/INCONNU");
        assertProblem(product, 404, "not-found");
        assertDeclared("/products/{code}", "get", 404);

        Response draft = given().when().get(PREFIX + "/drafts/99999999-9999-9999-9999-999999999999");
        assertProblem(draft, 404, "not-found");
        assertDeclared("/drafts/{draftId}", "get", 404);
    }

    @Test
    void aSimulation422IsRelayedWithItsFieldErrorsVerbatim() {
        simulationAnswers(422, "{\"type\":\"about:blank\",\"title\":\"Unprocessable\","
                + "\"status\":422,\"detail\":\"" + INTERNAL_MARKER + "\",\"errors\":["
                + "{\"field\":\"monthlyAmount\",\"message\":\"La mensualite est hors bornes\","
                + "\"code\":\"out-of-range\"}]}", 0);

        Response response = CsrfTestPair.withCsrf().contentType("application/json")
                .body(SIMULATE_BODY).when().post(PREFIX + "/simulations");

        assertProblem(response, 422, "unprocessable-entity");
        assertEquals(List.of(Map.of("field", "monthlyAmount",
                        "message", "La mensualite est hors bornes", "code", "out-of-range")),
                response.jsonPath().getList("errors"),
                "the simulation verdict is relayed as received, code included");
    }

    @Test
    void aDownstream5xxIsA502() {
        // InternalError fixture of the catalogue contract stub (reserved code)
        Response catalogue = given().when().get(PREFIX + "/products/@@forced-error@@");
        assertProblem(catalogue, 502, "downstream-error");
        assertNull(catalogue.jsonPath().get("errors"));

        simulationAnswers(500, "{\"title\":\"" + INTERNAL_MARKER + "\",\"status\":500}", 0);
        Response simulation = CsrfTestPair.withCsrf().contentType("application/json")
                .body(SIMULATE_BODY).when().post(PREFIX + "/simulations");
        assertProblem(simulation, 502, "downstream-error");
        assertDeclared("/simulations", "post", 502);
    }

    @Test
    void aDownstream503StaysA503WithRetryAfter() {
        DownstreamWireMocks.server("subscription").stubFor(get(urlPathEqualTo("/v1/drafts/" + DRAFT))
                .atPriority(1).willReturn(aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"title\":\"" + INTERNAL_MARKER + "\",\"status\":503}")));

        Response response = given().when().get(PREFIX + "/drafts/" + DRAFT);

        assertProblem(response, 503, "service-unavailable");
        assertEquals(String.valueOf(ProblemFactory.RETRY_AFTER_SECONDS),
                response.header("Retry-After"), "a 503 advertises Retry-After");
        assertDeclared("/drafts/{draftId}", "get", 503);
    }

    @Test
    void aDownstreamTimeoutIsA504() {
        simulationAnswers(200, SIMULATE_BODY, readTimeout("simulation") + 1500);

        Response response = CsrfTestPair.withCsrf().contentType("application/json")
                .body(SIMULATE_BODY).when().post(PREFIX + "/simulations");

        assertProblem(response, 504, "downstream-timeout");
        assertNull(response.jsonPath().get("errors"), "no rejected field on a timeout");
        assertDeclared("/simulations", "post", 504);
    }

    // ---------------------------------------------------------------------------------- helpers

    private static void simulationAnswers(int status, String body, int delayMillis) {
        var response = aResponse().withStatus(status)
                .withHeader("Content-Type", status >= 400 ? "application/problem+json"
                        : "application/json")
                .withBody(body);
        if (delayMillis > 0) {
            response = response.withFixedDelay(delayMillis);
        }
        DownstreamWireMocks.server("simulation").stubFor(post(urlPathEqualTo("/v1/simulations"))
                .atPriority(1).willReturn(response));
    }

    private static void assertProblem(Response response, int status, String slug) {
        assertEquals(status, response.statusCode(), response.asString());
        assertTrue(String.valueOf(response.contentType()).startsWith("application/problem+json"),
                "problem+json expected: " + response.contentType());
        JsonNode body = ContractDocuments.parseJson(response.asString());
        Located fieldErrors = new Located(ContractDocuments.SHARED_SCHEMAS.resolve("FieldErrors.json"),
                ContractDocuments.load(ContractDocuments.SHARED_SCHEMAS.resolve("FieldErrors.json")));
        assertEquals(List.of(), ContractDocuments.validate(fieldErrors.file(), fieldErrors.node(),
                body, "problem " + status), "the problem body diverges from FieldErrors.json");
        assertEquals(status, body.get("status").asInt(), "status member equals the HTTP status");
        assertTrue(body.get("type").asText().endsWith(slug),
                "problem type " + body.get("type") + " expected to end with " + slug);
        assertFalse(response.asString().contains(INTERNAL_MARKER),
                "no downstream text may reach the public surface: " + response.asString());
    }

    /** The public status is declared on the operation by the published document. */
    private static void assertDeclared(String path, String method, int status) {
        int port = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class).orElse(9001);
        JsonNode document = ContractDocuments.parseJson(given().port(port)
                .accept("application/json").when().get("/q/openapi")
                .then().statusCode(200).extract().asString());
        assertFalse(document.path("paths").path(PREFIX + path).path(method).path("responses")
                        .path(String.valueOf(status)).isMissingNode(),
                method.toUpperCase() + " " + PREFIX + path + " must declare " + status);
    }

    private static int readTimeout(String configKey) {
        return ConfigProvider.getConfig()
                .getValue("quarkus.rest-client." + configKey + ".read-timeout", Integer.class);
    }
}
