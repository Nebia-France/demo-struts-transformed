package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import fr.demo.souscription.integration.CatalogueStubServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * W7-T03 : les trois en-tetes de securite pertinents pour une API JSON sont presents sur
 * <b>100 %</b> des reponses {@code /v1}.
 *
 * <p>Le {@code SecurityFilter} servlet de la source posait six en-tetes sur ses reponses HTML.
 * Trois ont un sens sur une reponse JSON et restent ici, portes par la couche HTTP de Quarkus
 * ({@code quarkus.http.header.*}) ; les trois autres concernent le document HTML ou la restriction
 * de methodes et sont alloues a souscription-gateway / souscription-web, l'allocation complete
 * etant tracee dans {@code SECURITY_GATES.md}.
 *
 * <p>Cette classe pilote les six operations du contrat gele et les reponses 400, 404 et 405 sans
 * exiger de base : les en-tetes etant poses par la couche HTTP et non par une ressource, ils sont
 * presents quel que soit le statut et quel que soit le corps, y compris
 * {@code application/problem+json}. Le 503 est verifie par {@link SecurityHeaderOutageTest} et les
 * assertions qui exigent une reponse de succes reelle (type de contenu, aller-retour UTF-8,
 * statuts documentes) par {@link SecurityHeaderWireTest}.
 *
 * <p>Deux comportements de la source ne sont volontairement pas reimplementes et sont verifies par
 * test plutot que par un filtre : la restriction de methodes (405 natif de JAX-RS) et l'encodage
 * UTF-8 (defaut de Quarkus REST).
 */
@QuarkusTest
class SecurityHeaderTest {
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


    /** Les trois en-tetes retenus, avec leur valeur exacte. */
    static final Map<String, String> EXPECTED = Map.of(
            "Cache-Control", "no-store",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "no-referrer");

    static final String SUBSCRIBER = "{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
            + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}";

    static final String SIMULATION = "{\"initialAmount\":\"1000.00\","
            + "\"monthlyAmount\":\"100.00\",\"durationYears\":10}";

    private static final String DRAFT = "/v1/drafts/" + UUID.randomUUID();

    /** Les trois en-tetes sont presents, avec leur valeur exacte, sur chaque reponse. */
    static void assertSecurityHeaders(Response response, String operation) {
        EXPECTED.forEach((name, value) -> assertEquals(value, response.getHeader(name),
                "l'en-tete " + name + " doit porter la valeur " + value + " sur " + operation
                        + " (statut " + response.statusCode() + ")"));
    }

    /** Le type de contenu d'une reponse declare l'encodage UTF-8. */
    static void assertUtf8Json(Response response, String operation) {
        String contentType = String.valueOf(response.getContentType()).toLowerCase(Locale.ROOT);
        assertTrue(contentType.contains("json"),
                operation + " doit repondre en JSON ; contentType=" + contentType);
        assertTrue(contentType.contains("charset=utf-8"),
                operation + " doit declarer charset=UTF-8 ; contentType=" + contentType);
    }

    private static Response headersOn(String operation, Supplier<Response> call) {
        Response response = call.get();
        assertSecurityHeaders(response, operation);
        return response;
    }

    private static Response statusAndHeaders(String operation, int expected,
                                             Supplier<Response> call) {
        Response response = headersOn(operation, call);
        assertEquals(expected, response.statusCode(),
                operation + " doit repondre " + expected + " ; corps=" + response.asString());
        return response;
    }

    /**
     * Les six operations du contrat gele portent les trois en-tetes. Le statut est hors sujet ici
     * (la base est inactive dans ce profil) : ce qui est prouve est qu'aucune reponse
     * d'aucune operation n'echappe aux en-tetes.
     */
    @Test
    void theSixOperationsCarryTheThreeHeaders() {
        headersOn("POST /v1/drafts", () -> given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts"));
        headersOn("GET /v1/drafts/{draftId}", () -> given().when().get(DRAFT));
        headersOn("PUT /v1/drafts/{draftId}/subscriber", () -> given()
                .contentType(ContentType.JSON).body(SUBSCRIBER)
                .when().put(DRAFT + "/subscriber"));
        headersOn("PUT /v1/drafts/{draftId}/simulation", () -> given()
                .contentType(ContentType.JSON).body(SIMULATION)
                .when().put(DRAFT + "/simulation"));
        headersOn("POST /v1/drafts/{draftId}/confirmation", () -> given()
                .contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post(DRAFT + "/confirmation"));
        headersOn("GET /v1/subscriptions/{reference}",
                () -> given().when().get("/v1/subscriptions/REF-2024-000001"));
    }

    /** Un champ inconnu : 400 {@code application/problem+json}, en-tetes inclus. */
    @Test
    void aBadRequestCarriesTheThreeHeaders() {
        statusAndHeaders("400 sur un champ inconnu", 400,
                () -> given().contentType(ContentType.JSON)
                        .body("{\"productCode\":\"PRUDENT\",\"inconnu\":\"x\"}")
                        .when().post("/v1/drafts"));
    }

    /**
     * Un code produit absent du catalogue : 404, en-tetes inclus. Le refus precede tout acces a la
     * base, l'assertion ne depend donc pas d'un conteneur.
     */
    @Test
    void aNotFoundCarriesTheThreeHeaders() {
        statusAndHeaders("404 sur un code produit inconnu", 404,
                () -> given().contentType(ContentType.JSON)
                        .body("{\"productCode\":\"INCONNU\"}")
                        .when().post("/v1/drafts"));
    }

    /**
     * {@code DELETE /v1/drafts/{draftId}} n'est declare par aucune methode de ressource : JAX-RS
     * repond nativement 405, aucune liste blanche de methodes n'etant ecrite a la main. Couvre le
     * scenario « Methodes HTTP non autorisees ».
     */
    @Test
    void anUnsupportedMethodYieldsANative405WithTheThreeHeaders() {
        statusAndHeaders("405 sur DELETE /v1/drafts/{draftId}", 405,
                () -> given().when().delete(DRAFT));
    }
}

/**
 * Volet 503 de W7-T03 : un catalogue injoignable repond 503 {@code application/problem+json}, et
 * cette reponse porte elle aussi les trois en-tetes.
 *
 * <p>Classe de test distincte parce que le 503 exige un profil different : le client REST du
 * catalogue doit etre actif et son fournisseur absent. Le refus se produit avant tout acces a la
 * base.
 */
@QuarkusTest
@TestProfile(SecurityHeaderOutageTest.CatalogueOutageProfile.class)
class SecurityHeaderOutageTest {

    /** Catalogue distant actif, pointe sur un port ou rien n'ecoute. */
    public static class CatalogueOutageProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.rest-client.catalogue.url", "http://localhost:1",
                    "quarkus.rest-client.simulation.url", "http://localhost:1");
        }
    }

    @Test
    void aServiceUnavailableCarriesTheThreeHeaders() {
        Response response = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts");

        assertEquals(503, response.statusCode(),
                "un catalogue injoignable doit rendre 503 et jamais 500 ; corps="
                        + response.asString());
        SecurityHeaderTest.assertSecurityHeaders(response, "503 catalogue indisponible");
    }
}

/**
 * Volet « reponse de succes reelle » de W7-T03 : type de contenu UTF-8, aller-retour de caracteres
 * francais non ASCII et statuts documentes des methodes declarees. Ces assertions exigent une base,
 * d'ou le profil Dev Services PostgreSQL et la desactivation en l'absence de daemon de conteneurs,
 * convention du module.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class SecurityHeaderWireTest {

    /**
     * Chaque reponse de succes declare {@code charset=UTF-8} et porte les trois en-tetes, et les
     * methodes declarees rendent leur statut documente : couvre les scenarios « L'encodage des
     * requetes et des reponses est UTF-8 » et « Methodes HTTP autorisees ».
     */
    @Test
    void everySuccessfulResponseIsUtf8JsonWithTheThreeHeaders() {
        Response started = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts");
        check(started, 201, "POST /v1/drafts");
        String draftId = started.path("draftId");

        check(given().when().get("/v1/drafts/" + draftId), 200, "GET /v1/drafts/{draftId}");
        check(given().contentType(ContentType.JSON).body(SecurityHeaderTest.SUBSCRIBER)
                .when().put("/v1/drafts/" + draftId + "/subscriber"), 200,
                "PUT /v1/drafts/{draftId}/subscriber");
        check(given().contentType(ContentType.JSON).body(SecurityHeaderTest.SIMULATION)
                .when().put("/v1/drafts/" + draftId + "/simulation"), 200,
                "PUT /v1/drafts/{draftId}/simulation");

        String key = given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().path("confirmationKey");
        Response confirmed = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation");
        check(confirmed, 201, "POST /v1/drafts/{draftId}/confirmation");

        check(given().when().get("/v1/subscriptions/" + confirmed.path("reference")), 200,
                "GET /v1/subscriptions/{reference}");
    }

    /**
     * Un corps contenant des caracteres francais non ASCII revient inchange, a l'echo comme a la
     * relecture : l'encodage UTF-8 est le defaut de Quarkus REST et aucun filtre d'encodage n'est
     * reecrit.
     */
    @Test
    void nonAsciiFrenchInputRoundTripsUnchanged() {
        String draftId = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");

        String firstName = "Noël";
        String lastName = "Françoise-Hélène";
        Response updated = given().contentType("application/json;charset=UTF-8")
                .body("{\"firstName\":\"" + firstName + "\",\"lastName\":\"" + lastName + "\","
                        + "\"email\":\"noel.herve@example.com\",\"birthDate\":\"1979-11-02\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber");
        check(updated, 200, "aller-retour UTF-8");

        assertEquals(firstName, updated.path("subscriber.firstName"),
                "le prenom accentue doit revenir inchange");
        assertEquals(lastName, updated.path("subscriber.lastName"),
                "le nom accentue doit revenir inchange");
        assertEquals(lastName, given().when().get("/v1/drafts/" + draftId)
                        .then().statusCode(200).extract().path("subscriber.lastName"),
                "la relecture doit rendre la meme chaine : aucune perte a la persistance");
    }

    private static void check(Response response, int expected, String operation) {
        assertEquals(expected, response.statusCode(),
                operation + " doit repondre " + expected + " ; corps=" + response.asString());
        SecurityHeaderTest.assertSecurityHeaders(response, operation);
        SecurityHeaderTest.assertUtf8Json(response, operation);
    }
}
