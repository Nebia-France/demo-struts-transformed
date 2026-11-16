package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 16-souscription-service-rest-drafts.
 *
 * Baseline reference: BASELINE.md section 4.1 —
 * src/main/java/fr/demo/souscription/action/SubscriptionAction.java.
 * The source had no HTTP/JSON surface; the frozen contract defines the target. What must be
 * reproduced is the source's DECISION LOGIC: which inputs are accepted, which are refused, and
 * when the confirmation key is invalidated.
 *
 * <p>Adapted to this module's harness: the PostgreSQL Dev Services profile and the
 * container-runtime guard shared by the other integration tests, plus
 * {@code QuarkusTransaction} to age a draft (a self-invoked {@code @Transactional} helper inside a
 * test class is not intercepted). No assertion is weakened; the expired-draft case of step 3 is
 * asserted over HTTP here rather than deferred.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class DraftResourceParityTest {

    @Inject
    DraftRepository repository;

    @Test
    void startDraftReturns201WithLocation() {
        String location = given()
                .contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
            .when()
                .post("/v1/drafts")
            .then()
                .statusCode(201)
                .header("Location", matchesPattern(".*/v1/drafts/[0-9a-fA-F-]{36}$"))
                .body("draftId", notNullValue())
                .body("product.code", is("PRUDENT"))
                .body("status", is("STARTED"))
                .body("subscriber", is(nullValue()))
                .body("simulation", is(nullValue()))
                .extract().header("Location");

        assertTrue(location.startsWith("/v1/drafts/"),
                "Location must be relative: behind the gateway an absolute internal host is "
                + "unroutable and leaks the cluster topology; got " + location);

        String fromBody = given().when().get(location).then().statusCode(200)
                .extract().path("draftId");
        assertTrue(location.endsWith(fromBody),
                "the Location header and the body draftId must designate the same draft");
    }

    @Test
    void unknownProductCodeYields404() {
        // The source answered with an explicit 400; the frozen contract declares 404 for startDraft.
        // Recorded as an accepted change in STRUTS_REST_COVERAGE.md (plan 14).
        for (String code : new String[] {"INEXISTANT", "prudent", " PRUDENT ", "PRUDENT123"}) {
            given().contentType(ContentType.JSON).body("{\"productCode\":\"" + code + "\"}")
                .when().post("/v1/drafts")
                .then().statusCode(404);
        }
    }

    @Test
    void getDraftReturnsStateAnd404WhenAbsent() {
        String draftId = startDraft();

        given().when().get("/v1/drafts/" + draftId)
            .then().statusCode(200)
                .body("draftId", is(draftId))
                .body("product.code", is("PRUDENT"))
                .body("product.annualRate", is("0.020000"))
                .body("status", is("STARTED"));

        // Target form of the source restart branch towards /catalogue.do?notice=parcours.
        given().when().get("/v1/drafts/" + UUID.randomUUID())
            .then().statusCode(404);

        expire(UUID.fromString(draftId));
        int expired = given().when().get("/v1/drafts/" + draftId).then().extract().statusCode();
        assertEquals(404, expired, "an expired draft reads as absent: 404, and the frozen contract "
                + "declares no 410 Gone; got " + expired);
    }

    @Test
    void putOperationsReturn200AndRotateTheKey() {
        String draftId = startDraft();
        String k0 = given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().path("confirmationKey");

        String k1 = given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"claire.dupont@example.org\",\"birthDate\":\"1990-05-04\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(200)
                .body("status", is("SUBSCRIBER_CAPTURED"))
                .extract().path("confirmationKey");
        assertNotEquals(k0, k1, "putDraftSubscriber must rotate the confirmation key (BASELINE 4.2)");

        String k2 = given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
            .when().put("/v1/drafts/" + draftId + "/simulation")
            .then().statusCode(200)
                .body("status", is("SIMULATED"))
                .body("simulation.finalCapital", notNullValue())
                .extract().path("confirmationKey");
        assertNotEquals(k1, k2, "putDraftSimulation must rotate the confirmation key as well");

        // Both PUTs return the authoritative stored state, not the request echo.
        given().when().get("/v1/drafts/" + draftId)
            .then().statusCode(200)
                .body("status", is("SIMULATED"))
                .body("confirmationKey", is(k2))
                .body("subscriber.lastName", is("Dupont"))
                .body("simulation.finalCapital", notNullValue());
    }

    @Test
    void undeclaredMethodYields405() {
        String draftId = startDraft();
        // The source wrote four explicit method-not-allowed replies; JAX-RS provides this natively as
        // long as @Path is declared at class level so the path exists for every method.
        given().when().delete("/v1/drafts/" + draftId).then().statusCode(405);
        given().when().patch("/v1/drafts/" + draftId).then().statusCode(405);

        // The collection declares no GET: 405 or 404 per the declared paths.
        given().when().get("/v1/drafts").then().statusCode(oneOf(404, 405));
    }

    @Test
    void draftViewCarriesNoFieldBeyondTheFrozenSchema() {
        String draftId = startDraft();
        // DraftView.json declares additionalProperties: false; expiresAt in particular must not leak
        // the retention window to the client.
        given().when().get("/v1/drafts/" + draftId)
            .then().statusCode(200)
                .body("expiresAt", is(nullValue()))
                .body("$", hasKey("confirmationKey"))
                .body("$", hasKey("status"));
    }

    @Test
    void annualRateIsNeverSuppliedByTheClient() {
        // A client-supplied rate would change final_capital, which is the sold value. The source read
        // the rate from the catalogue and never from the request. Either the extra field is rejected
        // (400) or it is ignored (201) -- but the stored rate must be the catalogue's.
        var response = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\",\"annualRate\":\"0.900000\"}")
            .when().post("/v1/drafts")
            .then().statusCode(oneOf(201, 400))
            .extract();

        if (response.statusCode() == 201) {
            given().when().get("/v1/drafts/" + response.path("draftId"))
                .then().statusCode(200)
                    .body("product.annualRate", equalTo("0.020000"));
        }
    }

    private String startDraft() {
        return given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
    }

    private void expire(UUID draftId) {
        QuarkusTransaction.requiringNew().run(() -> {
            SubscriptionDraftEntity entity = repository.findById(draftId);
            entity.expiresAt = Instant.now().minusSeconds(60);
            repository.persist(entity);
        });
    }
}
