package fr.demo.souscription.gateway.health;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity check for 25-souscription-gateway-health-graceful-shutdown.
 *
 * <p>The source had no operational endpoint at all, so this is a new-capability validation. The two
 * design rules are the parity-critical part:
 * <ul>
 *   <li>{@code @Liveness} stays trivial: a degraded dependency must NEVER restart the pod (AG-007);
 *   <li>{@code @Readiness} reflects own capacity only: failing it on a downstream outage turns a
 *       partial failure into a total outage of the public entry point (NFG-013).
 * </ul>
 *
 * <p>Binding of the skeleton to this module: the "simulated downstream outage" is a real one — every
 * downstream double is shut down ({@link DownstreamWireMocks#shutDown(String)}) and traffic is sent
 * until the catalogue breaker opens, using the outage profile of {@link HealthCheckTest} (same
 * profile, so no extra application restart). The test suite binds random ports
 * ({@code quarkus.http.test-port=0}, {@code quarkus.management.test-port=0}); the 9000-vs-8080 split
 * is therefore asserted on the configured production ports and observed on the effective ones.
 */
@QuarkusTest
@TestProfile(HealthCheckTest.DownstreamOutageProfile.class)
class HealthProbeParityTest {

    private static final int PRODUCTION_MANAGEMENT_PORT = 9000;

    private static final int PRODUCTION_PUBLIC_PORT = 8080;

    @Inject
    CircuitBreakerMaintenance maintenance;

    @BeforeEach
    @AfterEach
    void healthyDownstreams() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::bringBack);
        maintenance.resetAll();
    }

    @Test
    void livenessStaysUpWhileADownstreamIsUnavailable() {
        simulateDownstreamOutage();
        Response response = management("/q/health/live");
        assertEquals(200, response.getStatusCode(),
                "a degraded dependency must never restart the pod (AG-007)");
        JsonPath body = response.jsonPath();
        assertEquals("UP", body.getString("status"));
        assertEquals(List.of("gateway-process"), body.getList("checks.name"));
    }

    @Test
    void readinessReflectsOwnCapacityOnly() {
        simulateDownstreamOutage();
        Response response = management("/q/health/ready");
        assertEquals(200, response.getStatusCode(),
                "failing readiness on a downstream outage removes the whole public API from"
                        + " rotation (NFG-013)");
        JsonPath body = response.jsonPath();
        assertEquals("UP", body.getString("status"));
        assertEquals(List.of("gateway-routes"), body.getList("checks.name"),
                "readiness must not contain any dependency check");
    }

    @Test
    void downstreamCheckIsInformativeAndReportsBreakerStates() {
        simulateDownstreamOutage();
        Response response = management("/q/health");
        assertEquals(200, response.getStatusCode(),
                "an informative dependency check must not turn the aggregate document DOWN");
        JsonPath body = response.jsonPath();
        assertEquals("UP", body.getString("status"));
        String downstream = "checks.find { it.name == 'downstream' }";
        assertEquals("UP", body.getString(downstream + ".status"),
                "the dependency check reports the three breaker states as DATA, staying UP");
        assertEquals("OPEN", body.getString(downstream + ".data.catalogue"));
        for (String edge : List.of("simulation", "subscription")) {
            assertTrue(List.of("CLOSED", "OPEN", "HALF_OPEN")
                            .contains(body.getString(downstream + ".data." + edge)),
                    "breaker state of " + edge + " must be reported");
        }
    }

    @Test
    void healthChecksIssueNoProbeCallToTheDownstreams() {
        long before = servedRequests();
        for (String path : List.of("/q/health", "/q/health/live", "/q/health/ready", "/q/health/well")) {
            assertEquals(200, management(path).getStatusCode(), path);
        }
        assertEquals(before, servedRequests(),
                "a probing health check multiplies load on an already failing provider");
    }

    @Test
    void probesAreOnTheManagementPortAndNotOnThePublicPort() {
        // At test time Quarkus publishes the effective (random) ports under these keys, so the
        // production values are read from the packaged application.properties itself.
        Properties packaged = packagedProperties();
        assertEquals(String.valueOf(PRODUCTION_MANAGEMENT_PORT), packaged.getProperty("quarkus.management.port"));
        assertEquals(String.valueOf(PRODUCTION_PUBLIC_PORT), packaged.getProperty("quarkus.http.port"));
        assertEquals("true", packaged.getProperty("quarkus.management.enabled"));
        assertTrue(ConfigProvider.getConfig().getValue("quarkus.management.enabled", Boolean.class));
        assertNotEquals(managementPort(), publicPort(), "the two interfaces must be distinct");

        for (String probe : List.of("/q/health/live", "/q/health/ready")) {
            assertEquals(200, management(probe).getStatusCode(), probe + " on the management port");
            given().when().get(probe).then().statusCode(404);
        }
        given().when().get("/q/health").then().statusCode(404);
    }

    private static Properties packagedProperties() {
        Properties properties = new Properties();
        try (InputStream in = HealthProbeParityTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertNotNull(in, "application.properties must be on the classpath");
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    private static Response management(String path) {
        return given().port(managementPort()).when().get(path);
    }

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(PRODUCTION_MANAGEMENT_PORT);
    }

    private static int publicPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.test-port", Integer.class)
                .orElse(PRODUCTION_PUBLIC_PORT);
    }

    private static long servedRequests() {
        return DownstreamWireMocks.CONFIG_KEYS.stream()
                .mapToLong(key -> DownstreamWireMocks.server(key).getAllServeEvents().size())
                .sum();
    }

    /**
     * Real outage: every downstream double refuses connections, and public traffic is sent until the
     * catalogue breaker is open, so the probes are read in the degraded state.
     */
    private void simulateDownstreamOutage() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::shutDown);
        for (int attempt = 0; attempt < 32
                && maintenance.currentState("catalogue-list") != CircuitBreakerState.OPEN; attempt++) {
            assertEquals(503, given().when().get("/api/v1/products").getStatusCode(),
                    "an unreachable downstream reads 503 on the public surface");
        }
        assertEquals(CircuitBreakerState.OPEN, maintenance.currentState("catalogue-list"));
        assertFalse(DownstreamWireMocks.isRunning("catalogue"));
    }
}
