package fr.demo.souscription.simulation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.security.MutualTlsHarness;
import fr.demo.souscription.simulation.service.SimulationService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * W6-T05: fault injection against the three bounded resources of the frozen contracts.
 *
 * <p>Each degradation has one contracted answer, and they are not interchangeable because their
 * retry rules differ:
 * <ul>
 *   <li>a call exceeding the <strong>server timeout</strong> (2 500 ms) is answered before the
 *       caller's own 3 s timeout fires, so the caller observes the server's verdict rather than a
 *       transport error;</li>
 *   <li><strong>bulkhead</strong> overflow is {@code 503} - retryable once;</li>
 *   <li><strong>rate-limit</strong> excess is {@code 429} - never retried.</li>
 * </ul>
 *
 * <p>The rate-limit case runs last ({@link Order}) because it deliberately drains the token bucket
 * of the calling identity: running it first would make the following cases observe a 429 instead of
 * the capacity verdict they assert.
 */
@QuarkusTest
@Tag("contract")
@TestProfile(FaultInjectionTest.FaultInjectionProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FaultInjectionTest {

    /**
     * The production pool (16 threads) is narrower than the bulkhead (20), so the limit reached
     * first would be the pool rather than the contracted bulkhead; widened as in
     * {@link OverloadRejectionParityTest}. The rate limiter is pinned to its contracted figures -
     * the shared test instance disables it, which is exactly what this suite must not inherit.
     */
    public static final class FaultInjectionProfile implements QuarkusTestProfile {
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

    /** rateLimiting of both frozen security policies; cross-checked by the first test. */
    private static final int CONTRACT_RPS = 100;
    private static final int CONTRACT_BURST = 200;

    private static final long SERVER_TIMEOUT_MILLIS = 2_500L;
    private static final long CALLER_TIMEOUT_MILLIS = 3_000L;

    /** 20 concurrent + 40 queued = 60 places; fired well beyond, below the 100 HTTP/2 streams. */
    private static final int BULKHEAD_BURST = 90;
    private static final Duration SATURATION_HOLD = Duration.ofMillis(600);
    /** Longer than the server timeout: the request must be cut by @Timeout, not served. */
    private static final Duration TIMEOUT_HOLD = Duration.ofMillis(4_000);

    private static final String BODY =
            "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,\"durationYears\":30,\"annualRate\":0.060000}";

    private static final List<Path> SECURITY_POLICIES = List.of(
            Path.of("..", "global", "contracts",
                    "souscription-gateway-to-souscription-simulation-service", "security-policy.json"),
            Path.of("..", "global", "contracts",
                    "souscription-service-to-souscription-simulation-service", "security-policy.json"));

    @TestHTTPResource(value = "metrics", management = true)
    URL metricsUrl;

    @Test
    @Order(1)
    void theEnforcedRateMatchesBothFrozenSecurityPolicies() throws IOException {
        for (Path policy : SECURITY_POLICIES) {
            String json = Files.readString(policy).replaceAll("\\s+", "");
            assertTrue(json.contains("\"requestsPerSecond\":" + CONTRACT_RPS),
                    policy + " does not declare " + CONTRACT_RPS + " requests/s");
            assertTrue(json.contains("\"burstSize\":" + CONTRACT_BURST),
                    policy + " does not declare a burst of " + CONTRACT_BURST);
            assertTrue(json.contains("\"onExceeded\":\"429withproblem+json;thecallermustnotretrya429\""),
                    policy + " does not contract 429 problem+json on excess");
        }
    }

    @Test
    @Order(2)
    void aCallExceedingTheServerTimeoutIsAnsweredBeforeTheCallerTimeout() throws Exception {
        QuarkusMock.installMockForType(new SlowSimulationService(TIMEOUT_HOLD), SimulationService.class);

        long startNanos = System.nanoTime();
        HttpResponse<String> response = send();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertEquals(503, response.statusCode(),
                "a server timeout is a temporary unavailability, not a bug: " + response.body());
        assertProblemJson(response);
        assertTrue(elapsedMillis >= SERVER_TIMEOUT_MILLIS - 200,
                "the answer came back in " + elapsedMillis + " ms: the 2 500 ms budget was not"
                        + " the thing that cut the call");
        assertTrue(elapsedMillis < CALLER_TIMEOUT_MILLIS,
                "the server answered in " + elapsedMillis + " ms, at or past the caller's "
                        + CALLER_TIMEOUT_MILLIS + " ms timeout: the caller would see a transport"
                        + " error instead of the contracted 503");
    }

    @Test
    @Order(3)
    void bulkheadOverflowIsRejectedWith503AndMovesTheFaultToleranceMetrics() throws Exception {
        QuarkusMock.installMockForType(new SlowSimulationService(SATURATION_HOLD), SimulationService.class);

        List<HttpResponse<String>> responses = fire(BULKHEAD_BURST);
        List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();

        assertTrue(statuses.stream().anyMatch(status -> status == 503),
                "no 503 while " + BULKHEAD_BURST + " concurrent calls exceed the 60 places: "
                        + statuses);
        assertTrue(statuses.stream().noneMatch(status -> status == 429),
                "capacity overflow is 503; 429 belongs to the rate limiter: " + statuses);
        assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 503),
                "unexpected statuses under saturation: " + statuses);
        for (HttpResponse<String> response : responses) {
            if (response.statusCode() == 503) {
                assertProblemJson(response);
            }
        }

        String metrics = metrics();
        double rejections = series(metrics,
                "ft_bulkhead_calls_total", "bulkheadResult=\"rejected\"");
        assertTrue(rejections > 0,
                "the bulkhead rejections are not observable in ft_bulkhead_calls_total:\n" + metrics);
        assertTrue(series(metrics, "ft_timeout_calls_total", "timedOut=\"true\"") > 0,
                "the server timeout of the previous case is not observable in ft_timeout_calls_total");
        assertTrue(metrics.contains("worker_pool_"),
                "the worker pool is not instrumented, so saturation cannot be diagnosed:\n" + metrics);
    }

    @Test
    @Order(4)
    void rateLimitExcessIsRejectedWith429() throws Exception {
        // Real (fast) service: what is measured here is the rate, not the capacity.
        int calls = CONTRACT_BURST * 2;
        List<HttpResponse<String>> responses = new ArrayList<>(calls);
        for (int i = 0; i < calls; i++) {
            responses.add(send());
        }
        List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();

        long refused = statuses.stream().filter(status -> status == 429).count();
        assertTrue(refused > 0,
                calls + " calls at once against a burst of " + CONTRACT_BURST + " and "
                        + CONTRACT_RPS + " req/s produced no 429: " + statuses);
        assertTrue(statuses.stream().noneMatch(status -> status == 500),
                "exceeding the contracted rate is not a bug: " + statuses);
        assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 429),
                "unexpected statuses above the contracted rate: " + statuses);
        // The burst must be honoured before any refusal: the contract promises 200 queued-free
        // calls, so a limiter refusing from the first call would breach it.
        assertTrue(statuses.subList(0, CONTRACT_BURST).stream().allMatch(status -> status == 200),
                "the contracted burst of " + CONTRACT_BURST + " was not honoured: " + statuses);

        HttpResponse<String> refusal = responses.stream()
                .filter(response -> response.statusCode() == 429)
                .findFirst()
                .orElseThrow();
        assertProblemJson(refusal);
        assertTrue(refusal.body().contains("\"status\":429"),
                "the refusal body must carry status 429: " + refusal.body());
        assertTrue(refusal.headers().firstValue("retry-after").isEmpty(),
                "the contract forbids retrying a 429, so no Retry-After may invite one");

        assertTrue(series(metrics(), "simulation_ratelimit_rejected_total", null) >= refused,
                "the 429 refusals are not observable in simulation_ratelimit_rejected_total");
    }

    private static void assertProblemJson(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        assertTrue(contentType.startsWith("application/problem+json"),
                "a rejection must be a problem+json, was [" + contentType + "]");
        assertTrue(!response.body().contains("\"errors\""),
                "errors[] is the field-validation signal, absent from a capacity or rate event: "
                        + response.body());
    }

    private String metrics() {
        return RestAssured.get(metricsUrl).then().statusCode(200).extract().asString();
    }

    /** Value of a Prometheus series, optionally filtered on a tag fragment. */
    private static double series(String metrics, String name, String tagFragment) {
        double total = 0;
        Matcher matcher = Pattern.compile("^" + Pattern.quote(name)
                                + "(\\{[^}]*\\})?\\s+([0-9.eE+-]+)(\\s+#.*)?$", Pattern.MULTILINE)
                .matcher(metrics);
        boolean found = false;
        while (matcher.find()) {
            String tags = matcher.group(1);
            if (tagFragment != null && (tags == null || !tags.contains(tagFragment))) {
                continue;
            }
            found = true;
            total += Double.parseDouble(matcher.group(2));
        }
        assertTrue(found, name
                + (tagFragment == null ? "" : " with " + tagFragment) + " is not exported:\n" + metrics);
        return total;
    }

    private static HttpRequest request() {
        return HttpRequest.newBuilder()
                .uri(URI.create(MutualTlsHarness.trafficBaseUri() + "/v1/simulations"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();
    }

    private static HttpResponse<String> send() throws Exception {
        return sharedClient().send(request(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * One client for the whole suite: a fresh client per call would pay a TLS 1.3 handshake every
     * time, and the rate-limit case needs its calls to be close enough together to actually exceed
     * the contracted rate rather than be absorbed by the refill.
     */
    private static synchronized HttpClient sharedClient() {
        if (sharedClient == null) {
            sharedClient = MutualTlsHarness.httpClient(Duration.ofSeconds(5));
        }
        return sharedClient;
    }

    private static HttpClient sharedClient;

    private static List<HttpResponse<String>> fire(int count) throws Exception {
        HttpClient client = MutualTlsHarness.httpClient(Duration.ofSeconds(5));
        HttpRequest request = request();
        List<CompletableFuture<HttpResponse<String>>> calls = IntStream.range(0, count)
                .mapToObj(i -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .toList();
        List<HttpResponse<String>> responses = new ArrayList<>(count);
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            try {
                responses.add(call.get());
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof IOException)) {
                    throw failure;
                }
                // A connection reset during the burst is an OS-level event, not a verdict of the
                // service: replay it so the measured status is the server's.
                responses.add(client.send(request, HttpResponse.BodyHandlers.ofString()));
            }
        }
        return responses;
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
