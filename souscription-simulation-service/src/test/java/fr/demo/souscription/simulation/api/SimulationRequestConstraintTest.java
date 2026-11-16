package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Bornes déclaratives de SimulationRequest (VALIDATION_TRACEABILITY.md).
 * Les exemples reprennent les scénarios Gherkin « Bornes du versement initial »,
 * « Bornes de la mensualite » et « Bornes de la duree en annees ».
 */
@QuarkusTest
class SimulationRequestConstraintTest {

    @ParameterizedTest
    @CsvSource({
        "99.99,     100.00, 10, 400",
        "100.00,    100.00, 10, 200",
        "100000.00, 100.00, 10, 200",
        "100000.01, 100.00, 10, 400",
        "1000.00,   0.00,   10, 200",
        "1000.00,   2000.00, 10, 200",
        "1000.00,   2000.01, 10, 400",
        "1000.00,   100.00, 0,  400",
        "1000.00,   100.00, 1,  200",
        "1000.00,   100.00, 30, 200",
        "1000.00,   100.00, 31, 400"
    })
    void enforcesBounds(String initial, String monthly, int years, int expectedStatus) {
        given().contentType(ContentType.JSON)
            .body("{\"initialAmount\":\"" + initial + "\",\"monthlyAmount\":\"" + monthly
                    + "\",\"durationYears\":" + years + ",\"annualRate\":\"0.020000\"}")
        .when().post("/v1/simulations")
        .then().statusCode(expectedStatus);
    }
}
