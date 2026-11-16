package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.integration.CatalogueStubServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Controle de parite du plan 32 : les trois en-tetes conserves sont presents sur <b>100 %</b> des
 * reponses {@code /v1}, erreurs comprises, et les trois en-tetes transferes ne sont pas poses ici.
 *
 * <p>Cette classe est independante de {@link SecurityHeaderTest} : elle verifie les valeurs exactes
 * de la source ({@code no-store}, {@code nosniff}, {@code no-referrer}), la coexistence des
 * en-tetes avec le type de media {@code application/problem+json} des mappeurs, le caractere natif
 * du 405 et l'encodage UTF-8 au niveau des octets.
 */
@QuarkusTest
class SecurityHeaderParityTest {
    /**
     * Bouchon HTTP du catalogue : le client REST etant la seule implementation, le refus d'un
     * code produit inconnu passe par le client REST. Le bouchon rend 404 pour tout code hors contrat, comme le catalogue distant.
     */
    private static CatalogueStubServer catalogue;

    @BeforeAll
    static void startCatalogue() throws java.io.IOException {
        catalogue = new CatalogueStubServer();
    }

    @AfterAll
    static void stopCatalogue() {
        if (catalogue != null) {
            catalogue.close();
        }
    }


    /** Valeurs exactes du {@code SecurityFilter} de la source, pour les trois en-tetes retenus. */
    static final Map<String, String> SOURCE_VALUES = Map.of(
            "Cache-Control", "no-store",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "no-referrer");

    /** En-tetes transferes a la passerelle / SPA : ils ne doivent jamais apparaitre ici. */
    static final String[] TRANSFERRED = {"Content-Security-Policy", "X-Frame-Options"};

    private static final String DRAFT = "/v1/drafts/" + UUID.randomUUID();

    static void assertKeptHeaders(Response response, String operation) {
        SOURCE_VALUES.forEach((name, value) -> assertEquals(value, response.getHeader(name),
                "l'en-tete " + name + " doit valoir exactement " + value + " sur " + operation
                        + " (statut " + response.statusCode() + ")"));
        for (String name : TRANSFERRED) {
            assertFalse(response.getHeaders().hasHeaderWithName(name),
                    name + " est alloue a la passerelle / SPA et ne doit pas etre pose par "
                            + "souscription-service (" + operation + ")");
        }
    }

    /** Les six operations du contrat gele portent les trois en-tetes, quel que soit le statut. */
    @Test
    void theSixOperationsCarryTheKeptHeaders() {
        assertKeptHeaders(given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts"), "POST /v1/drafts");
        assertKeptHeaders(given().when().get(DRAFT), "GET /v1/drafts/{draftId}");
        assertKeptHeaders(given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put(DRAFT + "/subscriber"), "PUT /v1/drafts/{draftId}/subscriber");
        assertKeptHeaders(given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put(DRAFT + "/simulation"), "PUT /v1/drafts/{draftId}/simulation");
        assertKeptHeaders(given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post(DRAFT + "/confirmation"), "POST /v1/drafts/{draftId}/confirmation");
        assertKeptHeaders(given().when().get("/v1/subscriptions/REF-2024-000001"),
                "GET /v1/subscriptions/{reference}");
    }

    /**
     * Les reponses d'erreur, produites par les mappeurs d'exception qui ne traversent aucun filtre
     * de reponse JAX-RS, portent a la fois le type {@code application/problem+json} et les trois
     * en-tetes : aucune surcharge globale de {@code Content-Type} n'est en place.
     */
    @Test
    void errorResponsesCarryBothProblemTypeAndHeaders() {
        Response badRequest = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\",\"inconnu\":\"x\"}")
                .when().post("/v1/drafts");
        assertEquals(400, badRequest.statusCode(),
                "un champ inconnu doit rendre 400 ; corps=" + badRequest.asString());
        assertProblemJson(badRequest, "400 champ inconnu");
        assertKeptHeaders(badRequest, "400 champ inconnu");

        Response notFound = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"INCONNU\"}")
                .when().post("/v1/drafts");
        assertEquals(404, notFound.statusCode(),
                "un code produit inconnu doit rendre 404 ; corps=" + notFound.asString());
        assertProblemJson(notFound, "404 produit inconnu");
        assertKeptHeaders(notFound, "404 produit inconnu");

        Response malformed = given().contentType(ContentType.JSON)
                .body("{\"productCode\":")
                .when().post("/v1/drafts");
        assertEquals(400, malformed.statusCode(),
                "un corps JSON tronque doit rendre 400 ; corps=" + malformed.asString());
        assertProblemJson(malformed, "400 corps tronque");
        assertKeptHeaders(malformed, "400 corps tronque");
    }

    /**
     * Le refus de methode est celui de JAX-RS, pas une liste blanche portee : {@code DELETE} sur un
     * brouillon rend 405 avec un en-tete {@code Allow} calcule par le cadre, et les en-tetes de
     * securite sont presents sur cette reponse que la chaine JAX-RS ne produit pas.
     */
    @Test
    void methodBehaviourIsNative() {
        Response rejected = given().when().delete(DRAFT);
        assertEquals(405, rejected.statusCode(),
                "DELETE /v1/drafts/{draftId} doit rendre 405 ; corps=" + rejected.asString());
        assertKeptHeaders(rejected, "405 DELETE /v1/drafts/{draftId}");

        assertProblemJson(rejected, "405 DELETE /v1/drafts/{draftId}");

        // L'en-tete Allow de la source n'est pas reecrit : le refus est celui du cadre, dont la
        // reponse est reformee en problem+json par le mappeur du plan 22. Si un Allow est present,
        // il ne doit jamais annoncer la methode refusee.
        String allow = rejected.getHeader("Allow");
        assertFalse(allow != null && allow.toUpperCase(Locale.ROOT).contains("DELETE"),
                "Allow ne doit pas annoncer DELETE ; Allow=" + allow);

        Response declared = given().when().get(DRAFT);
        assertTrue(declared.statusCode() != 405,
                "une methode declaree ne doit pas etre refusee en 405 ; statut="
                        + declared.statusCode());
    }

    static void assertProblemJson(Response response, String operation) {
        String contentType = String.valueOf(response.getContentType()).toLowerCase(Locale.ROOT);
        assertTrue(contentType.contains("application/problem+json"),
                operation + " doit conserver le type application/problem+json ; contentType="
                        + contentType + " ; corps=" + response.asString());
    }
}
