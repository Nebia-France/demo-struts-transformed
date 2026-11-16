package fr.demo.souscription.simulation.health;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parity test for 20-simulation-health-probes-management-port.
 *
 * The source had no health endpoint, so this is a new-capability validation -- except for one
 * inherited obligation: a busy Tomcat finished its in-flight requests, so a saturated replica must be
 * taken OUT OF TRAFFIC (readiness DOWN) and never RESTARTED (liveness must stay UP).
 *
 * The two adapters of the skeleton are wired to the mechanisms plan 20 actually chose:
 *   - managementPort(): quarkus.management.test-port=0 plus @TestHTTPResource(management = true);
 *   - saturatePool(): the readiness check reads the Micrometer gauge worker.pool.queue.size from the
 *     application's own MeterRegistry and compares it with quarkus.thread-pool.queue-size, so the
 *     fixture drives that gauge in the real registry to the configured queue size. The comparison
 *     path exercised is the production one -- a hardcoded `false` check would not go DOWN.
 */
@QuarkusTest
class HealthProbeParityTest {

    @Inject
    @org.eclipse.microprofile.health.Readiness
    WorkerPoolHealthCheck workerPoolHealthCheck;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "quarkus.thread-pool.queue-size")
    int queueCapacity;

    @TestHTTPResource(value = "health", management = true)
    URL managementHealth;

    /** The test-mode management port, assigned ephemerally by quarkus.management.test-port=0. */
    private int managementPort() {
        return managementHealth.getPort();
    }

    /** Drives the worker-queue gauge the readiness check reads to its configured capacity. */
    private AutoCloseable saturatePool() {
        AtomicInteger queued = new AtomicInteger(queueCapacity);
        Gauge gauge = Gauge.builder("worker.pool.queue.size", queued, AtomicInteger::doubleValue)
                .tag("source", "parity-fixture")
                .register(meterRegistry);
        return () -> meterRegistry.remove(gauge);
    }

    private static Response health(int port, String path) {
        // The management interface is plaintext: it is cluster-internal, unpublished by the Service
        // and probed by the kubelet, which holds no workload certificate. Only the traffic port is
        // mTLS (W6-T01), and RestAssured's base URI points there, so it is overridden here.
        return given().baseUri("http://localhost").port(port)
                .when().get("/q/health/" + path).then().extract().response();
    }

    /** Three endpoints, each answering UP at rest. */
    @Test
    void theThreeProbesAnswerUp() {
        for (String path : new String[] {"live", "ready", "started"}) {
            Response response = health(managementPort(), path);
            assertEquals(200, response.statusCode(), "/q/health/" + path + " must answer 200");
            assertTrue(response.asString().contains("\"status\": \"UP\"")
                            || response.asString().contains("\"status\":\"UP\""),
                    "/q/health/" + path + " must report UP, got: " + response.asString());
        }
    }

    /** The management interface is enabled and distinct from the business port. */
    @Test
    void theManagementPortIsSeparateFromTheBusinessPort() {
        assertNotEquals(io.restassured.RestAssured.port, managementPort(),
                "the probes must not share the port serving the two contractual callers");
    }

    /**
     * Health on the business port would be exposed to the two contractual callers and, after plan 24,
     * would demand a client certificate from the kubelet.
     */
    @Test
    void healthIsNotServedOnTheBusinessPort() {
        Response response = given().when().get("/q/health/live").then().extract().response();
        assertEquals(404, response.statusCode(),
                "health must not be reachable on the business port, got " + response.statusCode());
    }

    /** A saturated replica is removed from traffic. */
    @Test
    void readinessGoesDownWhenThePoolIsSaturated() throws Exception {
        assumeTrue(workerPoolHealthCheck != null, "the worker-pool readiness check must be a CDI bean");
        try (AutoCloseable saturation = saturatePool()) {
            HealthCheckResponse response = workerPoolHealthCheck.call();
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                    "readiness must go DOWN when the worker queue is at its configured size");
            Response ready = health(managementPort(), "ready");
            assertEquals(503, ready.statusCode(),
                    "the readiness endpoint must reflect the saturated check, got " + ready.asString());
        }
    }

    /** ... and never restarted: a restart drops the in-flight simulations. */
    @Test
    void livenessStaysUpWhenReadinessIsDown() throws Exception {
        try (AutoCloseable saturation = saturatePool()) {
            Response live = health(managementPort(), "live");
            assertEquals(200, live.statusCode(),
                    "liveness must stay UP under saturation -- a kubelet restart would lose work the "
                            + "monolith would have completed");
            assertTrue(!live.asString().contains(WorkerPoolHealthCheck.NAME),
                    "the worker-pool check must be a readiness check only, got: " + live.asString());
        }
    }

    /** The name is referenced by the plan-21 alert rules and the plan-31 audit. */
    @Test
    void readinessCheckIsNamedAndReadsTheQueueDepth() {
        HealthCheckResponse response = workerPoolHealthCheck.call();
        assertEquals("simulation-worker-pool", response.getName(),
                "the readiness check must keep its contracted name");
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "at rest the pool is not saturated; a DOWN here means a missing gauge is treated as "
                        + "saturation, and the service would never become ready");
        assertEquals(String.valueOf(queueCapacity),
                String.valueOf(response.getData().orElseThrow().get("queueCapacity")),
                "the verdict must compare against quarkus.thread-pool.queue-size");
    }

    /**
     * The gauge read is an instantaneous depth that comes back down, not a monotonic total: a total
     * would latch readiness DOWN forever after the first burst.
     */
    @Test
    void readinessRecoversWhenTheQueueDrains() throws Exception {
        try (AutoCloseable saturation = saturatePool()) {
            assertEquals(HealthCheckResponse.Status.DOWN, workerPoolHealthCheck.call().getStatus());
        }
        assertEquals(HealthCheckResponse.Status.UP, workerPoolHealthCheck.call().getStatus(),
                "readiness must rejoin traffic once the queue drains");
    }

    /** The monolith never interrupted a simulation mid-request. */
    @Test
    void inFlightRequestsCompleteDuringShutdown() {
        // Static criterion first: without the drain budget the listener closes on SIGTERM.
        assertEquals("20S", org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("quarkus.shutdown.timeout", String.class));
        // Environment dependent: a real SIGTERM cannot be issued inside @QuarkusTest. The rollout
        // evidence is recorded in README.md and replayed by 31-...-parity.
        assumeTrue(Boolean.getBoolean("simulation.shutdown.harness"),
                "graceful-shutdown evidence is produced by the rollout harness, not by @QuarkusTest");
    }
}
