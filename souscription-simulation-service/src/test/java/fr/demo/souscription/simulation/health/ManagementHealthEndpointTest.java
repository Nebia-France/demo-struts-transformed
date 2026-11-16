package fr.demo.souscription.simulation.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Les trois sondes répondent UP, et uniquement sur le port de management (W5-T01).
 */
@QuarkusTest
class ManagementHealthEndpointTest {

    @TestHTTPResource(value = "health", management = true)
    URL managementHealth;

    @Test
    void livenessReadinessAndStartedAreUpOnManagementPort() {
        for (String probe : new String[] {"live", "ready", "started"}) {
            Response response = RestAssured.get(managementHealth + "/" + probe);
            assertEquals(200, response.statusCode(), probe);
            assertEquals("UP", response.jsonPath().getString("status"), probe);
        }
    }

    @Test
    void readinessReportsTheWorkerPoolCheck() {
        String body = RestAssured.get(managementHealth + "/ready").asString();
        assertTrue(body.contains(WorkerPoolHealthCheck.NAME), body);
    }

    @Test
    void healthIsNotServedOnTheTrafficPort() {
        RestAssured.given()
                .when().get("/q/health/live")
                .then().statusCode(404);
    }
}
