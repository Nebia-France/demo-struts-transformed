package fr.demo.souscription.gateway.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers the 8 routes of {@code docs/routing-matrix.md} against the provisional stub clients.
 * Error payloads are normalised in plan 12, so only statuses and the happy-path bodies are
 * asserted here.
 */
@QuarkusTest
class GatewayApiRoutesTest {

    @Test
    void listProductsAnswers200() {
        given().when().get("/api/v1/products")
                .then().statusCode(200)
                .body("size()", is(3));
    }

    @Test
    void getProductAnswers200() {
        given().when().get("/api/v1/products/PRUDENT")
                .then().statusCode(200)
                .body("code", is("PRUDENT"))
                .body("annualRate", is("0.020000"));
    }

    @Test
    void getUnknownProductAnswers404() {
        given().when().get("/api/v1/products/INCONNU")
                .then().statusCode(404);
    }

    @Test
    void simulateAnswers200() {
        fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("productCode", "PRUDENT", "initialAmount", "1000.00",
                        "monthlyAmount", "100.00", "durationYears", 10))
                .when().post("/api/v1/simulations")
                .then().statusCode(200)
                .body("finalCapital", notNullValue());
    }

    @Test
    void startDraftAnswers201WithLocation() {
        fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts")
                .then().statusCode(201)
                .header("Location", notNullValue())
                .body("status", is("STARTED"));
    }

    @Test
    void getDraftAnswers200() {
        String draftId = startDraft();
        given().when().get("/api/v1/drafts/" + draftId)
                .then().statusCode(200)
                .body("draftId", is(draftId));
    }

    @Test
    void getUnknownDraftAnswers404() {
        given().when().get("/api/v1/drafts/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void putDraftSubscriberAnswers200() {
        fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("firstName", "Alice", "lastName", "Durand",
                        "email", "alice@example.com", "birthDate", "1990-01-15"))
                .when().put("/api/v1/drafts/" + startDraft() + "/subscriber")
                .then().statusCode(200)
                .body("status", is("SUBSCRIBER_CAPTURED"));
    }

    @Test
    void putDraftSimulationAnswers200() {
        fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("initialAmount", "1000.00", "monthlyAmount", "100.00",
                        "durationYears", 10))
                .when().put("/api/v1/drafts/" + startDraft() + "/simulation")
                .then().statusCode(200)
                .body("status", is("SIMULATED"));
    }

    @Test
    void confirmDraftAnswers201ThenReplaysWith200() {
        String draftId = startDraft();
        String confirmationKey = given().when().get("/api/v1/drafts/" + draftId)
                .then().statusCode(200)
                .extract().path("confirmationKey");

        String reference = fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", confirmationKey))
                .when().post("/api/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201)
                .header("Location", notNullValue())
                .extract().path("reference");

        fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", confirmationKey))
                .when().post("/api/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(200)
                .body("reference", is(reference));

        given().when().get("/api/v1/subscriptions/" + reference)
                .then().statusCode(200)
                .body("reference", is(reference));
    }

    @Test
    void getUnknownSubscriptionAnswers404() {
        given().when().get("/api/v1/subscriptions/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void unknownPathUnderApiV1Answers404() {
        given().when().get("/api/v1/unknown")
                .then().statusCode(404);
    }

    private String startDraft() {
        return fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
    }
}
