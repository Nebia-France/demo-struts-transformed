package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * W3-T06 : contrat de statut explicite de {@code confirmDraft} et {@code getSubscription}.
 *
 * <p>Remplace les semantiques implicites de la source (null-check de session, redirections de
 * reprise, conflit brut) : 201 a la premiere confirmation, 200 au rejeu de la meme cle,
 * 409 sur cle divergente ou parcours incomplet, 404 sur brouillon ou reference inconnus.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class ConfirmationResourceTest {

    private String startDraft() {
        return given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
    }

    private String completeDraft(String draftId) {
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200)
                .extract().path("confirmationKey");
    }

    @Test
    void confirmDraftReturns201ThenReplaysWith200AndTheSameReference() {
        String draftId = startDraft();
        String key = completeDraft(draftId);

        String reference = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201)
                .body("reference", notNullValue())
                .body("product.code", equalTo("PRUDENT"))
                .body("product.annualRate", equalTo("0.020000"))
                .body("subscriber.email", equalTo("claire.martin@example.com"))
                .body("simulation.contributed", equalTo("13000.00"))
                .body("createdAt", notNullValue())
                .extract().path("reference");

        String replayed = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(200)
                .extract().path("reference");
        assertEquals(reference, replayed,
                "replaying the same confirmationKey must return the same reference and create no "
                + "second row");
    }

    @Test
    void confirmDraftReturns409OnAMismatchedKey() {
        String draftId = startDraft();
        completeDraft(draftId);

        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(409);
    }

    @Test
    void confirmDraftReturns409OnAnIncompleteDraft() {
        String draftId = startDraft();
        String key = given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().path("confirmationKey");

        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(409);
    }

    @Test
    void confirmDraftReturns404OnAnUnknownDraftId() {
        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post("/v1/drafts/" + UUID.randomUUID() + "/confirmation")
                .then().statusCode(404);
    }

    @Test
    void getSubscriptionReturns200OnAConfirmedReferenceAnd404OnAnUnknownOne() {
        String draftId = startDraft();
        String key = completeDraft(draftId);
        String reference = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201)
                .extract().path("reference");

        given().when().get("/v1/subscriptions/" + reference)
                .then().statusCode(200)
                .body("reference", equalTo(reference))
                .body("product.code", equalTo("PRUDENT"))
                .body("simulation.finalCapital", notNullValue());

        given().when().get("/v1/subscriptions/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    /** 405 natif JAX-RS : aucune methode POST n'est declaree sur la ressource de lecture. */
    @Test
    void postOnASubscriptionReturnsTheNative405() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/v1/subscriptions/" + UUID.randomUUID())
                .then().statusCode(405);
    }
}
