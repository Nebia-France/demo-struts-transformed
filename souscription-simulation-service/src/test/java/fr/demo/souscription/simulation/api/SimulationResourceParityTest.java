package fr.demo.souscription.simulation.api;

import java.util.Set;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.is;

/**
 * Parity skeleton for 09-simulation-rest-resource-v1.
 *
 * Source counterpart: the /simulation branch of action/SubscriptionAction.java, which computed the
 * projection, stored it in the HttpSession and forwarded to a JSP. The computation and the
 * input->output mapping must be reproduced; the session, the step/pageTitle attributes and the HTML
 * have no counterpart here (BASELINE.md section 4).
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/api/.
 */
@QuarkusTest
class SimulationResourceParityTest {

    private static final String NOMINAL = """
            {"initialAmount":1200,"monthlyAmount":100,"durationYears":1,"annualRate":0}
            """;

    @Test
    void nominalRequestReturnsTheLegacyValues() {
        given().contentType("application/json").body(NOMINAL)
                .when().post("/v1/simulations")
                .then().statusCode(200)
                .contentType("application/json")
                // Legacy reference case: calculate(1200, 100, 1, ZERO) -> 2400.00 / 2400.00
                .body("contributed", is("2400.00"))
                .body("finalCapital", is("2400.00"));
    }

    /** The response key set is the frozen contract: no gains, no step, no pageTitle. */
    @Test
    void responseCarriesExactlyTheFiveContractedFields() {
        Response response = given().contentType("application/json").body(NOMINAL)
                .when().post("/v1/simulations").then().statusCode(200).extract().response();

        Set<String> keys = new java.util.HashSet<>(response.jsonPath().<String, Object>getMap("$").keySet());
        assertEquals(Set.of("initialAmount", "monthlyAmount", "durationYears", "contributed", "finalCapital"),
                keys, "the response must carry exactly the five fields of SimulationResult.json");
    }

    /** No server-side state: the HttpSession storage of the legacy action has no counterpart. */
    @Test
    void noSessionCookieIsEmitted() {
        Response response = given().contentType("application/json").body(NOMINAL)
                .when().post("/v1/simulations").then().statusCode(200).extract().response();
        assertNull(response.getHeader("Set-Cookie"), "the service is stateless; no session is created");
    }

    /** The legacy response was a full HTML page; the JSON projection must stay small. */
    @Test
    void responseBodyIsUnderOneKilobyte() {
        Response response = given().contentType("application/json").body(NOMINAL)
                .when().post("/v1/simulations").then().statusCode(200).extract().response();
        int size = response.asByteArray().length;
        assertTrue(size < 1024, "response body must stay under 1 KB, was " + size + " bytes");
    }

    /**
     * Interim criterion only: plan 14 adds the 400 + application/problem+json body. Here the
     * assertion is deliberately "4xx, not 5xx" so plan 09's intended intermediate state is not
     * flagged as a parity gap, while a silently ACCEPTED out-of-bounds input still fails.
     */
    @Test
    void outOfBoundsInputIsRejectedWithoutAServerError() {
        int status = given().contentType("application/json")
                .body("""
                      {"initialAmount":99,"monthlyAmount":100,"durationYears":1,"annualRate":0}
                      """)
                .when().post("/v1/simulations").then().extract().statusCode();
        assertTrue(status >= 400 && status < 500,
                "the legacy guard refused initial = 99; expected a 4xx, got " + status);
    }

    /** The resource declares no @GET: the legacy *.do query shape is not reproduced. */
    @Test
    void getIsNotSupportedOnTheCollection() {
        int status = given().when().get("/v1/simulations").then().extract().statusCode();
        assertTrue(status == 405 || status == 404,
                "no read endpoint is contracted on /v1/simulations, got " + status);
    }

    /** The upper inclusive bounds still compute, as they did in the legacy action. */
    @Test
    void upperBoundsStillCompute() {
        given().contentType("application/json")
                .body("""
                      {"initialAmount":100000,"monthlyAmount":2000,"durationYears":30,"annualRate":0.06}
                      """)
                .when().post("/v1/simulations")
                .then().statusCode(200)
                .body("durationYears", is(30));
    }
}
