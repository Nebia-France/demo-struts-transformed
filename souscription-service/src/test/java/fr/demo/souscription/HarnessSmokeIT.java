package fr.demo.souscription;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Preuve du harnais de test cible (W8-T01) : l'application demarre et repond pendant la phase
 * failsafe, sans Cargo, sans Tomcat telecharge et sans conteneur applicatif externe. La base est
 * fournie par Dev Services PostgreSQL, donc par Testcontainers, d'ou le profil de test
 * {@link PostgresDevServicesProfile} et l'ignorance a defaut de daemon de conteneurs, comme pour
 * {@code HealthProbeTest} et {@code DatasourceSmokeTest}.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class HarnessSmokeIT {

    @Test
    void theApplicationStartsAndReadinessIsUp() {
        RestAssured.when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", Matchers.is("UP"));
    }
}
