package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SimulationResourceSmokeTest {

    @Test
    void computesAProjection() {
        given().contentType(ContentType.JSON)
            .body("{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}")
        .when().post("/v1/simulations")
        .then().statusCode(200)
            .contentType(ContentType.JSON)
            .body("contributed", notNullValue())
            .body("finalCapital", notNullValue());
    }
}
