package fr.demo.souscription.gateway.api;

import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

/**
 * Parity skeleton for 07-souscription-gateway-rest-resources-api-v1.
 *
 * Asserts that the 8 journey operations of the Struts front controller remain reachable as
 * /api/v1 routes (BASELINE.md section 5), that an unknown path still answers 404 as the source
 * did (SubscriptionIT lines 43-44), and that no *.do alias was created.
 *
 * Routability is asserted as "not 404 on the route itself and not 405 on its declared method".
 * The business status depends on the provisional stub clients of plan 07 and is tightened by the
 * later checks (12 for the error payload, 17 for the real clients).
 *
 * Two routes are taken from the committed provider contract
 * (global/contracts/souscription-web-to-souscription-gateway/openapi.yaml), which wins over the
 * skeleton on any divergence (check step 2 / R2a): the simulation attachment is
 * PUT /api/v1/drafts/{draftId}/simulation (operationId putDraftSimulation), and the confirmation
 * write is POST /api/v1/drafts/{draftId}/confirmation (operationId confirmDraft) rather than
 * POST /api/v1/subscriptions. The stateless computation POST /api/v1/simulations (operationId
 * simulate) is asserted as well, which brings the count to the 9 contract operations for 8 source
 * actions. GET /api/v1/csrf-token completes the surface since W3-T02 and is asserted by
 * CsrfTokenServiceTest.
 */
@QuarkusTest
class RouteSurfaceParityTest {

    private static final String DRAFT_ID = UUID.randomUUID().toString();
    private static final String KEY = UUID.randomUUID().toString();

    @Test
    void catalogueRoutesAreReachable() {
        given().when().get("/api/v1/products")
                .then().statusCode(not(oneOf(404, 405)));
        given().when().get("/api/v1/products/{code}", "PRUDENT")
                .then().statusCode(not(oneOf(404, 405)));
    }

    @Test
    void draftRoutesAreReachableWithTheirDeclaredMethods() {
        // POST /api/v1/drafts  (source /commencer.do)
        given().contentType("application/json").body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/api/v1/drafts")
                .then().statusCode(not(oneOf(404, 405)));
        // PUT /api/v1/drafts/{id}/subscriber  (source /souscripteur.do) - the new method
        given().contentType("application/json").body("{}")
                .when().put("/api/v1/drafts/{id}/subscriber", DRAFT_ID)
                .then().statusCode(not(405));
        // GET /api/v1/drafts/{id}  (source /recapitulatif.do)
        given().when().get("/api/v1/drafts/{id}", DRAFT_ID)
                .then().statusCode(not(405));
    }

    @Test
    void simulationAndConfirmationRoutesAreReachable() {
        // POST /api/v1/simulations  (source /simulation.do, command "calculer")
        given().contentType("application/json")
                .body("{\"productCode\":\"PRUDENT\",\"initialAmount\":\"1000.00\","
                        + "\"monthlyAmount\":\"100.00\",\"durationYears\":10}")
                .when().post("/api/v1/simulations")
                .then().statusCode(not(oneOf(404, 405)));
        // PUT /api/v1/drafts/{id}/simulation  (source /simulation.do, attachment to the draft)
        given().contentType("application/json").body("{}")
                .when().put("/api/v1/drafts/{id}/simulation", DRAFT_ID)
                .then().statusCode(not(405));
        // POST /api/v1/drafts/{id}/confirmation  (source /confirmer.do)
        given().contentType("application/json").body("{\"confirmationKey\":\"" + KEY + "\"}")
                .when().post("/api/v1/drafts/{id}/confirmation", DRAFT_ID)
                .then().statusCode(not(405));
        // GET /api/v1/subscriptions/{reference}  (source /confirmation.do)
        given().when().get("/api/v1/subscriptions/{key}", KEY)
                .then().statusCode(not(405));
    }

    @Test
    void unknownPathUnderApiV1StillAnswers404() {
        given().when().get("/api/v1/unknown").then().statusCode(404);
    }

    @Test
    void noLegacyDoAliasIsServed() {
        given().when().get("/catalogue.do").then().statusCode(404);
        given().when().get("/produit.do").then().statusCode(404);
    }
}
