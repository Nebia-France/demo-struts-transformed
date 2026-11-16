package fr.demo.souscription.health;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 25-souscription-service-health-probes — the database-down cases.
 *
 * A Quarkus test profile applies per test CLASS, so these two cases cannot live in
 * HealthProbeParityTest alongside the all-UP cases.
 *
 * Baseline reference: BASELINE.md section 4.8 and the Gherkin scenario
 * "Acces aux services avant initialisation" (features/initialisation-application).
 *
 * <p>Adapted to this module's harness: the pool must be ARMED and pointed at a closed port
 * ({@code quarkus.datasource.active=true}) while Hibernate stays inactive, otherwise the
 * readiness check would report DOWN because the datasource bean is inactive rather than because
 * the database is unreachable — a weaker proof.
 */
@QuarkusTest
@TestProfile(DatabaseDownReadinessParityTest.UnreachableDatabaseProfile.class)
class DatabaseDownReadinessParityTest {

    @Test
    void readinessIsDownWhenTheDatabaseIsUnreachable() {
        given().when().get("/q/health/ready")
            .then().statusCode(503)
                .body("status", is("DOWN"))
                .body("checks.find { it.name == 'database' }.status", is("DOWN"));
        // A readiness check that reports UP regardless of the database is worse than none: the
        // platform routes traffic to an instance that cannot serve it, so every request fails
        // instead of the instance being withheld from the load balancer.
    }

    @Test
    void livenessDoesNotDependOnTheDatabase() {
        given().when().get("/q/health/live")
            .then().statusCode(200)
                .body("status", is("UP"));
        // A liveness probe wired to an external dependency makes the platform kill EVERY replica
        // during a database incident: a degradation becomes a total outage, the service cannot even
        // serve its 503s, and the restart storm delays recovery once the database returns.
    }

    public static class UnreachableDatabaseProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("quarkus.datasource.active", "true"),
                    Map.entry("quarkus.hibernate-orm.active", "false"),
                    Map.entry("quarkus.datasource.devservices.enabled", "false"),
                    Map.entry("quarkus.datasource.jdbc.url", "jdbc:postgresql://127.0.0.1:1/absent"),
                    Map.entry("quarkus.datasource.username", "absent"),
                    Map.entry("quarkus.datasource.password", "absent"),
                    Map.entry("quarkus.datasource.jdbc.min-size", "0"),
                    Map.entry("quarkus.datasource.jdbc.initial-size", "0"),
                    Map.entry("quarkus.flyway.migrate-at-start", "false"),
                    Map.entry("quarkus.hibernate-orm.database.generation", "none"),
                    // The 3s acquisition timeout of plan 07 must hold, otherwise readiness reports
                    // DOWN later than its own probe period and the platform sees a timeout instead.
                    Map.entry("quarkus.datasource.jdbc.acquisition-timeout", "3S"));
        }
    }
}
