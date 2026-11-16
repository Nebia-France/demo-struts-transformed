package fr.demo.souscription.health;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Preuve de cablage des trois sondes (W5-T02). Les trois scenarios du domaine
 * {@code initialisation-application} encore pertinents sont rejoues sous cette forme : le
 * demarrage nominal devient {@code /q/health/started} et {@code /q/health/ready} a {@code UP},
 * l'acces aux services avant initialisation devient une readiness {@code DOWN}. Cf.
 * {@code deploy/OBSERVABILITY.md}.
 *
 * <p>Le cas nominal exige une base reelle, fournie par Dev Services, donc un daemon de
 * conteneurs ; il est ignore a defaut, comme {@code DatasourceSmokeTest}. Le cas d'indisponibilite
 * n'a besoin d'aucun conteneur : il pointe le datasource sur une adresse injoignable
 * ({@code HealthProbeDownTest}).
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class HealthProbeTest {

    /**
     * Datasource actif mais pointe sur un port ferme : le pool ne peut servir aucune connexion.
     * Flyway est desarme, sinon la migration de demarrage echouerait avant que la sonde ne puisse
     * etre interrogee.
     */
    public static class UnreachableDatasourceProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.active", "true",
                    "quarkus.hibernate-orm.active", "false",
                    "quarkus.datasource.devservices.enabled", "false",
                    "quarkus.datasource.jdbc.url", "jdbc:postgresql://127.0.0.1:1/horizon",
                    "quarkus.datasource.username", "unreachable",
                    "quarkus.datasource.password", "unreachable",
                    "quarkus.datasource.jdbc.min-size", "0",
                    "quarkus.datasource.jdbc.initial-size", "0",
                    "quarkus.datasource.jdbc.acquisition-timeout", "1S",
                    "quarkus.flyway.migrate-at-start", "false");
        }
    }

    @Test
    void startupProbeIsUp() {
        RestAssured.when()
                .get("/q/health/started")
                .then()
                .statusCode(200)
                .body("status", Matchers.is("UP"));
    }

    @Test
    void livenessProbeIsUp() {
        RestAssured.when()
                .get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", Matchers.is("UP"));
    }

    @Test
    void readinessProbeIsUpAndNamesTheDatabaseCheck() {
        RestAssured.when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", Matchers.is("UP"))
                .body("checks.name", Matchers.hasItem(DatabaseReadinessCheck.CHECK_NAME))
                .body("checks.name", Matchers.hasItem(DependencyReadinessCheck.CHECK_NAME))
                .body("checks.name", Matchers.hasItem(CatalogueReadinessCheck.CHECK_NAME))
                .body("checks.name", Matchers.hasItem(SimulationReadinessCheck.CHECK_NAME));
    }
}

/**
 * Cas d'indisponibilite du datasource : la readiness tombe a {@code DOWN} et repond 503, alors que
 * la liveness et le demarrage restent {@code UP} — le processus est vivant, il n'est simplement pas
 * apte a servir. C'est la condition qui retire le pod du Service sans le redemarrer.
 */
@QuarkusTest
@TestProfile(HealthProbeTest.UnreachableDatasourceProfile.class)
class HealthProbeDownTest {

    @Test
    void readinessIsDownWhenTheDatasourceIsUnreachable() {
        RestAssured.when()
                .get("/q/health/ready")
                .then()
                .statusCode(503)
                .body("status", Matchers.is("DOWN"))
                .body(
                        "checks.find { it.name == '%s' }.status".formatted(
                                DatabaseReadinessCheck.CHECK_NAME),
                        Matchers.is("DOWN"));
    }

    /**
     * Les deux sondes de dependance sont enregistrees et restent {@code UP} : aucun appel sortant
     * n'a echoue, et une base injoignable ne doit pas etre imputee au catalogue ni a la
     * simulation. C'est aussi la seule verification de cablage de ces sondes qui n'exige pas de
     * daemon de conteneurs.
     */
    @Test
    void theDependencyChecksAreRegisteredAndStayUp() {
        RestAssured.when()
                .get("/q/health/ready")
                .then()
                .statusCode(503)
                .body("checks.find { it.name == '%s' }.status".formatted(
                        CatalogueReadinessCheck.CHECK_NAME), Matchers.is("UP"))
                .body("checks.find { it.name == '%s' }.status".formatted(
                        SimulationReadinessCheck.CHECK_NAME), Matchers.is("UP"));
    }

    @Test
    void livenessAndStartupStayUpWhenTheDatasourceIsUnreachable() {
        RestAssured.when().get("/q/health/live").then().statusCode(200).body("status", Matchers.is("UP"));
        RestAssured.when().get("/q/health/started").then().statusCode(200).body("status", Matchers.is("UP"));
    }
}
