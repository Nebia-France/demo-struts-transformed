package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * W3-T05 : les quatre operations du contrat gele sur {@code /v1/drafts}. Statuts et forme des
 * corps uniquement ; les assertions metier fines appartiennent aux plans 20-23 et 27-28.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class DraftResourceTest {

    private String startDraft() {
        return given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
    }

    @Test
    void startDraftReturns201WithLocationAndDraftBody() {
        given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .header("Location", startsWith("/v1/drafts/"))
                .body("draftId", notNullValue())
                .body("confirmationKey", notNullValue())
                .body("status", equalTo("STARTED"))
                .body("product.code", equalTo("PRUDENT"));
    }

    @Test
    void startDraftReturns404OnAnUnknownProductCode() {
        given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"INCONNU\"}")
                .when().post("/v1/drafts")
                .then().statusCode(404);
    }

    @Test
    void getDraftReturns200OnAnExistingDraft() {
        String draftId = startDraft();

        given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200)
                .body("draftId", equalTo(draftId))
                .body("status", equalTo("STARTED"));
    }

    @Test
    void getDraftReturns404OnAnUnknownDraftId() {
        given().when().get("/v1/drafts/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void putDraftSubscriberReturns200AndRotatesTheConfirmationKey() {
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200)
                .body("status", equalTo("SUBSCRIBER_CAPTURED"))
                .body("subscriber.email", equalTo("claire.martin@example.com"))
                .body("confirmationKey", notNullValue());
    }

    @Test
    void putDraftSubscriberReturns400OnANonCalendarBirthDate() {
        // 2023-02-31 respecte le motif YYYY-MM-DD mais n'existe pas : resolution stricte, donc un
        // 400 structure et jamais un decalage silencieux vers le 28 fevrier (parite
        // ResolverStyle.STRICT du source).
        String draftId = startDraft();

        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"2023-02-31\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(400);
    }

    @Test
    void putDraftSubscriberReturns404OnAnUnknownDraftId() {
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().statusCode(404);
    }

    @Test
    void putDraftSimulationReturns200WithTheServerComputedResult() {
        String draftId = startDraft();
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200)
                .body("status", equalTo("SIMULATED"))
                .body("simulation.contributed", notNullValue())
                .body("simulation.finalCapital", notNullValue());
    }

    @Test
    void putDraftSimulationReturns404OnAnUnknownDraftId() {
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/simulation")
                .then().statusCode(404);
    }

    /** 405 natif JAX-RS : aucune methode DELETE n'est declaree, aucune garde ecrite a la main. */
    @Test
    void deleteOnADraftReturnsTheNative405() {
        given().when().delete("/v1/drafts/" + UUID.randomUUID())
                .then().statusCode(405);
    }

    /** 404 natif JAX-RS sur un chemin non declare. */
    @Test
    void anUndeclaredPathReturnsTheNative404() {
        given().when().get("/v1/drafts/" + UUID.randomUUID() + "/inconnu")
                .then().statusCode(404);
    }
}
