package fr.demo.souscription.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Parity check 23 — parallel fan-out and per-dependency bulkheads.
 *
 * <p>The source composed its calls in-process at microsecond latency, so chaining them cost
 * nothing. Every call is now remote: a chained pair doubles the user-visible latency while passing
 * every functional test, and an unbounded demand on one slow dependency would consume the whole
 * gateway capacity (CFG-007, AG-016).
 *
 * <p>The confirmation path legitimately keeps <strong>two</strong> serial remote calls (the
 * subscription write, then the remote simulation performed downstream, per the confirmed topology
 * decision). That cost is recorded in {@code docs/latency-budget.md} and is not a defect; what is
 * verified here is that the <em>gateway</em> adds no third serial hop of its own.
 */
@QuarkusTest
@TestProfile(FanOutParityTest.NarrowSimulationBulkhead.class)
class FanOutParityTest {

    /** Narrows the simulation bulkhead so saturation is observable without touching production sizes. */
    public static class NarrowSimulationBulkhead implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "fr.demo.souscription.gateway.client.SimulationGateway/compute/Bulkhead/value",
                    "1",
                    "fr.demo.souscription.gateway.client.SimulationGateway"
                            + "/compute/Bulkhead/waitingTaskQueue", "1");
        }
    }

    private static final int STUB_DELAY_MS = 600;

    private static final String DRAFT_ID = "11111111-1111-1111-1111-111111111111";

    private static final String CONFIRMATION_KEY = "22222222-2222-2222-2222-222222222222";

    private static final String DRAFT_BODY = "{\"productCode\":\"PRUDENT\"}";

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

    /** Step 1 — two independent remote calls cost about max(delays), not their sum. */
    @Test
    void independentCallsRunInParallelNotSequentially() {
        catalogue().stubFor(get(urlPathEqualTo("/v1/products/PRUDENT")).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(STUB_DELAY_MS)
                        .withBody("{\"code\":\"PRUDENT\",\"name\":\"Horizon Prudent\","
                                + "\"profile\":\"Prudent\",\"annualRate\":\"0.020000\"}")));
        subscription().stubFor(post(urlPathEqualTo("/v1/drafts")).atPriority(1)
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(STUB_DELAY_MS)
                        .withBody("{\"draftId\":\"" + DRAFT_ID + "\",\"product\":{\"code\":\"PRUDENT\","
                                + "\"name\":\"Horizon Prudent\",\"profile\":\"Prudent\","
                                + "\"annualRate\":\"0.020000\"},\"confirmationKey\":\""
                                + CONFIRMATION_KEY + "\",\"status\":\"STARTED\"}")));

        // Warm the route so the measurement is not polluted by first-call initialisation.
        CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(DRAFT_BODY)
                .when().post("/api/v1/drafts").then().statusCode(201);

        long startedAt = System.nanoTime();
        try {
            // R1b: the tuple mapping must also be right — a parallel composition returning the
            // wrong field is worse than a slow one.
            CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(DRAFT_BODY)
                    .when().post("/api/v1/drafts")
                    .then().statusCode(201)
                    .body("draftId", org.hamcrest.Matchers.equalTo(DRAFT_ID))
                    .body("product.code", org.hamcrest.Matchers.equalTo("PRUDENT"))
                    .body("confirmationKey", org.hamcrest.Matchers.equalTo(CONFIRMATION_KEY));
        } finally {
            catalogue().resetToDefaultMappings();
            subscription().resetToDefaultMappings();
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(elapsedMillis < 2 * STUB_DELAY_MS - 150,
                "two independent " + STUB_DELAY_MS + " ms calls must cost ~max, not ~sum; measured "
                        + elapsedMillis + " ms. A chained flatMap passes every functional test and"
                        + " only shows up as doubled production latency.");
    }

    /** Step 5 — a slow, saturated simulation edge does not degrade the catalogue endpoint. */
    @Test
    void aSlowDependencyDoesNotDegradeTheOtherEndpoints() throws Exception {
        simulation().stubFor(post(urlPathEqualTo("/v1/simulations")).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(1500)
                        .withBody("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                                + "\"durationYears\":5,\"contributed\":\"7000.00\","
                                + "\"finalCapital\":\"7500.00\"}")));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<CompletableFuture<Integer>> inflight = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                inflight.add(CompletableFuture.supplyAsync(() ->
                        CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(SIMULATION_BODY)
                                .when().post("/api/v1/simulations").getStatusCode(), pool));
            }

            long startedAt = System.nanoTime();
            Response products = given().when().get("/api/v1/products");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals(200, products.getStatusCode(),
                    "the catalogue endpoint must keep answering while the simulation edge is"
                            + " saturated (per-dependency bulkhead)");
            assertTrue(elapsedMillis < 2_000,
                    "the catalogue endpoint must stay within its budget while the simulation edge"
                            + " is saturated; measured " + elapsedMillis + " ms");

            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<Integer> call : inflight) {
                statuses.add(call.get(30, TimeUnit.SECONDS));
            }
            assertTrue(statuses.contains(503),
                    "beyond value + waitingTaskQueue the bulkhead must refuse the excess at once"
                            + " (503) rather than queue it without bound; statuses were " + statuses);
        } finally {
            pool.shutdownNow();
            simulation().resetToDefaultMappings();
        }
    }

    /**
     * Step 3 — the confirmation path keeps at most two serial remote calls: one issued by the
     * gateway, plus the remote simulation performed by souscription-service. The gateway must add
     * no third hop, and the rule must be written down in the latency budget.
     */
    @Test
    void confirmationPathKeepsAtMostTwoSerialRemoteCalls() throws Exception {
        catalogue().resetRequests();
        simulation().resetRequests();
        subscription().resetRequests();

        CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .header("Idempotency-Key", CONFIRMATION_KEY)
                .body("{\"confirmationKey\":\"" + CONFIRMATION_KEY + "\"}")
                .when().post("/api/v1/drafts/{draftId}/confirmation", DRAFT_ID)
                .then().statusCode(org.hamcrest.Matchers.oneOf(200, 201));

        long gatewayCalls = subscription().getAllServeEvents().size()
                + catalogue().getAllServeEvents().size()
                + simulation().getAllServeEvents().size();
        assertEquals(1L, gatewayCalls,
                "the gateway must issue a single remote call on the confirmation path; the second"
                        + " serial call is the remote simulation performed downstream. A third hop"
                        + " must be justified in docs/latency-budget.md.");

        String budget = Files.readString(Path.of("docs", "latency-budget.md"));
        assertTrue(budget.contains("2 appels distants en série"),
                "docs/latency-budget.md must state the 'at most 2 serial remote calls' rule for the"
                        + " confirmation path, together with the decision to keep the simulation"
                        + " remote; without it a later plan silently adds a third hop.");
    }
}
