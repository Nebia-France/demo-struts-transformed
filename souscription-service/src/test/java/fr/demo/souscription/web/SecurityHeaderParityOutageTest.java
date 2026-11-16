package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Volet 503 du controle de parite : une reponse produite par l'indisponibilite d'un fournisseur
 * distant porte elle aussi les trois en-tetes et le type {@code problem+json}.
 */
@QuarkusTest
@TestProfile(SecurityHeaderParityOutageTest.ParityOutageProfile.class)
class SecurityHeaderParityOutageTest {

    public static class ParityOutageProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.rest-client.catalogue.url", "http://localhost:1",
                    "quarkus.rest-client.simulation.url", "http://localhost:1");
        }
    }

    @Test
    void serviceUnavailableCarriesBothProblemTypeAndHeaders() {
        Response response = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts");

        assertEquals(503, response.statusCode(),
                "un catalogue injoignable doit rendre 503 ; corps=" + response.asString());
        SecurityHeaderParityTest.assertProblemJson(response, "503 catalogue indisponible");
        SecurityHeaderParityTest.assertKeptHeaders(response, "503 catalogue indisponible");
    }
}
