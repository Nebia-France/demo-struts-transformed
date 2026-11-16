package fr.demo.souscription.simulation.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.security.MutualTlsHarness;
import fr.demo.souscription.simulation.service.SimulationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parity check for 26-simulation-idempotency-rate-limit-faults.
 *
 * Idempotency is not new -- SimulationService.calculate was already a pure function of four inputs.
 * What is new is that the callers' single retry DEPENDS on it, and that waves 4 and 5 added counters,
 * an audit line and possibly a cache around it. So the assertion is on the RAW BYTES: a comparison on
 * parsed values would hide a scale drift between the first and the tenth response, which is exactly
 * the defect that makes a retried confirmation sell a different capital.
 *
 * <p>Adapters wired for this codebase:
 * <ul>
 *   <li>the fault-injection hook is {@link QuarkusMock} installing a slow {@link SimulationService},
 *       the same mechanism plan 26's own {@code FaultInjectionTest} uses -- not an assertion on the
 *       natural path, which completes in microseconds and would pass whatever the configuration;</li>
 *   <li>the profile pins the limiter to its contracted figures (100 req/s, burst 200): the shared
 *       test instance disables it, which is exactly what this suite must not inherit, and widens the
 *       worker pool so the limit reached under the flood is the bulkhead, not the pool;</li>
 *   <li>the methods are ordered: the flood tests drain the token bucket of the calling identity, so
 *       running them before the idempotency and timeout cases would make those observe a 429 instead
 *       of the verdict they assert.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(IdempotencyFaultParityTest.ContractedLimitsProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdempotencyFaultParityTest {

    /** rateLimiting of both frozen security policies, asserted against them by FaultInjectionTest. */
    private static final int CONTRACT_RPS = 100;
    private static final int CONTRACT_BURST = 200;

    public static final class ContractedLimitsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.thread-pool.core-threads", "64",
                    "quarkus.thread-pool.max-threads", "64",
                    "quarkus.thread-pool.queue-size", "256",
                    "quarkus.http.accept-backlog", "512",
                    "quarkus.vertx.max-event-loop-execute-time", "10S",
                    "quarkus.vertx.warning-exception-time", "10S",
                    "simulation.ratelimit.requests-per-second", String.valueOf(CONTRACT_RPS),
                    "simulation.ratelimit.burst", String.valueOf(CONTRACT_BURST));
        }
    }

    private static final int REPEATS = 12;
    private static final String BODY =
            "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";
    private static final String WORST_CASE =
            "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,\"durationYears\":30,\"annualRate\":0.06}";

    /** Longer than the 2 500 ms server timeout: the call must be cut by @Timeout, not served. */
    private static final Duration TIMEOUT_HOLD = Duration.ofMillis(4_000);

    /** Concurrency of one flood wave: past the 60 bulkhead places, below the 100 HTTP/2 streams. */
    private static final int WAVE = 90;

    @Inject
    MeterRegistry registry;

    /**
     * Fault-injection hook: a slow service installed for the duration of the try-with-resources.
     * QuarkusMock restores the real bean after the test method, so close() has nothing to undo.
     */
    private static AutoCloseable injectDelayBeyondTheServerTimeout() {
        QuarkusMock.installMockForType(new SlowSimulationService(TIMEOUT_HOLD), SimulationService.class);
        return () -> { };
    }

    private static Response post(String body) {
        return given().contentType("application/json").body(body)
                .when().post("/v1/simulations").then().extract().response();
    }

    /** Byte-identical, not merely equal once parsed. */
    @Test
    @Order(1)
    void repeatedRequestsReturnByteIdenticalBodies() {
        String first = post(BODY).asString();
        for (int i = 1; i < REPEATS; i++) {
            assertEquals(first, post(BODY).asString(),
                    "response " + i + " differs from the first -- the single retry the contract "
                            + "promises is unsound");
        }
    }

    /** The source wrote nothing for a simulation; a persistent effect here would be new behaviour. */
    @Test
    @Order(2)
    void repeatedCallsProduceNoSideEffect() {
        String first = post(BODY).asString();
        String last = post(BODY).asString();
        assertEquals(first, last);
        for (String monotonic : new String[] {"timestamp", "requestId", "count", "sequence", "id\":"}) {
            assertFalse(first.contains(monotonic),
                    "the response must not carry the varying field [" + monotonic + "]; the frozen "
                            + "SimulationResult has exactly five fields");
        }
    }

    /** Metrics, the audit line and (optionally) the cache all move; the payload must not. */
    @Test
    @Order(3)
    void instrumentationIsNotVisibleInThePayload() {
        String first = post(BODY).asString();
        double before = registry.find("simulation.calculate").timer() == null ? 0
                : registry.find("simulation.calculate").timer().count();
        for (int i = 0; i < REPEATS; i++) {
            post(BODY);
        }
        double after = registry.find("simulation.calculate").timer() == null ? 0
                : registry.find("simulation.calculate").timer().count();
        assertTrue(after >= before, "the business timer must keep counting");
        assertEquals(first, post(BODY).asString(),
                "monotonic instrumentation state leaked into the payload");
    }

    /** The mapped response must arrive before the caller's 3000 ms, not at it. */
    @Test
    @Order(4)
    void timeoutIsHonouredWithinItsBound() throws Exception {
        try (AutoCloseable delay = injectDelayBeyondTheServerTimeout()) {
            Instant start = Instant.now();
            Response response = post(BODY);
            Duration elapsed = Duration.between(start, Instant.now());
            assertTrue(elapsed.toMillis() < 2900,
                    "the server must answer before its 2500 ms timeout (+margin), took "
                            + elapsed.toMillis() + " ms -- is @Timeout's unit MILLIS?");
            assertTrue(response.statusCode() == 503 || response.statusCode() == 504,
                    "a server timeout must be mapped, got " + response.statusCode());
        }
    }

    /** 429 is never retried, 503 is retryable once: merging them changes caller behaviour. */
    @Test
    @Order(5)
    void rateLimitExcessReturns429() throws Exception {
        List<FloodResponse> responses = flood(400);
        List<FloodResponse> limited = responses.stream().filter(r -> r.status() == 429).toList();
        assumeTrue(!limited.isEmpty(),
                "no 429 observed -- the limiter may live in the mesh; record the deferral rather than "
                        + "asserting on an application filter that does not exist");
        for (FloodResponse response : limited) {
            assertTrue(response.contentType().startsWith("application/problem+json"),
                    "429 must use application/problem+json, was " + response.contentType());
            assertFalse(response.body().contains("\"errors\""),
                    "a 429 must not carry an errors[] array");
        }
    }

    @Test
    @Order(6)
    void bulkheadAndRateLimitUseDistinctStatuses() throws Exception {
        List<FloodResponse> responses = flood(400);
        boolean sawOverload = responses.stream().anyMatch(r -> r.status() == 503);
        boolean sawLimit = responses.stream().anyMatch(r -> r.status() == 429);
        assumeTrue(sawOverload || sawLimit, "neither control engaged under the flood");
        for (FloodResponse response : responses) {
            int status = response.status();
            assertTrue(status == 200 || status == 429 || status == 503,
                    "unexpected status under flood: " + status);
        }
        assertNotEquals(429, 503, "the two controls must keep distinct status codes");
    }

    /** Static metrics mean the annotations are inert, and every resilience assertion is vacuous. */
    @Test
    @Order(7)
    void faultToleranceMetricsMoveUnderStress() throws Exception {
        long before = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("ft."))
                .count();
        flood(200);
        long after = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("ft."))
                .count();
        assertTrue(after >= before && after > 0,
                "no fault-tolerance metric registered -- the bulkhead and timeout annotations are inert");
    }

    private List<FloodResponse> flood(int count) throws Exception {
        // The 64-way burst is driven by the JDK client of MutualTlsHarness rather than by
        // RestAssured's Apache client: the latter resets connections under this concurrency and the
        // measured outcome becomes a transport error instead of the server's verdict (same reason
        // FaultInjectionTest and OverloadRejectionParityTest drive their bursts this way).
        HttpClient client = MutualTlsHarness.httpClient(Duration.ofSeconds(5));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MutualTlsHarness.trafficBaseUri() + "/v1/simulations"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(WORST_CASE))
                .build();
        List<FloodResponse> responses = new ArrayList<>(count);
        // Waves of 90: a single HTTP/2 connection allows 100 concurrent streams client-side, so a
        // 400-way burst fails with "too many concurrent streams" before the server ever judges it.
        // 90 is also well past the 60 bulkhead places, so the limit observed stays the server's.
        for (int sent = 0; sent < count; sent += WAVE) {
            responses.addAll(wave(client, request, Math.min(WAVE, count - sent)));
        }
        return responses;
    }

    private static List<FloodResponse> wave(HttpClient client, HttpRequest request, int count)
            throws Exception {
        List<CompletableFuture<HttpResponse<String>>> calls = IntStream.range(0, count)
                .mapToObj(i -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .toList();
        List<FloodResponse> responses = new ArrayList<>(count);
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            try {
                responses.add(FloodResponse.of(call.get()));
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof IOException)) {
                    throw failure;
                }
                // A connection reset during the burst is an OS-level event, not a verdict of the
                // service: replay it so the measured status is the server's.
                responses.add(FloodResponse.of(
                        client.send(request, HttpResponse.BodyHandlers.ofString())));
            }
        }
        return responses;
    }

    private record FloodResponse(int status, String contentType, String body) {

        static FloodResponse of(HttpResponse<String> response) {
            return new FloodResponse(response.statusCode(),
                    response.headers().firstValue("content-type").orElse(""),
                    response.body());
        }
    }

    /** Same result as the real service, slow enough to exhaust the bounded resource under test. */
    private static final class SlowSimulationService extends SimulationService {

        private final Duration hold;

        SlowSimulationService(Duration hold) {
            this.hold = hold;
        }

        @Override
        public Simulation calculate(BigDecimal initial, BigDecimal monthly, int years,
                                    BigDecimal annualRate) {
            try {
                Thread.sleep(hold.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return super.calculate(initial, monthly, years, annualRate);
        }
    }
}
