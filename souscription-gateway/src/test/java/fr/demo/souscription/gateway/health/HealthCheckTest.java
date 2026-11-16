package fr.demo.souscription.gateway.health;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Health probes of the gateway (W6-T01).
 *
 * <ul>
 *   <li>liveness stays UP while a downstream is unavailable (AG-007): a degraded dependency must
 *       never restart the pod;
 *   <li>readiness reflects only the gateway's own capacity: it contains no dependency check and
 *       stays UP during a downstream outage (NFG-013);
 *   <li>the downstream check reports the breaker state of each edge while staying UP;
 *   <li>the probes are served on the management interface only, never on the public port.
 * </ul>
 */
@QuarkusTest
@TestProfile(HealthCheckTest.DownstreamOutageProfile.class)
class HealthCheckTest {

    private static final String CATALOGUE = "fr.demo.souscription.gateway.client.CatalogueGateway";

    private static final int BREAKER_VOLUME = 4;

    /**
     * Opens the catalogue breaker after a handful of refused connections and keeps it open for the
     * duration of the assertions. Only values are overridden, never the presence of a policy.
     */
    public static class DownstreamOutageProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    CATALOGUE + "/list/Retry/maxRetries", "0",
                    CATALOGUE + "/CircuitBreaker/requestVolumeThreshold", String.valueOf(BREAKER_VOLUME),
                    CATALOGUE + "/CircuitBreaker/failureRatio", "0.5",
                    CATALOGUE + "/CircuitBreaker/delay", "60000");
        }
    }

    @Inject
    CircuitBreakerMaintenance maintenance;

    @BeforeEach
    @AfterEach
    void healthyDownstreams() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::bringBack);
        maintenance.resetAll();
    }

    @Test
    void livenessIsUpOnTheManagementPort() {
        given().port(managementPort()).when().get("/q/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", contains("gateway-process"));
    }

    @Test
    void readinessReflectsOnlyTheGatewayOwnCapacity() {
        given().port(managementPort()).when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", contains("gateway-routes"));
    }

    @Test
    void downstreamCheckReportsClosedBreakersWhenHealthy() {
        given().port(managementPort()).when().get("/q/health/well")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'downstream' }.status", equalTo("UP"))
                .body("checks.find { it.name == 'downstream' }.data.catalogue", equalTo("CLOSED"))
                .body("checks.find { it.name == 'downstream' }.data.simulation", equalTo("CLOSED"))
                .body("checks.find { it.name == 'downstream' }.data.subscription", equalTo("CLOSED"));
    }

    @Test
    void probesAreNotServedOnThePublicPort() {
        for (String path : new String[] {"/q/health", "/q/health/live", "/q/health/ready", "/q/health/well"}) {
            int status = given().when().get(path).getStatusCode();
            assertNotEquals(200, status, path + " ne doit pas etre servi sur le port public");
        }
    }

    @Test
    void livenessAndReadinessStayUpWhileADownstreamIsUnavailable() {
        DownstreamWireMocks.shutDown("catalogue");
        for (int attempt = 0; attempt < 4 * BREAKER_VOLUME
                && maintenance.currentState("catalogue-list") != CircuitBreakerState.OPEN; attempt++) {
            assertEquals(503, given().when().get("/api/v1/products").getStatusCode(),
                    "un aval injoignable se lit 503 sur la surface publique");
        }
        assertEquals(CircuitBreakerState.OPEN, maintenance.currentState("catalogue-list"),
                "le disjoncteur du catalogue doit etre ouvert pour la suite du test");

        given().port(managementPort()).when().get("/q/health/live")
                .then().statusCode(200).body("status", equalTo("UP"));
        given().port(managementPort()).when().get("/q/health/ready")
                .then().statusCode(200).body("status", equalTo("UP"))
                .body("checks.name", not(hasItem("downstream")));

        // Informative only: the breaker state is reported, the check itself stays UP, and so does
        // the aggregate document.
        given().port(managementPort()).when().get("/q/health")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'downstream' }.status", equalTo("UP"))
                .body("checks.find { it.name == 'downstream' }.data.catalogue", equalTo("OPEN"))
                .body("checks.find { it.name == 'downstream' }.data.simulation", equalTo("CLOSED"))
                .body("checks.find { it.name == 'downstream' }.data.subscription", equalTo("CLOSED"));
    }

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }
}
