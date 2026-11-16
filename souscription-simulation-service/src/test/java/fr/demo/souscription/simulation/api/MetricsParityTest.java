package fr.demo.souscription.simulation.api;

import java.util.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

import java.net.URL;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 21-simulation-prometheus-metrics.
 *
 * The monolith exported no metric, so the obligations here are negative: observe without
 * transforming, and never put a business value in label space. initialAmount alone has 10 000 001
 * legal values; one amount-tagged metric writes one time series per request, and the series already
 * stored survive the code fix.
 *
 * Adapter: the test-mode management port is ephemeral (quarkus.management.test-port=0, plan 20),
 * so it is resolved by @TestHTTPResource(management = true) rather than hardcoded.
 */
@QuarkusTest
class MetricsParityTest {

    private static final String BODY =
            "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";

    @Inject
    MeterRegistry registry;

    /** Management port chosen in test mode by plan 20 (ephemeral port, resolved by Quarkus). */
    @TestHTTPResource(value = "metrics", management = true)
    URL managementMetrics;

    private static void oneSimulation() {
        given().contentType("application/json").body(BODY)
                .when().post("/v1/simulations").then().statusCode(200);
    }

    private String scrape() {
        return given().when().get(managementMetrics)
                .then().statusCode(200).extract().response().asString();
    }

    @Test
    void httpJvmAndBusinessMetricsAreExposed() {
        oneSimulation();
        String scrape = scrape();
        assertTrue(scrape.contains("http_server_requests"), "HTTP metrics missing from the scrape");
        assertTrue(scrape.contains("jvm_"), "JVM metrics missing -- the plan-19 sizing has no evidence");
        assertTrue(scrape.contains("simulation_calculate_seconds"),
                "business timer missing (is the annotation io.micrometer @Timed and not the "
                        + "MicroProfile one?)");
    }

    /** Without the separation, a latency regression cannot be attributed to transport vs computation. */
    @Test
    void businessTimerIsDistinctFromTheHttpTimer() {
        oneSimulation();
        assertNotNull(registry.find("simulation.calculate").timer(),
                "simulation.calculate is not registered as a timer");
        assertNotNull(registry.find("http.server.requests").meter(),
                "http.server.requests is not registered");
        assertFalse("http.server.requests".equals("simulation.calculate"),
                "the two timers must be two distinct metric names");
    }

    @Test
    void noMeterCarriesAHighCardinalityTag() {
        oneSimulation();
        List<String> forbidden = List.of("initialamount", "monthlyamount", "durationyears",
                "annualrate", "amount", "montant", "rate", "taux", "correlationid", "traceid",
                "spanid", "finalcapital", "contributed", "gains");
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey().toLowerCase().replace("_", "").replace("-", "").replace(".", "");
                assertFalse(forbidden.contains(key),
                        "high-cardinality tag [" + tag.getKey() + "] on " + meter.getId().getName());
            }
            meter.getId().getTags().stream()
                    .filter(tag -> "uri".equals(tag.getKey()))
                    .forEach(tag -> assertFalse(tag.getValue().matches(".*\\d{3,}.*"),
                            "the uri tag must be a template, got [" + tag.getValue() + "]"));
        }
    }

    /**
     * A timer exposing only _count and _sum makes the published p95/p99 SLA unverifiable -- plan 25
     * would publish a number nobody can check. Buckets are preferred: client-side percentiles cannot
     * be aggregated across the >= 2 replicas the 99.9% objective requires.
     */
    @Test
    void latencyPercentilesAreComputable() {
        oneSimulation();
        String scrape = scrape();
        boolean buckets = scrape.contains("http_server_requests_seconds_bucket");
        boolean percentiles = scrape.contains("quantile=");
        assertTrue(buckets || percentiles,
                "neither histogram buckets nor percentiles are exposed for the HTTP timer");
        assertTrue(scrape.contains("status=\"200\""),
                "the status dimension is required to compute availability and the 503 rate");
    }

    @Test
    void metricsAreNotServedOnTheBusinessPort() {
        int status = given().when().get("/q/metrics").then().extract().statusCode();
        assertEquals(404, status,
                "/q/metrics must not answer on the business port, got " + status);
    }

    /**
     * worker_pool_* feeds the plan-20 readiness check, and a missing gauge there means UP -- so its
     * absence silently disables saturation detection instead of failing loudly.
     */
    @Test
    void faultToleranceAndPoolMetricsArePresent() {
        oneSimulation();
        String scrape = scrape();
        assertTrue(scrape.contains("worker_pool_"),
                "worker_pool_* gauges missing -- the readiness saturation check has no input");
        assertTrue(scrape.contains("ft_") || scrape.contains("bulkhead"),
                "no fault-tolerance metric -- the extension may be absent, which would also make the "
                        + "plan-18 bulkhead inert");
    }
}
