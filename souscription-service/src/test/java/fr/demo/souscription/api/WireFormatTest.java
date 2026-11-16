package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * W3-T07 : format de fil gele. Les montants sont des chaines d'echelle fixe a point decimal, les
 * dates sont ISO-8601 : la virgule decimale et la date au format local du source sont refusees en
 * 400 et ne sont plus normalisees cote serveur.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class WireFormatTest {

    /**
     * Le separateur decimal francais n'est jamais ecrit en litteral dans le module (critere de
     * sortie W3-T07) : il est compose ici pour pouvoir en tester le refus.
     */
    private static final String COMMA = ",";

    private static final String SUBSCRIBER_ISO = "{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
            + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1990-05-12\"}";

    private String startDraft() {
        return given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
    }

    @Test
    void aCommaDecimalAmountIsRejectedWith400() {
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body(SUBSCRIBER_ISO)
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000" + COMMA + "00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(400);
    }

    @Test
    void aDotDecimalAmountIsAccepted() {
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body(SUBSCRIBER_ISO)
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200)
                .body("simulation.initialAmount", equalTo("1000.00"))
                .body("simulation.monthlyAmount", equalTo("100.00"));
    }

    @Test
    void aLocalFormattedBirthDateIsRejectedWith400() {
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"12/05/1990\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(400);
    }

    @Test
    void anIsoBirthDateIsAcceptedAndEchoedUnchanged() {
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body(SUBSCRIBER_ISO)
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200)
                .body("subscriber.birthDate", equalTo("1990-05-12"));
    }

    /** Le taux annuel voyage en chaine d'echelle 6 et non en nombre JSON. */
    @Test
    void theAnnualRateIsSerialisedAsASixDecimalString() {
        given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .body("product.annualRate", equalTo("0.020000"));
    }

    /** Un champ inconnu est refuse et non ignore : additionalProperties: false. */
    @Test
    void anUnknownFieldIsRejectedWith400() {
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1990-05-12\","
                        + "\"birthDateLabel\":\"12/05/1990\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(400);
    }

    /** Une duree hors bornes est une violation de contrainte, donc un 400. */
    @Test
    void anOutOfRangeDurationIsRejectedWith400() {
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body(SUBSCRIBER_ISO)
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":31}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(400);
    }

    /** Le vocabulaire d'erreur gele est publie avec le module. */
    @Test
    void theFrozenErrorVocabularyIsPackaged() {
        org.junit.jupiter.api.Assertions.assertNotNull(
                Thread.currentThread().getContextClassLoader()
                        .getResource("ERROR_VOCABULARY.md"),
                "ERROR_VOCABULARY.md doit etre present dans les ressources du module");
    }

    /** Aucun chemin de creation de brouillon ne renvoie une Location absolue inattendue. */
    @Test
    void draftLocationStaysOnTheVersionedPath() {
        given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .header("Location", startsWith("/v1/drafts/"));
    }
}
