package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * W7-T01 : le service est sans cookie et sans session.
 *
 * <p>Les six operations du contrat gele sont pilotees ; aucune reponse ne doit porter de
 * {@code Set-Cookie}, et {@code JSESSIONID} ne doit apparaitre dans aucun en-tete ni dans aucun
 * corps. Le statut des reponses est hors sujet ici (la base est inactive dans ce profil) : seule
 * l'absence d'etat cote client est verifiee.
 */
@QuarkusTest
class NoCookieTest {

    private static final String DRAFT = "/v1/drafts/" + UUID.randomUUID();

    @Test
    void noOperationSetsACookie() {
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts"));
        assertNoCookie(() -> given().when().get(DRAFT));
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put(DRAFT + "/subscriber"));
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":10}")
                .when().put(DRAFT + "/simulation"));
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post(DRAFT + "/confirmation"));
        assertNoCookie(() -> given().when().get("/v1/subscriptions/REF-2024-000001"));
    }

    private static void assertNoCookie(Supplier<Response> call) {
        Response response = call.get();
        assertTrue(response.getHeaders().getValues("Set-Cookie").isEmpty(),
                "aucune reponse ne doit poser de cookie");
        assertTrue(response.getDetailedCookies().size() == 0,
                "aucun cookie ne doit etre renvoye");
        boolean sessionIdLeaked = response.getHeaders().asList().stream()
                .anyMatch(header -> header.getName().contains("JSESSIONID")
                        || String.valueOf(header.getValue()).contains("JSESSIONID"));
        assertTrue(!sessionIdLeaked, "aucun en-tete ne doit mentionner JSESSIONID");
        assertTrue(!response.asString().contains("JSESSIONID"),
                "aucun corps ne doit mentionner JSESSIONID");
    }
}
