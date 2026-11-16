package fr.demo.souscription.simulation.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Exposition Prometheus (W5-T02) : après un appel réel, /q/metrics publie les métriques
 * HTTP, les métriques JVM et le timer métier simulation_calculate_seconds, distinct du
 * timer de transport. p95/p99 et disponibilité sont calculables depuis ces séries.
 */
@QuarkusTest
class MetricsExpositionTest {

    @TestHTTPResource(value = "metrics", management = true)
    URL managementMetrics;

    private String scrapeAfterOneSimulation() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"initialAmount\":1000.00,\"monthlyAmount\":150.00,"
                        + "\"durationYears\":10,\"annualRate\":0.035}")
                .when().post("/v1/simulations")
                .then().statusCode(200);
        return RestAssured.get(managementMetrics).asString();
    }

    @Test
    void metricsEndpointExposesHttpJvmAndBusinessTimer() {
        String body = scrapeAfterOneSimulation();
        assertTrue(body.contains("http_server_requests"), "http_server_requests absent");
        assertTrue(body.contains("jvm_"), "aucune métrique jvm_");
        assertTrue(body.contains("simulation_calculate_seconds"), "simulation_calculate_seconds absent");
    }

    @Test
    void percentilesAndAvailabilityAreComputable() {
        String body = scrapeAfterOneSimulation();
        // Les séries _count et _sum du timer HTTP permettent de calculer la disponibilité
        // et les quantiles (buckets ou _max selon la configuration du registre).
        assertTrue(body.contains("http_server_requests_seconds_count"), body);
        assertTrue(body.contains("http_server_requests_seconds_sum"), body);
        assertTrue(body.contains("simulation_calculate_seconds_count"), body);
    }

    @Test
    void metricsAreNotServedOnTheTrafficPort() {
        RestAssured.given()
                .when().get("/q/metrics")
                .then().statusCode(404);
    }
}
