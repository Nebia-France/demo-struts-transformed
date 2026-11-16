package fr.demo.souscription.catalogue.health;

// Parity-test skeleton for 21-souscription-catalogue-health-probes-parity.
// Copy to souscription-catalogue-service/src/test/java/fr/demo/souscription/catalogue/health/.
//
// Source reference: the WAR had NO health endpoint; the only failure signal was
// "IllegalStateException: Application non initialisée" in the Tomcat log. The invariant the
// readiness check must express is the source one: the catalogue is populated before any
// request is served (Application.contextInitialized).
//
// Note: the management interface is enabled (quarkus.management.enabled=true), so @QuarkusTest
// serves /q/health/* on the management test port, not on the application test port. The 9000
// binding itself is asserted by the SKILL.md grep step and by the deployment manifest.

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class HealthProbeParityTest {

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }

    @Test
    void laSondeDeReadinessRefleteLeCatalogueAmorce() {
        given().port(managementPort()).when().get("/q/health/ready").then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'catalogue-seed' }.status", equalTo("UP"))
                .body("checks.find { it.name == 'catalogue-seed' }.data.products", is(3));
    }

    @Test
    void laSondeDeLivenessEstUp() {
        given().port(managementPort()).when().get("/q/health/live").then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void lesSondesNExposentAucunDetailInterne() {
        String body = given().port(managementPort()).when().get("/q/health/ready").then()
                .statusCode(200).extract().asString().toLowerCase();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("exception"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("fr.demo"), body);
    }
}
