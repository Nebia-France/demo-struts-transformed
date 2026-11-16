package fr.demo.souscription.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Parallel fan-out and bulkhead isolation (W5-T03).
 *
 * <p>The source called the business tier in-process, so chaining calls cost nothing. Every call is
 * now remote, and two properties are locked here:
 *
 * <ol>
 *   <li>the independent downstream calls of {@code POST /api/v1/drafts} are composed in parallel:
 *       with both doubles delayed, the route costs about {@code max(delay1, delay2)} and not their
 *       sum;
 *   <li>a saturated and slow simulation edge is isolated by its own bulkhead: the excess calls are
 *       refused at once (503, never an unbounded queue) and the catalogue keeps answering within
 *       its budget.
 * </ol>
 *
 * <p>The per-endpoint call count and the "at most 2 serial remote calls on the confirmation path"
 * rule are recorded in {@code docs/latency-budget.md}.
 */
@QuarkusTest
@TestProfile(FanOutTest.NarrowSimulationBulkhead.class)
class FanOutTest {

    /**
     * Narrows the simulation bulkhead so the saturation is observable in a test; this is exactly
     * what the config-overridability of the bulkhead values is for, the production sizes (20 / 20)
     * staying untouched in {@code application.properties}.
     */
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

    private static final long DELAY_MILLIS = 800;

    private static final String DRAFT_BODY = "{\"productCode\":\"PRUDENT\"}";

    private static final String SIMULATION_BODY = "{\"productCode\":\"EQUILIBRE\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}";

    private static final String DRAFT_PAYLOAD = "{\"draftId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"product\":{\"code\":\"PRUDENT\",\"name\":\"Horizon Prudent\",\"profile\":\"Prudent\","
            + "\"annualRate\":\"0.020000\"},"
            + "\"confirmationKey\":\"22222222-2222-2222-2222-222222222222\",\"status\":\"STARTED\"}";

    private static WireMockServer catalogue() {
        return DownstreamWireMocks.server("catalogue");
    }

    private static WireMockServer simulation() {
        return DownstreamWireMocks.server("simulation");
    }

    private static WireMockServer subscription() {
        return DownstreamWireMocks.server("subscription");
    }

    /** The two independent calls of the draft start are issued concurrently, not one after the other. */
    @Test
    void theIndependentCallsOfTheDraftStartAreComposedInParallel() {
        catalogue().stubFor(get(urlPathEqualTo("/v1/products/PRUDENT")).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay((int) DELAY_MILLIS)
                        .withBody("{\"code\":\"PRUDENT\",\"name\":\"Horizon Prudent\","
                                + "\"profile\":\"Prudent\",\"annualRate\":\"0.020000\"}")));
        subscription().stubFor(post(urlPathEqualTo("/v1/drafts")).atPriority(1)
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay((int) DELAY_MILLIS)
                        .withBody(DRAFT_PAYLOAD)));

        // Warm the route first: a cold connection pool costs a reconnection on one of the two
        // edges, which a retry turns into an extra serial call and makes the measurement read as a
        // sum. The parity check of the same property warms the route the same way.
        CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(DRAFT_BODY)
                .when().post("/api/v1/drafts").then().statusCode(201);

        long startedAt = System.nanoTime();
        try {
            CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(DRAFT_BODY)
                    .when().post("/api/v1/drafts")
                    .then().statusCode(201);
        } finally {
            catalogue().resetToDefaultMappings();
            subscription().resetToDefaultMappings();
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(elapsedMillis < 2 * DELAY_MILLIS - 100,
                "the two independent calls must be composed in parallel: expected about "
                        + DELAY_MILLIS + " ms (the max), not " + (2 * DELAY_MILLIS)
                        + " ms (the sum); measured " + elapsedMillis + " ms");
    }

    /**
     * The simulation edge is saturated by calls slower than its bulkhead can absorb. The excess is
     * refused at once rather than queued without bound, and the catalogue — a distinct bulkhead —
     * keeps answering within its own budget.
     */
    @Test
    void aSlowAndSaturatedSimulationDoesNotDegradeTheCatalogue() throws Exception {
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

            // While the simulation edge is saturated, the catalogue edge answers normally.
            long startedAt = System.nanoTime();
            Response products = given().when().get("/api/v1/products");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertTrue(products.getStatusCode() == 200,
                    "a saturated simulation edge must not degrade the catalogue endpoint, got "
                            + products.getStatusCode());
            assertTrue(elapsedMillis < 1_000,
                    "the catalogue endpoint must stay within its budget while the simulation edge"
                            + " is saturated; measured " + elapsedMillis + " ms");

            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<Integer> call : inflight) {
                statuses.add(call.get(30, TimeUnit.SECONDS));
            }
            assertTrue(statuses.contains(503),
                    "beyond value + waitingTaskQueue the bulkhead must refuse the excess at once"
                            + " (503), never queue it without bound; statuses were " + statuses);
        } finally {
            pool.shutdownNow();
            simulation().resetToDefaultMappings();
        }
    }
}
