package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.application.ConfirmationService;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.ClientErrorException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 17-souscription-service-rest-confirmation.
 *
 * <p>Baseline reference: BASELINE.md section 4.1 —
 * src/main/java/fr/demo/souscription/action/SubscriptionAction.java. The source expressed its
 * confirmation contract implicitly: session null-checks, recovery redirects and a bare
 * {@code sendError(409)}. What must be reproduced is the DECISION LOGIC behind each of those
 * situations, now observable as a declared HTTP status.
 *
 * <p>Adapted to this module's harness: the PostgreSQL Dev Services profile and the
 * container-runtime guard shared by the other integration tests. The row count of step 2 is read
 * with an {@link EntityManager} native query, as in the other persistence parity tests, instead of
 * raw JDBC. The {@code PRECONDITION_SEQUENCE} code of step 4 is asserted on the service outcome
 * because the {@code problem+json} body that carries it over HTTP is installed by plan 22.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class ConfirmationResourceParityTest {

    @Inject
    EntityManager entityManager;

    @Inject
    ConfirmationService confirmationService;

    private String startDraft() {
        return given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
    }

    private void putSubscriber(String draftId) {
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);
    }

    private String putSimulation(String draftId) {
        return given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200)
                .extract().path("confirmationKey");
    }

    private String completeDraft(String draftId) {
        putSubscriber(draftId);
        return putSimulation(draftId);
    }

    private long rowsForKey(String confirmationKey) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("select count(*) from subscriptions.subscriptions"
                        + " where confirmation_key = cast(?1 as uuid)")
                .setParameter(1, confirmationKey)
                .getSingleResult()).longValue());
    }

    /** Step 1: the 201 path and the five frozen fields of SubscriptionConfirmation, no more. */
    @Test
    void firstConfirmationReturns201WithTheFrozenBody() {
        String draftId = startDraft();
        String key = completeDraft(draftId);

        Map<String, Object> body = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201)
                .body("reference", notNullValue())
                .body("product.code", equalTo("PRUDENT"))
                .body("subscriber.email", equalTo("claire.martin@example.com"))
                .body("simulation.finalCapital", notNullValue())
                .body("createdAt", notNullValue())
                .extract().jsonPath().getMap("$");

        // additionalProperties: false — returning the confirmation key after use would widen the
        // replay surface, so it must not appear in the body.
        org.hamcrest.MatcherAssert.assertThat(body.keySet(),
                containsInAnyOrder("reference", "product", "subscriber", "simulation",
                        "createdAt"));
    }

    /** Step 2: the idempotent replay returns 200, the same reference, and creates no second row. */
    @Test
    void replayReturns200WithTheSameReference() {
        String draftId = startDraft();
        String key = completeDraft(draftId);

        String reference = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201)
                .extract().path("reference");

        String replayed = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(200)
                .extract().path("reference");

        assertEquals(reference, replayed,
                "a replay must return the same reference: a new one means a duplicate "
                + "subscription was sold");
        assertEquals(1L, rowsForKey(key),
                "exactly one subscriptions row may exist per confirmation key");
    }

    /** Step 3: a random key and a stale key (captured before a later PUT) both yield 409. */
    @Test
    void keyMismatchYields409() {
        String draftId = startDraft();
        completeDraft(draftId);

        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(409);

        String stale = given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().path("confirmationKey");
        putSubscriber(draftId);

        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + stale + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(409);
    }

    /** Step 4: an incomplete funnel yields 409 with code PRECONDITION_SEQUENCE, never 500. */
    @Test
    void incompleteFunnelYields409() {
        String noSubscriber = startDraft();
        String keyA = given().when().get("/v1/drafts/" + noSubscriber)
                .then().statusCode(200).extract().path("confirmationKey");
        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + keyA + "\"}")
                .when().post("/v1/drafts/" + noSubscriber + "/confirmation")
                .then().statusCode(409);

        String noSimulation = startDraft();
        putSubscriber(noSimulation);
        String keyB = given().when().get("/v1/drafts/" + noSimulation)
                .then().statusCode(200).extract().path("confirmationKey");
        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + keyB + "\"}")
                .when().post("/v1/drafts/" + noSimulation + "/confirmation")
                .then().statusCode(409);

        ClientErrorException refused = assertThrows(ClientErrorException.class,
                () -> QuarkusTransaction.requiringNew().call(
                        () -> confirmationService.confirm(UUID.fromString(noSimulation),
                                UUID.fromString(keyB))));
        assertEquals(409, refused.getResponse().getStatus());
        assertTrue(refused.getMessage().contains(ConfirmationService.PRECONDITION_SEQUENCE),
                "the refusal must carry the code PRECONDITION_SEQUENCE; got "
                + refused.getMessage());
    }

    /** Step 5: an unknown draft and an unknown reference are 404, and no 410 exists anywhere. */
    @Test
    void unknownDraftAndReferenceYield404() {
        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post("/v1/drafts/" + UUID.randomUUID() + "/confirmation")
                .then().statusCode(404);

        given().when().get("/v1/subscriptions/" + UUID.randomUUID())
                .then().statusCode(404);

        // A malformed reference must not surface as a 500: the contract declares 200/404/500 only.
        given().when().get("/v1/subscriptions/not-a-uuid")
                .then().statusCode(404);
    }

    /** Step 7: the confirmed subscription is read from the denormalised row, not the catalogue. */
    @Test
    void getSubscriptionIsDenormalised() {
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
                .body("product.name", notNullValue())
                .body("product.profile", notNullValue())
                .body("product.annualRate", notNullValue())
                .body("subscriber.email", equalTo("claire.martin@example.com"))
                .body("simulation.finalCapital", notNullValue())
                .body("createdAt", notNullValue());
    }

    /** Step 9: the request body is exactly the frozen ConfirmationRequest. */
    @Test
    void confirmationRequestRejectsUnknownFields() {
        String draftId = startDraft();
        String key = completeDraft(draftId);

        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\",\"accepted\":true}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body("{}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(400);
    }

    /** Step 11: a write on /v1/subscriptions/{reference} is the native 405, not a 404. */
    @Test
    void writeOnSubscriptionsYields405() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/v1/subscriptions/" + UUID.randomUUID())
                .then().statusCode(405);
    }
}
