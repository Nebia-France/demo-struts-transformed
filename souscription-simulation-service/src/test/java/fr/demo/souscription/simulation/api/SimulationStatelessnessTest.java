package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/**
 * Le legacy stockait la Simulation dans la HttpSession (timeout 20 minutes, web.xml).
 * La cible ne conserve aucun état : le brouillon serveur vit dans souscription-service.
 */
@QuarkusTest
class SimulationStatelessnessTest {

    private static final String BODY =
        "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";

    @Test
    void twoIdenticalRequestsReturnByteIdenticalBodies() {
        String first = post().asString();
        String second = post().asString();
        assertEquals(first, second);
    }

    @Test
    void aCallDoesNotInfluenceTheNextOne() {
        post();
        String other = given().contentType(ContentType.JSON)
            .body("{\"initialAmount\":100.00,\"monthlyAmount\":0.00,\"durationYears\":1,\"annualRate\":0.00}")
            .when().post("/v1/simulations").then().statusCode(200).extract().asString();
        assertTrue(other.contains("\"finalCapital\":100.00") || other.contains("\"finalCapital\":\"100.00\""),
            () -> "unexpected body: " + other);
    }

    @Test
    void noSetCookieHeaderIsEmitted() {
        assertNull(post().header("Set-Cookie"));
    }

    private ExtractableResponse<Response> post() {
        return given().contentType(ContentType.JSON).body(BODY)
            .when().post("/v1/simulations").then().statusCode(200).extract();
    }
}
