package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
 * Per-edge fault tolerance policies (W5-T01), asserted on the real HTTP surface and on the WireMock
 * request journal.
 *
 * <p>Four properties are locked here, each of which the source could not express (the web tier
 * called the business tier in-process and could not fail partially):
 *
 * <ol>
 *   <li>a timeout fires at the configured bound instead of letting the caller wait on the
 *       dependency;
 *   <li>the bounded retry is applied on the idempotent catalogue read, and <strong>not</strong> on
 *       the confirmation write — the WireMock journal must show exactly one POST;
 *   <li>the breaker opens once the volume and ratio thresholds are reached, and closes again after
 *       its delay;
 *   <li>the fallback degrades the catalogue to 503 + {@code Retry-After} and is declared nowhere
 *       else: a simulation failure still surfaces as 502, unmasked.
 * </ol>
 *
 * <p>The methods are ordered because the circuit breaker is stateful: the retry assertion runs
 * while the breaker is still closed, and the breaker assertions afterwards.
 */
@QuarkusTest
@TestProfile(FaultTolerancePolicyTest.LowBreakerThresholds.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FaultTolerancePolicyTest {

    /**
     * Lowers the catalogue breaker thresholds so the open/close transition is observable in a test,
     * which is exactly what the config-overridability of step 6 is for: the production values
     * (20 requests, 10 s) are untouched.
     */
    public static class LowBreakerThresholds implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "fr.demo.souscription.gateway.client.CatalogueGateway"
                            + "/CircuitBreaker/requestVolumeThreshold", "4",
                    "fr.demo.souscription.gateway.client.CatalogueGateway"
                            + "/CircuitBreaker/failureRatio", "0.5",
                    "fr.demo.souscription.gateway.client.CatalogueGateway"
                            + "/CircuitBreaker/delay", "1000",
                    "fr.demo.souscription.gateway.client.CatalogueGateway"
                            + "/detail/Timeout/value", "300",
                    "fr.demo.souscription.gateway.client.SimulationGateway"
                            + "/compute/Timeout/value", "30000");
        }
    }

    /** Reserved code the catalogue double answers with a 500 (contract response InternalError). */
    private static final String FORCED_ERROR_CODE = "@@forced-error@@";

    private static final String SLOW_DRAFT_ID = "44444444-4444-4444-4444-444444444444";

    private static final String CONFIRMATION_KEY = "22222222-2222-2222-2222-222222222222";

    private static final String SIMULATE = "/api/v1/simulations";

    private static final String SIMULATION_PATH = "/v1/simulations";

    private static final String SIMULATION_BODY = "{\"productCode\":\"EQUILIBRE\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}";

    private static WireMockServer catalogue() {
        return DownstreamWireMocks.server("catalogue");
    }

    private static WireMockServer subscription() {
        return DownstreamWireMocks.server("subscription");
    }

    private static WireMockServer simulation() {
        return DownstreamWireMocks.server("simulation");
    }

    /**
     * The catalogue read is idempotent, so the declared two retries are applied: the dependency
     * sees three attempts for one public call, and the public answer stays the 502 of the frozen
     * mapping (a downstream 5xx is not a reason to degrade silently).
     */
    @Test
    @Order(1)
    void theBoundedRetryIsAppliedOnTheIdempotentCatalogueRead() {
        catalogue().resetRequests();

        given().when().get("/api/v1/products/" + FORCED_ERROR_CODE)
                .then().statusCode(502);

        assertEquals(1 + configuredRetries(), catalogue().getServeEvents().getServeEvents().size(),
                "one public read must produce 1 + maxRetries attempts on the dependency");
    }

    /**
     * The confirmation write carries a timeout and nothing else. The timeout fires at its configured
     * bound — before the 7 s the dependency would need — and the journal shows a single POST: a
     * transparent retry on a write whose outcome is unknown could duplicate a user action.
     */
    @Test
    @Order(2)
    void theTimeoutFiresAtItsBoundAndTheConfirmationWriteIsNeverRetried() {
        subscription().resetRequests();
        subscription().resetScenarios();
        int bound = configuredConfirmationTimeout();

        long startedAt = System.nanoTime();
        CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", CONFIRMATION_KEY))
                .when().post("/api/v1/drafts/" + SLOW_DRAFT_ID + "/confirmation")
                .then().statusCode(504);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(elapsedMillis < 6_500,
                "the timeout must fire at its " + bound + " ms bound, waited " + elapsedMillis);
        subscription().verify(1, postRequestedFor(
                urlPathEqualTo("/v1/drafts/" + SLOW_DRAFT_ID + "/confirmation")));
    }

    /**
     * Once the volume and ratio thresholds are reached the catalogue breaker opens, and the
     * rejection is the degraded 503 + {@code Retry-After} of the fallback rather than a transport
     * exception. After the breaker delay a healthy read closes it again.
     *
     * <p>The failures are produced by a catalogue slower than the timeout, the breaker counting
     * exactly that: a dependency unreachable or too slow, not a dependency answering an error
     * promptly.
     */
    @Test
    @Order(3)
    void theBreakerOpensOnTheFailingCatalogueThenClosesAfterItsDelay() throws InterruptedException {
        catalogue().stubFor(get(urlPathEqualTo("/v1/products/EQUILIBRE")).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(5 * configuredDetailTimeout())
                        .withBody("{\"code\":\"EQUILIBRE\"}")));

        Response rejected = null;
        try {
            for (int attempt = 0; attempt < 8 && rejected == null; attempt++) {
                Response response = given().when().get("/api/v1/products/EQUILIBRE");
                if (response.statusCode() == 503) {
                    rejected = response;
                }
            }
        } finally {
            catalogue().resetToDefaultMappings();
        }

        assertNotNull(rejected, "the breaker must open once the threshold is reached");
        assertEquals(503, rejected.statusCode());
        assertTrue(String.valueOf(rejected.contentType()).contains("problem+json"));
        assertNotNull(rejected.header("Retry-After"),
                "an open circuit must advertise Retry-After");

        Thread.sleep(configuredBreakerDelay() + 500L);

        given().when().get("/api/v1/products/PRUDENT")
                .then().statusCode(200)
                .body("code", org.hamcrest.Matchers.is("PRUDENT"));
    }

    /**
     * The fallback is declared on the catalogue only: a failing simulation still surfaces as the
     * 502 of the frozen mapping. A fallback here would hand the subscriber invented figures.
     */
    @Test
    @Order(4)
    void noFallbackMasksASimulationFailure() {
        simulation().stubFor(post(urlPathEqualTo(SIMULATION_PATH)).atPriority(1)
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":500}")));
        try {
            CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(SIMULATION_BODY)
                    .when().post(SIMULATE)
                    .then().statusCode(502);
        } finally {
            simulation().resetToDefaultMappings();
        }
    }

    private static int configuredRetries() {
        return ConfigProvider.getConfig().getValue(
                "fr.demo.souscription.gateway.client.CatalogueGateway/detail/Retry/maxRetries",
                Integer.class);
    }

    private static int configuredConfirmationTimeout() {
        return ConfigProvider.getConfig().getValue(
                "fr.demo.souscription.gateway.client.SubscriptionGateway/confirm/Timeout/value",
                Integer.class);
    }

    private static int configuredDetailTimeout() {
        return ConfigProvider.getConfig().getValue(
                "fr.demo.souscription.gateway.client.CatalogueGateway/detail/Timeout/value",
                Integer.class);
    }

    private static long configuredBreakerDelay() {
        return ConfigProvider.getConfig().getValue(
                "fr.demo.souscription.gateway.client.CatalogueGateway/CircuitBreaker/delay",
                Long.class);
    }
}
