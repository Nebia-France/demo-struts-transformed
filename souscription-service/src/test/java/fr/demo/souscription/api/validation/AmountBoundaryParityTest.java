package fr.demo.souscription.api.validation;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SimulationInput;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 21-souscription-service-amount-validation.
 *
 * Baseline reference: BASELINE.md section 4.5 — InputValidator.simulationErrors / validateAmount.
 * Source bounds, all INCLUSIVE: initial 100..100000, monthly 0..2000, years 1..30.
 *
 * The same 8-row table must also pass in souscription-simulation-service
 * (AMOUNT_BOUNDARY_CONTRACT.md): the validation is deliberately duplicated across the split, so the
 * two sides must never disagree on a boundary value.
 *
 * <p>Comme les autres tests {@code @QuarkusTest} du module, la classe est ignoree sans runtime de
 * conteneurs (Dev Services PostgreSQL). Les verdicts de bornes eux-memes sont rejoues sans
 * conteneur par {@link AmountBoundaryParityOfflineTest}.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class AmountBoundaryParityTest {

    /** Le separateur decimal francais n'est jamais ecrit en litteral dans le module (W3-T07). */
    private static final String COMMA = ",";

    private static final String SUBSCRIBER_ISO = "{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
            + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1990-05-12\"}";

    @Inject
    Validator validator;

    private Set<ConstraintViolation<SimulationInput>> violations(String initial, String monthly,
                                                                 Integer years) {
        return validator.validate(new SimulationInput(initial, monthly, years));
    }

    private boolean accepts(String initial, String monthly, Integer years) {
        return violations(initial, monthly, years).isEmpty();
    }

    private String rejectedField(String initial, String monthly, Integer years) {
        return violations(initial, monthly, years).stream()
                .map(v -> v.getPropertyPath().toString())
                .findFirst()
                .orElse("<accepted>");
    }

    @Test
    void theEightContractRows() {
        assertTrue(accepts("100.00", "0.00", 1), "row 1: all three minima are valid");
        assertEquals("initialAmount", rejectedField("99.99", "100.00", 5), "row 2");
        assertTrue(accepts("100000.00", "2000.00", 30), "row 3: all three maxima are valid");
        assertEquals("initialAmount", rejectedField("100000.01", "100.00", 5), "row 4");
        assertEquals("monthlyAmount", rejectedField("1000.00", "2000.01", 5), "row 5");
        assertEquals("durationYears", rejectedField("1000.00", "100.00", 0), "row 6");
        assertEquals("durationYears", rejectedField("1000.00", "100.00", 31), "row 7");
        assertEquals("initialAmount", rejectedField("1000" + COMMA + "00", "100.00", 5),
                "row 8: the comma form belongs to souscription-web, not to the wire contract");
    }

    @Test
    void boundsAreInclusive() {
        // Source: compareTo(min) < 0 / compareTo(max) > 0 — the endpoints are valid.
        assertTrue(accepts("100.00", "100.00", 5), "100.00 is the advertised minimum initial payment");
        assertTrue(accepts("100000.00", "100.00", 5));
        assertTrue(accepts("1000.00", "0.00", 5),
                "a zero monthly contribution was allowed (source guard was monthly.signum() < 0)");
        assertTrue(accepts("1000.00", "2000.00", 5));
        assertTrue(accepts("1000.00", "100.00", 1));
        assertTrue(accepts("1000.00", "100.00", 30));
    }

    @Test
    void rejectionNamesTheOffendingField() {
        // The source keyed errors per field in a LinkedHashMap; the SPA focuses the named input.
        assertEquals("initialAmount", rejectedField("50.00", "100.00", 5));
        assertEquals("monthlyAmount", rejectedField("1000.00", "5000.00", 5));
        assertEquals("durationYears", rejectedField("1000.00", "100.00", 99));
    }

    @Test
    void patternViolationIsReportedOnce() {
        Set<ConstraintViolation<SimulationInput>> result = violations("abc", "100.00", 5);
        long onInitial = result.stream()
                .filter(v -> v.getPropertyPath().toString().equals("initialAmount"))
                .count();
        assertEquals(1, onInitial,
                "DecimalRangeValidator must return true for a value that already fails @Pattern, "
                + "otherwise the user sees two contradictory messages for one input");
    }

    @Test
    void durationRejectsSourceEdgeCases() {
        assertFalse(accepts("1000.00", "100.00", 0));
        assertFalse(accepts("1000.00", "100.00", 31));
        assertFalse(accepts("1000.00", "100.00", null), "durationYears is @NotNull");
    }

    @Test
    void malformedAmountIsAStructured400() {
        String draftId = draftWithSubscriber();
        // A NumberFormatException escaping as a 500 would make a user error look like a service
        // fault and pollute the availability budget.
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"abc\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}")
            .when().put("/v1/drafts/" + draftId + "/simulation")
            .then().statusCode(400)
                .contentType(containsString("application/problem+json"));
    }

    @Test
    void outOfRangeAmountNeverReachesTheDatabase() {
        String draftId = draftWithSubscriber();
        // The three CHECK constraints of V1__subscriptions.sql are the last barrier; reaching them
        // means the DTO constraints are inert and the user gets a 500 instead of a 400.
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"99.99\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}")
            .when().put("/v1/drafts/" + draftId + "/simulation")
            .then().statusCode(400);
    }

    private String draftWithSubscriber() {
        String draftId = given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");
        given().contentType(ContentType.JSON).body(SUBSCRIBER_ISO)
                .when().put("/v1/drafts/" + draftId + "/subscriber").then().statusCode(200);
        return draftId;
    }
}
