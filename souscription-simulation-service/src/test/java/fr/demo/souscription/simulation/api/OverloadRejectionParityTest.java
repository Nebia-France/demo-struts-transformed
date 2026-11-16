package fr.demo.souscription.simulation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.service.SimulationService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import fr.demo.souscription.simulation.security.MutualTlsHarness;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity check for plan 18: overload is rejected cleanly, and only overload.
 *
 * <p>The source monolith computed the projection in process, so it could neither time out nor
 * fast-reject. The new 503 path must therefore never fire for a request the source would have
 * served, and the server budget must stay strictly below the caller timeout of the frozen
 * contracts so the caller observes the server's 503 rather than its own transport error.
 */
@QuarkusTest
@TestProfile(OverloadRejectionParityTest.LargePoolProfile.class)
class OverloadRejectionParityTest {

    /**
     * The production pool (16 threads) is narrower than the bulkhead (20), so the first limit
     * reached would be the pool, not the contractual bulkhead. Widened here, exactly as in
     * {@link SimulationOverloadTest}.
     */
    public static final class LargePoolProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.thread-pool.core-threads", "64",
                    "quarkus.thread-pool.max-threads", "64",
                    "quarkus.thread-pool.queue-size", "256",
                    // The saturation burst opens more connections at once than the default accept
                    // backlog (128) holds: the kernel resets the excess and the client observes an
                    // IOException instead of the server's verdict. Widened as in
                    // SimulationOverloadTest so the measured limit stays the bulkhead.
                    "quarkus.http.accept-backlog", "512",
                    "quarkus.vertx.max-event-loop-execute-time", "10S",
                    "quarkus.vertx.warning-exception-time", "10S");
        }
    }

    /** Service-side bulkhead of the frozen contract: 20 concurrent + 40 queued = 60 places. */
    private static final int EXPECTED_CONCURRENT_CALLS = 20;
    private static final int EXPECTED_WAITING_QUEUE = 40;
    private static final long EXPECTED_SERVER_TIMEOUT_MILLIS = 2500L;
    /**
     * Well beyond the 60 places, yet below the 100 concurrent streams a single HTTP/2 connection
     * allows client-side, so the limit observed is the server bulkhead.
     */
    private static final int CONCURRENT_CALLS = 90;
    private static final Duration HOLD = Duration.ofMillis(600);

    private static final String VALID_BODY =
            "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,\"durationYears\":30,\"annualRate\":0.06}";
    /** durationYears = 0 violates @Min(1): a deterministic 400 in the source. */
    private static final String INVALID_BODY =
            "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,\"durationYears\":0,\"annualRate\":0.06}";

    @BeforeEach
    void installSlowService() {
        QuarkusMock.installMockForType(new SlowSimulationService(), SimulationService.class);
    }

    @Test
    void bulkheadMatchesTheFrozenServiceSideContract() throws Exception {
        Bulkhead bulkhead = computeMethod().getAnnotation(Bulkhead.class);
        assertTrue(bulkhead != null, "compute must carry @Bulkhead");
        assertEquals(EXPECTED_CONCURRENT_CALLS, bulkhead.value(),
                "the service-side bulkhead is 20 (the gateway's 30 is a caller-side allowance)");
        assertEquals(EXPECTED_WAITING_QUEUE, bulkhead.waitingTaskQueue(),
                "the waiting queue must stay at the contract bound of 40, never unbounded");
    }

    @Test
    void serverTimeoutIsStrictlyBelowTheCallerTimeout() throws Exception {
        Timeout timeout = computeMethod().getAnnotation(Timeout.class);
        assertTrue(timeout != null, "compute must carry @Timeout");
        assertEquals(ChronoUnit.MILLIS, timeout.unit(),
                "the unit must be explicit: the default SECONDS would make 2500 a 2 500 s no-op");
        assertEquals(EXPECTED_SERVER_TIMEOUT_MILLIS, timeout.value(),
                "the server budget is 2 500 ms");

        long callerTimeoutMillis = callerTimeoutMillis();
        assertTrue(timeout.value() < callerTimeoutMillis,
                "the server timeout (" + timeout.value() + " ms) must be strictly below the caller"
                        + " timeout (" + callerTimeoutMillis + " ms); equality makes both sides fire");
    }

    @Test
    void overloadBodyCarriesNoFieldErrors() throws Exception {
        List<HttpResponse<String>> rejected = fire(VALID_BODY).stream()
                .filter(response -> response.statusCode() == 503)
                .toList();
        assertTrue(!rejected.isEmpty(),
                "no rejection observed while " + CONCURRENT_CALLS + " concurrent calls exceed the "
                        + (EXPECTED_CONCURRENT_CALLS + EXPECTED_WAITING_QUEUE) + " places");

        for (HttpResponse<String> response : rejected) {
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType.startsWith("application/problem+json"),
                    "overload must be a problem+json, was [" + contentType + "]");
            String payload = response.body();
            assertTrue(payload.contains("\"status\":503") && payload.contains("\"title\"")
                            && payload.contains("\"type\""),
                    "the overload document must carry type, title and status 503: " + payload);
            assertTrue(!payload.contains("\"errors\""),
                    "errors[] is the validation signal; a capacity event must not carry it: "
                            + payload);
        }
    }

    @Test
    void bulkheadNeverReturns429() throws Exception {
        List<Integer> statuses = fire(VALID_BODY).stream().map(HttpResponse::statusCode).toList();
        assertTrue(statuses.stream().noneMatch(status -> status == 429),
                "429 is reserved for the rate limiter (retry rules differ): " + statuses);
        assertTrue(statuses.stream().noneMatch(status -> status == 500),
                "overload is not a bug: " + statuses);
        assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 503),
                "unexpected statuses under load: " + statuses);
    }

    @Test
    void invalidInputStillGets400UnderLoad() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> load = submit(client(), VALID_BODY);
        try {
            // The capacity is saturated by the slow valid calls; the invalid body must still be
            // judged on its content, not on the service load.
            HttpResponse<String> response = sendWithConnectionRetry(INVALID_BODY);
            assertEquals(400, response.statusCode(),
                    "an invalid body must stay a deterministic 400 under load, body: "
                            + response.body());
            assertTrue(response.body().contains("\"errors\""),
                    "a validation verdict keeps its errors[] array: " + response.body());
        } finally {
            // The background load only exists to saturate the capacity; its own transport
            // outcomes are asserted by the other tests.
            load.forEach(call -> call.exceptionally(failure -> null).join());
        }
    }

    private static Method computeMethod() throws NoSuchMethodException {
        return SimulationResource.class.getMethod("compute", SimulationRequest.class,
                String.class, String.class);
    }

    /** Reads the caller timeout from the frozen contract rather than restating it. */
    private static long callerTimeoutMillis() throws IOException {
        String relative = "global/contracts/"
                + "souscription-service-to-souscription-simulation-service/resilience-contract.json";
        Path contract = Path.of(relative);
        if (!Files.exists(contract)) {
            contract = Path.of("..").resolve(relative).normalize();
        }
        assertTrue(Files.exists(contract), "frozen contract not found: " + contract.toAbsolutePath());
        String json = Files.readString(contract);
        Matcher matcher = Pattern.compile("\"timeout\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*\"(\\d+)(ms|s)\"")
                .matcher(json);
        assertTrue(matcher.find(), "no timeout.value in the frozen contract");
        long value = Long.parseLong(matcher.group(1));
        return "s".equals(matcher.group(2)) ? value * 1000L : value;
    }

    private static HttpClient client() {
        // TLS 1.3 with client-auth required on the traffic port (W6-T01).
        return MutualTlsHarness.httpClient(Duration.ofSeconds(5));
    }

    private static HttpRequest request(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(MutualTlsHarness.trafficBaseUri() + "/v1/simulations"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static List<CompletableFuture<HttpResponse<String>>> submit(HttpClient client,
                                                                        String body) {
        HttpRequest request = request(body);
        return IntStream.range(0, CONCURRENT_CALLS)
                .mapToObj(i -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .toList();
    }

    /**
     * A connection reset or EOF during the saturation burst is not a verdict from the service: it
     * is an OS-level accept failure on a connection the server never answered, not a bulkhead
     * rejection. The call is replayed on a fresh connection so the assertion is made on a real HTTP
     * status rather than on a system hazard. Same treatment as {@link SimulationOverloadTest}.
     */
    private static HttpResponse<String> sendWithConnectionRetry(String body) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return client().send(request(body), HttpResponse.BodyHandlers.ofString());
            } catch (IOException transportFailure) {
                last = transportFailure;
                Thread.sleep(200L * (attempt + 1));
            }
        }
        throw last;
    }

    private static HttpResponse<String> awaitWithConnectionRetry(
            CompletableFuture<HttpResponse<String>> call) throws Exception {
        try {
            return call.get();
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof IOException)) {
                throw failure;
            }
            return sendWithConnectionRetry(VALID_BODY);
        }
    }

    private static List<HttpResponse<String>> drain(
            List<CompletableFuture<HttpResponse<String>>> calls) throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>(calls.size());
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            responses.add(awaitWithConnectionRetry(call));
        }
        return responses;
    }

    private static List<HttpResponse<String>> fire(String body) throws Exception {
        return drain(submit(client(), body));
    }

    /** Same result as the real service, slow enough to saturate the bounded capacity. */
    private static final class SlowSimulationService extends SimulationService {
        @Override
        public Simulation calculate(BigDecimal initial, BigDecimal monthly, int years,
                                    BigDecimal annualRate) {
            try {
                Thread.sleep(HOLD.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return super.calculate(initial, monthly, years, annualRate);
        }
    }
}
