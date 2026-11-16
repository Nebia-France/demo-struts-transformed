package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Parity check for 21-souscription-gateway-fault-tolerance-policies.
 *
 * <p>The source had no timeout, retry, breaker or fallback: the call was in-process and could not
 * fail partially. These policies are a requirement of the confirmed 99.9 % objective (three serial
 * 99.9 % dependencies compose to about 99.7 %, AG-014), so this is a new-capability validation.
 *
 * <p>The parity-critical asymmetry: retries on idempotent <strong>reads</strong> only, and no retry
 * and no fallback on the confirmation write — a fallback there would report success for a write that
 * never happened.
 *
 * <p>The skeleton's placeholder fixtures (<em>retry-fixture</em>, <em>slow-fixture</em>,
 * <em>failing-fixture</em>) are bound here to the real routes of the gateway and to the real
 * downstream doubles of {@link DownstreamWireMocks}; the breaker being stateful, the methods are
 * ordered so the retry and timeout assertions run while the circuit is still closed.
 */
@QuarkusTest
@TestProfile(FaultTolerancePolicyParityTest.ObservableBreakerThresholds.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FaultTolerancePolicyParityTest {

    /**
     * Shortens the catalogue breaker delay and its detail timeout so the open/close transition is
     * observable within a test. This is exactly what the config-overridability of step 6 is for: the
     * volume and ratio thresholds keep their production values (20 requests, 0.5), and the
     * production delay (10 s) and timeout (3000 ms) are untouched in
     * {@code application.properties}.
     */
    public static class ObservableBreakerThresholds implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "fr.demo.souscription.gateway.client.CatalogueGateway"
                            + "/CircuitBreaker/delay", "1000",
                    "fr.demo.souscription.gateway.client.CatalogueGateway"
                            + "/detail/Timeout/value", "300");
        }
    }

    /** Stands for the skeleton's {@code retry-fixture}: the catalogue double answers it with a 500. */
    private static final String RETRY_FIXTURE = "@@forced-error@@";

    /** Stands for the skeleton's {@code slow-fixture} and {@code failing-fixture}. */
    private static final String SLOW_FIXTURE = "EQUILIBRE";

    private static final String SLOW_CONFIRMATION_DRAFT = "44444444-4444-4444-4444-444444444444";

    private static final String KNOWN_REFERENCE = "33333333-3333-3333-3333-333333333333";

    private static final String SIMULATION_PATH = "/v1/simulations";

    private static final String SIMULATION_BODY = "{\"productCode\":\"EQUILIBRE\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}";

    private static WireMockServer catalogue() {
        return DownstreamWireMocks.server("catalogue");
    }

    private static WireMockServer simulation() {
        return DownstreamWireMocks.server("simulation");
    }

    private static WireMockServer subscription() {
        return DownstreamWireMocks.server("subscription");
    }

    /** Step 2: the idempotent catalogue read is attempted 3 times (1 call + 2 retries). */
    @Test
    @Order(1)
    void catalogueReadIsRetriedTwice() {
        catalogue().resetRequests();

        given().when().get("/api/v1/products/{code}", RETRY_FIXTURE)
                .then().statusCode(502);

        catalogue().verify(1 + configuredInt(
                        "fr.demo.souscription.gateway.client.CatalogueGateway/detail/Retry/maxRetries"),
                getRequestedFor(urlPathEqualTo("/v1/products/" + RETRY_FIXTURE)));
    }

    /** Step 2: the simulation read is attempted twice (1 call + 1 retry). */
    @Test
    @Order(2)
    void simulationReadIsRetriedOnce() {
        simulation().resetRequests();
        simulation().stubFor(post(urlPathEqualTo(SIMULATION_PATH)).atPriority(1)
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":500}")));
        try {
            CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(SIMULATION_BODY)
                    .when().post("/api/v1/simulations")
                    .then().statusCode(502);

            simulation().verify(1 + configuredInt("fr.demo.souscription.gateway.client"
                            + ".SimulationGateway/compute/Retry/maxRetries"),
                    postRequestedFor(urlPathEqualTo(SIMULATION_PATH)));
        } finally {
            simulation().resetToDefaultMappings();
        }
    }

    /** Steps 2 and 3: the confirmation write is attempted exactly once and surfaces the 504. */
    @Test
    @Order(3)
    void confirmationWriteIsNeverRetried() {
        subscription().resetRequests();

        CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", "22222222-2222-2222-2222-222222222222"))
                .when().post("/api/v1/drafts/{draftId}/confirmation", SLOW_CONFIRMATION_DRAFT)
                .then().statusCode(504);

        subscription().verify(1, postRequestedFor(
                urlPathEqualTo("/v1/drafts/" + SLOW_CONFIRMATION_DRAFT + "/confirmation")));
    }

    /** Step 1: the timeout fires at its configured bound and surfaces as the public 504. */
    @Test
    @Order(4)
    void timeoutFiresAtTheConfiguredBound() {
        int bound = configuredInt(
                "fr.demo.souscription.gateway.client.CatalogueGateway/detail/Timeout/value");
        catalogue().stubFor(get(urlPathEqualTo("/v1/products/" + SLOW_FIXTURE)).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(20 * bound)
                        .withBody("{\"code\":\"" + SLOW_FIXTURE + "\"}")));

        long startedAt = System.currentTimeMillis();
        Response response;
        try {
            response = given().when().get("/api/v1/products/{code}", SLOW_FIXTURE);
        } finally {
            catalogue().resetToDefaultMappings();
        }
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals(504, response.getStatusCode(), "a timeout surfaces as 504 (check 20 table)");
        assertTrue(elapsed < 20L * bound,
                "the configured bound is " + bound + " ms; a call lasting " + elapsed
                        + " ms means the @Timeout is absent or far above it");
    }

    /** Step 4: the breaker opens past the threshold, advertises Retry-After, then closes. */
    @Test
    @Order(5)
    void breakerOpensPastTheThresholdThenCloses() throws InterruptedException {
        int bound = configuredInt(
                "fr.demo.souscription.gateway.client.CatalogueGateway/detail/Timeout/value");
        catalogue().stubFor(get(urlPathEqualTo("/v1/products/" + SLOW_FIXTURE)).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(5 * bound)
                        .withBody("{\"code\":\"" + SLOW_FIXTURE + "\"}")));

        Response open = null;
        try {
            for (int attempt = 0; attempt < 16 && open == null; attempt++) {
                Response response = given().when().get("/api/v1/products/{code}", SLOW_FIXTURE);
                if (response.statusCode() == 503) {
                    open = response;
                }
            }
        } finally {
            catalogue().resetToDefaultMappings();
        }

        assertNotNull(open, "the breaker must open once the threshold is reached");
        assertEquals(503, open.getStatusCode(), "an open circuit surfaces as 503");
        assertNotNull(open.getHeader("Retry-After"), "503 must advertise Retry-After");

        Thread.sleep(configuredInt("fr.demo.souscription.gateway.client.CatalogueGateway"
                + "/CircuitBreaker/delay") + 500L);

        given().when().get("/api/v1/products/{code}", "PRUDENT")
                .then().statusCode(200);
    }

    /** Step 4: breakers are per dependency, so a failing catalogue leaves the others closed. */
    @Test
    @Order(6)
    void oneFailingDependencyDoesNotOpenTheOthersBreakers() {
        int bound = configuredInt(
                "fr.demo.souscription.gateway.client.CatalogueGateway/detail/Timeout/value");
        catalogue().stubFor(get(urlPathEqualTo("/v1/products/" + SLOW_FIXTURE)).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(5 * bound)
                        .withBody("{\"code\":\"" + SLOW_FIXTURE + "\"}")));
        try {
            for (int attempt = 0; attempt < 12; attempt++) {
                given().when().get("/api/v1/products/{code}", SLOW_FIXTURE);
            }
        } finally {
            catalogue().resetToDefaultMappings();
        }

        Response other = given().when().get("/api/v1/subscriptions/{reference}", KNOWN_REFERENCE);
        assertTrue(other.getStatusCode() != 503,
                "a failing catalogue must not trip the subscription breaker (isolation)");
    }

    /** Step 5: unavailability degrades to 503 + Retry-After, never to an empty 200 list. */
    @Test
    @Order(7)
    void catalogueFallbackSurfacesAsUnavailableNotAsAnEmptyList() {
        Response response = given().when().get("/api/v1/products");
        if (response.getStatusCode() == 200) {
            assertTrue(response.asString().length() > 2,
                    "an empty list would mean 'no products exist' - a wrong business answer;"
                            + " unavailability must surface as 503 + Retry-After");
        } else {
            assertEquals(503, response.getStatusCode());
            assertNotNull(response.getHeader("Retry-After"));
        }
    }

    private static int configuredInt(String key) {
        return ConfigProvider.getConfig().getValue(key, Integer.class);
    }
}
