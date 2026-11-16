package fr.demo.souscription.catalogue.health;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * W5-T01: two health endpoints where the source had none (NFG-015). Both are served by the
 * management interface, whose test port is distinct from quarkus.management.port.
 */
@QuarkusTest
class HealthCheckTest {

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }

    @Test
    void readinessReportsSeededCatalogue() {
        given().port(managementPort())
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'catalogue-seed' }.status", equalTo("UP"))
                .body("checks.find { it.name == 'catalogue-seed' }.data.products", equalTo(3));
    }

    @Test
    void livenessIsUp() {
        given().port(managementPort())
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }
}
