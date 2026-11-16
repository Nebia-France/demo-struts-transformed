package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Vérifie que le document publié sur /q/openapi expose bien l'opération attendue par les
 * contrats figés de global/contracts (le diff complet est fait par l'étape de pipeline
 * openapi-contract-diff), et qu'il n'est servi que sur le port de management (W5-T06).
 */
@QuarkusTest
@Tag("contract")
class OpenApiDocumentTest {

    @TestHTTPResource(value = "openapi", management = true)
    URL managementOpenApi;

    @Test
    void publishesTheSimulationOperation() {
        given().accept("application/json")
        .when().get(managementOpenApi + "?format=json")
        .then().statusCode(200)
            .body("info.version", equalTo("1.0.0"))
            .body("paths.'/v1/simulations'.post.operationId", equalTo("computeSimulation"))
            .body("paths.'/v1/simulations'.post.parameters.name",
                  hasItems("X-Correlation-Id", "traceparent"))
            .body("paths.'/v1/simulations'.post.responses.'400'.content.keySet()",
                  contains("application/problem+json"));
    }

    @Test
    void documentIsNotServedOnTheTrafficPort() {
        given().accept("application/json")
        .when().get("/q/openapi")
        .then().statusCode(404);
    }
}
