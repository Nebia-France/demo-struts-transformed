package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Volet « sur le fil » du controle de parite : declaration de l'encodage UTF-8 sur les reponses de
 * succes et aller-retour d'un nom non ASCII verifie au niveau des octets.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class SecurityHeaderParityWireTest {

    /** Chaque reponse de succes declare l'encodage UTF-8 et porte les trois en-tetes. */
    @Test
    void responsesDeclareUtf8() {
        Response started = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts");
        utf8(started, 201, "POST /v1/drafts");
        String draftId = started.path("draftId");

        utf8(given().when().get("/v1/drafts/" + draftId), 200, "GET /v1/drafts/{draftId}");
        utf8(given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber"), 200,
                "PUT /v1/drafts/{draftId}/subscriber");
        utf8(given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation"), 200,
                "PUT /v1/drafts/{draftId}/simulation");

        String key = given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().path("confirmationKey");
        Response confirmed = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation");
        utf8(confirmed, 201, "POST /v1/drafts/{draftId}/confirmation");

        utf8(given().when().get("/v1/subscriptions/" + confirmed.path("reference")), 200,
                "GET /v1/subscriptions/{reference}");
    }

    /**
     * Un nom contenant {@code e} accentue revient avec les octets UTF-8 {@code 0xC3 0xA9} et sans
     * signature de double encodage ({@code 0xC3 0x83}).
     */
    @Test
    void frenchCharactersRoundTripUnchanged() {
        String draftId = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");

        String lastName = "Hervé";
        Response updated = given().contentType("application/json;charset=UTF-8")
                .body(("{\"firstName\":\"Zoé\",\"lastName\":\"" + lastName + "\","
                        + "\"email\":\"zoe.herve@example.com\",\"birthDate\":\"1979-11-02\"}")
                        .getBytes(StandardCharsets.UTF_8))
                .when().put("/v1/drafts/" + draftId + "/subscriber");
        assertEquals(200, updated.statusCode(),
                "la mise a jour accentuee doit rendre 200 ; corps=" + updated.asString());

        assertEquals(lastName, updated.path("subscriber.lastName"),
                "le nom accentue doit revenir inchange");

        byte[] raw = updated.asByteArray();
        assertTrue(contains(raw, new byte[] {(byte) 0xC3, (byte) 0xA9}),
                "le corps doit contenir les octets UTF-8 0xC3 0xA9 pour « é »");
        assertFalse(contains(raw, new byte[] {(byte) 0xC3, (byte) 0x83}),
                "le corps ne doit pas porter la signature de double encodage 0xC3 0x83");

        assertEquals(lastName, given().when().get("/v1/drafts/" + draftId)
                        .then().statusCode(200).extract().path("subscriber.lastName"),
                "la relecture doit rendre la meme chaine");
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void utf8(Response response, int expected, String operation) {
        assertEquals(expected, response.statusCode(),
                operation + " doit repondre " + expected + " ; corps=" + response.asString());
        SecurityHeaderParityTest.assertKeptHeaders(response, operation);
        String contentType = String.valueOf(response.getContentType()).toLowerCase(Locale.ROOT);
        assertTrue(contentType.contains("json"),
                operation + " doit repondre en JSON ; contentType=" + contentType);
        assertTrue(contentType.contains("charset=utf-8"),
                operation + " doit declarer charset=UTF-8 ; contentType=" + contentType);
    }
}
